package app.raum.sonos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Cover laden: Adressen auf dem Lautsprecher („http://<ip>:1400/getaa?…“) über die lokale Verbindung, Cover der
 * Musikdienste (https) direkt. Klartext ins Internet gibt es nicht. Kleiner Speicher-Cache, verkleinert auf ~[maxPx].
 */
class SonosArt(private val transport: SonosTransport, private val allowOnline: () -> Boolean = { false }, private val maxPx: Int = 480) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = LruCache<String, Long>(200)

    fun cached(url: String): Bitmap? = if (url.startsWith("https://") && !allowOnline()) null else cache.get(url)

    /** Blockiert – nur aus IO-Threads. */
    fun load(url: String): Bitmap? {
        if (url.startsWith("https://") && !allowOnline()) return null // ohne Einwilligung kein Internet
        cache.get(url)?.let { return it }
        failed.get(url)?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null }
        val bytes = runCatching { fetch(url) }.getOrNull()
        val bmp = bytes?.let { decode(it) }
        if (bmp == null) failed.put(url, System.currentTimeMillis()) else cache.put(url, bmp)
        return bmp
    }

    private fun fetch(url: String): ByteArray? {
        val u = URI(url)
        return when (u.scheme?.lowercase()) {
            "http" -> {
                if (u.port != -1 && u.port != SocketSonosTransport.PORT) return null
                val path = u.rawPath.orEmpty().ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
                transport.getBytes(u.host.removePrefix("[").removeSuffix("]"), path) // nur lokale Adressen (prüft der Transport)
            }
            "https" -> {
                val c = URL(url).openConnection() as HttpsURLConnection
                try {
                    c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true
                    if (c.responseCode != HttpURLConnection.HTTP_OK) null
                    else c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null }
                        out.toByteArray()
                    }
                } finally {
                    c.disconnect()
                }
            }
            else -> null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val RETRY_MS = 5 * 60_000L
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
