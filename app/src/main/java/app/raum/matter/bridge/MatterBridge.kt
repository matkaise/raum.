package app.raum.matter.bridge

import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.PairingWindow
import app.raum.matter.controller.PairingWindowResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.util.UUID

/** Zustand der Bridge (raum. selbst als Matter-Gerät). */
data class BridgeState(
    val running: Boolean = false,
    /** Apps, die raum. als Bridge gekoppelt haben (Apple Home, Google Home …) */
    val admins: List<AdminFabric> = emptyList(),
    /** Geräte (auch einzelne Kanäle), die als „Bridged Node“-Endpunkte sichtbar sind */
    val exposed: Set<UUID> = emptySet(),
    val window: PairingWindow? = null,
    /** Echtheitsnachweis der Bridge; null = unbekannt (Simulation) */
    val attestation: AttestationStatus? = null,
)

/** Befehl einer anderen App an ein überbrücktes Gerät. */
data class BridgedCommand(val deviceId: UUID, val command: DeviceCommand, val fromVendorId: Int)

/**
 * raum. als Matter-Bridge (Gerätetyp Aggregator mit „Bridged Node“-Endpunkten, Matter-Spez. 9.12):
 * andere Apps koppeln raum. **einmal** und sehen alle freigegebenen Geräte. Befehle kommen über
 * [incomingCommands] an und laufen über den normalen raum.-Weg zu den Geräten; Zustände meldet [publish].
 *
 * Gegenstück zum Multi-Admin je Gerät (MatterController.openPairingWindow). Umsetzungen: [ChipMatterBridge]
 * (Matter-SDK, Server-Rolle im Prozess „:bridge“) und [MockMatterBridge] (Simulation).
 */
interface MatterBridge {
    val state: StateFlow<BridgeState>
    val incomingCommands: Flow<BridgedCommand>

    suspend fun start()
    suspend fun stop()

    /** Welche Geräte als Endpunkte erscheinen – dynamisch, andere Apps übernehmen Änderungen selbst. */
    fun expose(devices: List<Device>)

    /** Aktuelle Zustände an die Endpunkte melden (Attribut-Reports). */
    fun publish(devices: List<Device>)

    suspend fun openPairingWindow(timeout: Duration = Duration.ofMinutes(15)): PairingWindowResult
    suspend fun closePairingWindow()
    suspend fun removeAdmin(fabricIndex: Int): CommandResult

    /**
     * Werksreset der Bridge-Identität: alle Kopplungen weg, Bridge danach aus – auch wenn sie gerade nicht läuft.
     * Wirft, wenn sich das nicht bestätigen lässt; der Reset gilt dann nicht als abgeschlossen.
     */
    suspend fun reset()
}
