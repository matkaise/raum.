package app.raum.thread

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.domain.models.DeviceState
import app.raum.domain.models.NetworkTransport
import app.raum.domain.models.OnlineState
import app.raum.i18n.Strings
import app.raum.matter.controller.MatterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.net.Inet6Address

enum class ThreadHealth {
    /** Kein Dataset hinterlegt – Thread-Geräte lassen sich nur per Code aus einer anderen App aufnehmen */
    NOT_CONFIGURED,
    /** Kein Border Router des hinterlegten Netzes im LAN gefunden */
    NO_BORDER_ROUTER,
    /** Border Router gefunden, seine Thread-Schnittstelle ist aber nicht aktiv */
    BORDER_ROUTER_INACTIVE,
    OK,
}

/** IPv6 des Panels – Thread-Geräte sind nur per IPv6 erreichbar. */
data class Ipv6Status(val hasRoutableAddress: Boolean, val hasThreadRoute: Boolean)

data class ThreadStatus(
    val network: ThreadNetwork?,
    val routers: List<BorderRouter>,
    /** Border Router, die das hinterlegte Netz bedienen */
    val matching: List<BorderRouter>,
    val health: ThreadHealth,
    val threadDevices: Int,
    val threadDevicesOnline: Int,
    /** Netznamen, in denen raum.-Geräte laut Diagnose hängen (auch ohne hinterlegtes Dataset, z. B. Apple-Netz) */
    val deviceNetworks: Set<String>,
    val ipv6: Ipv6Status?,
    /** Suche lief lange genug für belastbare Aussagen zu fehlenden Border Routern */
    val searched: Boolean = true,
) {
    /** Systemhinweis (Spez. 9.4): Thread-Geräte vorhanden, keines erreichbar und kein aktiver Border Router im Netz. */
    val borderRouterUnreachable: Boolean
        get() = searched && threadDevices > 0 && threadDevicesOnline == 0 &&
            routers.none { it.active || it.state == ThreadInterfaceState.UNKNOWN }
}

/** Reine Auswertung – getrennt testbar. */
object ThreadDiagnosis {
    fun evaluate(
        network: ThreadNetwork?,
        routers: List<BorderRouter>,
        devices: Collection<DeviceState>,
        ipv6: Ipv6Status?,
        searched: Boolean = true,
    ): ThreadStatus {
        val matching = network?.let { n ->
            routers.filter { r ->
                r.extPanId?.equals(n.summary.extPanId, ignoreCase = true) ?: (r.networkName == n.summary.networkName)
            }
        }.orEmpty()
        val health = when {
            network == null -> ThreadHealth.NOT_CONFIGURED
            matching.isEmpty() -> ThreadHealth.NO_BORDER_ROUTER
            // Unbekannter Zustand (ältere Router ohne „sb“) gilt als aktiv
            matching.none { it.state == ThreadInterfaceState.ACTIVE || it.state == ThreadInterfaceState.UNKNOWN } -> ThreadHealth.BORDER_ROUTER_INACTIVE
            else -> ThreadHealth.OK
        }
        val thread = devices.filter { it.network?.transport == NetworkTransport.THREAD }
        return ThreadStatus(
            network = network,
            routers = routers,
            matching = matching,
            health = health,
            threadDevices = thread.size,
            threadDevicesOnline = thread.count { it.onlineState == OnlineState.ONLINE },
            deviceNetworks = thread.mapNotNull { it.network?.threadNetworkName }.toSet(),
            ipv6 = ipv6,
            searched = searched,
        )
    }
}

sealed interface ImportResult {
    data class Ok(val network: ThreadNetworkSummary) : ImportResult
    data class Invalid(val error: ThreadDataset.Error) : ImportResult
    data class FetchFailed(val reason: DatasetFetch) : ImportResult
}

/**
 * Thread-Verwaltung (M6, Spez. 8.3): Dataset importieren/übernehmen, Border Router finden, Zustand auswerten.
 * Der Matter-Controller holt sich das Dataset beim Koppeln selbst aus dem [NetworkCredentialStore].
 */
class ThreadService(
    context: Context,
    private val credentials: NetworkCredentialStore,
    private val discovery: BorderRouterDiscovery,
    controller: MatterController,
    private val log: EventLog,
    private val strings: Strings,
    scope: CoroutineScope,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val ipv6 = MutableStateFlow<Ipv6Status?>(null)

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { ipv6.value = ipv6Of(lp) }
                override fun onLost(network: Network) { ipv6.value = Ipv6Status(false, false) }
            })
        }
    }

    val status: StateFlow<ThreadStatus> =
        combine(credentials.thread, discovery.routers, controller.devices, ipv6, discovery.searched) { net, routers, devices, v6, searched ->
            ThreadDiagnosis.evaluate(net, routers, devices.values, v6, searched)
        }.stateIn(scope, SharingStarted.Eagerly, ThreadDiagnosis.evaluate(credentials.thread.value, emptyList(), emptyList(), null, false))

    val routers get() = discovery.routers
    val scanning get() = discovery.scanning
    fun startScan() = discovery.start()
    fun stopScan() = discovery.stop()
    /** Nach dem Start einmal kurz suchen, damit Hinweise (Border Router fehlt) stimmen. */
    fun scanBriefly() = discovery.scanFor(30_000)

    fun preview(text: String): ThreadDataset.ParseResult = ThreadDataset.parse(text)

    fun importDataset(text: String): ImportResult = when (val p = ThreadDataset.parse(text)) {
        is ThreadDataset.ParseResult.Invalid -> ImportResult.Invalid(p.error)
        is ThreadDataset.ParseResult.Ok -> {
            credentials.setThread(p.dataset, DatasetSource.MANUAL)
            log.info(LogCategory.SYSTEM, strings.get(R.string.log_thread_imported, p.dataset.networkName))
            ImportResult.Ok(p.dataset.summary)
        }
    }

    /** ONB-008: aktives Dataset eines OpenThread Border Routers übernehmen. */
    suspend fun adoptFrom(router: BorderRouter): ImportResult = when (val r = discovery.fetchActiveDataset(router)) {
        is DatasetFetch.Ok -> {
            credentials.setThread(r.dataset, DatasetSource.BORDER_ROUTER, router.displayName)
            log.info(LogCategory.SYSTEM, strings.get(R.string.log_thread_adopted, r.dataset.networkName, router.displayName))
            ImportResult.Ok(r.dataset.summary)
        }
        else -> {
            log.warning(LogCategory.SYSTEM, strings.get(R.string.log_thread_adopt_failed, router.displayName))
            ImportResult.FetchFailed(r)
        }
    }

    fun clearThread() {
        val name = credentials.thread.value?.summary?.networkName ?: return
        credentials.clearThread()
        log.info(LogCategory.SYSTEM, strings.get(R.string.log_thread_removed, name))
    }

    val wifiSsid get() = credentials.wifiSsid

    fun setWifi(ssid: String, password: String) {
        credentials.setWifi(ssid.trim(), password)
        log.info(LogCategory.SYSTEM, strings.get(R.string.log_commissioning_wifi_set, ssid.trim()))
    }

    fun clearWifi() = credentials.clearWifi()

    private fun ipv6Of(lp: LinkProperties): Ipv6Status {
        val routable = lp.linkAddresses.any { a ->
            val ad = a.address
            ad is Inet6Address && !ad.isLinkLocalAddress && !ad.isLoopbackAddress
        }
        // Route über einen Border Router: /64-Präfix mit Gateway, kein Standardweg
        val threadRoute = lp.routes.any { r ->
            r.destination.address is Inet6Address && r.destination.prefixLength == 64 && r.hasGateway() && !r.isDefaultRoute
        }
        return Ipv6Status(routable, threadRoute)
    }
}
