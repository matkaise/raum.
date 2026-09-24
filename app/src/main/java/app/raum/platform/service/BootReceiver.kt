package app.raum.platform.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Autostart nach Einschalten/Stromausfall und nach App-Update (SYS-001, SYS-003, Spez. 12.2). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> CoreService.start(context)
        }
    }
}
