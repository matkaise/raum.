package app.raum.ui.appliance

import app.raum.i18n.Strings
import app.raum.R
import android.app.Activity
import androidx.lifecycle.ViewModel
import app.raum.data.preferences.SettingsStore
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.platform.display.DisplayController
import app.raum.platform.display.DisplaySettings
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.kiosk.KioskStatus
import app.raum.platform.sensors.AmbientSensors
import app.raum.security.AdminPinStore
import app.raum.security.AdminPinStore.VerifyResult
import app.raum.security.MaintenanceSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display, Administrator-PIN und Wartungsmodus (M5). */
class ApplianceViewModel(
    private val settings: SettingsStore,
    val display: DisplayController,
    sensors: AmbientSensors,
    private val pins: AdminPinStore,
    private val session: MaintenanceSession,
    private val kiosk: KioskManager,
    private val log: EventLog,
    private val strings: Strings,
) : ViewModel() {
    val displaySettings: StateFlow<DisplaySettings> = settings.display
    val lux: StateFlow<Float?> = display.currentLux
    val near: StateFlow<Boolean?> = display.currentNear
    val hasLightSensor = sensors.hasLight
    val hasProximitySensor = sensors.hasProximity

    val unlocked: StateFlow<Boolean> = session.unlocked
    val kioskPaused: StateFlow<Boolean> = session.kioskPaused
    val kioskEnabled: StateFlow<Boolean> = settings.kioskEnabled

    private val _pinSet = MutableStateFlow(pins.isSet)
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()

    private val _kioskStatus = MutableStateFlow(kiosk.status())
    val kioskStatus: StateFlow<KioskStatus> = _kioskStatus.asStateFlow()

    fun updateDisplay(t: (DisplaySettings) -> DisplaySettings) = settings.updateDisplay(t)
    fun sleepNow() = display.sleepNow()

    fun pinValid(pin: String) = pins.isValidFormat(pin)

    /** Prüft die PIN und schaltet den Wartungsmodus frei. */
    fun unlock(pin: String): VerifyResult {
        val r = pins.verify(pin)
        when (r) {
            VerifyResult.Ok -> { session.unlock(); log.info(LogCategory.SYSTEM, strings.get(R.string.log_maintenance_unlocked)) }
            is VerifyResult.LockedOut -> log.warning(LogCategory.SYSTEM, strings.get(R.string.log_maintenance_locked_out))
            is VerifyResult.Wrong -> log.warning(LogCategory.SYSTEM, strings.get(R.string.log_maintenance_wrong_pin))
            VerifyResult.NotSet -> Unit
        }
        return r
    }

    /**
     * Ohne festgelegte PIN öffnet sich der Wartungsmodus direkt (bewusste Abweichung von SYS-006,
     * siehe docs/SECURITY.md). Mit PIN liefert die Funktion false – dann muss [unlock] benutzt werden.
     */
    fun enterWithoutPin(): Boolean {
        refreshPin()
        if (pins.isSet) return false
        session.unlock()
        log.warning(LogCategory.SYSTEM, strings.get(R.string.log_maintenance_open_no_pin))
        return true
    }

    /** Die PIN kann auch in der Einrichtung gesetzt worden sein (anderes ViewModel). */
    fun refreshPin() { _pinSet.value = pins.isSet }

    /** Legt die PIN fest (erstmalig) oder ändert sie (mit aktueller PIN). */
    fun setPin(newPin: String, current: String?): VerifyResult {
        val r = pins.setPin(newPin, current)
        if (r == VerifyResult.Ok) {
            _pinSet.value = true
            log.info(LogCategory.SYSTEM, strings.get(if (current == null) R.string.log_pin_set else R.string.log_pin_changed))
        }
        return r
    }

    fun lock() { session.lock(); log.info(LogCategory.SYSTEM, strings.get(R.string.log_maintenance_locked)) }

    fun refreshKioskStatus() { _kioskStatus.value = kiosk.status() }

    fun setKioskEnabled(enabled: Boolean) {
        settings.setKioskEnabled(enabled)
        log.info(LogCategory.SYSTEM, strings.get(if (enabled) R.string.log_kiosk_enabled else R.string.log_kiosk_disabled))
    }

    /**
     * Verlässt den Kiosk synchron – erst danach darf eine fremde Activity (z. B. Android-Einstellungen)
     * gestartet werden, sonst blockiert Android sie im Lock Task.
     */
    fun pauseKiosk(activity: Activity) {
        session.pauseKiosk()
        kiosk.exitLockTask(activity)
        log.info(LogCategory.SYSTEM, strings.get(R.string.log_kiosk_paused))
    }
    fun resumeKiosk() = session.resumeKiosk()

    fun setAdb(enabled: Boolean): Boolean = kiosk.setAdbEnabled(enabled).also {
        if (it) log.warning(LogCategory.SYSTEM, strings.get(if (enabled) R.string.log_adb_enabled else R.string.log_adb_disabled))
        refreshKioskStatus()
    }

    fun releaseDeviceOwner(activity: Activity) {
        log.warning(LogCategory.SYSTEM, strings.get(R.string.log_device_owner_released))
        kiosk.releaseDeviceOwner(activity)
        session.lock()
        refreshKioskStatus()
    }

    fun touch() = session.touch()
}
