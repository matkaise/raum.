package app.raum.automation

import app.raum.R
import app.raum.data.preferences.TemperatureUnit
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SunEvent
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.Trigger
import app.raum.domain.models.Weekdays
import app.raum.domain.usecases.DeviceTarget
import app.raum.domain.usecases.SceneTargets
import app.raum.i18n.Strings
import app.raum.ui.components.Units
import java.util.Locale
import java.util.UUID

/**
 * Verständliche Beschreibung einer Automation in der App-Sprache (AUT-009).
 *
 * Jeder Satzteil ist eine Sprachvorlage mit nummerierten Platzhaltern – so kann jede Sprache ihre
 * eigene Wortstellung verwenden (z. B. „„Flurlicht“ einschalten“ vs. „turn on “Hallway light””).
 */
class AutomationDescriber(
    private val devices: Map<UUID, Device>,
    private val sceneNames: Map<UUID, String>,
    private val strings: Strings,
    private val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
    private val locale: Locale = Locale.getDefault(),
) {
    private fun s(id: Int, vararg args: Any) = strings.get(id, *args)

    private fun device(id: UUID) = s(R.string.auto_quoted, devices[id]?.displayName ?: s(R.string.auto_deleted_device))

    fun sentence(a: Automation): String {
        if (!a.isComplete) return s(R.string.auto_incomplete)
        val triggers = a.triggers.joinToString(" ${s(R.string.auto_or)} ") { trigger(it) }
        val actions = a.actions.joinToString(", ") { action(it) }
        return if (a.conditions.isEmpty()) {
            s(R.string.auto_sentence, triggers, actions)
        } else {
            s(R.string.auto_sentence_conditions, triggers, a.conditions.joinToString(" ${s(R.string.auto_and)} ") { condition(it) }, actions)
        }
    }

    fun trigger(t: Trigger): String = when (t) {
        is Trigger.TimeOfDay -> s(R.string.auto_trigger_time, time(t.minuteOfDay)) + weekdaySuffix(t.weekdays)
        is Trigger.Sun -> sun(t.event, t.offsetMinutes) + weekdaySuffix(t.weekdays)
        is Trigger.DeviceStateChanged -> s(R.string.auto_device_phrase, device(t.deviceId), transition(t.property, t.value))
        is Trigger.SensorThreshold -> s(
            if (t.comparison == Comparison.ABOVE) R.string.auto_trigger_rises_above else R.string.auto_trigger_falls_below,
            device(t.deviceId), value(t.metric, t.threshold),
        )
        is Trigger.Connectivity -> s(if (t.online) R.string.auto_trigger_online else R.string.auto_trigger_offline, device(t.deviceId))
        Trigger.SystemStart -> s(R.string.auto_trigger_system_start)
    }

    fun condition(c: Condition): String = when (c) {
        is Condition.TimeWindow -> s(R.string.auto_condition_time_window, time(c.fromMinute), time(c.toMinute))
        is Condition.OnWeekdays -> s(R.string.auto_condition_weekdays, weekdays(c.weekdays))
        is Condition.DeviceStateIs -> s(R.string.auto_condition_state, device(c.deviceId), state(c.property, c.value))
        is Condition.SensorValue -> s(
            if (c.comparison == Comparison.ABOVE) R.string.auto_condition_above else R.string.auto_condition_below,
            device(c.deviceId), value(c.metric, c.threshold),
        )
    }

    fun action(a: AutomationAction): String = when (a) {
        is AutomationAction.ControlDevice -> {
            val (verb, details) = target(SceneTargets.fromCommands(a.commands, devices[a.deviceId]))
            s(R.string.auto_action_control, device(a.deviceId), verb, details?.let { " ($it)" } ?: "")
        }
        is AutomationAction.RunScene -> s(R.string.auto_action_scene, sceneNames[a.sceneId] ?: s(R.string.auto_deleted_scene))
        is AutomationAction.Delay -> s(R.string.auto_action_wait, duration(a.seconds))
        is AutomationAction.Notify -> s(R.string.auto_action_notify, a.message)
    }

    /** Verb und optionale Details („einschalten“, „40 %, 2700 K“) – die Vorlage ordnet sie je Sprache an. */
    private fun target(t: DeviceTarget?): Pair<String, String?> = when (t) {
        null -> s(R.string.auto_target_control) to null
        is DeviceTarget.Light -> if (!t.on) s(R.string.auto_target_off) to null else {
            val details = listOfNotNull(
                t.brightnessPercent?.let { "$it %" },
                t.colorTemperatureKelvin?.let { "$it K" },
                t.color?.let { s(R.string.auto_target_color) },
            )
            s(R.string.auto_target_on) to details.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
        is DeviceTarget.Switch -> s(if (t.on) R.string.auto_target_on else R.string.auto_target_off) to null
        is DeviceTarget.Thermostat -> when (t.mode) {
            ThermostatMode.OFF -> s(R.string.auto_target_off) to null
            else -> s(R.string.auto_target_set_to, Units.format(t.targetCelsius, unit, locale)) to null
        }
        is DeviceTarget.Cover -> when (t.openPercent) {
            100 -> s(R.string.auto_target_open) to null
            0 -> s(R.string.auto_target_close) to null
            else -> s(R.string.auto_target_open_to, t.openPercent) to null
        }
    }

    fun time(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun weekdays(days: Weekdays): String = when {
        days.isEmpty() || days.size == 7 -> s(R.string.auto_days_daily)
        days == (1..5).toSet() -> s(R.string.auto_days_workdays)
        days == setOf(6, 7) -> s(R.string.auto_days_weekend)
        else -> days.sorted().joinToString(", ") { dayShort(it) }
    }

    fun dayShort(day: Int): String = s(
        when (day) {
            1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed; 4 -> R.string.day_thu
            5 -> R.string.day_fri; 6 -> R.string.day_sat; else -> R.string.day_sun
        }
    )

    private fun weekdaySuffix(days: Weekdays) = if (days.isEmpty() || days.size == 7) "" else " (${weekdays(days)})"

    fun sun(event: SunEvent, offset: Int): String {
        val name = s(if (event == SunEvent.SUNRISE) R.string.auto_sunrise else R.string.auto_sunset)
        return when {
            offset == 0 -> s(if (event == SunEvent.SUNRISE) R.string.auto_trigger_sun_rises else R.string.auto_trigger_sun_sets)
            offset > 0 -> s(R.string.auto_trigger_sun_after, duration(offset * 60), name)
            else -> s(R.string.auto_trigger_sun_before, duration(-offset * 60), name)
        }
    }

    fun duration(seconds: Int): String = when {
        seconds % 3600 == 0 && seconds >= 3600 -> s(R.string.duration_hours, seconds / 3600)
        seconds % 60 == 0 && seconds >= 60 -> s(R.string.duration_minutes, seconds / 60)
        else -> s(R.string.duration_seconds, seconds)
    }

    fun value(metric: SensorMetric, v: Double): String = when (metric) {
        SensorMetric.TEMPERATURE -> Units.format(v, unit, locale)
        SensorMetric.HUMIDITY -> s(R.string.auto_value_humidity, v.toInt())
    }

    fun transition(p: DeviceProperty, value: Boolean): String = s(
        when (p) {
            DeviceProperty.POWER -> if (value) R.string.auto_transition_on else R.string.auto_transition_off
            DeviceProperty.CONTACT_OPEN -> if (value) R.string.auto_transition_opened else R.string.auto_transition_closed
            DeviceProperty.OCCUPIED -> if (value) R.string.auto_transition_motion else R.string.auto_transition_no_motion
        }
    )

    fun state(p: DeviceProperty, value: Boolean): String = s(
        when (p) {
            DeviceProperty.POWER -> if (value) R.string.auto_state_on else R.string.auto_state_off
            DeviceProperty.CONTACT_OPEN -> if (value) R.string.auto_state_open else R.string.auto_state_closed
            DeviceProperty.OCCUPIED -> if (value) R.string.auto_state_occupied else R.string.auto_state_clear
        }
    )
}
