package app.raum.matter.bridge

import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.matter.commissioning.SetupCodeGenerator
import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.PairingWindow
import app.raum.matter.controller.PairingWindowResult
import app.raum.security.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
import kotlin.random.Random

/**
 * Simulierte Bridge. Kopplungen anderer Apps werden gespeichert (wie es der echte Matter-Server täte),
 * damit sie einen Neustart überstehen.
 */
class MockMatterBridge(
    private val store: KeyValueStore?,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) : MatterBridge {

    private val _state = MutableStateFlow(BridgeState(admins = loadAdmins()))
    override val state: StateFlow<BridgeState> = _state.asStateFlow()

    private val commands = MutableSharedFlow<BridgedCommand>(extraBufferCapacity = 16)
    override val incomingCommands: Flow<BridgedCommand> = commands.asSharedFlow()

    /** Zuletzt gemeldete Zustände (nur zur Kontrolle in Tests). */
    var published: List<Device> = emptyList()
        private set

    override suspend fun start() = _state.update { it.copy(running = true) }

    override suspend fun stop() = _state.update { it.copy(running = false, window = null) }

    override fun expose(devices: List<Device>) = _state.update { it.copy(exposed = devices.map { d -> d.matterNodeId }.toSet()) }

    override fun publish(devices: List<Device>) { published = devices }

    override suspend fun openPairingWindow(timeout: Duration): PairingWindowResult {
        if (!_state.value.running) return PairingWindowResult.Failure(CommandFailure.OFFLINE)
        if (_state.value.admins.size >= 5) return PairingWindowResult.Failure(CommandFailure.DEVICE_ERROR)
        val d = SetupCodeGenerator.randomDiscriminator(random)
        val p = SetupCodeGenerator.randomPasscode(random)
        val window = PairingWindow(
            nodeId = 0u,
            manualCode = SetupCodeGenerator.manualCode(d, p),
            qrPayload = SetupCodeGenerator.qrPayload(TEST_VENDOR, BRIDGE_PRODUCT, d, p),
            expiresAt = clock.instant().plus(timeout),
        )
        _state.update { it.copy(window = window) }
        return PairingWindowResult.Open(window)
    }

    override suspend fun closePairingWindow() = _state.update { it.copy(window = null) }

    override suspend fun removeAdmin(fabricIndex: Int): CommandResult {
        _state.update { s -> s.copy(admins = s.admins.filterNot { it.fabricIndex == fabricIndex }) }
        saveAdmins()
        return CommandResult.Success
    }

    override suspend fun reset() {
        _state.update { it.copy(admins = emptyList(), window = null) }
        saveAdmins()
    }

    /** Vorführung: eine App koppelt die Bridge über das offene Fenster. */
    fun simulateJoin(vendorId: Int, label: String = ""): Boolean {
        val w = _state.value.window ?: return false
        if (w.expiresAt.isBefore(clock.instant())) return false
        _state.update { s ->
            s.copy(window = null, admins = s.admins + AdminFabric((s.admins.maxOfOrNull { it.fabricIndex } ?: 0) + 1, vendorId, label, own = false))
        }
        saveAdmins()
        return true
    }

    /** Vorführung/Test: eine gekoppelte App schickt einen Befehl. Nur freigegebene Geräte reagieren. */
    fun simulateCommand(nodeId: ULong, command: DeviceCommand, vendorId: Int): Boolean {
        val s = _state.value
        if (!s.running || nodeId !in s.exposed || s.admins.none { it.vendorId == vendorId }) return false
        return commands.tryEmit(BridgedCommand(nodeId, command, vendorId))
    }

    private fun loadAdmins(): List<AdminFabric> = store?.getString(KEY)?.split(";")?.filter { it.isNotBlank() }?.mapNotNull { e ->
        val p = e.split(",", limit = 3)
        runCatching { AdminFabric(p[0].toInt(), p[1].toInt(), p.getOrElse(2) { "" }, own = false) }.getOrNull()
    }.orEmpty()

    private fun saveAdmins() {
        store?.putString(KEY, _state.value.admins.joinToString(";") { "${it.fabricIndex},${it.vendorId},${it.label.replace(";", " ")}" })
    }

    private companion object {
        const val KEY = "mock_bridge_admins"
        const val TEST_VENDOR = 0xFFF1
        const val BRIDGE_PRODUCT = 0x8010
    }
}
