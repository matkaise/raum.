package app.raum.matter.chip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import app.raum.domain.models.Capability
import app.raum.domain.models.DeviceState
import app.raum.domain.models.OnlineState
import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.CommissioningFailure
import app.raum.matter.controller.CommissioningResult
import app.raum.matter.controller.CommissioningStep
import app.raum.matter.controller.CredentialStorage
import app.raum.matter.controller.FabricInfo
import app.raum.matter.controller.MatterCommand
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.PairingWindow
import app.raum.matter.controller.PairingWindowResult
import app.raum.platform.bluetooth.BluetoothAccess
import app.raum.security.KeyValueStore
import app.raum.thread.NetworkCredentialStore
import chip.devicecontroller.NetworkCredentials
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.CommissionParameters
import chip.devicecontroller.ControllerParams
import chip.devicecontroller.DeviceAttestationDelegate
import chip.devicecontroller.GetConnectedDeviceCallbackJni.GetConnectedDeviceCallback
import chip.devicecontroller.ICDDeviceInfo
import chip.devicecontroller.ICDRegistrationInfo
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.OpenCommissioningCallback
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.ResubscriptionAttemptCallback
import chip.devicecontroller.SubscriptionEstablishedCallback
import chip.devicecontroller.UnpairDeviceCallback
import chip.devicecontroller.WriteAttributesCallback
import chip.devicecontroller.model.AttributeWriteRequest
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.ChipPathId
import chip.devicecontroller.model.InvokeElement
import chip.devicecontroller.model.NodeState
import chip.devicecontroller.model.Status
import chip.platform.AndroidBleManager
import chip.platform.AndroidChipPlatform
import chip.platform.AndroidNfcCommissioningManager
import chip.platform.ChipMdnsCallbackImpl
import chip.platform.DiagnosticDataProviderImpl
import chip.platform.NsdManagerServiceBrowser
import chip.platform.NsdManagerServiceResolver
import chip.platform.PreferencesConfigurationManager
import chip.platform.PreferencesKeyValueStoreManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Echter Matter-Controller auf Basis des offiziellen Matter-SDK (connectedhomeip, Android-JNI).
 *
 * - Fabric: Root-CA und Schlüssel erzeugt das SDK beim ersten Start und legt sie im Schlüsselspeicher ab.
 * - Koppeln: per Setup-Code – über das Netzwerk (Multi-Admin aus Apple/Google/Alexa) oder, bei neuen Geräten,
 *   per Bluetooth LE mit Übergabe der Thread- bzw. WLAN-Zugangsdaten aus [NetworkCredentialStore] (M6).
 * - Zustände: Abo auf die relevanten Cluster, Übersetzung durch [ClusterMapper].
 * - Letzte Attributwerte je Node werden zwischengespeichert, damit Karten nach einem Neustart sofort Werte zeigen
 *   (schlafende Thread-Sensoren melden sich teils erst nach Minuten).
 */
class ChipMatterController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: KeyValueStore,
    private val credentials: NetworkCredentialStore,
    private val bluetooth: BluetoothAccess,
    private val clock: Clock = Clock.systemUTC(),
) : MatterController {

    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())
    private val cacheDir = File(context.filesDir, "matter-cache").apply { mkdirs() }

    private val nodeData = ConcurrentHashMap<ULong, NodeData>()
    private val rawTlv = ConcurrentHashMap<ULong, MutableMap<AttrPath, ByteArray>>()
    private val online = ConcurrentHashMap<ULong, OnlineState>()
    private val lastSeen = ConcurrentHashMap<ULong, Instant>()
    /**
     * Nodes mit bestehendem Abo. Das SDK prüft dort selbst die Lebenszeichen des Geräts (leere Berichte im
     * ausgehandelten Intervall) und baut das Abo bei Ausbleiben neu auf – leere Berichte erreichen die App aber nicht.
     * Ein Schalter, an dem sich nichts ändert, ist also erreichbar, auch wenn keine Werte kommen.
     */
    private val subscribed: MutableSet<ULong> = ConcurrentHashMap.newKeySet()

    private val _devices = MutableStateFlow<Map<ULong, DeviceState>>(emptyMap())
    override val devices: StateFlow<Map<ULong, DeviceState>> = _devices.asStateFlow()

    private val _admins = MutableStateFlow<Map<ULong, List<AdminFabric>>>(emptyMap())
    override val adminFabrics: StateFlow<Map<ULong, List<AdminFabric>>> = _admins.asStateFlow()

    private val _fabric = MutableStateFlow(
        store.getString(KEY_FABRIC)?.split(":")?.takeIf { it.size == 2 }?.let { (id, ts) ->
            runCatching { FabricInfo(id.toULong(16), Instant.ofEpochMilli(ts.toLong())) }.getOrNull()
        },
    )
    override val fabric: StateFlow<FabricInfo?> = _fabric.asStateFlow()

    // --- SDK ---------------------------------------------------------------------------------

    private val initLock = Mutex()
    @Volatile private var chip: ChipDeviceController? = null
    private var kvs: EncryptedKeyValueStore? = null

    private val _credentialStorage = MutableStateFlow<CredentialStorage?>(null)
    override val credentialStorage: StateFlow<CredentialStorage?> = _credentialStorage.asStateFlow()

    /**
     * Verschlüsselter Schlüsselspeicher; übernimmt beim ersten Mal den Klartextspeicher des SDK.
     * Scheitert das, bleibt es beim bisherigen Speicher – die Fabric darf nie verloren gehen.
     */
    private fun keyValueStore(): chip.platform.KeyValueStoreManager = runCatching { EncryptedKeyValueStore(context) }
        .onSuccess { s ->
            kvs = s
            if (s.migrated > 0) Log.i(TAG, "Fabric-Schlüssel in den Keystore-Speicher übernommen (${s.migrated})")
            _credentialStorage.value = CredentialStorage(encrypted = true, protection = s.protection, unreadable = s.unreadable)
        }
        .getOrElse { e ->
            Log.e(TAG, "Verschlüsselter Schlüsselspeicher nicht verfügbar – Klartextspeicher bleibt", e)
            _credentialStorage.value = CredentialStorage(encrypted = false, protection = app.raum.security.KeyProtection.UNKNOWN)
            PreferencesKeyValueStoreManager(context)
        }

    /** SDK erst bei Bedarf laden (JNI, Plattform, Controller) – alles auf dem Hauptthread wie in der Beispiel-App. */
    private suspend fun controller(): ChipDeviceController = initLock.withLock {
        chip ?: withContext(Dispatchers.Main) {
            ChipDeviceController.loadJni()
            AndroidChipPlatform(
                AndroidBleManager(context),
                AndroidNfcCommissioningManager(),
                keyValueStore(),
                PreferencesConfigurationManager(context),
                NsdManagerServiceResolver(context, NsdManagerServiceResolver.NsdManagerResolverAvailState()),
                NsdManagerServiceBrowser(context),
                ChipMdnsCallbackImpl(),
                DiagnosticDataProviderImpl(context),
            )
            ChipDeviceController(
                ControllerParams.newBuilder()
                    .setControllerVendorId(VENDOR_ID)
                    .setEnableServerInteractions(true) // ICD-Check-ins schlafender Geräte
                    .build(),
            ).also { c ->
                val trust = PaaTrustStore(context)
                Log.i(TAG, "PAA-Stammzertifikate: ${trust.size}")
                c.setAttestationTrustStoreDelegate(trust)
                chip = c
            }
        }
    }

    override suspend fun ensureFabric(): FabricInfo {
        _fabric.value?.let { return it }
        val c = controller()
        val info = FabricInfo(c.compressedFabricId.toULong(), clock.instant())
        store.putString(KEY_FABRIC, "${info.fabricId.toString(16)}:${info.createdAt.toEpochMilli()}")
        _fabric.value = info
        return info
    }

    // --- Nodes -------------------------------------------------------------------------------

    override suspend fun resume(knownNodes: Collection<ULong>) {
        // Nur selbst gekoppelte Nodes verbinden. Andere (z. B. aus der Simulation) bleiben „nicht erreichbar“,
        // ohne Verbindungsversuch – Android löst mDNS-Namen nur einzeln auf, vergebliche Versuche würden
        // jede Kopplung blockieren.
        val own = storedNodes()
        (knownNodes.toSet() - own).forEach { online[it] = OnlineState.OFFLINE }
        own.forEach { node ->
            loadCache(node)
            // Kürzlich gesehene Geräte gelten bis zum Beweis des Gegenteils als erreichbar
            online[node] = if (recentlySeen(node)) OnlineState.ONLINE else OnlineState.UNKNOWN
        }
        startLivenessWatch()
        publish()
        if (_fabric.value == null) return
        controller()
        kvs?.let { s -> _credentialStorage.value = _credentialStorage.value?.copy(unreadable = s.unreadable) }
        own.forEach { node -> scope.launch { subscribe(node) } }
        scanOwnFabric()
    }

    private fun recentlySeen(node: ULong): Boolean =
        lastSeen[node]?.let { Duration.between(it, clock.instant()) < OFFLINE_GRACE } == true

    private var livenessJob: kotlinx.coroutines.Job? = null

    /** Minütlich: Geräte ohne Kontakt seit der Karenzzeit als offline markieren (CTRL-005). */
    private fun startLivenessWatch() {
        if (livenessJob?.isActive == true) return
        livenessJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                var changed = false
                val now = clock.instant()
                storedNodes().forEach { n ->
                    when {
                        // Abo steht: Gerät hat sich im Abo-Intervall gemeldet (sonst hätte das SDK neu aufgebaut)
                        n in subscribed -> {
                            lastSeen[n] = now
                            if (online[n] != OnlineState.ONLINE) online[n] = OnlineState.ONLINE
                            changed = true
                        }
                        online[n] == OnlineState.ONLINE && !recentlySeen(n) -> { online[n] = OnlineState.OFFLINE; changed = true }
                    }
                }
                if (changed) publish()
            }
        }
    }

    // --- Selbstheilung: Knoten der eigenen Fabric, die raum. nicht kennt ----------------------

    /** Übernimmt einen Knoten unserer Fabric (idempotent): speichern, anzeigen, abonnieren. */
    private fun adopt(node: ULong) {
        if (node in storedNodes() || node in removedNodes()) return
        if (chip?.let { runCatching { it.controllerNodeId.toULong() }.getOrNull() } == node) return
        Log.i(TAG, "Übernehme Node 0x%X".format(node.toLong()))
        rememberNode(node, keep = true)
        online[node] = OnlineState.UNKNOWN
        publish()
        scope.launch { subscribe(node) }
    }

    /**
     * Sucht im Netz nach Betriebsankündigungen unserer Fabric („<Fabric-ID>-<Node-ID>“ in _matter._tcp).
     * So geht kein Gerät verloren, dessen Kopplung abgebrochen wurde, nachdem das Gerät sie schon abgeschlossen hatte.
     */
    private fun scanOwnFabric() {
        val fabric = _fabric.value ?: return
        val nsd = context.getSystemService(android.net.nsd.NsdManager::class.java) ?: return
        val prefix = "%016X-".format(fabric.fabricId.toLong())
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (!name.startsWith(prefix, ignoreCase = true)) return
                name.substring(prefix.length).toULongOrNull(16)?.let(::adopt)
            }
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }
        runCatching { nsd.discoverServices("_matter._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
        scope.launch {
            kotlinx.coroutines.delay(SCAN_MS)
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private fun removedNodes(): Set<ULong> =
        store.getString(KEY_REMOVED)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toULongOrNull(16) }?.toSet().orEmpty()

    private fun storedNodes(): Set<ULong> =
        store.getString(KEY_NODES)?.split(",")?.filter { it.isNotBlank() }?.mapNotNull { it.toULongOrNull(16) }?.toSet().orEmpty()

    private fun rememberNode(node: ULong, keep: Boolean) {
        val nodes = if (keep) storedNodes() + node else storedNodes() - node
        store.putString(KEY_NODES, nodes.joinToString(",") { it.toString(16) })
    }

    private fun newNodeId(): ULong {
        val used = storedNodes() + nodeData.keys
        while (true) {
            // operativer Bereich 0x0000_0000_0000_0001 … 0xFFFF_FFEF_FFFF_FFFF
            val id = (random.nextLong().toULong() and 0x0000_FFFF_FFFF_FFFFuL) or 0x0001_0000_0000_0000uL
            if (id !in used) return id
        }
    }

    private fun publish() {
        val nodes = storedNodes() + nodeData.keys + online.keys
        _devices.value = nodes.associateWith { node ->
            val data = nodeData[node]
            val info = data?.let(ClusterMapper::info)
            DeviceState(
                nodeId = node,
                onlineState = online[node] ?: OnlineState.UNKNOWN,
                capabilities = data?.let(ClusterMapper::capabilities).orEmpty(),
                lastSeenAt = lastSeen[node],
                vendorName = info?.vendorName,
                productName = info?.productName,
                label = info?.nodeLabel,
                network = data?.let(ClusterMapper::network),
                channels = data?.let(ClusterMapper::channels).orEmpty(),
            )
        }
        _admins.value = nodes.associateWith { node -> nodeData[node]?.let(ClusterMapper::admins).orEmpty() }
    }

    override fun observeDevice(nodeId: ULong): Flow<DeviceState> =
        devices.map { it[nodeId] }.filterNotNull().distinctUntilChanged()

    override suspend fun readCapabilities(nodeId: ULong): List<Capability> =
        nodeData[nodeId]?.let(ClusterMapper::capabilities).orEmpty()

    // --- Koppeln -----------------------------------------------------------------------------

    override suspend fun commission(
        setupCode: String,
        allowUncertified: Boolean,
        onProgress: (CommissioningStep) -> Unit,
    ): CommissioningResult {
        onProgress(CommissioningStep.PARSING)
        ensureFabric()
        val c = controller()
        val nodeId = newNodeId()
        val done = CompletableDeferred<Long>()
        var failedStage: String? = null
        // Neue Geräte (noch in keinem Netz) findet das SDK nur per Bluetooth – ohne Berechtigung/Adapter nur übers Netz
        val useBle = bluetooth.ready
        // Welches Netz das Gerät braucht, erfahren wir erst nach dem Verbindungsaufbau (onReadCommissioningInfo)
        var needs: NetworkTransportNeed? = null
        // Hat sich überhaupt ein Gerät gemeldet? Die Netzwerksuche des SDK hat selbst kein Zeitlimit.
        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        Log.i(TAG, "Kopplung startet: Node 0x%X, Bluetooth %s".format(nodeId.toLong(), if (useBle) "an" else "aus"))

        c.setCompletionListener(object : ChipDeviceController.CompletionListener {
            override fun onCommissioningStageStart(nodeId: Long, stage: String?) { found.set(true); stage?.let(::stepOf)?.let(onProgress) }
            override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
                Log.i(TAG, "Kopplung abgeschlossen: Node 0x%X, Fehler 0x%X".format(nodeId, errorCode))
                // Sofort übernehmen – auch wenn der Dialog inzwischen geschlossen wurde
                if (errorCode == 0L) adopt(nodeId.toULong())
                done.complete(errorCode)
            }
            override fun onPairingComplete(errorCode: Long) {
                if (errorCode == 0L) found.set(true) else done.complete(errorCode)
            }
            override fun onError(error: Throwable?) { Log.w(TAG, "Kopplung", error); done.complete(ERROR_GENERIC) }
            override fun onICDRegistrationInfoRequired() {
                // Schlafende Geräte (ICD): Standardregistrierung wie die Beispiel-App
                main.post { c.updateCommissioningICDRegistrationInfo(ICDRegistrationInfo.newBuilder().build()) }
            }
            override fun onConnectDeviceComplete() { found.set(true) }
            override fun onStatusUpdate(status: Int) {
                Log.i(TAG, "PASE-Status $status")
                if (status == STATUS_PASE_SUCCESS) { found.set(true); return }
                // SecurePairingFailed ohne vorherigen Kontakt: Suche des SDK (30 s) ohne Treffer oder Code passt nicht.
                // Kommt kein genauerer Fehler mehr (onPairingComplete), als „nicht gefunden“ werten.
                if (status == STATUS_PASE_FAILED) scope.launch {
                    kotlinx.coroutines.delay(2_000)
                    done.complete(ERROR_NOT_FOUND)
                }
            }
            override fun onPairingDeleted(errorCode: Long) {}
            override fun onReadCommissioningInfo(vendorId: Int, productId: Int, wifiEndpointId: Int, threadEndpointId: Int) {
                val thread = threadEndpointId in 0 until INVALID_ENDPOINT
                val wifi = wifiEndpointId in 0 until INVALID_ENDPOINT
                needs = when { thread -> NetworkTransportNeed.THREAD; wifi -> NetworkTransportNeed.WIFI; else -> null }
                Log.i(TAG, "Gerät 0x%04X/0x%04X: WLAN-Endpunkt %d, Thread-Endpunkt %d".format(vendorId, productId, wifiEndpointId, threadEndpointId))
                // Passende Zugangsdaten nachreichen – nur, was das Gerät kann. Läuft vor unserer Entscheidung zur
                // Echtheitsprüfung (ebenfalls auf dem Hauptthread), also bevor das SDK das Netzwerk einrichtet.
                val network = networkCredentialsFor(thread, wifi) ?: return
                main.post {
                    runCatching { c.updateCommissioningNetworkCredentials(network) }
                        .onSuccess { Log.i(TAG, "Zugangsdaten für ${needs} bereitgestellt") }
                        .onFailure { Log.w(TAG, "Zugangsdaten", it) }
                }
            }
            override fun onCommissioningStatusUpdate(nodeId: Long, stage: String?, errorCode: Long) {
                // Übergangene Echtheitsprüfung („Trotzdem hinzufügen“) ist kein Fehler – sonst verdeckt sie spätere
                if (errorCode != 0L && failedStage == null && !(allowUncertified && stage?.contains("Attestation") == true)) failedStage = stage
            }
            override fun onNotifyChipConnectionClosed() {}
            override fun onCloseBleComplete() {}
            override fun onOpCSRGenerationComplete(csr: ByteArray?) {}
            override fun onICDRegistrationComplete(errorCode: Long, icdDeviceInfo: ICDDeviceInfo?) {}
        })
        // Nach der Echtheitsprüfung wartet das SDK auf unsere Entscheidung
        c.setDeviceAttestationDelegate(ATTESTATION_FAILSAFE_S, DeviceAttestationDelegate { devicePtr, _, errorCode ->
            Log.i(TAG, "Echtheitsprüfung: Ergebnis $errorCode, trotzdem fortfahren: $allowUncertified")
            main.post { c.continueCommissioning(devicePtr, errorCode == 0L || allowUncertified) }
        })

        onProgress(CommissioningStep.DISCOVERING)
        withContext(Dispatchers.Main) {
            // Netzwerk (Multi-Admin, WLAN/LAN) und – falls möglich – zugleich Bluetooth (neues Gerät)
            c.pairDeviceWithCode(nodeId.toLong(), setupCode.trim(), false, !useBle, CommissionParameters.Builder().build())
        }
        val err = try {
            // Phase 1: Suche. Meldet sich kein Gerät, abbrechen statt minutenlang zu warten.
            val appeared = withTimeoutOrNull(DISCOVERY_TIMEOUT.toMillis()) {
                while (!found.get() && !done.isCompleted) kotlinx.coroutines.delay(250)
            } != null
            if (!appeared) {
                Log.i(TAG, "Kein Gerät gefunden (Bluetooth %s)".format(if (useBle) "an" else "aus"))
                withContext(Dispatchers.Main) { runCatching { c.stopDevicePairing(nodeId.toLong()) } }
                return CommissioningResult.Failure(
                    if (useBle) CommissioningFailure.DEVICE_NOT_FOUND else CommissioningFailure.BLUETOOTH_UNAVAILABLE,
                    detail = "no commissionable device found",
                )
            }
            // Phase 2: Kopplung selbst
            withTimeoutOrNull((if (useBle) COMMISSIONING_TIMEOUT_BLE else COMMISSIONING_TIMEOUT).toMillis()) { done.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Dialog geschlossen: laufende Kopplung sauber beenden (war sie schon fertig, ist das Gerät übernommen)
            if (!done.isCompleted) runCatching { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { c.stopDevicePairing(nodeId.toLong()) } }
            throw e
        }
        if (err == null) {
            withContext(Dispatchers.Main) { runCatching { c.stopDevicePairing(nodeId.toLong()) } }
            return CommissioningResult.Failure(CommissioningFailure.TIMEOUT)
        }
        Log.i(TAG, "Kopplung Ergebnis 0x%X (Schritt: %s)".format(err, failedStage))
        if (err != 0L) {
            val reason = classifyFailure(err, failedStage, useBle)
            // Schon auf unserer Fabric (frühere Kopplung nicht übernommen)? Im Netz suchen und übernehmen.
            if (reason == CommissioningFailure.ALREADY_PAIRED) scanOwnFabric()
            return CommissioningResult.Failure(reason, detail = "0x%X".format(err))
        }

        onProgress(CommissioningStep.READING)
        // Abo läuft bereits (adopt); kurz auf Herstellerdaten warten – schlafende Sensoren melden sich teils später
        val gotReport = withTimeoutOrNull(FIRST_REPORT_TIMEOUT.toMillis()) {
            while (nodeData[nodeId] == null) kotlinx.coroutines.delay(250)
        } != null
        Log.i(TAG, "Nach Kopplung: erste Werte erhalten = $gotReport")
        val info = nodeData[nodeId]?.let(ClusterMapper::info)
        return CommissioningResult.Success(
            nodeId = nodeId,
            vendorName = info?.vendorName,
            productName = info?.productName,
            suggestedName = info?.nodeLabel ?: info?.productName,
        )
    }

    private enum class NetworkTransportNeed { THREAD, WIFI }

    /** Thread bevorzugt (stromsparend, eigenes Netz), sonst WLAN – jeweils nur, wenn hinterlegt. */
    private fun networkCredentialsFor(thread: Boolean, wifi: Boolean): NetworkCredentials? {
        if (thread) credentials.threadDataset()?.let { return NetworkCredentials.forThread(NetworkCredentials.ThreadCredentials(it.bytes())) }
        if (wifi) credentials.wifi()?.let { return NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(it.ssid, it.password)) }
        return null
    }

    private fun classifyFailure(err: Long, stage: String?, usedBle: Boolean): CommissioningFailure = when {
        err == ERROR_FABRIC_EXISTS -> CommissioningFailure.ALREADY_PAIRED
        stage == null && !usedBle && (err == ERROR_TIMEOUT || err == ERROR_GENERIC || err == ERROR_NOT_FOUND) -> CommissioningFailure.BLUETOOTH_UNAVAILABLE
        err == ERROR_NOT_FOUND -> CommissioningFailure.DEVICE_NOT_FOUND
        stage == null -> if (err == ERROR_PASE) CommissioningFailure.PASE_FAILED else CommissioningFailure.DEVICE_NOT_FOUND
        "Attestation" in stage -> CommissioningFailure.ATTESTATION
        "RequestThreadCredentials" in stage -> CommissioningFailure.NO_THREAD_NETWORK
        "RequestWiFiCredentials" in stage -> CommissioningFailure.NO_WIFI_CREDENTIALS
        "ThreadNetwork" in stage -> CommissioningFailure.THREAD_JOIN_FAILED
        "WiFiNetwork" in stage -> CommissioningFailure.WIFI_JOIN_FAILED
        "FindOperational" in stage -> CommissioningFailure.NOT_REACHABLE
        err == ERROR_TIMEOUT -> CommissioningFailure.TIMEOUT
        err == ERROR_PASE -> CommissioningFailure.PASE_FAILED
        else -> CommissioningFailure.DEVICE_NOT_FOUND
    }

    private fun stepOf(stage: String): CommissioningStep? = when {
        "PASE" in stage || "SecurePairing" in stage || "ReadCommissioningInfo" in stage -> CommissioningStep.PASE
        "Attestation" in stage || "DAC" in stage || "PAI" in stage -> CommissioningStep.ATTESTATION
        "Network" in stage || "Thread" in stage || "WiFi" in stage -> CommissioningStep.NETWORK
        "Operational" in stage || "NOC" in stage || "TrustedRoot" in stage -> CommissioningStep.OPERATIONAL
        else -> null
    }

    override suspend fun removeDevice(nodeId: ULong) {
        val c = controller()
        // eigene Fabric vom Gerät entfernen; auch bei Fehler lokal vergessen (Gerät evtl. schon zurückgesetzt)
        withTimeoutOrNull(COMMAND_TIMEOUT.toMillis()) {
            suspendCancellableCoroutine { cont ->
                c.unpairDeviceCallback(nodeId.toLong(), object : UnpairDeviceCallback {
                    override fun onError(status: Int, remoteDeviceId: Long) { if (cont.isActive) cont.resume(Unit) }
                    override fun onSuccess(remoteDeviceId: Long) { if (cont.isActive) cont.resume(Unit) }
                })
            }
        }
        forget(nodeId)
    }

    /** Abo beim SDK beenden – sonst versucht es für entfernte Geräte weiter, sich zu verbinden. */
    private fun stopSubscription(nodeId: ULong) {
        subscribed -= nodeId
        val c = chip ?: return
        main.post { runCatching { c.shutdownSubscriptions(c.fabricIndex, nodeId.toLong()) } }
    }

    private fun forget(nodeId: ULong) {
        stopSubscription(nodeId)
        rememberNode(nodeId, keep = false)
        store.putString(KEY_REMOVED, (removedNodes() + nodeId).joinToString(",") { it.toString(16) })
        nodeData.remove(nodeId); rawTlv.remove(nodeId); online.remove(nodeId); lastSeen.remove(nodeId)
        File(cacheDir, "%016x.json".format(nodeId.toLong())).delete()
        publish()
    }

    override suspend fun resetFabric() {
        (storedNodes() + nodeData.keys).forEach { runCatching { removeDevice(it) } }
        store.putString(KEY_FABRIC, null)
        store.putString(KEY_NODES, null)
        store.putString(KEY_REMOVED, null)
        _fabric.value = null
        // SDK-Schlüsselspeicher (Root-CA, Fabric) leeren – beim nächsten Start entsteht eine neue Fabric
        runCatching { withContext(Dispatchers.Main) { chip?.shutdownCommissioning(); chip?.close() } }
        chip = null
        kvs?.clear() ?: EncryptedKeyValueStore(context).clear()
        context.getSharedPreferences(SDK_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        cacheDir.listFiles()?.forEach { it.delete() }
        publish()
    }

    // --- Verbindung, Abo, Befehle ------------------------------------------------------------

    /** Verbindung zum Gerät; der native Zeiger gilt nur innerhalb von [block] und wird danach freigegeben. null = nicht erreichbar. */
    private suspend fun <T : Any> withDevice(nodeId: ULong, block: suspend (Long) -> T): T? {
        val c = controller()
        return withDevicePointer(
            CONNECT_TIMEOUT.toMillis(),
            connect = { onConnected, onFailure ->
                c.getConnectedDevicePointer(nodeId.toLong(), object : GetConnectedDeviceCallback {
                    override fun onDeviceConnected(devicePointer: Long) = onConnected(devicePointer)
                    override fun onConnectionFailure(nodeId: Long, error: Exception) {
                        Log.w(TAG, "Verbindung zu 0x%X fehlgeschlagen".format(nodeId), error)
                        onFailure()
                    }
                })
            },
            // Nach einem Fabric-Reset ist dieser Controller geschlossen – seine Objekte nicht mehr anfassen
            release = { p -> if (chip === c) c.releaseConnectedDevicePointer(p) },
            block = block,
        )
    }

    private fun paths(nodeId: ULong): List<ChipAttributePath> =
        ClusterMapper.subscriptionPaths(nodeData[nodeId]).map { p ->
            ChipAttributePath.newInstance(
                p.endpoint?.let { ChipPathId.forId(it.toLong()) } ?: ChipPathId.forWildcard(),
                ChipPathId.forId(p.cluster),
                p.attribute?.let { ChipPathId.forId(it) } ?: ChipPathId.forWildcard(),
            )
        }.also { Log.i(TAG, "Abo 0x%X: %d Pfade".format(nodeId.toLong(), it.size)) }

    private suspend fun subscribe(nodeId: ULong, onFirstReport: () -> Unit = {}) {
        Log.i(TAG, "Abo: verbinde Node 0x%X".format(nodeId.toLong()))
        val c = controller()
        var first = true
        // Das Abo übernimmt die Sitzung beim Aufruf; der Zeiger wird danach nicht mehr gebraucht und freigegeben.
        val connected = withDevice(nodeId) { ptr -> startSubscription(c, nodeId, ptr) { if (first) { first = false; onFirstReport() } }; true }
        Log.i(TAG, "Abo: Verbindung Node 0x%X = %s".format(nodeId.toLong(), connected?.let { "ok" } ?: "fehlgeschlagen"))
        if (connected == null) {
            if (!recentlySeen(nodeId)) online[nodeId] = OnlineState.OFFLINE
            publish()
            // später erneut versuchen – z. B. Gerät war stromlos oder Border Router neu gestartet
            scope.launch { kotlinx.coroutines.delay(RETRY_CONNECT_MS); if (nodeId in storedNodes()) subscribe(nodeId, onFirstReport) }
        }
    }

    private suspend fun startSubscription(c: ChipDeviceController, nodeId: ULong, ptr: Long, onReport: () -> Unit) {
        withContext(Dispatchers.Main) {
            c.subscribeToPath(
                SubscriptionEstablishedCallback { id ->
                    Log.i(TAG, "Abo aktiv: Node 0x%X (%d)".format(nodeId.toLong(), id))
                    subscribed += nodeId
                    online[nodeId] = OnlineState.ONLINE
                    lastSeen[nodeId] = clock.instant()
                    publish()
                },
                ResubscriptionAttemptCallback { _, nextMs ->
                    // SDK baut das Abo neu auf (bei schlafenden Thread-Geräten üblich). Erst nach der Karenzzeit
                    // ohne Kontakt gilt das Gerät als offline – sonst flackert der Status.
                    Log.i(TAG, "Abo 0x%X: Neuaufbau in %d ms".format(nodeId.toLong(), nextMs))
                    subscribed -= nodeId
                    if (nodeId !in storedNodes()) { stopSubscription(nodeId); return@ResubscriptionAttemptCallback }
                    if (!recentlySeen(nodeId)) { online[nodeId] = OnlineState.OFFLINE; publish() }
                },
                object : ReportCallback {
                    override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                        Log.w(TAG, "Bericht 0x%X: %s".format(nodeId.toLong(), attributePath), e)
                    }
                    override fun onReport(nodeState: NodeState) {
                        apply(nodeId, nodeState)
                        onReport()
                    }
                },
                ptr,
                paths(nodeId),
                emptyList(),
                MIN_INTERVAL_S,
                MAX_INTERVAL_S,
                true,   // weitere Abos (andere Nodes) bestehen lassen
                false,  // nicht fabric-gefiltert: Fabric-Liste aller Apps lesen
                0,
                null,
            )
        }
    }

    private fun apply(nodeId: ULong, state: NodeState) {
        val update = HashMap<AttrPath, Any?>()
        val raw = rawTlv.getOrPut(nodeId) { ConcurrentHashMap() }
        state.endpointStates.forEach { (ep, es) ->
            es.clusterStates.forEach { (cluster, cs) ->
                cs.attributeStates.forEach { (attr, a) ->
                    val tlv = a.tlv ?: return@forEach
                    val path = AttrPath(ep, cluster, attr)
                    update[path] = runCatching { TlvReader.read(tlv) }.getOrNull()
                    raw[path] = tlv
                }
            }
        }
        nodeData[nodeId] = (nodeData[nodeId] ?: NodeData(emptyMap())).merge(update)
        online[nodeId] = OnlineState.ONLINE
        lastSeen[nodeId] = clock.instant()
        saveCache(nodeId)
        publish()
    }

    override suspend fun execute(command: MatterCommand): CommandResult {
        val node = command.nodeId
        val data = nodeData[node] ?: return CommandResult.Failure(CommandFailure.OFFLINE, "no data yet")
        val actions = ClusterMapper.actions(command.command, data, command.endpointId)
            ?: return CommandResult.Failure(CommandFailure.UNSUPPORTED, "command not supported")
        return withDevice(node) { ptr ->
            for (a in actions) {
                val r = when (a) {
                    is MatterAction.Invoke -> invoke(ptr, a.endpoint, a.cluster, a.command, a.fields, timed = false)
                    is MatterAction.Write -> write(ptr, a)
                }
                if (r !is CommandResult.Success) return@withDevice r
            }
            CommandResult.Success
        } ?: CommandResult.Failure(CommandFailure.OFFLINE, "unreachable")
    }

    private suspend fun invoke(ptr: Long, ep: Int, cluster: Long, cmd: Long, fields: ByteArray, timed: Boolean): CommandResult {
        val c = controller()
        return withTimeoutOrNull(COMMAND_TIMEOUT.toMillis()) {
            suspendCancellableCoroutine { cont ->
                c.invoke(object : InvokeCallback {
                    override fun onError(e: Exception) { if (cont.isActive) cont.resume(CommandResult.Failure(CommandFailure.DEVICE_ERROR, e.message)) }
                    override fun onResponse(invokeElement: InvokeElement?, successCode: Long) { if (cont.isActive) cont.resume(CommandResult.Success) }
                }, ptr, InvokeElement.newInstance(ep, cluster, cmd, fields, null), if (timed) TIMED_MS else 0, 0)
            }
        } ?: CommandResult.Failure(CommandFailure.TIMEOUT, "no response")
    }

    private suspend fun write(ptr: Long, a: MatterAction.Write): CommandResult {
        val c = controller()
        return withTimeoutOrNull(COMMAND_TIMEOUT.toMillis()) {
            suspendCancellableCoroutine { cont ->
                c.write(object : WriteAttributesCallback {
                    override fun onError(attributePath: ChipAttributePath?, e: Exception) {
                        if (cont.isActive) cont.resume(CommandResult.Failure(CommandFailure.DEVICE_ERROR, e.message))
                    }
                    override fun onResponse(attributePath: ChipAttributePath, status: Status) {
                        if (cont.isActive) cont.resume(writeResult(status))
                    }
                }, ptr, listOf(AttributeWriteRequest.newInstance(a.endpoint, a.cluster, a.attribute, a.value)), 0, 0)
            }
        } ?: CommandResult.Failure(CommandFailure.TIMEOUT, "no response")
    }

    // --- Multi-Admin -------------------------------------------------------------------------

    override suspend fun openPairingWindow(nodeId: ULong, timeout: Duration): PairingWindowResult {
        val c = controller()
        val discriminator = random.nextInt(0x1000)
        return withDevice(nodeId) { ptr ->
            val result = withTimeoutOrNull(COMMAND_TIMEOUT.toMillis()) {
                suspendCancellableCoroutine { cont ->
                    val ok = c.openPairingWindowWithPINCallback(ptr, timeout.seconds.toInt(), PBKDF_ITERATIONS, discriminator, null,
                        object : OpenCommissioningCallback {
                            override fun onError(status: Int, deviceId: Long) { if (cont.isActive) cont.resume(null) }
                            override fun onSuccess(deviceId: Long, manualPairingCode: String, qrCode: String) {
                                if (cont.isActive) cont.resume(PairingWindow(nodeId, manualPairingCode, qrCode, clock.instant().plus(timeout)))
                            }
                        })
                    if (!ok && cont.isActive) cont.resume(null)
                }
            }
            result?.let { PairingWindowResult.Open(it) } ?: PairingWindowResult.Failure(CommandFailure.DEVICE_ERROR)
        } ?: PairingWindowResult.Failure(CommandFailure.OFFLINE)
    }

    override suspend fun closePairingWindow(nodeId: ULong) {
        // AdministratorCommissioning.RevokeCommissioning verlangt eine zeitgebundene Anfrage
        withDevice(nodeId) { ptr -> invoke(ptr, 0, Cluster.ADMIN_COMMISSIONING, Cmd.REVOKE_COMMISSIONING, TlvWriter.empty(), timed = true) }
    }

    override suspend fun removeAdmin(nodeId: ULong, fabricIndex: Int): CommandResult {
        val own = nodeData[nodeId]?.let(ClusterMapper::admins)?.firstOrNull { it.own }?.fabricIndex
        if (fabricIndex == own) return CommandResult.Failure(CommandFailure.UNSUPPORTED, "own fabric")
        val fields = TlvWriter().startStructure().uint(0, fabricIndex.toLong()).endContainer().bytes()
        return withDevice(nodeId) { ptr -> invoke(ptr, 0, Cluster.OPERATIONAL_CREDENTIALS, Cmd.REMOVE_FABRIC, fields, timed = false) }
            ?: CommandResult.Failure(CommandFailure.OFFLINE)
    }

    override suspend fun readAdmins(nodeId: ULong): List<AdminFabric>? {
        val c = controller()
        val attrs = listOf(Attr.FABRICS, Attr.CURRENT_FABRIC_INDEX).map {
            ChipAttributePath.newInstance(ChipPathId.forId(0), ChipPathId.forId(Cluster.OPERATIONAL_CREDENTIALS), ChipPathId.forId(it))
        }
        val state = withDevice(nodeId) { ptr ->
            // Result als Hülle: null innen = gelesen, aber ohne Antwort; null außen = nicht erreichbar
            Result.success(withTimeoutOrNull(COMMAND_TIMEOUT.toMillis()) {
                suspendCancellableCoroutine<NodeState?> { cont ->
                    c.readPath(object : ReportCallback {
                        override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                            Log.w(TAG, "Admins 0x%X lesen: %s".format(nodeId.toLong(), attributePath), e)
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onReport(nodeState: NodeState) { if (cont.isActive) cont.resume(nodeState) }
                    }, ptr, attrs, emptyList(), false /* alle Fabrics, nicht nur die eigene */, 0)
                }
            })
        }?.getOrNull() ?: return null
        val read = state.endpointStates[0]?.clusterStates?.get(Cluster.OPERATIONAL_CREDENTIALS)?.attributeStates
        if (read?.containsKey(Attr.FABRICS) != true || !read.containsKey(Attr.CURRENT_FABRIC_INDEX)) return null
        apply(nodeId, state) // aktualisiert auch adminFabrics für die Oberfläche
        return nodeData[nodeId]?.let(ClusterMapper::admins)
    }

    // --- Zwischenspeicher der letzten Werte ----------------------------------------------------

    private fun saveCache(nodeId: ULong) {
        val raw = rawTlv[nodeId] ?: return
        val json = JSONObject()
        raw.forEach { (p, tlv) -> json.put("${p.endpoint}/${p.cluster}/${p.attribute}", Base64.encodeToString(tlv, Base64.NO_WRAP)) }
        lastSeen[nodeId]?.let { json.put(KEY_LAST_SEEN, it.toEpochMilli()) }
        runCatching { File(cacheDir, "%016x.json".format(nodeId.toLong())).writeText(json.toString()) }
    }

    private fun loadCache(nodeId: ULong) {
        val file = File(cacheDir, "%016x.json".format(nodeId.toLong()))
        if (!file.exists() || nodeData.containsKey(nodeId)) return
        runCatching {
            val json = JSONObject(file.readText())
            val raw = ConcurrentHashMap<AttrPath, ByteArray>()
            val values = HashMap<AttrPath, Any?>()
            if (json.has(KEY_LAST_SEEN)) lastSeen[nodeId] = Instant.ofEpochMilli(json.getLong(KEY_LAST_SEEN))
            json.keys().forEach { k ->
                if (k == KEY_LAST_SEEN) return@forEach
                val (ep, cl, at) = k.split("/")
                val path = AttrPath(ep.toInt(), cl.toLong(), at.toLong())
                val tlv = Base64.decode(json.getString(k), Base64.NO_WRAP)
                raw[path] = tlv
                values[path] = runCatching { TlvReader.read(tlv) }.getOrNull()
            }
            rawTlv[nodeId] = raw
            nodeData[nodeId] = NodeData(values)
        }
    }

    companion object {
        private const val TAG = "raum.matter"
        /** Hersteller-ID (gradle.properties raumVendorId; ohne Angabe Test-ID 0xFFF1). Siehe docs/VENDOR.md */
        val VENDOR_ID: Int = app.raum.BuildConfig.MATTER_VENDOR_ID
        private const val KEY_FABRIC = "chip_fabric"
        private const val KEY_NODES = "chip_nodes"
        /** SharedPreferences, in denen PreferencesKeyValueStoreManager die SDK-Schlüssel ablegt */
        private const val SDK_PREFS = "chip.platform.KeyValueStore"
        private const val ATTESTATION_FAILSAFE_S = 600
        private const val MIN_INTERVAL_S = 1
        private const val MAX_INTERVAL_S = 300
        private const val TIMED_MS = 10_000
        private const val PBKDF_ITERATIONS = 1000L
        private const val ERROR_GENERIC = -1L
        /** raum.-intern: kein koppelbares Gerät gefunden */
        private const val ERROR_NOT_FOUND = -2L
        /** DevicePairingDelegate::Status */
        private const val STATUS_PASE_SUCCESS = 0
        private const val STATUS_PASE_FAILED = 1
        private const val ERROR_TIMEOUT = 0x32L       // CHIP_ERROR_TIMEOUT
        private const val ERROR_PASE = 0x38L          // CHIP_ERROR_INVALID_PASE_PARAMETER
        private const val ERROR_FABRIC_EXISTS = 0x7EL // CHIP_ERROR_FABRIC_EXISTS
        private val COMMISSIONING_TIMEOUT: Duration = Duration.ofMinutes(3)
        /** Bis sich ein koppelbares Gerät meldet (Bluetooth oder Netzwerk) */
        private val DISCOVERY_TIMEOUT: Duration = Duration.ofSeconds(90)
        /** Bluetooth + Netzbeitritt + erste Suche im Betriebsnetz dauern bei Thread-Geräten länger */
        private val COMMISSIONING_TIMEOUT_BLE: Duration = Duration.ofMinutes(4)
        /** kInvalidEndpointId */
        private const val INVALID_ENDPOINT = 0xFFFF
        private val FIRST_REPORT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val KEY_REMOVED = "chip_removed"
        private const val KEY_LAST_SEEN = "_lastSeen"
        /** Ohne Kontakt so lange gilt ein Gerät noch als erreichbar (Abo-Intervall 5 min + Neuaufbau). */
        private val OFFLINE_GRACE: Duration = Duration.ofMinutes(10)
        private const val RETRY_CONNECT_MS = 60_000L
        private const val SCAN_MS = 20_000L
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
