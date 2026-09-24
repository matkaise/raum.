package app.raum.thread

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/** Ergebnis beim Übernehmen der Zugangsdaten von einem Border Router. */
sealed interface DatasetFetch {
    data class Ok(val dataset: ThreadDataset) : DatasetFetch
    /** Border Router hat (noch) kein aktives Thread-Netz */
    data object NoNetwork : DatasetFetch
    data object Unreachable : DatasetFetch
    data object Invalid : DatasetFetch
}

/** Findet Thread Border Router im lokalen Netz (Spez. 8.3, ONB-008). */
interface BorderRouterDiscovery {
    val routers: StateFlow<List<BorderRouter>>
    val scanning: StateFlow<Boolean>
    /** Mindestens eine Suche lief lange genug, um „kein Border Router“ sicher sagen zu können. */
    val searched: StateFlow<Boolean>
    /** Dauerhaft suchen, solange eine Ansicht es braucht (Aufrufe werden gezählt). */
    fun start()
    fun stop()
    /** Kurz suchen – z. B. nach dem Start für Statusanzeigen. */
    fun scanFor(millis: Long)
    suspend fun fetchActiveDataset(router: BorderRouter): DatasetFetch
}

/**
 * DNS-SD-Suche nach `_meshcop._udp` über Androids NsdManager.
 * OpenThread Border Router mit REST-API (Port 8081) geben ihr aktives Dataset heraus;
 * Apple, Google und Amazon tun das nicht – deren Geräte koppelt man dort und teilt sie per Code mit raum.
 */
class NsdBorderRouterDiscovery(
    context: Context,
    private val scope: CoroutineScope,
) : BorderRouterDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)

    private val _routers = MutableStateFlow<List<BorderRouter>>(emptyList())
    override val routers: StateFlow<List<BorderRouter>> = _routers.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    override val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _searched = MutableStateFlow(false)
    override val searched: StateFlow<Boolean> = _searched.asStateFlow()

    private var users = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private var stopJob: Job? = null
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private val pendingRemoval = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val resolvedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        // Android löst Namen zuverlässig nur einzeln auf – also nacheinander
        scope.launch { for (info in resolveQueue) resolve(info) }
    }

    @Synchronized override fun start() {
        users++
        stopJob?.cancel()
        begin()
    }

    @Synchronized override fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) end()
    }

    @Synchronized override fun scanFor(millis: Long) {
        begin()
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(millis)
            synchronized(this@NsdBorderRouterDiscovery) { if (users == 0) end() }
        }
    }

    private fun begin() {
        if (listener != null || nsd == null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                // Android meldet Router regelmäßig „verloren“ und gleich wieder „gefunden“ – nicht flackern
                pendingRemoval.remove(info.serviceName)?.cancel()
                val known = _routers.value.any { it.id == info.serviceName }
                val fresh = resolvedAt[info.serviceName]?.let { System.currentTimeMillis() - it < REFRESH_MS } == true
                if (!(known && fresh)) resolveQueue.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val id = info.serviceName
                pendingRemoval.remove(id)?.cancel()
                pendingRemoval[id] = scope.launch {
                    delay(LOST_GRACE_MS)
                    _routers.update { list -> list.filterNot { it.id == id } }
                    resolvedAt.remove(id)
                    pendingRemoval.remove(id)
                }
            }
            override fun onDiscoveryStarted(serviceType: String?) {
                _scanning.value = true
                if (!_searched.value) scope.launch { delay(SEARCH_SETTLE_MS); _searched.value = true }
            }
            override fun onDiscoveryStopped(serviceType: String?) { _scanning.value = false }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Suche fehlgeschlagen: $errorCode"); _scanning.value = false
                synchronized(this@NsdBorderRouterDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null; Log.w(TAG, "Suche nicht gestartet", it) }
    }

    private fun end() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
        _scanning.value = false
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo) {
        val m = nsd ?: return
        repeat(3) { attempt ->
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    runCatching {
                        m.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(r: NsdServiceInfo) { if (cont.isActive) cont.resume(r) }
                            override fun onResolveFailed(r: NsdServiceInfo?, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (resolved != null) {
                val router = MeshcopTxt.parse(resolved.serviceName, resolved.attributes.orEmpty(), hostOf(resolved), resolved.port)
                Log.i(TAG, "Border Router: ${router.displayName} (${router.vendor}), Netz ${router.networkName}, ${router.state}")
                upsert(router)
                resolvedAt[router.id] = System.currentTimeMillis()
                if (!router.closedEcosystem) scope.launch(Dispatchers.IO) { probeRest(router) }
                return
            }
            delay(500L * (attempt + 1))
        }
    }

    private fun hostOf(info: NsdServiceInfo): InetAddress? {
        val all = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(@Suppress("DEPRECATION") info.host)
        // IPv4 zuerst: Link-lokale IPv6 brauchen die richtige Schnittstelle
        return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    }

    private fun upsert(router: BorderRouter) = _routers.update { list ->
        val old = list.firstOrNull { it.id == router.id }
        (list.filterNot { it.id == router.id } + router.copy(restAvailable = old?.restAvailable == true)).sortedBy { it.displayName.lowercase() }
    }

    private fun probeRest(router: BorderRouter) {
        val host = router.host?.takeIf(LocalHttp::isLocal) ?: return
        val ok = LocalHttp.get(host, OTBR_REST_PORT, "/node/state", "application/json", 1500)?.status == 200
        if (ok) _routers.update { list -> list.map { if (it.id == router.id) it.copy(restAvailable = true) else it } }
    }

    override suspend fun fetchActiveDataset(router: BorderRouter): DatasetFetch = withContext(Dispatchers.IO) {
        val host = router.host?.takeIf(LocalHttp::isLocal) ?: return@withContext DatasetFetch.Unreachable
        val r = LocalHttp.get(host, OTBR_REST_PORT, "/node/dataset/active", "text/plain") ?: return@withContext DatasetFetch.Unreachable
        when (r.status) {
            200 -> when (val p = ThreadDataset.parse(r.body.trim().trim('"'))) {
                is ThreadDataset.ParseResult.Ok -> DatasetFetch.Ok(p.dataset)
                is ThreadDataset.ParseResult.Invalid -> DatasetFetch.Invalid
            }
            204, 404 -> DatasetFetch.NoNetwork
            else -> DatasetFetch.Unreachable
        }
    }

    private companion object {
        const val TAG = "raum.thread"
        const val SERVICE = "_meshcop._udp"
        const val OTBR_REST_PORT = 8081
        const val RESOLVE_TIMEOUT_MS = 5_000L
        /** So lange bleibt ein „verlorener“ Router sichtbar – Android meldet Verlust und Wiederfund im Wechsel */
        const val LOST_GRACE_MS = 90_000L
        /** Nach dieser Suchdauer gilt „nichts gefunden“ als belastbar */
        const val SEARCH_SETTLE_MS = 15_000L
        /** Zustand (aktiv/inaktiv, Netz) spätestens nach dieser Zeit neu lesen */
        const val REFRESH_MS = 5 * 60_000L
    }
}
