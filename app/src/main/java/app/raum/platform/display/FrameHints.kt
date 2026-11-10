package app.raum.platform.display

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Leistungshinweise (Android Dynamic Performance Framework) für UI- und Render-Thread: Nach jedem Bild wird die
 * tatsächliche Bildzeit gegen das Ziel (60 Bilder/s) gemeldet. Ein Leistungsdienst, der das unterstützt, legt die
 * Threads dann auf schnellere Kerne bzw. höheren Takt – ohne Root und ohne Systemeinstellungen.
 *
 * Hintergrund: Die Firmware des RK3576-Panels lässt die Vordergrund-App sonst auf den sparsamen A53-Kernen laufen
 * (gemessen: Scrollen 17 % zu langsame Bilder, auf den A72-Kernen 7 %). Erst ab Android 12 verfügbar.
 */
@RequiresApi(Build.VERSION_CODES.S)
class FrameHints(private val activity: Activity) {
    private var session: PerformanceHintManager.Session? = null
    private var thread: HandlerThread? = null
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (total > 0) runCatching { session?.reportActualWorkDuration(total) }
    }

    /** Auf dem Hauptthread aufrufen (dessen Thread-ID wird angemeldet), nach dem ersten Bild – dann gibt es den Render-Thread. */
    fun start() {
        if (session != null) return
        val manager = activity.getSystemService(PerformanceHintManager::class.java) ?: return
        if (manager.preferredUpdateRateNanos < 0) { Log.i(TAG, "Leistungshinweise nicht unterstützt"); return }
        val tids = (listOf(Process.myTid()) + threadIds("RenderThread")).distinct().toIntArray()
        session = runCatching { manager.createHintSession(tids, TARGET_NS) }.getOrNull()
        if (session == null) { Log.i(TAG, "Leistungshinweise: keine Sitzung"); return }
        val t = HandlerThread("FrameHints").also { it.start() }
        thread = t
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(t.looper))
        Log.i(TAG, "Leistungshinweise aktiv für Threads ${tids.joinToString()}")
    }

    fun stop() {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        session?.close(); session = null
        thread?.quitSafely(); thread = null
    }

    private fun threadIds(name: String): List<Int> =
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            val comm = runCatching { File(task, "comm").readText().trim() }.getOrNull()
            task.name.toIntOrNull()?.takeIf { comm == name }
        }

    private companion object {
        const val TAG = "raum.frames"
        const val TARGET_NS = 16_666_667L
    }
}
