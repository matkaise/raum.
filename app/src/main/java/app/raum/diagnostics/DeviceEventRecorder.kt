package app.raum.diagnostics

import app.raum.i18n.Strings
import app.raum.R
import app.raum.automation.AutomationDescriber
import app.raum.automation.conditions.DeviceReadings
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceProperty
import app.raum.domain.usecases.DeviceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Protokolliert Geräteereignisse (LOG-001): Erreichbarkeit und binäre Zustandswechsel.
 * Messwerte werden bewusst nicht einzeln protokolliert (Datenmenge).
 */
class DeviceEventRecorder(
    private val deviceService: DeviceService,
    private val log: EventLog,
    private val scope: CoroutineScope,
    private val strings: Strings,
) {
    private val describer = AutomationDescriber(emptyMap(), emptyMap(), strings)

    fun start() {
        scope.launch {
            var previous: Map<UUID, Device> = emptyMap()
            deviceService.confirmedDevices.collect { list ->
                val current = list.associateBy { it.id }
                if (previous.isNotEmpty()) record(previous, current)
                previous = current
            }
        }
    }

    internal fun record(previous: Map<UUID, Device>, current: Map<UUID, Device>) {
        for ((id, now) in current) {
            val before = previous[id] ?: continue
            if (before.isOnline != now.isOnline) {
                log.record(
                    LogCategory.DEVICE, if (now.isOnline) LogLevel.INFO else LogLevel.WARNING,
                    strings.get(if (now.isOnline) R.string.log_device_reachable else R.string.log_device_unreachable), now.displayName, id,
                )
                continue
            }
            for (p in DeviceProperty.entries) {
                val a = DeviceReadings.property(before, p) ?: continue
                val b = DeviceReadings.property(now, p) ?: continue
                if (a != b) {
                    log.record(LogCategory.DEVICE, LogLevel.INFO, describer.state(p, b).replaceFirstChar { it.uppercase() }, now.displayName, id)
                }
            }
        }
    }
}
