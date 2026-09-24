package app.raum.automation.engine

import app.raum.data.preferences.TemperatureUnit
import app.raum.i18n.ErrorTexts
import app.raum.i18n.Strings
import app.raum.R
import app.raum.automation.AutomationDescriber
import app.raum.automation.conditions.ConditionEvaluator
import app.raum.automation.conditions.DeviceReadings
import app.raum.automation.triggers.GeoLocation
import app.raum.automation.triggers.SunCalculator
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogLevel
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.AutomationLimits
import app.raum.domain.models.Device
import app.raum.domain.models.Trigger
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.controller.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.UUID

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/** Laufzeitstatus einer Automation für die Oberfläche. */
data class AutomationStatus(val running: Boolean = false, val pausedUntil: Instant? = null)

/**
 * Lokale Automations-Engine (Spez. 7.8, M4).
 *
 * - Zeit-/Sonnen-Trigger über einen minütlichen Scheduler (verpasste Minuten werden bis
 *   [Limits.maxCatchUp] nachgeholt, z. B. nach kurzem Hänger).
 * - Geräte-Trigger flankengesteuert aus den bestätigten Gerätezuständen.
 * - Schleifenschutz (AUT-006): eine Automation läuft nie parallel zu sich selbst; wird sie
 *   zu häufig ausgelöst, pausiert sie für [Limits.pause].
 * - Jede Ausführung wird mit Zeit, Auslöser und Ergebnis protokolliert (AUT-007).
 * - Definitionen liegen in der Datenbank → übersteht Neustarts (AUT-005).
 */
class AutomationEngine(
    private val repository: HomeRepository,
    private val deviceService: DeviceService,
    private val sceneRunner: SceneRunner,
    private val messages: UiMessageBus,
    private val log: EventLog,
    private val location: () -> GeoLocation?,
    private val scope: CoroutineScope,
    private val strings: Strings,
    private val temperatureUnit: () -> TemperatureUnit = { TemperatureUnit.CELSIUS },
    private val clock: Clock = Clock.systemDefaultZone(),
    private val limits: Limits = Limits(),
) {
    private fun s(id: Int, vararg args: Any) = strings.get(id, *args)

    data class Limits(
        val maxRunsPerWindow: Int = 6,
        val window: Duration = Duration.ofMinutes(1),
        val pause: Duration = Duration.ofMinutes(5),
        val maxCatchUp: Duration = Duration.ofMinutes(5),
    )

    private class RunState {
        var job: Job? = null
        val recentRuns = ArrayDeque<Instant>()
        var pausedUntil: Instant? = null
        var lastSkipLog: Instant? = null
    }

    private val states = mutableMapOf<UUID, RunState>()
    private val _status = MutableStateFlow<Map<UUID, AutomationStatus>>(emptyMap())
    val status: StateFlow<Map<UUID, AutomationStatus>> = _status.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { watchDevices() }
        scope.launch { schedule() }
        scope.launch {
            // Systemstart-Trigger, sobald Geräte bekannt sind (spätestens nach 10 s).
            withTimeoutOrNull(10_000) { deviceService.confirmedDevices.first { it.isNotEmpty() } }
            onSystemStart()
        }
    }

    // --- Ereignisquellen ----------------------------------------------------------

    private suspend fun watchDevices() {
        var previous: Map<UUID, Device> = emptyMap()
        deviceService.confirmedDevices.collect { list ->
            val current = list.associateBy { it.id }
            // Erste Liste ist nur die Ausgangslage – sonst würde jeder Start Trigger auslösen.
            if (previous.isNotEmpty()) onDevicesChanged(previous, current)
            previous = current
        }
    }

    private suspend fun schedule() {
        var last = now().truncatedTo(ChronoUnit.MINUTES)
        while (true) {
            val now = now()
            val next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(Duration.between(now, next).toMillis() + 50)
            val current = now().truncatedTo(ChronoUnit.MINUTES)
            var minute = last.plusMinutes(1)
            val earliest = current.minus(limits.maxCatchUp)
            if (minute.isBefore(earliest)) minute = earliest
            while (!minute.isAfter(current)) {
                onMinute(minute)
                minute = minute.plusMinutes(1)
            }
            last = current
        }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    private fun automations(): List<Automation> = repository.automations.value.filter { it.enabled && it.isComplete }

    /** Prüft Zeit- und Sonnen-Trigger für eine konkrete Minute. */
    fun onMinute(minute: ZonedDateTime) {
        val minuteOfDay = minute.hour * 60 + minute.minute
        val weekday = minute.dayOfWeek.value
        for (a in automations()) {
            val hit = a.triggers.firstOrNull { t ->
                when (t) {
                    is Trigger.TimeOfDay -> t.minuteOfDay == minuteOfDay && (t.weekdays.isEmpty() || weekday in t.weekdays)
                    is Trigger.Sun -> (t.weekdays.isEmpty() || weekday in t.weekdays) && sunMatches(t, minute)
                    else -> false
                }
            }
            if (hit != null) fire(a, describe(hit))
        }
    }

    private fun sunMatches(t: Trigger.Sun, minute: ZonedDateTime): Boolean {
        val loc = location() ?: return false
        // Der Versatz kann über Mitternacht reichen – daher Vortag/Folgetag mitprüfen.
        return (-1L..1L).any { d ->
            SunCalculator.time(t.event, minute.toLocalDate().plusDays(d), loc, minute.zone)
                ?.plusMinutes(t.offsetMinutes.toLong())
                ?.truncatedTo(ChronoUnit.MINUTES)
                ?.toInstant() == minute.truncatedTo(ChronoUnit.MINUTES).toInstant()
        }
    }

    fun onDevicesChanged(previous: Map<UUID, Device>, current: Map<UUID, Device>) {
        for (a in automations()) {
            val hit = a.triggers.firstOrNull { t ->
                when (t) {
                    is Trigger.DeviceStateChanged -> {
                        val before = DeviceReadings.property(previous[t.deviceId], t.property)
                        val after = DeviceReadings.property(current[t.deviceId], t.property)
                        before != null && after == t.value && before != after
                    }
                    is Trigger.SensorThreshold -> {
                        val before = DeviceReadings.metric(previous[t.deviceId], t.metric)
                        val after = DeviceReadings.metric(current[t.deviceId], t.metric)
                        before != null && after != null &&
                            !DeviceReadings.compare(before, t.comparison, t.threshold) &&
                            DeviceReadings.compare(after, t.comparison, t.threshold)
                    }
                    is Trigger.Connectivity -> {
                        val before = previous[t.deviceId]?.isOnline
                        val after = current[t.deviceId]?.isOnline
                        before != null && after == t.online && before != after
                    }
                    else -> false
                }
            }
            if (hit != null) fire(a, describe(hit, current))
        }
    }

    fun onSystemStart() {
        automations().filter { a -> a.triggers.any { it == Trigger.SystemStart } }
            .forEach { fire(it, s(R.string.auto_reason_system_start)) }
    }

    // --- Ausführung ---------------------------------------------------------------

    /** Testweise Ausführung (AUT-008): ignoriert Auslöser und Bedingungen, Schleifenschutz gilt. */
    fun runNow(automation: Automation) {
        fire(automation, s(R.string.auto_reason_manual), ignoreConditions = true)
    }

    private fun fire(a: Automation, reason: String, ignoreConditions: Boolean = false) {
        val devices = deviceService.confirmedDevices.value.associateBy { it.id }
        if (!ignoreConditions) {
            val check = ConditionEvaluator.evaluate(a.conditions, now(), devices)
            if (!check.satisfied) {
                val text = check.failed?.let { describer().condition(it) } ?: "?"
                logRun(a, LogLevel.INFO, s(R.string.auto_log_condition_failed, text, reason))
                return
            }
        }
        val instant = clock.instant()
        synchronized(states) {
            val st = states.getOrPut(a.id) { RunState() }
            st.pausedUntil?.let { until ->
                if (instant.isBefore(until)) {
                    logSkipped(a, st, instant, s(R.string.auto_log_paused_until, HH_MM.format(until.atZone(clock.zone))))
                    return
                }
                st.pausedUntil = null
            }
            if (st.job?.isActive == true) {
                logSkipped(a, st, instant, s(R.string.auto_log_still_running))
                return
            }
            while (st.recentRuns.isNotEmpty() && st.recentRuns.first().isBefore(instant.minus(limits.window))) st.recentRuns.removeFirst()
            if (st.recentRuns.size >= limits.maxRunsPerWindow) {
                st.pausedUntil = instant.plus(limits.pause)
                st.recentRuns.clear()
                logRun(a, LogLevel.ERROR, s(R.string.auto_log_too_frequent, limits.maxRunsPerWindow, limits.window.toMinutes(), limits.pause.toMinutes()))
                messages.error(s(R.string.msg_automation_paused, a.name))
                publishStatus()
                return
            }
            st.recentRuns.addLast(instant)
            st.job = scope.launch { execute(a, reason) }.also { job ->
                job.invokeOnCompletion { publishStatus() }
            }
        }
        publishStatus()
    }

    private fun logSkipped(a: Automation, st: RunState, now: Instant, why: String) {
        // Höchstens ein Eintrag pro Minute, damit Dauerauslöser das Protokoll nicht fluten.
        if (st.lastSkipLog?.isAfter(now.minusSeconds(60)) == true) return
        st.lastSkipLog = now
        logRun(a, LogLevel.WARNING, s(R.string.auto_log_trigger_ignored, why))
    }

    private suspend fun execute(a: Automation, reason: String) {
        val failures = mutableListOf<String>()
        // Bei Wartezeiten den Start festhalten: Wird die Ausführung unterbrochen (Neustart,
        // Stromausfall), bleibt sie im Protokoll sichtbar statt spurlos zu verschwinden.
        if (a.actions.any { it is AutomationAction.Delay }) logRun(a, LogLevel.INFO, s(R.string.auto_log_started, reason))
        for (action in a.actions) {
            when (action) {
                is AutomationAction.ControlDevice -> {
                    for (command in action.commands) {
                        val device = deviceService.device(action.deviceId)
                        if (device == null) { failures += s(R.string.auto_failure_device_deleted); break }
                        val result = deviceService.send(device, command, notify = false)
                        if (result is CommandResult.Failure) { failures += "${device.displayName}: ${s(ErrorTexts.command(result.reason))}"; break }
                    }
                }
                is AutomationAction.RunScene -> {
                    val scene = repository.scene(action.sceneId)
                    if (scene == null) failures += s(R.string.auto_failure_scene_deleted)
                    else sceneRunner.run(scene, notify = false).failedDevices.forEach { failures += s(R.string.auto_failure_in_scene, it, scene.name) }
                }
                is AutomationAction.Delay -> delay(action.seconds.coerceIn(0, AutomationLimits.MAX_DELAY_SECONDS) * 1000L)
                is AutomationAction.Notify -> messages.info(action.message)
            }
        }
        if (failures.isEmpty()) {
            logRun(a, LogLevel.INFO, s(R.string.auto_log_done, reason))
        } else {
            logRun(a, LogLevel.ERROR, s(R.string.auto_log_partial, reason, failures.joinToString()))
        }
    }

    private fun logRun(a: Automation, level: LogLevel, message: String) =
        log.record(LogCategory.AUTOMATION, level, s(R.string.auto_log_entry, a.name, message), automationId = a.id)

    private fun publishStatus() {
        _status.update {
            synchronized(states) {
                states.mapValues { (_, st) -> AutomationStatus(st.job?.isActive == true, st.pausedUntil) }
            }
        }
    }

    private fun describer(devices: Map<UUID, Device> = deviceService.confirmedDevices.value.associateBy { it.id }) =
        AutomationDescriber(devices, repository.scenes.value.associate { it.id to it.name }, strings, temperatureUnit())

    private fun describe(trigger: Trigger, devices: Map<UUID, Device>? = null): String =
        (if (devices != null) describer(devices) else describer()).trigger(trigger)

    /** Hebt eine Pause auf (z. B. nach Korrektur der Automation). */
    fun resume(automationId: UUID) {
        synchronized(states) { states[automationId]?.pausedUntil = null }
        publishStatus()
    }
}
