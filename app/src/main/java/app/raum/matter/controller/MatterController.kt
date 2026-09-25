package app.raum.matter.controller

import java.time.Duration
import java.time.Instant
import app.raum.R
import androidx.annotation.StringRes

import app.raum.domain.models.Capability
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceState
import app.raum.matter.commissioning.SetupCodeParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Austauschbarer Matter-Adapter (Spez. 12.3, 13.2).
 *
 * Implementierungen:
 *  - [app.raum.matter.controller.mock.MockMatterController] für Emulator und Tests
 *  - [app.raum.matter.chip.ChipMatterController] auf Basis des offiziellen Matter-SDK (connectedhomeip)
 */
interface MatterController {
    /** Eigene Matter-Fabric; null, solange noch keine erzeugt wurde (Erstinbetriebnahme). */
    val fabric: StateFlow<FabricInfo?>

    /** Erzeugt und speichert die eigene Fabric, falls noch keine existiert (ONB-006). Idempotent. */
    suspend fun ensureFabric(): FabricInfo

    /** Alle Nodes der eigenen Fabric mit ihrem aktuellen Zustand. */
    val devices: StateFlow<Map<ULong, DeviceState>>

    /**
     * Koppelt ein Gerät. [allowUncertified]: Gerät auch ohne erfolgreiche Echtheitsprüfung aufnehmen
     * (nur nach ausdrücklicher Bestätigung – z. B. Hersteller-Zertifikat nicht in raum. hinterlegt).
     */
    suspend fun commission(
        setupCode: String,
        allowUncertified: Boolean = false,
        onProgress: (CommissioningStep) -> Unit = {},
    ): CommissioningResult

    /** Nach dem Start: bekannte Nodes wieder verbinden/abonnieren (Zustände kommen danach über [devices]). */
    suspend fun resume(knownNodes: Collection<ULong>)
    suspend fun removeDevice(nodeId: ULong)
    suspend fun readCapabilities(nodeId: ULong): List<Capability>
    suspend fun execute(command: MatterCommand): CommandResult
    fun observeDevice(nodeId: ULong): Flow<DeviceState>

    /**
     * Entfernt alle Nodes aus der eigenen Fabric und löscht die Fabric-Credentials (RST-002).
     * Geräte bleiben in anderen Matter-Ökosystemen (Multi-Admin) unberührt.
     */
    suspend fun resetFabric()

    // --- Multi-Admin: Geräte zusätzlich in Apple Home, Google Home, Alexa … (Matter-Spez. 5.5, 11.18) ---

    /** Wer jedes Gerät steuern darf (OperationalCredentials › Fabrics), inkl. raum. selbst. */
    val adminFabrics: StateFlow<Map<ULong, List<AdminFabric>>>

    /** Kopplungsfenster öffnen (Enhanced Commissioning Method) – liefert einen einmaligen Code. */
    suspend fun openPairingWindow(nodeId: ULong, timeout: Duration = Duration.ofMinutes(15)): PairingWindowResult

    /** Fenster vorzeitig schließen (AdministratorCommissioning › RevokeCommissioning). */
    suspend fun closePairingWindow(nodeId: ULong)

    /** Einer anderen App den Zugriff entziehen (RemoveFabric). Die eigene Fabric ist ausgenommen. */
    suspend fun removeAdmin(nodeId: ULong, fabricIndex: Int): CommandResult

    /** Administratoren frisch vom Gerät lesen (nicht aus dem Zwischenspeicher); null = nicht erreichbar/lesbar. */
    suspend fun readAdmins(nodeId: ULong): List<AdminFabric>?

    /** Wie die Fabric-Schlüssel gespeichert sind (Spez. 11.2); null = Simulation ohne echte Schlüssel. */
    val credentialStorage: StateFlow<CredentialStorage?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
}

/** Schutz der Fabric-Schlüssel: verschlüsselt im Keystore oder (Rückfall) im Klartextspeicher des SDK. */
data class CredentialStorage(val encrypted: Boolean, val protection: app.raum.security.KeyProtection, val unreadable: Int = 0)

/** Kennzeichen der eigenen Fabric (die Schlüssel verwaltet der Controller selbst). */
data class FabricInfo(val fabricId: ULong, val createdAt: Instant)

/** Ein Eintrag der Fabric-Liste eines Geräts. */
data class AdminFabric(val fabricIndex: Int, val vendorId: Int, val label: String, val own: Boolean)

data class PairingWindow(val nodeId: ULong, val manualCode: String, val qrPayload: String, val expiresAt: Instant)

sealed interface PairingWindowResult {
    data class Open(val window: PairingWindow) : PairingWindowResult
    data class Failure(val reason: CommandFailure) : PairingWindowResult
}

data class MatterCommand(
    val nodeId: ULong,
    val command: DeviceCommand,
    val endpointId: Int = 1,
)

sealed interface CommandResult {
    data object Success : CommandResult
    /** @param detail technische Zusatzinfo (Englisch, fürs Protokoll); der Nutzertext folgt aus [reason]. */
    data class Failure(val reason: CommandFailure, val detail: String? = null) : CommandResult
}

enum class CommandFailure { OFFLINE, TIMEOUT, UNSUPPORTED, DEVICE_ERROR }

enum class CommissioningStep(@StringRes val labelRes: Int) {
    PARSING(R.string.commissioning_step_parsing),
    DISCOVERING(R.string.commissioning_step_discovering),
    PASE(R.string.commissioning_step_pase),
    ATTESTATION(R.string.commissioning_step_attestation),
    NETWORK(R.string.commissioning_step_network),
    OPERATIONAL(R.string.commissioning_step_operational),
    READING(R.string.commissioning_step_reading),
}

sealed interface CommissioningResult {
    data class Success(
        val nodeId: ULong,
        val vendorName: String?,
        val productName: String?,
        /** Vom Gerät gemeldeter Name, falls vorhanden (sonst wählt raum. einen neutralen Namen). */
        val suggestedName: String?,
    ) : CommissioningResult

    data class AlreadyCommissioned(val nodeId: ULong) : CommissioningResult

    data class Failure(
        val reason: CommissioningFailure,
        val invalidCode: SetupCodeParser.InvalidReason? = null,
        val detail: String? = null,
    ) : CommissioningResult
}

enum class CommissioningFailure {
    INVALID_CODE, DEVICE_NOT_FOUND, PASE_FAILED, NETWORK_FAILED, TIMEOUT, ATTESTATION, ALREADY_PAIRED,
    /** Nicht im Netz gefunden, und Bluetooth ist nicht nutzbar – neue Geräte brauchen es (COM-002) */
    BLUETOOTH_UNAVAILABLE,
    /** Thread-Gerät, aber in raum. ist kein Thread-Netz hinterlegt (COM-004) */
    NO_THREAD_NETWORK,
    /** WLAN-Gerät, aber in raum. ist kein WLAN für neue Geräte hinterlegt (COM-003) */
    NO_WIFI_CREDENTIALS,
    /** Gerät ist dem Thread-Netz nicht beigetreten (Dataset veraltet, Border Router aus) */
    THREAD_JOIN_FAILED,
    /** Gerät ist dem WLAN nicht beigetreten (Passwort, 2,4 GHz) */
    WIFI_JOIN_FAILED,
    /** Gerät ist im Netz, raum. erreicht es aber nicht (Border Router, IPv6) */
    NOT_REACHABLE,
}
