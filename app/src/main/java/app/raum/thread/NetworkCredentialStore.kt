package app.raum.thread

import app.raum.security.KeyValueStore
import app.raum.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Instant

/** Woher das Thread-Dataset stammt. */
enum class DatasetSource { MANUAL, BORDER_ROUTER }

/** Hinterlegtes Thread-Netz (Spez. 10.1 ThreadConfiguration) – ohne Schlüssel. */
data class ThreadNetwork(val summary: ThreadNetworkSummary, val source: DatasetSource, val importedAt: Instant, val sourceName: String?)

/**
 * Zugangsdaten, die raum. beim Koppeln neuer Geräte weitergibt (COM-003, COM-004, ONB-007):
 * das Thread Operational Dataset und ein WLAN für Matter-over-Wi-Fi-Geräte.
 * Geheimes liegt im [SecretStore] (Android Keystore), Anzeigedaten im normalen Speicher.
 */
class NetworkCredentialStore(
    private val secrets: SecretStore,
    private val meta: KeyValueStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _thread = MutableStateFlow(loadThread())
    val thread: StateFlow<ThreadNetwork?> = _thread.asStateFlow()

    private val _wifiSsid = MutableStateFlow(meta.getString(KEY_WIFI_SSID))
    /** SSID des WLANs für neue Geräte (das Passwort bleibt verborgen). */
    val wifiSsid: StateFlow<String?> = _wifiSsid.asStateFlow()

    private fun loadThread(): ThreadNetwork? {
        val ds = threadDataset() ?: return null
        return ThreadNetwork(
            summary = ds.summary,
            source = meta.getString(KEY_THREAD_SOURCE)?.let { s -> DatasetSource.entries.firstOrNull { it.name == s } } ?: DatasetSource.MANUAL,
            importedAt = meta.getString(KEY_THREAD_AT)?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
            sourceName = meta.getString(KEY_THREAD_FROM),
        )
    }

    /** Das vollständige Dataset – nur für das Koppeln. */
    fun threadDataset(): ThreadDataset? =
        secrets.get(KEY_THREAD)?.let { (ThreadDataset.parse(it) as? ThreadDataset.ParseResult.Ok)?.dataset }

    fun setThread(dataset: ThreadDataset, source: DatasetSource, sourceName: String? = null) {
        secrets.put(KEY_THREAD, dataset.bytes())
        meta.putString(KEY_THREAD_SOURCE, source.name)
        meta.putString(KEY_THREAD_AT, clock.millis().toString())
        meta.putString(KEY_THREAD_FROM, sourceName?.takeIf { it.isNotBlank() })
        _thread.value = loadThread()
    }

    fun clearThread() {
        secrets.put(KEY_THREAD, null)
        listOf(KEY_THREAD_SOURCE, KEY_THREAD_AT, KEY_THREAD_FROM).forEach { meta.putString(it, null) }
        _thread.value = null
    }

    data class WifiCredentials(val ssid: String, val password: String)

    fun wifi(): WifiCredentials? {
        val ssid = meta.getString(KEY_WIFI_SSID) ?: return null
        val password = secrets.get(KEY_WIFI_PASSWORD)?.decodeToString() ?: return null
        return WifiCredentials(ssid, password)
    }

    fun setWifi(ssid: String, password: String) {
        secrets.put(KEY_WIFI_PASSWORD, password.encodeToByteArray())
        meta.putString(KEY_WIFI_SSID, ssid)
        _wifiSsid.value = ssid
    }

    fun clearWifi() {
        secrets.put(KEY_WIFI_PASSWORD, null)
        meta.putString(KEY_WIFI_SSID, null)
        _wifiSsid.value = null
    }

    /** Werksreset und Übergabe: Zugangsdaten gehören den bisherigen Bewohnern. */
    fun clearAll() { clearThread(); clearWifi() }

    private companion object {
        const val KEY_THREAD = "thread_dataset"
        const val KEY_THREAD_SOURCE = "thread_source"
        const val KEY_THREAD_AT = "thread_imported_at"
        const val KEY_THREAD_FROM = "thread_source_name"
        const val KEY_WIFI_SSID = "commissioning_wifi_ssid"
        const val KEY_WIFI_PASSWORD = "commissioning_wifi_password"
    }
}
