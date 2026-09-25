package app.raum.matter.controller.mock

import java.time.Duration
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import app.raum.matter.commissioning.SetupCodeGenerator
import app.raum.matter.controller.PairingWindowResult
import app.raum.matter.controller.PairingWindow
import app.raum.matter.controller.AdminFabric
import app.raum.security.KeyValueStore
import app.raum.matter.controller.FabricInfo
import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.Capability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.DeviceNetwork
import app.raum.domain.models.NetworkTransport
import app.raum.domain.models.ThreadRole
import app.raum.thread.NetworkCredentialStore
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceState
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.OnlineState
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.usecases.CapabilityReducer
import app.raum.matter.commissioning.SetupCodeParser
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.CommissioningFailure
import app.raum.matter.controller.CommissioningResult
import app.raum.matter.controller.CommissioningStep
import app.raum.matter.controller.MatterCommand
import app.raum.matter.controller.MatterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

/**
 * Virtueller Matter-Controller mit simulierten Geräten (Spez. 13.2).
 *
 * Simuliert Latenz, Offline-Geräte, laufende Storen, Heizverhalten und Sensordrift,
 * damit UI-Zustände (optimistisch, bestätigt, Fehler) realistisch entwickelt werden können.
 *
 * Commissioning-Testcodes:
 *  - `3497-011-2332` (Matter-Standardcode) → neue Farbtemperaturleuchte
 *  - Codes mit kurzem Discriminator 0 → "Gerät nicht gefunden"
 *  - derselbe Code zweimal → "bereits vorhanden" (COM-010)
 */
class MockMatterController(
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Simulierte Befehlslatenz; in Tests 0. */
    private val latencyMs: LongRange = 80L..320L,
    /** Wahrscheinlichkeit, dass ein Befehl an ein Online-Gerät fehlschlägt. */
    private val failureRate: Double = 0.0,
    /** Taktung der Simulation; null deaktiviert sie (Tests). */
    simulationTickMs: Long? = 1_000L,
    seed: List<MockHomeSeed.SeedDevice> = MockHomeSeed.devices,
    /** Dauerhafte Ablage der Mock-Fabric (App: SharedPreferences; Tests: null = nur im Speicher). */
    private val fabricStore: KeyValueStore? = null,
    /** Zugangsdaten für simulierte neue Geräte (Thread/WLAN); null = keine hinterlegt. */
    private val credentials: NetworkCredentialStore? = null,
) : MatterController {

    private val _fabric = MutableStateFlow(
        fabricStore?.getString(KEY_FABRIC)?.split(":")?.takeIf { it.size == 2 }?.let { (id, ts) ->
            runCatching { FabricInfo(id.toULong(16), Instant.ofEpochMilli(ts.toLong())) }.getOrNull()
        }
    )
    override val fabric: StateFlow<FabricInfo?> = _fabric.asStateFlow()

    override suspend fun ensureFabric(): FabricInfo {
        _fabric.value?.let { return it }
        delay(latency() * 3) // Schlüsselerzeugung simulieren
        val info = FabricInfo(random.nextLong().toULong() or 1uL, clock.instant().truncatedTo(ChronoUnit.MILLIS))
        fabricStore?.putString(KEY_FABRIC, "${info.fabricId.toString(16)}:${info.createdAt.toEpochMilli()}")
        _fabric.value = info
        return info
    }

    private val _devices = MutableStateFlow(
        seed.associate { d ->
            d.nodeId to DeviceState(
                nodeId = d.nodeId,
                onlineState = if (d.online) OnlineState.ONLINE else OnlineState.OFFLINE,
                capabilities = d.capabilities,
                lastSeenAt = if (d.online) clock.instant() else clock.instant().minusSeconds(3 * 3600),
                network = demoNetwork(d.capabilities),
            )
        }
    )
    override val devices: StateFlow<Map<ULong, DeviceState>> = _devices.asStateFlow()

    /** Zielpositionen laufender Storen. */
    private val coverTargets = mutableMapOf<ULong, Int>()
    private val commissionedCodes = mutableMapOf<String, ULong>()
    private var nextNodeId: ULong = 0x100u

    /** Alle Zustandsänderungen laufen unter diesem Lock (Befehle und Simulation sind nebenläufig). */
    private val lock = Any()

    private fun mutate(transform: (Map<ULong, DeviceState>) -> Map<ULong, DeviceState>) {
        synchronized(lock) { _devices.value = transform(_devices.value) }
    }

    init {
        if (simulationTickMs != null) {
            scope.launch {
                var tick = 0L
                while (isActive) {
                    delay(simulationTickMs)
                    simulationTick(tick++)
                }
            }
        }
    }

    override suspend fun resume(knownNodes: Collection<ULong>) = adoptNodes(knownNodes)

    override suspend fun commission(
        setupCode: String,
        allowUncertified: Boolean,
        onProgress: (CommissioningStep) -> Unit,
    ): CommissioningResult {
        onProgress(CommissioningStep.PARSING)
        val parsed = when (val r = SetupCodeParser.parse(setupCode)) {
            is SetupCodeParser.ParseResult.Invalid -> return CommissioningResult.Failure(CommissioningFailure.INVALID_CODE, invalidCode = r.reason)
            is SetupCodeParser.ParseResult.Valid -> r.code
        }
        val existing = synchronized(lock) { commissionedCodes[parsed.key]?.takeIf { _devices.value.containsKey(it) } }
        if (existing != null) return CommissioningResult.AlreadyCommissioned(existing)

        onProgress(CommissioningStep.DISCOVERING)
        delay(latency() * 4)
        if (parsed.shortDiscriminator == 0) {
            return CommissioningResult.Failure(CommissioningFailure.DEVICE_NOT_FOUND, detail = "mock: no device in pairing mode")
        }
        // Simulierte neue Geräte per Bluetooth: Diskriminator 14 = Thread-Sensor, 13 = WLAN-Steckdose
        val newDevice = when (parsed.shortDiscriminator) {
            NEW_THREAD_DISCRIMINATOR -> {
                val ds = credentials?.threadDataset()
                    ?: return CommissioningResult.Failure(CommissioningFailure.NO_THREAD_NETWORK, detail = "mock: no Thread dataset")
                DeviceNetwork(NetworkTransport.THREAD, ThreadRole.SLEEPY_END_DEVICE, ds.networkName, ds.extPanId, ds.channel) to
                    listOf(TemperatureSensorCapability(21.5), HumiditySensorCapability(45.0), BatteryCapability(100))
            }
            NEW_WIFI_DISCRIMINATOR -> {
                credentials?.wifi() ?: return CommissioningResult.Failure(CommissioningFailure.NO_WIFI_CREDENTIALS, detail = "mock: no Wi-Fi")
                DeviceNetwork(NetworkTransport.WIFI) to listOf(SwitchCapability(isOn = false))
            }
            else -> null
        }
        for (step in listOf(
            CommissioningStep.PASE,
            CommissioningStep.ATTESTATION,
            CommissioningStep.NETWORK,
            CommissioningStep.OPERATIONAL,
            CommissioningStep.READING,
        )) {
            onProgress(step)
            delay(latency() * 3)
        }

        var nodeId: ULong = 0u
        mutate {
            nodeId = nextNodeId++
            commissionedCodes[parsed.key] = nodeId
            it + (nodeId to DeviceState(
                nodeId = nodeId,
                onlineState = OnlineState.ONLINE,
                capabilities = newDevice?.second ?: listOf(LightCapability(true, 100, 3000, 2200..6500)),
                lastSeenAt = clock.instant(),
                network = newDevice?.first ?: DeviceNetwork(NetworkTransport.THREAD, ThreadRole.ROUTER, DEMO_THREAD_NAME),
            ))
        }
        return CommissioningResult.Success(
            nodeId = nodeId,
            vendorName = parsed.vendorId?.let { "Hersteller 0x%04X".format(it) } ?: "Matter Test Vendor",
            productName = when (parsed.shortDiscriminator) {
                NEW_THREAD_DISCRIMINATOR -> "Virtual Thread Sensor"
                NEW_WIFI_DISCRIMINATOR -> "Virtual Wi-Fi Plug"
                else -> "Virtual Light"
            },
            suggestedName = null,
        )
    }

    // --- Multi-Admin (Simulation) -------------------------------------------------------------

    /** Weitere Admins je Node – raum. selbst ist immer Fabric-Index 1. */
    private val otherAdmins = MutableStateFlow<Map<ULong, List<AdminFabric>>>(emptyMap())
    private val windows = mutableMapOf<ULong, PairingWindow>()

    override val adminFabrics: StateFlow<Map<ULong, List<AdminFabric>>> =
        combine(_devices, otherAdmins) { devices, others ->
            devices.keys.associateWith { listOf(AdminFabric(1, TEST_VENDOR, "raum.", own = true)) + others[it].orEmpty() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun openPairingWindow(nodeId: ULong, timeout: Duration): PairingWindowResult {
        delay(latency())
        val s = _devices.value[nodeId] ?: return PairingWindowResult.Failure(CommandFailure.DEVICE_ERROR)
        if (s.onlineState != OnlineState.ONLINE) return PairingWindowResult.Failure(CommandFailure.OFFLINE)
        // Matter erlaubt mindestens 5 Fabrics je Gerät
        if ((otherAdmins.value[nodeId]?.size ?: 0) >= 4) return PairingWindowResult.Failure(CommandFailure.DEVICE_ERROR)
        val discriminator = SetupCodeGenerator.randomDiscriminator(random)
        val passcode = SetupCodeGenerator.randomPasscode(random)
        val window = PairingWindow(
            nodeId = nodeId,
            manualCode = SetupCodeGenerator.manualCode(discriminator, passcode),
            qrPayload = SetupCodeGenerator.qrPayload(TEST_VENDOR, TEST_PRODUCT, discriminator, passcode),
            expiresAt = clock.instant().plus(timeout),
        )
        synchronized(lock) { windows[nodeId] = window }
        return PairingWindowResult.Open(window)
    }

    override suspend fun closePairingWindow(nodeId: ULong) {
        synchronized(lock) { windows.remove(nodeId) }
    }

    override suspend fun removeAdmin(nodeId: ULong, fabricIndex: Int): CommandResult {
        delay(latency())
        if (fabricIndex == 1) return CommandResult.Failure(CommandFailure.UNSUPPORTED, "own fabric")
        if (_devices.value[nodeId]?.onlineState != OnlineState.ONLINE) return CommandResult.Failure(CommandFailure.OFFLINE)
        // Echte Geräte melden eine Ablehnung teils nur in der Antwort (NOCResponse) – der Befehl selbst „gelingt“
        if (nodeId in stuckAdmins) return CommandResult.Success
        otherAdmins.update { m -> m + (nodeId to m[nodeId].orEmpty().filterNot { it.fabricIndex == fabricIndex }) }
        return CommandResult.Success
    }

    override suspend fun readAdmins(nodeId: ULong): List<AdminFabric>? {
        delay(latency())
        if (_devices.value[nodeId]?.onlineState != OnlineState.ONLINE) return null
        // Direkt der aktuelle Stand (adminFabrics folgt asynchron) – wie ein frischer Lesezugriff beim Gerät
        return listOf(AdminFabric(1, TEST_VENDOR, "raum.", own = true)) + otherAdmins.value[nodeId].orEmpty()
    }

    private val stuckAdmins: MutableSet<ULong> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Test: Gerät bestätigt RemoveFabric, behält die fremde Fabric aber. */
    fun simulateStuckAdmins(nodeId: ULong) { stuckAdmins += nodeId }

    /** Test/Vorführung: eine andere App koppelt über das offene Fenster. */
    fun simulateExternalAdmin(nodeId: ULong, vendorId: Int, label: String): Boolean {
        val open = synchronized(lock) { windows.remove(nodeId) } ?: return false
        if (open.expiresAt.isBefore(clock.instant())) return false
        otherAdmins.update { m ->
            val list = m[nodeId].orEmpty()
            m + (nodeId to list + AdminFabric((list.maxOfOrNull { it.fabricIndex } ?: 1) + 1, vendorId, label, own = false))
        }
        return true
    }

    override suspend fun removeDevice(nodeId: ULong) {
        delay(latency())
        mutate {
            coverTargets.remove(nodeId)
            commissionedCodes.entries.removeAll { e -> e.value == nodeId }
            windows.remove(nodeId)
            otherAdmins.update { m -> m - nodeId }
            it - nodeId
        }
    }

    override suspend fun readCapabilities(nodeId: ULong): List<Capability> =
        _devices.value[nodeId]?.capabilities.orEmpty()

    override suspend fun execute(command: MatterCommand): CommandResult {
        delay(latency())
        val state = _devices.value[command.nodeId]
            ?: return CommandResult.Failure(CommandFailure.DEVICE_ERROR, "unknown node")
        if (state.onlineState != OnlineState.ONLINE) {
            delay(latency() * 3) // Timeout-ähnliches Verhalten
            return CommandResult.Failure(CommandFailure.OFFLINE, "no response")
        }
        if (!CapabilityReducer.supports(state.capabilities, command.command)) {
            return CommandResult.Failure(CommandFailure.UNSUPPORTED, "command not supported")
        }
        if (failureRate > 0 && random.nextDouble() < failureRate) {
            return CommandResult.Failure(CommandFailure.TIMEOUT, "response timeout")
        }
        mutate { map ->
            val s = map[command.nodeId] ?: return@mutate map
            trackCoverTarget(command, s.capabilities)
            val caps = CapabilityReducer.apply(s.capabilities, command.command).map(::settleSwitchPower)
            map + (command.nodeId to s.copy(capabilities = caps, lastSeenAt = clock.instant()))
        }
        return CommandResult.Success
    }

    override suspend fun resetFabric() {
        fabricStore?.putString(KEY_FABRIC, null)
        _fabric.value = null
        mutate {
            coverTargets.clear()
            commissionedCodes.clear()
            windows.clear()
            otherAdmins.value = emptyMap()
            emptyMap()
        }
    }

    override fun observeDevice(nodeId: ULong): Flow<DeviceState> =
        devices.map { it[nodeId] }.filterNotNull().distinctUntilChanged()

    // --- Test-/Entwicklungshilfen -------------------------------------------------

    /**
     * Der Mock hält seinen Zustand nur im Speicher. Nach einem Neustart werden zuvor
     * hinzugefügte Nodes, die raum. noch kennt, als virtuelle Leuchten wiederhergestellt –
     * so wie ein echter Controller seine Fabric-Nodes wieder erreicht.
     */
    fun adoptNodes(nodeIds: Collection<ULong>) {
        mutate { map ->
            val missing = nodeIds.filterNot { it in map }
            if (missing.isEmpty()) return@mutate map
            nextNodeId = maxOf(nextNodeId, missing.max() + 1u)
            val demo = MockHomeSeed.devices.associateBy { it.nodeId }
            map + missing.associateWith { nodeId ->
                // Bekannte Beispielgeräte kommen mit ihren echten Fähigkeiten zurück, alles andere als Leuchte.
                val seedDevice = demo[nodeId]
                DeviceState(
                    nodeId = nodeId,
                    onlineState = if (seedDevice?.online == false) OnlineState.OFFLINE else OnlineState.ONLINE,
                    capabilities = seedDevice?.capabilities ?: listOf(LightCapability(false, 100, 3000, 2200..6500, colorMode = null)),
                    lastSeenAt = clock.instant(),
                )
            }
        }
    }

    /** Simuliert eine Zustandsänderung außerhalb von raum. (z. B. Wandtaster, andere App). */
    fun simulateExternalChange(nodeId: ULong, transform: (List<Capability>) -> List<Capability>) =
        updateNode(nodeId, transform)

    fun setOnline(nodeId: ULong, online: Boolean) {
        mutate { map ->
            val s = map[nodeId] ?: return@mutate map
            map + (nodeId to s.copy(
                onlineState = if (online) OnlineState.ONLINE else OnlineState.OFFLINE,
                lastSeenAt = if (online) clock.instant() else s.lastSeenAt,
            ))
        }
    }

    // --- Simulation --------------------------------------------------------------

    private fun latency(): Long =
        if (latencyMs.first >= latencyMs.last) latencyMs.first else random.nextLong(latencyMs.first, latencyMs.last)

    private fun updateNode(nodeId: ULong, transform: (List<Capability>) -> List<Capability>) {
        mutate { map ->
            val s = map[nodeId] ?: return@mutate map
            map + (nodeId to s.copy(capabilities = transform(s.capabilities), lastSeenAt = clock.instant()))
        }
    }

    private fun trackCoverTarget(command: MatterCommand, caps: List<Capability>) {
        if (caps.none { it is CoverCapability }) return
        when (val c = command.command) {
            is DeviceCommand.SetCoverPosition -> coverTargets[command.nodeId] = c.openPercent.coerceIn(0, 100)
            DeviceCommand.OpenCover -> coverTargets[command.nodeId] = 100
            DeviceCommand.CloseCover -> coverTargets[command.nodeId] = 0
            DeviceCommand.StopCover -> coverTargets.remove(command.nodeId)
            else -> Unit
        }
    }

    private fun settleSwitchPower(cap: Capability): Capability =
        if (cap is SwitchCapability && cap.powerWatts != null) {
            cap.copy(powerWatts = if (cap.isOn) (if (cap.powerWatts > 0) cap.powerWatts else 35.0) else 0.0)
        } else cap

    internal fun simulationTick(tick: Long) {
        val now: Instant = clock.instant()
        mutate { map ->
            map.mapValues { (nodeId, state) ->
                if (state.onlineState != OnlineState.ONLINE) return@mapValues state
                val caps = state.capabilities.map { cap -> simulate(nodeId, cap, tick) }
                if (caps == state.capabilities) state else state.copy(capabilities = caps, lastSeenAt = now)
            }
        }
    }

    private fun simulate(nodeId: ULong, cap: Capability, tick: Long): Capability = when (cap) {
        is CoverCapability -> {
            val target = coverTargets[nodeId]
            if (target == null || cap.movement == CoverMovement.STOPPED) {
                coverTargets.remove(nodeId)
                cap.copy(movement = CoverMovement.STOPPED)
            } else {
                val step = 5
                val next = if (target > cap.openPercent) minOf(target, cap.openPercent + step)
                else maxOf(target, cap.openPercent - step)
                if (next == target) {
                    coverTargets.remove(nodeId)
                    cap.copy(openPercent = next, movement = CoverMovement.STOPPED)
                } else cap.copy(openPercent = next)
            }
        }
        is ThermostatCapability -> {
            val current = cap.currentCelsius ?: return cap
            if (tick % 5 != 0L) return cap
            val goal = if (cap.mode == ThermostatMode.OFF) 17.0 else cap.targetCelsius
            val delta = goal - current
            if (abs(delta) < 0.05) cap else cap.copy(currentCelsius = round1(current + delta.coerceIn(-0.1, 0.1)))
        }
        is TemperatureSensorCapability ->
            if (tick % 30 == 0L) cap.copy(celsius = round1(cap.celsius + random.nextInt(-1, 2) * 0.1)) else cap
        is HumiditySensorCapability ->
            if (tick % 45 == 0L) cap.copy(percent = (cap.percent + random.nextInt(-1, 2)).coerceIn(20.0, 90.0)) else cap
        is OccupancyCapability ->
            if (tick % 20 == 0L && random.nextDouble() < 0.3) cap.copy(isOccupied = !cap.isOccupied) else cap
        is SwitchCapability ->
            if (cap.isOn && cap.powerWatts != null && tick % 3 == 0L) {
                cap.copy(powerWatts = round1((cap.powerWatts + random.nextDouble(-1.5, 1.5)).coerceAtLeast(1.0)))
            } else cap
        else -> cap
    }

    private fun round1(v: Double): Double = round(v * 10) / 10

    private companion object {
        /** Test-Hersteller-ID der CSA (bis raum. eine eigene hat) */
        const val TEST_VENDOR = 0xFFF1
        const val TEST_PRODUCT = 0x8000
        const val KEY_FABRIC = "mock_fabric"
        const val NEW_THREAD_DISCRIMINATOR = 14
        const val NEW_WIFI_DISCRIMINATOR = 13
        const val DEMO_THREAD_NAME = "raum-demo"

        /** Demo-Wohnung: Sensoren als schlafende Thread-Geräte, Leuchten als Thread-Router, der Rest im WLAN. */
        fun demoNetwork(caps: List<Capability>): DeviceNetwork = when {
            caps.any { it is LightCapability } -> DeviceNetwork(NetworkTransport.THREAD, ThreadRole.ROUTER, DEMO_THREAD_NAME, DEMO_XP, 15)
            caps.all { it is TemperatureSensorCapability || it is HumiditySensorCapability || it is BatteryCapability ||
                it is ContactSensorCapability || it is OccupancyCapability } ->
                DeviceNetwork(NetworkTransport.THREAD, ThreadRole.SLEEPY_END_DEVICE, DEMO_THREAD_NAME, DEMO_XP, 15)
            else -> DeviceNetwork(NetworkTransport.WIFI)
        }
        private const val DEMO_XP = "dead00beef00cafe"
    }
}
