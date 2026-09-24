package app.raum.platform.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.raum.platform.kiosk.KioskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

enum class WifiSecurity { OPEN, WEP, PSK, SAE, ENTERPRISE }

data class WifiNetwork(
    val ssid: String,
    /** Signalstärke 0 – 4 */
    val level: Int,
    val security: WifiSecurity,
) {
    val secured: Boolean get() = security != WifiSecurity.OPEN
}

data class NetworkStatus(
    val ethernet: Boolean = false,
    val wifiConnected: Boolean = false,
    /** null, wenn Android den Namen ohne Standortfreigabe verbirgt */
    val ssid: String? = null,
    val ipAddress: String? = null,
    val wifiEnabled: Boolean = false,
)

sealed interface ConnectResult {
    data object Ok : ConnectResult
    data object NotAllowed : ConnectResult
    data object Failed : ConnectResult
}

/**
 * Netzwerkstatus und WLAN-Verwaltung für die Einstellungen.
 *
 * Seit Android 10 dürfen normale Apps weder WLAN schalten noch sich mit Netzen verbinden –
 * Geräteeigentümer (Kiosk-Betrieb) schon. Ohne diesen Status bleibt nur das System-WLAN-Panel.
 * Für die Suche braucht Android 13+ „Geräte in der Nähe“ (ohne Ortung), Android 11/12 die
 * Standortberechtigung und einen aktiven Standortdienst – raum. ortet dabei nichts.
 */
class NetworkController(
    private val context: Context,
    private val kiosk: KioskManager,
) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val _networks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val networks: StateFlow<List<WifiNetwork>> = _networks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile private var callbackWifiInfo: WifiInfo? = null

    /** Zuletzt über raum. verbundenes Netz – Rückfall, falls Android den Namen verbirgt. */
    private var lastConnectedSsid: String? = null

    val canManage: Boolean get() = kiosk.isDeviceOwner && wifi != null

    private var active = 0
    /** Ab Android 12 mit FLAG_INCLUDE_LOCATION_INFO – nur dann ist der Netzname lesbar. */
    private inner class Callback : ConnectivityManager.NetworkCallback {
        constructor() : super()
        @RequiresApi(Build.VERSION_CODES.S) constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // getNetworkCapabilities() schwärzt den Netznamen seit Android 12 immer
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) callbackWifiInfo = caps.transportInfo as? WifiInfo
            refreshStatus()
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refreshStatus()
        override fun onLost(network: Network) { callbackWifiInfo = null; refreshStatus() }
    }

    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) else Callback()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> { _scanning.value = false; readScanResults() }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> refreshStatus()
            }
        }
    }

    /** Beobachtung starten (Einstellungen sichtbar). Aufrufe werden gezählt. */
    fun start() {
        if (active++ > 0) return
        ensurePermissions()
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshStatus()
        scan()
    }

    fun stop() {
        if (--active > 0) return
        active = 0
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 33) kiosk.grantPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        kiosk.grantPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Darf raum. Suchergebnisse lesen? */
    val canScan: Boolean
        get() = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION) && locationServiceOn

    /** Android 11/12 liefert Suchergebnisse nur bei aktivem Standortdienst. */
    val needsLocationService: Boolean
        get() = Build.VERSION.SDK_INT < 33 && !locationServiceOn

    private val locationServiceOn: Boolean
        get() = context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    fun enableLocationService(): Boolean = kiosk.setLocationEnabled(true).also { if (it) scan() }

    @SuppressLint("MissingPermission")
    fun refreshStatus() {
        val cm = connectivity ?: return
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ip = net?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        val ssid = if (onWifi) currentSsid(caps) ?: lastConnectedSsid else null
        _status.value = NetworkStatus(
            ethernet = ethernet,
            wifiConnected = onWifi,
            ssid = ssid,
            ipAddress = ip,
            wifiEnabled = wifi?.isWifiEnabled == true,
        )
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(caps: NetworkCapabilities?): String? {
        fun clean(info: WifiInfo?) = info?.ssid?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        return clean(callbackWifiInfo)
            ?: clean(if (Build.VERSION.SDK_INT >= 31) caps?.transportInfo as? WifiInfo else null)
            ?: clean(runCatching { wifi?.connectionInfo }.getOrNull())
    }

    @Suppress("DEPRECATION")
    fun scan() {
        val w = wifi ?: return
        if (!w.isWifiEnabled || !canScan) { readScanResults(); return }
        _scanning.value = true
        // Android drosselt Suchen (4 je 2 min); dann gelten die letzten Ergebnisse
        if (!runCatching { w.startScan() }.getOrDefault(false)) { _scanning.value = false; readScanResults() }
    }

    @SuppressLint("MissingPermission")
    private fun readScanResults() {
        val results = runCatching { wifi?.scanResults }.getOrNull().orEmpty()
        _networks.value = results
            .mapNotNull { r -> ssidOf(r)?.let { it to r } }
            .groupBy({ it.first }, { it.second })
            .map { (ssid, list) ->
                val best = list.maxBy { it.level }
                WifiNetwork(ssid, WifiManager.calculateSignalLevel(best.level, 5).coerceIn(0, 4), securityOf(best))
            }
            .sortedByDescending { it.level }
        refreshStatus()
    }

    @Suppress("DEPRECATION")
    private fun ssidOf(r: ScanResult): String? {
        val name = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.removeSurrounding("\"") else r.SSID
        return name?.takeIf { it.isNotBlank() }
    }

    private fun securityOf(r: ScanResult): WifiSecurity {
        val c = r.capabilities.orEmpty()
        return when {
            "EAP" in c -> WifiSecurity.ENTERPRISE
            "SAE" in c && "PSK" !in c -> WifiSecurity.SAE
            "PSK" in c || "SAE" in c -> WifiSecurity.PSK
            "WEP" in c -> WifiSecurity.WEP
            else -> WifiSecurity.OPEN
        }
    }

    @Suppress("DEPRECATION")
    fun setWifiEnabled(enabled: Boolean): Boolean {
        if (!canManage) return false
        val ok = runCatching { wifi!!.setWifiEnabled(enabled) }.getOrDefault(false)
        refreshStatus()
        return ok
    }

    /**
     * Mit einem Netz verbinden (nur Geräteeigentümer). Das Netz wird gespeichert, damit das Panel
     * sich nach einem Neustart selbst wieder verbindet.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun connect(network: WifiNetwork, password: String?): ConnectResult {
        if (!canManage) return ConnectResult.NotAllowed
        if (network.security == WifiSecurity.ENTERPRISE || network.security == WifiSecurity.WEP) return ConnectResult.NotAllowed
        val w = wifi!!
        val config = WifiConfiguration().apply {
            SSID = "\"${network.ssid}\""
            when (network.security) {
                WifiSecurity.OPEN -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN)
                WifiSecurity.PSK -> { setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK); preSharedKey = "\"$password\"" }
                WifiSecurity.SAE -> { setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE); preSharedKey = "\"$password\"" }
                else -> Unit
            }
        }
        // Vorhandene Einträge desselben Netzes ersetzen (z. B. neues Passwort)
        runCatching { w.configuredNetworks }.getOrNull().orEmpty()
            .filter { it.SSID == config.SSID }
            .forEach { runCatching { w.removeNetwork(it.networkId) } }
        val id = runCatching { w.addNetwork(config) }.getOrDefault(-1)
        if (id == -1) return ConnectResult.Failed
        val ok = runCatching { w.enableNetwork(id, true) && w.reconnect() }.getOrDefault(false)
        if (ok) lastConnectedSsid = network.ssid
        return if (ok) ConnectResult.Ok else ConnectResult.Failed
    }

    /** Aktuelles WLAN vergessen (nur Geräteeigentümer). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun forget(ssid: String): Boolean {
        if (!canManage) return false
        val w = wifi!!
        val removed = runCatching { w.configuredNetworks }.getOrNull().orEmpty()
            .filter { it.SSID.removeSurrounding("\"") == ssid }
            .map { runCatching { w.removeNetwork(it.networkId) }.getOrDefault(false) }
            .any { it }
        if (removed && lastConnectedSsid == ssid) lastConnectedSsid = null
        refreshStatus()
        return removed
    }
}
