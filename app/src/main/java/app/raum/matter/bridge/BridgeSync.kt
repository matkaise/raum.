package app.raum.matter.bridge

import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogLevel
import app.raum.domain.usecases.DeviceService
import app.raum.i18n.Strings
import app.raum.matter.controller.Ecosystems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** Einstellungen der Bridge (in Tests ersetzbar). */
interface BridgePrefs {
    val bridgeEnabled: StateFlow<Boolean>
    /** Nicht freigegebene Geräte – alle anderen (auch neue) erscheinen in der Bridge. */
    val bridgeExcluded: StateFlow<Set<UUID>>
}

/**
 * Verbindet die Bridge mit raum.: startet/stoppt sie, hält die freigegebenen Geräte und ihre Zustände
 * aktuell und führt Befehle anderer Apps über den normalen Weg (Offline-Schutz, Protokoll) aus.
 */
class BridgeSync(
    private val bridge: MatterBridge,
    private val devices: DeviceService,
    private val prefs: BridgePrefs,
    private val log: EventLog,
    private val strings: Strings,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            prefs.bridgeEnabled.collect { on ->
                if (on) bridge.start() else bridge.stop()
            }
        }
        scope.launch {
            combine(prefs.bridgeEnabled, prefs.bridgeExcluded, devices.devices) { on, excluded, list ->
                if (on) list.filter { it.id !in excluded } else null
            }.collect { shared ->
                if (shared != null) { bridge.expose(shared); bridge.publish(shared) }
            }
        }
        scope.launch {
            bridge.incomingCommands.collect { cmd ->
                val device = devices.devices.value.firstOrNull { it.matterNodeId == cmd.nodeId } ?: return@collect
                // Abgewählte Geräte sind für andere Apps tabu, auch wenn ein Befehl noch ankommt
                if (!prefs.bridgeEnabled.value || device.id in prefs.bridgeExcluded.value) return@collect
                val via = bridge.state.value.admins.firstOrNull { it.vendorId == cmd.fromVendorId }?.let(Ecosystems::name) ?: "?"
                log.record(LogCategory.DEVICE, LogLevel.INFO, strings.get(R.string.log_bridge_command, via), device.displayName, device.id)
                devices.send(device, cmd.command, notify = false)
            }
        }
    }
}
