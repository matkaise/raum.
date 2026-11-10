package app.raum.platform

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.annotation.StringRes
import app.raum.R
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.sensors.AmbientSensors
import java.time.ZonedDateTime

enum class CheckState { OK, WARNING, INFO }

/** Behebung direkt aus der Systemprüfung – im Kiosk gibt es keinen Weg in die Android-Einstellungen. */
enum class CheckAction { CHOOSE_WIFI, ENABLE_BLUETOOTH }

/** Ergebnis einer Prüfung; Texte als Ressourcen (übersetzbar). */
data class CheckItem(
    @StringRes val titleRes: Int,
    val state: CheckState,
    @StringRes val messageRes: Int,
    val arg: String? = null,
    val action: CheckAction? = null,
)

/**
 * Systemprüfung der Erstinbetriebnahme (ONB-005): Netzwerk, Bluetooth, Kiosk, Uhrzeit, Sensoren.
 * Warnungen blockieren nicht – raum. funktioniert auch ohne Internet (SYS-007).
 */
class SystemCheck(
    private val context: Context,
    private val kiosk: KioskManager,
    private val sensors: AmbientSensors,
) {
    fun run(now: ZonedDateTime = ZonedDateTime.now()): List<CheckItem> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
        return listOf(network(caps), bluetooth(), kiosk(), time(now, online = caps != null), sensors())
    }

    private fun network(caps: NetworkCapabilities?): CheckItem = when {
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
            CheckItem(R.string.check_network, CheckState.OK, R.string.check_network_lan)
        // WLAN wechseln bleibt möglich
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
            CheckItem(R.string.check_network, CheckState.OK, R.string.check_network_wifi, action = CheckAction.CHOOSE_WIFI)
        else -> CheckItem(R.string.check_network, CheckState.WARNING, R.string.check_network_none, action = CheckAction.CHOOSE_WIFI)
    }

    private fun bluetooth(): CheckItem {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return when {
            adapter == null -> CheckItem(R.string.check_bluetooth, CheckState.WARNING, R.string.check_bluetooth_missing)
            !runCatching { adapter.isEnabled }.getOrDefault(false) ->
                CheckItem(R.string.check_bluetooth, CheckState.WARNING, R.string.check_bluetooth_off, action = CheckAction.ENABLE_BLUETOOTH)
            else -> CheckItem(R.string.check_bluetooth, CheckState.OK, R.string.check_bluetooth_on)
        }
    }

    private fun kiosk(): CheckItem =
        if (kiosk.isDeviceOwner) CheckItem(R.string.check_kiosk, CheckState.OK, R.string.check_kiosk_ok)
        else CheckItem(R.string.check_kiosk, CheckState.INFO, R.string.check_kiosk_missing)

    private fun time(now: ZonedDateTime, online: Boolean): CheckItem {
        val auto = Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
        val shown = "%02d.%02d.%d %02d:%02d".format(now.dayOfMonth, now.monthValue, now.year, now.hour, now.minute)
        return when {
            now.year < 2026 -> CheckItem(R.string.check_time, CheckState.WARNING, R.string.check_time_implausible, shown)
            // Ohne Netz ist die automatische Uhrzeit nicht abgeglichen (neue Geräte stehen oft Wochen daneben)
            auto && !online -> CheckItem(R.string.check_time, CheckState.WARNING, R.string.check_time_unsynced, shown)
            auto -> CheckItem(R.string.check_time, CheckState.OK, R.string.check_time_auto, shown)
            else -> CheckItem(R.string.check_time, CheckState.INFO, R.string.check_time_manual, shown)
        }
    }

    private fun sensors(): CheckItem = when {
        sensors.hasLight && sensors.hasProximity -> CheckItem(R.string.check_sensors, CheckState.OK, R.string.check_sensors_ok)
        else -> CheckItem(R.string.check_sensors, CheckState.INFO, R.string.check_sensors_missing)
    }
}
