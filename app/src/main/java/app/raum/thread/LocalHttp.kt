package app.raum.thread

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimaler HTTP-GET ausschließlich ins lokale Netz – für die REST-API eines OpenThread Border Routers
 * (Port 8081, nur HTTP). raum. erlaubt sonst keinen Klartextverkehr; diese Ausnahme ist auf private und
 * Link-lokale Adressen beschränkt und wird nie für das Internet benutzt (Spez. 11.1).
 */
object LocalHttp {
    data class Response(val status: Int, val body: String)

    fun isLocal(address: InetAddress): Boolean = when {
        address.isSiteLocalAddress || address.isLinkLocalAddress -> true
        // IPv6 Unique Local fc00::/7
        address is Inet6Address -> (address.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    fun get(host: InetAddress, port: Int, path: String, accept: String, timeoutMs: Int = 3000): Response? {
        require(isLocal(host)) { "only local addresses" }
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val hostHeader = if (host is Inet6Address) "[${host.hostAddress?.substringBefore('%')}]" else host.hostAddress
                val request = "GET $path HTTP/1.1\r\nHost: $hostHeader:$port\r\nAccept: $accept\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply { write(request.toByteArray()); flush() }
                parse(socket.getInputStream())
            }
        }.getOrNull()
    }

    /** Antwort lesen: Statuszeile, Kopfzeilen, Rumpf (Content-Length, chunked oder bis Verbindungsende). */
    internal fun parse(input: InputStream): Response? {
        val all = input.readBytesLimited(64 * 1024)
        val headerEnd = all.indexOf("\r\n\r\n")
        if (headerEnd < 0) return null
        val head = all.copyOfRange(0, headerEnd).decodeToString().split("\r\n")
        val status = head.first().split(" ").getOrNull(1)?.toIntOrNull() ?: return null
        val headers = head.drop(1).mapNotNull { line ->
            val i = line.indexOf(':'); if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        var body = all.copyOfRange(headerEnd + 4, all.size)
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) body = dechunk(body)
        headers["content-length"]?.toIntOrNull()?.let { if (it <= body.size) body = body.copyOfRange(0, it) }
        return Response(status, body.decodeToString())
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val lineEnd = data.indexOf("\r\n", i).takeIf { it >= 0 } ?: break
            val size = data.copyOfRange(i, lineEnd).decodeToString().substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            if (start + size > data.size) break
            out.write(data, start, size)
            i = start + size + 2
        }
        return out.toByteArray()
    }

    private fun ByteArray.indexOf(pattern: String, from: Int = 0): Int {
        val p = pattern.toByteArray()
        outer@ for (i in from..size - p.size) {
            for (j in p.indices) if (this[i + j] != p[j]) continue@outer
            return i
        }
        return -1
    }

    private fun InputStream.readBytesLimited(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = read(buf); if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
