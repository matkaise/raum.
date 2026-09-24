package app.raum.platform.service

import java.io.File
import java.time.Instant

/**
 * Hält Abstürze fest (SYS-004): schreibt eine kurze Zusammenfassung synchron in eine Datei,
 * bevor der Prozess endet. Beim nächsten Start übernimmt [takeLastCrash] sie ins Protokoll.
 * Enthält nur Exception-Typ, Meldung und die obersten raum.-Stackframes – keine Nutzerdaten.
 */
class CrashRecorder(private val file: File) {

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(summary(thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Liest und löscht den letzten Absturzbericht. */
    fun takeLastCrash(): String? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text
    }

    internal fun summary(threadName: String, error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val frames = root.stackTrace.filter { it.className.startsWith("app.raum") }.take(3)
            .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return "${Instant.now()} · Thread $threadName · ${root.javaClass.simpleName}: ${root.message?.take(200) ?: "-"}" +
            if (frames.isNotEmpty()) " · $frames" else ""
    }
}
