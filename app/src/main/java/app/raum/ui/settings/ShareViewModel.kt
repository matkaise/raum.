package app.raum.ui.settings

import app.raum.data.preferences.SettingsStore
import app.raum.matter.bridge.BridgeState
import app.raum.matter.bridge.MockMatterBridge
import app.raum.matter.bridge.MatterBridge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.domain.models.Device
import app.raum.domain.usecases.DeviceService
import app.raum.i18n.Strings
import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.Ecosystems
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.PairingWindow
import app.raum.matter.controller.PairingWindowResult
import app.raum.matter.controller.mock.MockMatterController
import app.raum.security.AdminPinStore
import app.raum.security.AdminPinStore.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Laufender Teilen-Vorgang (ein Gerät oder mehrere nacheinander). */
data class ShareSession(
    val queue: List<Device>,
    val index: Int = 0,
    val window: PairingWindow? = null,
    val opening: Boolean = true,
    val error: CommandFailure? = null,
    /** Andere App hat gekoppelt */
    val joined: AdminFabric? = null,
) {
    val device: Device get() = queue[index]
    val hasNext: Boolean get() = index < queue.lastIndex
}

/** Kopplung der Bridge (raum. als Ganzes). */
data class BridgeSession(
    val window: PairingWindow? = null,
    val opening: Boolean = true,
    val error: CommandFailure? = null,
    val joined: AdminFabric? = null,
)

/**
 * Geräte mit anderen Apps teilen (Matter Multi-Admin). raum. öffnet am Gerät ein Kopplungsfenster;
 * die andere App (Apple Home, Google Home, Alexa, SmartThings …) koppelt mit dem angezeigten Code.
 */
class ShareViewModel(
    private val controller: MatterController,
    deviceService: DeviceService,
    private val pins: AdminPinStore,
    private val log: EventLog,
    private val strings: Strings,
    private val bridge: MatterBridge,
    private val settings: SettingsStore,
    private val messages: app.raum.domain.usecases.UiMessageBus,
) : ViewModel() {
    // --- Bridge: alles auf einmal ---
    val bridgeState: StateFlow<BridgeState> = bridge.state
    val bridgeEnabled: StateFlow<Boolean> = settings.bridgeEnabled
    val bridgeExcluded: StateFlow<Set<java.util.UUID>> = settings.bridgeExcluded
    val canSimulateBridge: Boolean = bridge is MockMatterBridge

    /** Echtheitszertifikat der echten Bridge importieren (Inbetriebnahme). Meldung über die Nachrichtenleiste. */
    val canImportAttestation: Boolean = bridge is app.raum.matter.bridge.ChipMatterBridge

    fun importAttestation(open: () -> java.io.InputStream?) {
        val real = bridge as? app.raum.matter.bridge.ChipMatterBridge ?: return
        viewModelScope.launch {
            val stream = runCatching { open() }.getOrNull()
            val result = stream?.use { real.importAttestation(it) }
            val text = when (result) {
                app.raum.matter.bridge.AttestationStore.ImportResult.Ok -> {
                    log.info(app.raum.diagnostics.LogCategory.SYSTEM, strings.get(R.string.log_bridge_attestation_imported))
                    strings.get(R.string.bridge_attestation_imported)
                }
                is app.raum.matter.bridge.AttestationStore.ImportResult.Invalid -> strings.get(
                    when (result.reason) {
                        app.raum.matter.bridge.AttestationStore.Reason.INCOMPLETE -> R.string.bridge_attestation_incomplete
                        app.raum.matter.bridge.AttestationStore.Reason.BAD_CERTIFICATE -> R.string.bridge_attestation_bad
                        app.raum.matter.bridge.AttestationStore.Reason.CHAIN -> R.string.bridge_attestation_chain
                        app.raum.matter.bridge.AttestationStore.Reason.WRONG_IDS -> R.string.bridge_attestation_ids
                        app.raum.matter.bridge.AttestationStore.Reason.KEY_MISMATCH -> R.string.bridge_attestation_key
                        app.raum.matter.bridge.AttestationStore.Reason.NO_REQUEST -> R.string.bridge_attestation_no_request
                    },
                )
                null -> strings.get(R.string.bridge_attestation_bad)
            }
            messages.info(text)
            refreshPending()
        }
    }

    private val _attestationPending = kotlinx.coroutines.flow.MutableStateFlow<java.time.Instant?>(null)
    /** Offene Zertifikatsanforderung (Schlüssel im Keystore wartet auf sein Zertifikat) */
    val attestationPending: StateFlow<java.time.Instant?> = _attestationPending

    fun refreshPending() {
        (bridge as? app.raum.matter.bridge.ChipMatterBridge)?.let { _attestationPending.value = it.pendingAttestationRequest() }
    }

    /** Schlüssel im Keystore erzeugen und die Anforderung über [write] (Dateiauswahl) speichern. */
    fun createAttestationRequest(write: (String) -> Unit) {
        val real = bridge as? app.raum.matter.bridge.ChipMatterBridge ?: return
        viewModelScope.launch {
            runCatching {
                val pem = real.createAttestationRequest()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { write(pem) }
            }.onSuccess {
                log.info(app.raum.diagnostics.LogCategory.SYSTEM, strings.get(R.string.log_bridge_attestation_request))
                messages.info(strings.get(R.string.bridge_attestation_request_saved))
            }.onFailure { messages.error(strings.get(R.string.bridge_attestation_request_failed)) }
            refreshPending()
        }
    }

    /** Kann die Bridge dieses Gerät zeigen? Die echte Bridge kennt (noch) keine Thermostate und Storen. */
    fun bridgeSupports(d: app.raum.domain.models.Device): Boolean =
        bridge !is app.raum.matter.bridge.ChipMatterBridge || app.raum.matter.bridge.BridgeMapping.kind(d) != null

    private val _bridgeSession = MutableStateFlow<BridgeSession?>(null)
    val bridgeSession: StateFlow<BridgeSession?> = _bridgeSession.asStateFlow()
    private var bridgeBaseline = 0

    fun setBridgeEnabled(on: Boolean) {
        settings.setBridgeEnabled(on)
        log.info(LogCategory.SYSTEM, strings.get(if (on) R.string.log_bridge_on else R.string.log_bridge_off))
    }

    fun setExcluded(ids: Set<java.util.UUID>) = settings.setBridgeExcluded(ids)

    fun openBridge() {
        bridgeBaseline = bridge.state.value.admins.size
        _bridgeSession.value = BridgeSession()
        viewModelScope.launch {
            when (val r = bridge.openPairingWindow()) {
                is PairingWindowResult.Open -> {
                    _bridgeSession.update { it?.copy(opening = false, window = r.window) }
                    log.info(LogCategory.SYSTEM, strings.get(R.string.log_bridge_open))
                }
                is PairingWindowResult.Failure -> _bridgeSession.update { it?.copy(opening = false, error = r.reason) }
            }
        }
    }

    fun closeBridge() {
        if (_bridgeSession.value?.window != null) viewModelScope.launch { bridge.closePairingWindow() }
        _bridgeSession.value = null
    }

    fun removeBridgeAdmin(f: AdminFabric) {
        viewModelScope.launch {
            bridge.removeAdmin(f.fabricIndex)
            log.warning(LogCategory.SYSTEM, strings.get(R.string.log_bridge_removed, Ecosystems.name(f) ?: "?"))
        }
    }

    fun simulateBridgeJoin(vendorId: Int) { (bridge as? MockMatterBridge)?.simulateJoin(vendorId) }

    val devices: StateFlow<List<Device>> = deviceService.devices
    val admins: StateFlow<Map<ULong, List<AdminFabric>>> = controller.adminFabrics
    val canSimulate: Boolean = controller is MockMatterController

    private val _session = MutableStateFlow<ShareSession?>(null)
    val session: StateFlow<ShareSession?> = _session.asStateFlow()

    /** Teilen gibt anderen Kontrolle über Geräte – mit PIN geschützt, wenn eine festgelegt ist. */
    val pinRequired: Boolean get() = pins.isSet
    fun verifyPin(pin: String): VerifyResult = pins.verify(pin)

    private var baseline = 0

    init {
        // Bridge-Kopplung erkennen
        viewModelScope.launch {
            bridge.state.collect { st ->
                val s = _bridgeSession.value ?: return@collect
                if (s.joined == null && s.window != null && st.admins.size > bridgeBaseline) {
                    val added = st.admins.last()
                    _bridgeSession.update { it?.copy(joined = added, window = null) }
                    log.info(LogCategory.SYSTEM, strings.get(R.string.log_bridge_joined, Ecosystems.name(added) ?: "?"))
                }
            }
        }
        // Kopplung erkennen: eine weitere Fabric taucht am aktuellen Gerät auf
        viewModelScope.launch {
            controller.adminFabrics.collect { map ->
                val s = _session.value ?: return@collect
                if (s.joined != null || s.window == null) return@collect
                val others = map[s.device.matterNodeId].orEmpty().filterNot { it.own }
                if (others.size > baseline) {
                    val added = others.last()
                    _session.update { it?.copy(joined = added, window = null) }
                    log.info(LogCategory.DEVICE, strings.get(R.string.log_share_joined, s.device.displayName, Ecosystems.name(added) ?: "?"))
                }
            }
        }
    }

    fun start(queue: List<Device>) {
        if (queue.isEmpty()) return
        _session.value = ShareSession(queue)
        open()
    }

    fun renew() = open()

    fun next() {
        val s = _session.value ?: return
        closeWindow(s)
        if (!s.hasNext) { _session.value = null; return }
        _session.value = ShareSession(s.queue, s.index + 1)
        open()
    }

    fun close() {
        _session.value?.let(::closeWindow)
        _session.value = null
    }

    private fun closeWindow(s: ShareSession) {
        val node = s.device.matterNodeId
        if (s.window != null) viewModelScope.launch { controller.closePairingWindow(node) }
    }

    private fun open() {
        val s = _session.value ?: return
        val device = s.device
        baseline = admins.value[device.matterNodeId].orEmpty().count { !it.own }
        _session.update { it?.copy(opening = true, error = null, window = null, joined = null) }
        viewModelScope.launch {
            when (val r = controller.openPairingWindow(device.matterNodeId)) {
                is PairingWindowResult.Open -> {
                    _session.update { it?.copy(opening = false, window = r.window) }
                    log.info(LogCategory.DEVICE, strings.get(R.string.log_share_open, device.displayName))
                }
                is PairingWindowResult.Failure -> _session.update { it?.copy(opening = false, error = r.reason) }
            }
        }
    }

    fun removeAdmin(device: Device, fabric: AdminFabric) {
        viewModelScope.launch {
            val r = controller.removeAdmin(device.matterNodeId, fabric.fabricIndex)
            if (r is CommandResult.Success) {
                log.warning(LogCategory.DEVICE, strings.get(R.string.log_share_removed, device.displayName, Ecosystems.name(fabric) ?: "?"))
            }
        }
    }

    /** Nur Simulation: so tun, als hätte die andere App gekoppelt. */
    fun simulateJoin(vendorId: Int, label: String) {
        val s = _session.value ?: return
        (controller as? MockMatterController)?.simulateExternalAdmin(s.device.matterNodeId, vendorId, label)
    }
}
