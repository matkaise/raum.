package app.raum.platform.display

import android.content.Context
import android.os.PowerManager
import app.raum.platform.kiosk.KioskManager

/** Setzt [DisplayMode.OFF] physisch um: Display aus (Device Owner) und wieder an. */
class ScreenPower(context: Context, private val kiosk: KioskManager) {
    private val power = context.getSystemService(PowerManager::class.java)

    val canTurnOff: Boolean get() = kiosk.isDeviceOwner

    fun turnOff() { kiosk.screenOff() }

    @Suppress("DEPRECATION") // ACQUIRE_CAUSES_WAKEUP: einziger Weg, das Display ohne Nutzeraktion einzuschalten
    fun turnOn() {
        if (power.isInteractive) return
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "raum:wake")
            .acquire(3_000)
    }
}
