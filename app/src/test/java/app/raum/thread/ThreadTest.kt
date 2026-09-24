package app.raum.thread

import app.raum.domain.models.DeviceNetwork
import app.raum.domain.models.DeviceState
import app.raum.domain.models.NetworkTransport
import app.raum.domain.models.OnlineState
import app.raum.domain.models.ThreadRole
import app.raum.security.InMemoryKeyValueStore
import app.raum.security.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.time.Instant

/** Baut ein Dataset aus TLVs (Thread-Spez. 8.10). */
internal object Datasets {
    fun tlv(type: Int, value: ByteArray) = byteArrayOf(type.toByte(), value.size.toByte()) + value
    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    const val KEY = "00112233445566778899aabbccddeeff"

    fun bytes(
        name: String = "raum-test",
        withKey: Boolean = true,
        extra: ByteArray = ByteArray(0),
    ): ByteArray =
        tlv(14, hex("0000000000010000")) +                  // Active Timestamp 1
            tlv(0, hex("00000f")) +                          // Kanal 15
            tlv(53, hex("0004001fffe0")) +                   // Kanalmaske
            tlv(2, hex("dead00beef00cafe")) +                // Extended PAN ID
            tlv(7, hex("fd000db800a00000")) +                // Mesh-Local Prefix
            (if (withKey) tlv(5, hex(KEY)) else ByteArray(0)) +
            tlv(3, name.encodeToByteArray()) +
            tlv(1, hex("1234")) +                            // PAN ID
            tlv(4, hex("445f2b5ca6f2a93a55ce570a70efeecb")) + // PSKc
            tlv(12, hex("02a0f7f8")) +                       // Security Policy
            extra

    fun hexString(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun dataset(name: String = "raum-test") = (ThreadDataset.parse(bytes(name)) as ThreadDataset.ParseResult.Ok).dataset
}

class ThreadDatasetTest {

    @Test fun `gültiges Dataset wird gelesen`() {
        val r = ThreadDataset.parse(Datasets.hexString(Datasets.bytes()))
        val ds = (r as ThreadDataset.ParseResult.Ok).dataset
        assertEquals("raum-test", ds.networkName)
        assertEquals(15, ds.channel)
        assertEquals(0x1234, ds.panId)
        assertEquals("dead00beef00cafe", ds.extPanId)
        assertEquals("fd00:db8:a0:0::/64", ds.meshLocalPrefix)
        assertEquals(1L, ds.activeTimestamp)
        assertEquals(Datasets.hexString(Datasets.bytes()), ds.hex())
    }

    @Test fun `Leerzeichen, Zeilenumbrüche, Doppelpunkte und 0x sind erlaubt`() {
        val hex = Datasets.hexString(Datasets.bytes()).chunked(2).joinToString(":")
        val messy = "0x" + hex.chunked(30).joinToString("\n  ")
        assertTrue(ThreadDataset.parse(messy) is ThreadDataset.ParseResult.Ok)
        assertTrue(ThreadDataset.parse(Datasets.hexString(Datasets.bytes()).uppercase()) is ThreadDataset.ParseResult.Ok)
    }

    @Test fun `Fehler werden unterschieden`() {
        fun err(s: String) = (ThreadDataset.parse(s) as ThreadDataset.ParseResult.Invalid).error
        val good = Datasets.hexString(Datasets.bytes())
        assertEquals(ThreadDataset.Error.EMPTY, err("  "))
        assertEquals(ThreadDataset.Error.NOT_HEX, err(good.dropLast(1)))
        assertEquals(ThreadDataset.Error.NOT_HEX, err("zz" + good))
        assertEquals(ThreadDataset.Error.MALFORMED, err(good.dropLast(4)))                        // abgeschnitten
        assertEquals(ThreadDataset.Error.MISSING_FIELDS, err(Datasets.hexString(Datasets.bytes(withKey = false))))
        assertEquals(ThreadDataset.Error.MALFORMED, err(Datasets.hexString(Datasets.bytes() + Datasets.tlv(1, Datasets.hex("12")))))
        assertEquals(ThreadDataset.Error.MALFORMED, err(Datasets.hexString(Datasets.bytes(name = "x".repeat(17)))))
        assertEquals(ThreadDataset.Error.TOO_LONG, err(Datasets.hexString(Datasets.bytes(extra = Datasets.tlv(99, ByteArray(200))))))
    }

    @Test fun `Schlüssel taucht in toString und Zusammenfassung nie auf`() {
        val ds = Datasets.dataset()
        assertFalse(ds.toString().contains(Datasets.KEY))
        assertFalse(ds.summary.toString().contains(Datasets.KEY))
    }
}

class MeshcopTxtTest {
    private fun sb(ifStatus: Int) = byteArrayOf(0, 0, 0, (ifStatus shl 3 or 0x1).toByte())

    @Test fun `TXT-Einträge eines Border Routers`() {
        val r = MeshcopTxt.parse(
            "Wohnzimmer",
            mapOf(
                "vn" to "Apple Inc.".toByteArray(), "mn" to "HomePod".toByteArray(), "nn" to "MyHome1234".toByteArray(),
                "xp" to Datasets.hex("dead00beef00cafe"), "tv" to "1.3.0".toByteArray(), "sb" to sb(2),
            ),
        )
        assertEquals("HomePod", r.displayName)
        assertEquals("MyHome1234", r.networkName)
        assertEquals("dead00beef00cafe", r.extPanId)
        assertEquals(ThreadInterfaceState.ACTIVE, r.state)
        assertTrue(r.active)
        assertTrue(r.closedEcosystem)
    }

    @Test fun `xp als Hex-Text, fehlendes sb, generischer Modellname`() {
        val r = MeshcopTxt.parse(
            "otbr-kueche",
            mapOf("vn" to "OpenThread".toByteArray(), "mn" to "BorderRouter".toByteArray(), "xp" to "DEAD00BEEF00CAFE".toByteArray(), "nn" to null),
        )
        assertEquals("dead00beef00cafe", r.extPanId)
        assertEquals("otbr-kueche", r.displayName)
        assertEquals(ThreadInterfaceState.UNKNOWN, r.state)
        assertNull(r.networkName)
        assertFalse(r.closedEcosystem)
        assertEquals(ThreadInterfaceState.INACTIVE, MeshcopTxt.parse("x", mapOf("sb" to sb(1))).state)
        assertEquals(ThreadInterfaceState.NOT_INITIALIZED, MeshcopTxt.parse("x", mapOf("sb" to sb(0))).state)
    }
}

class ThreadDiagnosisTest {
    private val net = ThreadNetwork(Datasets.dataset("raum-test").summary, DatasetSource.MANUAL, Instant.EPOCH, null)
    private fun router(xp: String? = "dead00beef00cafe", state: ThreadInterfaceState = ThreadInterfaceState.ACTIVE, name: String? = "raum-test") =
        BorderRouter("br-$xp-$state", "OpenThread", "BorderRouter", name, xp, "1.3.0", state)
    private fun device(node: ULong, online: Boolean, network: String = "raum-test") =
        DeviceState(node, if (online) OnlineState.ONLINE else OnlineState.OFFLINE, emptyList(),
            network = DeviceNetwork(NetworkTransport.THREAD, ThreadRole.SLEEPY_END_DEVICE, network))

    @Test fun `Zustände des hinterlegten Netzes`() {
        assertEquals(ThreadHealth.NOT_CONFIGURED, ThreadDiagnosis.evaluate(null, listOf(router()), emptyList(), null).health)
        assertEquals(ThreadHealth.NO_BORDER_ROUTER, ThreadDiagnosis.evaluate(net, emptyList(), emptyList(), null).health)
        assertEquals(ThreadHealth.NO_BORDER_ROUTER, ThreadDiagnosis.evaluate(net, listOf(router(xp = "0102030405060708")), emptyList(), null).health)
        assertEquals(ThreadHealth.BORDER_ROUTER_INACTIVE,
            ThreadDiagnosis.evaluate(net, listOf(router(state = ThreadInterfaceState.INACTIVE)), emptyList(), null).health)
        val ok = ThreadDiagnosis.evaluate(net, listOf(router(), router(xp = "0102030405060708")), emptyList(), null)
        assertEquals(ThreadHealth.OK, ok.health)
        assertEquals(1, ok.matching.size)
        // Router ohne xp: Abgleich über den Netznamen
        assertEquals(ThreadHealth.OK, ThreadDiagnosis.evaluate(net, listOf(router(xp = null)), emptyList(), null).health)
    }

    @Test fun `Border Router nicht erreichbar, wenn alle Thread-Geräte offline und kein Router aktiv`() {
        val offline = listOf(device(1u, false), device(2u, false))
        assertTrue(ThreadDiagnosis.evaluate(null, emptyList(), offline, null).borderRouterUnreachable)
        // Direkt nach dem Start (Suche noch nicht belastbar) kein Fehlalarm
        assertFalse(ThreadDiagnosis.evaluate(null, emptyList(), offline, null, searched = false).borderRouterUnreachable)
        assertFalse(ThreadDiagnosis.evaluate(null, listOf(router()), offline, null).borderRouterUnreachable)
        val mixed = ThreadDiagnosis.evaluate(null, emptyList(), listOf(device(1u, true), device(2u, false, "Apple")), null)
        assertFalse(mixed.borderRouterUnreachable)
        assertEquals(2, mixed.threadDevices)
        assertEquals(1, mixed.threadDevicesOnline)
        assertEquals(setOf("raum-test", "Apple"), mixed.deviceNetworks)
        // WLAN-Geräte zählen nicht
        val wifi = DeviceState(3u, OnlineState.OFFLINE, emptyList(), network = DeviceNetwork(NetworkTransport.WIFI))
        assertEquals(0, ThreadDiagnosis.evaluate(null, emptyList(), listOf(wifi), null).threadDevices)
    }
}

class NetworkCredentialStoreTest {
    @Test fun `Dataset und WLAN verschlüsselt ablegen, Anzeigedaten getrennt`() {
        val secrets = InMemorySecretStore()
        val meta = InMemoryKeyValueStore()
        val store = NetworkCredentialStore(secrets, meta)
        assertNull(store.thread.value)
        assertNull(store.threadDataset())

        val ds = Datasets.dataset()
        store.setThread(ds, DatasetSource.BORDER_ROUTER, "otbr")
        assertEquals("raum-test", store.thread.value?.summary?.networkName)
        assertEquals(DatasetSource.BORDER_ROUTER, store.thread.value?.source)
        assertEquals(ds, store.threadDataset())

        store.setWifi("Heimnetz", "geheim123")
        assertEquals("Heimnetz", store.wifiSsid.value)
        assertEquals(NetworkCredentialStore.WifiCredentials("Heimnetz", "geheim123"), store.wifi())

        // Neustart: alles wieder da
        val again = NetworkCredentialStore(secrets, meta)
        assertEquals("otbr", again.thread.value?.sourceName)
        assertEquals("Heimnetz", again.wifiSsid.value)

        // Geheimes steht nicht im normalen Speicher
        listOf("thread_dataset", "commissioning_wifi_password").forEach { assertNull(meta.getString(it)) }

        again.clearAll()
        assertNull(again.thread.value)
        assertNull(again.wifi())
        assertNull(NetworkCredentialStore(secrets, meta).threadDataset())
    }
}

class LocalHttpTest {
    @Test fun `Antworten mit Content-Length und chunked`() {
        val plain = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 6\r\n\r\n0e0800extra"
        assertEquals(LocalHttp.Response(200, "0e0800"), LocalHttp.parse(plain.byteInputStream()))
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n0e08\r\n2\r\n00\r\n0\r\n\r\n"
        assertEquals(LocalHttp.Response(200, "0e0800"), LocalHttp.parse(chunked.byteInputStream()))
        assertEquals(204, LocalHttp.parse("HTTP/1.1 204 No Content\r\n\r\n".byteInputStream())?.status)
        assertNull(LocalHttp.parse("garbage".byteInputStream()))
    }

    @Test fun `nur lokale Adressen`() {
        listOf("192.168.1.20", "10.0.0.2", "172.16.4.1", "fe80::1", "fd12:3456::1").forEach {
            assertTrue(it, LocalHttp.isLocal(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "2001:4860:4860::8888", "1.1.1.1").forEach {
            assertFalse(it, LocalHttp.isLocal(InetAddress.getByName(it)))
        }
        assertNotNull(LocalHttp)
    }
}
