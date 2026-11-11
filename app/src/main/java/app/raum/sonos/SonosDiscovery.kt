package app.raum.sonos

import android.content.Context
import android.net.wifi.WifiManager
import app.raum.thread.LocalHttp
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Findet Sonos-Lautsprecher im lokalen Netz; liefert deren Adressen. */
fun interface SonosDiscovery {
    fun discover(timeoutMs: Int): Set<String>
}

/**
 * SSDP-Suche (UPnP M-SEARCH an 239.255.255.250:1900) nach „ZonePlayer“. Bewusst nicht über NsdManager: Dessen
 * Namensauflösung teilt sich raum. schon mit Matter und Thread. Ein einziger gefundener Lautsprecher reicht – die
 * übrigen nennt er selbst (Zonengruppen).
 */
class SsdpSonosDiscovery(private val context: Context) : SonosDiscovery {

    override fun discover(timeoutMs: Int): Set<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("raum-sonos")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val found = LinkedHashSet<String>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 400
                val packet = SEARCH.toByteArray()
                val target = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(packet, packet.size, target, 1900)) }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                    if (!isSonos(text)) continue
                    val host = location(text)?.let(SonosXml::hostOf) ?: p.address.hostAddress ?: continue
                    if (runCatching { LocalHttp.isLocal(InetAddress.getByName(host)) }.getOrDefault(false)) found += host
                }
            }
        } catch (_: Exception) {
            // kein Netz o. Ä. – dann eben nichts gefunden
        } finally {
            runCatching { lock?.release() }
        }
        return found
    }

    companion object {
        private const val SEARCH = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

        internal fun isSonos(response: String) =
            response.contains("ZonePlayer", ignoreCase = true) || response.contains("Sonos", ignoreCase = true)

        internal fun location(response: String): String? =
            response.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }
}
