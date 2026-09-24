package app.raum.platform.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device-Admin-Komponente für den Device-Owner-Betrieb (SYS-002).
 * Einrichtung (Entwicklung, Gerät ohne Konten):
 *   adb shell dpm set-device-owner app.raum.panel/app.raum.platform.kiosk.RaumDeviceAdminReceiver
 */
class RaumDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        KioskManager(context).applyPolicies()
    }

    companion object {
        fun component(context: Context) = ComponentName(context, RaumDeviceAdminReceiver::class.java)
    }
}
