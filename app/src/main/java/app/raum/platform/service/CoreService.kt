package app.raum.platform.service

import app.raum.i18n.Strings
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.raum.CoreStartup
import app.raum.MainActivity
import app.raum.R
import org.koin.android.ext.android.inject

/**
 * Hält den raum.-Core dauerhaft am Leben (SYS-003, SYS-004, Spez. 12.2):
 * - Foreground Service → Android beendet den Prozess nicht im Hintergrund.
 * - START_STICKY → nach Absturz/Beenden startet das System den Dienst neu.
 * - Partieller WakeLock → Automationen und Sensoren laufen auch bei ausgeschaltetem Display.
 */
class CoreService : Service() {

    private val startup: CoreStartup by inject()
    private val strings: Strings by inject()
    private var wakeLock: PowerManager.WakeLock? = null

    // Dauerhafter WakeLock ist Absicht: Appliance am Netzteil, Automationen/Sensoren laufen bei Display aus (SYS-003).
    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "raum:core")
            .apply { setReferenceCounted(false); acquire() }
        startup.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, strings.get(R.string.notification_channel_core), NotificationManager.IMPORTANCE_MIN).apply {
                description = strings.get(R.string.notification_channel_core_description)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(strings.get(R.string.notification_core_title))
            .setContentText(strings.get(R.string.notification_core_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "core"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CoreService::class.java)) }
        }
    }
}
