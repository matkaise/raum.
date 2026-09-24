package app.raum.platform.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import app.raum.MainActivity

data class KioskStatus(
    val isDeviceOwner: Boolean,
    val lockTaskActive: Boolean,
    val isDefaultLauncher: Boolean,
    val adbEnabled: Boolean,
)

/**
 * Appliance-Betrieb (SYS-001, SYS-002, SYS-005): Device-Owner-Richtlinien, Launcher, Lock Task.
 * Ohne Device Owner bleibt raum. eine normale App – alle Aufrufe sind dann wirkungslos.
 */
class KioskManager(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin: ComponentName = RaumDeviceAdminReceiver.component(context)

    val isDeviceOwner: Boolean get() = dpm?.isDeviceOwnerApp(context.packageName) == true

    /** Richtlinien setzen; idempotent, bei jedem Start aufgerufen. */
    fun applyPolicies() {
        if (!isDeviceOwner) return
        // Systemdialog zur Dateiauswahl zulassen (Sicherung/Export/Update auf USB), sonst blockiert der Kiosk ihn
        val picker = context.packageManager
            .resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 0)
            ?.activityInfo?.packageName
        dpm.setLockTaskPackages(admin, listOfNotNull(context.packageName, picker).toTypedArray())
        // Nur Ein/Aus-Menü erlauben; keine Home-/Übersicht-Tasten, keine Benachrichtigungen
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        // raum. dauerhaft als Startbildschirm (SYS-001/002)
        dpm.addPersistentPreferredActivity(
            admin,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            ComponentName(context, MainActivity::class.java),
        )
        // Kein Sperrbildschirm und keine Statusleiste auf dem Wandpanel
        dpm.setKeyguardDisabled(admin, true)
        dpm.setStatusBarDisabled(admin, true)
        // Benachrichtigung des Kerndienstes ohne Nachfrage erlauben
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching {
            dpm.setPermissionGrantState(admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }

    fun status(activity: Activity? = null): KioskStatus {
        val am = context.getSystemService(ActivityManager::class.java)
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return KioskStatus(
            isDeviceOwner = isDeviceOwner,
            lockTaskActive = am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE,
            isDefaultLauncher = home?.activityInfo?.packageName == context.packageName,
            adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1,
        )
    }

    fun enterLockTask(activity: Activity) {
        if (!isDeviceOwner) return
        val am = context.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { activity.startLockTask() }
    }

    fun exitLockTask(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }

    /** Zeitzone systemweit setzen (ONB-003) – nur als Device Owner möglich. */
    fun setTimeZone(zoneId: String): Boolean =
        isDeviceOwner && runCatching { dpm.setTimeZone(admin, zoneId) }.getOrDefault(false)

    /** Display sofort aus (nur Device Owner; Keyguard ist deaktiviert, daher kein Sperrbildschirm). */
    /** Laufzeitberechtigung als Geräteeigentümer selbst erteilen (keine Nachfrage im Kiosk). */
    fun grantPermission(permission: String): Boolean = isDeviceOwner && runCatching {
        dpm.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
    }.getOrDefault(false)

    /** Standortdienst (nur für die WLAN-Suche unter Android 11/12 nötig – es wird nichts geortet). */
    fun setLocationEnabled(enabled: Boolean): Boolean =
        isDeviceOwner && runCatching { dpm.setLocationEnabled(admin, enabled); true }.getOrDefault(false)

    fun screenOff(): Boolean = if (isDeviceOwner) runCatching { dpm.lockNow(); true }.getOrDefault(false) else false

    /** ADB ein/aus (Spez. 11.1: im Endkundenbetrieb aus). */
    fun setAdbEnabled(enabled: Boolean): Boolean =
        isDeviceOwner && runCatching { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, if (enabled) "1" else "0"); true }.getOrDefault(false)

    /** Gibt den Device-Owner-Status ab (Übergabe/Entwicklung). Danach ist raum. eine normale App. */
    fun releaseDeviceOwner(activity: Activity) {
        if (!isDeviceOwner) return
        exitLockTask(activity)
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        dpm.setStatusBarDisabled(admin, false)
        dpm.setKeyguardDisabled(admin, false)
        dpm.setLockTaskPackages(admin, emptyArray())
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
    }
}
