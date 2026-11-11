package app.raum.sonos

import app.raum.thread.LocalHttp
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HTTP ausschließlich ins lokale Netz für die Sonos-Lautsprecher (UPnP auf Port 1400, nur HTTP). raum. erlaubt sonst
 * keinen Klartextverkehr; wie bei [LocalHttp] ist diese Ausnahme auf private und Link-lokale Adressen beschränkt.
 */
interface SonosTransport {
    /** SOAP-Aufruf; liefert den Antwortrumpf oder wirft [SonosException]. */
    fun soap(host: String, path: String, serviceType: String, action: String, envelope: String): String
    /** Binärer GET (Cover vom Lautsprecher). */
    fun getBytes(host: String, path: String, maxBytes: Int = 2 * 1024 * 1024): ByteArray?
}

class SonosException(val kind: Kind, message: String? = null, val upnpError: Int? = null) : IOException(message) {
    enum class Kind { NETWORK, REFUSED, BAD_RESPONSE }
}

internal class SonosRaw(val status: Int, val body: ByteArray)

class SocketSonosTransport(private val timeoutMs: Int = 4000) : SonosTransport {

    override fun soap(host: String, path: String, serviceType: String, action: String, envelope: String): String {
        val body = envelope.toByteArray(Charsets.UTF_8)
        val headers = listOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPACTION" to "\"$serviceType#$action\"",
            "Content-Length" to body.size.toString(),
        )
        val r = exchange(host, "POST", path, headers, body, 4 * 1024 * 1024)
        val text = r.body.decodeToString()
        if (r.status != 200) {
            val code = Regex("<errorCode>(\\d+)</errorCode>").find(text)?.groupValues?.get(1)?.toIntOrNull()
            throw SonosException(if (r.status == 403) SonosException.Kind.REFUSED else SonosException.Kind.BAD_RESPONSE, "$action → HTTP ${r.status}", code)
        }
        return text
    }

    override fun getBytes(host: String, path: String, maxBytes: Int): ByteArray? = runCatching {
        exchange(host, "GET", path, emptyList(), null, maxBytes).takeIf { it.status == 200 }?.body
    }.getOrNull()

    private fun exchange(host: String, method: String, path: String, headers: List<Pair<String, String>>, body: ByteArray?, maxBytes: Int): SonosRaw {
        val address = resolveLocal(host)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, PORT), timeoutMs)
                socket.soTimeout = timeoutMs
                val head = buildString {
                    append("$method $path HTTP/1.1\r\nHost: ${address.hostAddress}:$PORT\r\nConnection: close\r\n")
                    headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("\r\n")
                }
                socket.getOutputStream().apply { write(head.toByteArray()); body?.let(::write); flush() }
                return read(BufferedInputStream(socket.getInputStream()), maxBytes)
            }
        } catch (e: SonosException) {
            throw e
        } catch (e: Exception) {
            throw SonosException(SonosException.Kind.NETWORK, "$host: ${e.message}")
        }
    }

    companion object {
        const val PORT = 1400

        fun resolveLocal(host: String): InetAddress {
            val a = runCatching { InetAddress.getByName(host) }.getOrElse { throw SonosException(SonosException.Kind.NETWORK, "$host: ${it.message}") }
            if (!LocalHttp.isLocal(a)) throw SonosException(SonosException.Kind.REFUSED, "$host is not a local address")
            return a
        }

        /** Statuszeile, Kopfzeilen, dann Rumpf nach Content-Length, chunked oder bis Verbindungsende – ohne auf das Ende zu warten, wenn die Länge bekannt ist. */
        internal fun read(input: InputStream, maxBytes: Int): SonosRaw {
            val status = readLine(input)?.split(" ")?.getOrNull(1)?.toIntOrNull()
                ?: throw SonosException(SonosException.Kind.BAD_RESPONSE, "no status line")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull()
            val body = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input, maxBytes)
                length != null -> {
                    if (length > maxBytes) throw SonosException(SonosException.Kind.BAD_RESPONSE, "response too large")
                    readExactly(input, length)
                }
                else -> readToEnd(input, maxBytes)
            }
            return SonosRaw(status, body)
        }

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                out.write(b)
                if (out.size() > 16 * 1024) throw SonosException(SonosException.Kind.BAD_RESPONSE, "header line too long")
            }
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            return if (off == n) buf else buf.copyOf(off)
        }

        private fun readChunked(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                if (size == 0) break
                if (out.size() + size > maxBytes) throw SonosException(SonosException.Kind.BAD_RESPONSE, "response too large")
                out.write(readExactly(input, size))
                readLine(input) // CRLF nach dem Block
            }
            return out.toByteArray()
        }

        private fun readToEnd(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                if (out.size() + n > maxBytes) throw SonosException(SonosException.Kind.BAD_RESPONSE, "response too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
