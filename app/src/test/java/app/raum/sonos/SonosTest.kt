package app.raum.sonos

import app.raum.diagnostics.EventLog
import app.raum.i18n.XmlStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SonosTest {

    private val zgs = """
        <ZoneGroupState><ZoneGroups>
          <ZoneGroup Coordinator="RINCON_ARC01400" ID="RINCON_ARC01400:1">
            <ZoneGroupMember UUID="RINCON_ARC01400" Location="http://192.168.1.39:1400/xml/device_description.xml" ZoneName="Wohnzimmer">
              <Satellite UUID="RINCON_SUB01400" Location="http://192.168.1.50:1400/xml/device_description.xml" ZoneName="Wohnzimmer" Invisible="1"/>
            </ZoneGroupMember>
            <ZoneGroupMember UUID="RINCON_BAD01400" Location="http://192.168.1.111:1400/xml/device_description.xml" ZoneName="Badezimmer"/>
            <ZoneGroupMember UUID="RINCON_RIGHT01400" Location="http://192.168.1.112:1400/xml/device_description.xml" ZoneName="Badezimmer" Invisible="1"/>
          </ZoneGroup>
          <ZoneGroup Coordinator="RINCON_BOOST01400" ID="x">
            <ZoneGroupMember UUID="RINCON_BOOST01400" Location="http://192.168.1.5:1400/xml/device_description.xml" ZoneName="BOOST" IsZoneBridge="1"/>
          </ZoneGroup>
        </ZoneGroups><VanishedDevices/></ZoneGroupState>
    """.trimIndent()

    /** So liefert der Lautsprecher Favoriten: DIDL, darin r:resMD als (nochmals) maskiertes DIDL. */
    private val favoritesDidl = """
        <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
          <item id="FV:2/13" parentID="FV:2" restricted="false"><dc:title>Aktuell angesagt</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class><r:ordinal>0</r:ordinal></item>
          <item id="FV:2/14" parentID="FV:2" restricted="false"><dc:title>Hit List</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class>
            <res protocolInfo="x-sonosapi-radio:*:audio/x-sonosapi-radio:*">x-sonosapi-radio:2997?sid=303&amp;flags=8296&amp;sn=1</res>
            <upnp:albumArtURI>https://example.org/hitlist.jpg</upnp:albumArtURI><r:description>Sonos Radio</r:description>
            <r:resMD>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;item id=&quot;100c20682997&quot;&gt;&lt;dc:title&gt;Hit List&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</r:resMD></item>
          <item id="FV:2/15" parentID="FV:2" restricted="false"><dc:title>Chill Mix</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class>
            <res protocolInfo="x-rincon-cpcontainer:*:*:*">x-rincon-cpcontainer:1006206cmix?sid=284&amp;flags=8300&amp;sn=7</res>
            <upnp:albumArtURI>/getaa?s=1&amp;u=x-sonos-http%3amix</upnp:albumArtURI><r:description>YouTube Music</r:description>
            <r:resMD>&lt;DIDL-Lite&gt;&lt;item id=&quot;1006206cmix&quot;/&gt;&lt;/DIDL-Lite&gt;</r:resMD></item>
        </DIDL-Lite>
    """.trimIndent()

    @Test
    fun `zone groups skip hidden members and bridges and take hosts from locations`() {
        val groups = SonosXml.zoneGroups(zgs)
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals("RINCON_ARC01400", g.coordinatorUuid)
        assertEquals(listOf("Wohnzimmer" to "192.168.1.39", "Badezimmer" to "192.168.1.111"), g.members.map { it.name to it.host })
    }

    @Test
    fun `favorites without address are dropped and relative art is resolved on the speaker`() {
        val favs = SonosService.favoritesFrom(SonosXml.didl(favoritesDidl), "192.168.1.39")
        assertEquals(listOf("Hit List", "Chill Mix"), favs.map { it.title })
        val radio = favs[0]
        assertTrue(radio.isStream)
        assertEquals("x-sonosapi-radio:2997?sid=303&flags=8296&sn=1", radio.uri)
        assertTrue(radio.metadata.startsWith("<DIDL-Lite") && radio.metadata.contains("<dc:title>Hit List</dc:title>"))
        assertEquals("Sonos Radio", radio.subtitle)
        val mix = favs[1]
        assertFalse(mix.isStream)
        assertEquals(listOf("http://192.168.1.39:1400/getaa?s=1&u=x-sonos-http%3amix"), mix.artUrls)
        // Internet-Cover: erst über den Lautsprecher versuchen, die https-Adresse nur als zweite Wahl
        assertEquals(
            listOf("http://192.168.1.39:1400/getaa?s=1&u=x-sonosapi-radio%3A2997%3Fsid%3D303%26flags%3D8296%26sn%3D1", "https://example.org/hitlist.jpg"),
            radio.artUrls,
        )
        assertEquals("YouTube Music", mix.subtitle)
    }

    @Test
    fun `times, play modes and sources`() {
        assertEquals(205, SonosXml.seconds("0:03:25"))
        assertNull(SonosXml.seconds("NOT_IMPLEMENTED"))
        assertEquals("1:01:01", SonosXml.hms(3661))
        for (shuffle in listOf(true, false)) for (r in RepeatMode.entries) {
            assertEquals(shuffle to r, SonosXml.playMode(SonosXml.playModeName(shuffle, r)))
        }
        assertEquals(PlaySource.QUEUE, SonosXml.source("x-rincon-queue:RINCON_X#0"))
        assertEquals(PlaySource.TV, SonosXml.source("x-sonos-htastream:RINCON_X:spdif"))
        assertEquals(PlaySource.STREAM, SonosXml.source("x-sonosapi-radio:2997?sid=303"))
        assertEquals(PlaySource.NONE, SonosXml.source(null))
        assertEquals("fe80::1", SonosXml.hostOf("http://[fe80::1]:1400/x"))
    }

    @Test
    fun `radio stream content is split into artist and title`() {
        val md = DidlEntry("-1", false, "x-sonosapi-stream:s1234", streamContent = "Daft Punk - One More Time", artUri = "/getaa?u=1")
        val t = SonosService.trackOf(PositionInfo("x-sonosapi-stream:s1234", md, 10, null), PlaySource.STREAM, "10.0.0.2")!!
        assertEquals("One More Time", t.title); assertEquals("Daft Punk", t.artist)
        assertEquals("http://10.0.0.2:1400/getaa?u=1", t.artUrl)
        assertNull(SonosService.trackOf(PositionInfo(null, md, 0, null), PlaySource.TV, "10.0.0.2"))
    }

    @Test
    fun `http responses with content length and chunked bodies`() {
        val cl = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA".toByteArray()
        assertEquals("hello", SocketSonosTransport.read(ByteArrayInputStream(cl), 1000).body.decodeToString())
        val ch = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n".toByteArray()
        val r = SocketSonosTransport.read(ByteArrayInputStream(ch), 1000)
        assertEquals(200, r.status); assertEquals("abcde", r.body.decodeToString())
    }

    @Test
    fun `soap requests escape arguments and responses are unescaped`() {
        val t = FakeTransport()
        t.responses["GetZoneGroupState"] = "<ZoneGroupState>${SonosXml.escape(zgs)}</ZoneGroupState>"
        val api = UpnpSonosApi(t)
        assertEquals(2, api.zoneGroups("192.168.1.39").single().members.size)
        api.setUri("192.168.1.39", "x-sonosapi-radio:1?a=1&b=2", "<DIDL-Lite/>")
        val sent = t.calls.last()
        assertEquals("SetAVTransportURI", sent.action)
        assertTrue(sent.envelope.contains("<CurrentURI>x-sonosapi-radio:1?a=1&amp;b=2</CurrentURI>"))
        assertTrue(sent.envelope.contains("<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>"))
    }

    // --- Dienst ----------------------------------------------------------------------------------------

    @Test
    fun `finds speakers via discovery, remembers them and reads playback`() = runTest {
        val api = FakeApi()
        val settings = FakeSettings()
        val s = service(api, settings, discovery = { setOf("192.168.1.39") })
        s.start(backgroundScope); runCurrent()
        val st = s.state.value
        assertEquals(SonosLink.CONNECTED, st.link)
        assertEquals(listOf("Badezimmer", "Wohnzimmer"), st.players.map { it.name })
        assertEquals(setOf("192.168.1.39", "192.168.1.111"), settings.knownHosts)
        val living = st.group("ARC")!!
        assertTrue(living.playback.playing)
        assertEquals("Song", living.playback.track?.title)
        assertEquals(PlaySource.QUEUE, living.playback.source)
        assertTrue(living.playback.canNext)
        assertEquals(30, living.volume)
        assertEquals(2, st.favorites.size)
    }

    @Test
    fun `stream favorites play directly, others through the queue`() = runTest {
        val api = FakeApi()
        val s = service(api, FakeSettings(), discovery = { setOf("192.168.1.39") })
        s.start(backgroundScope); runCurrent()
        val (radio, mix) = s.state.value.favorites
        api.log.clear()
        assertTrue(s.play("ARC", radio).isSuccess)
        assertEquals(listOf("setUri 192.168.1.39 ${radio.uri}", "play 192.168.1.39"), api.log)
        api.log.clear()
        assertTrue(s.play("ARC", mix).isSuccess)
        assertEquals(
            listOf("clearQueue 192.168.1.39", "addToQueue 192.168.1.39 ${mix.uri}", "setUri 192.168.1.39 x-rincon-queue:ARC#0", "play 192.168.1.39"),
            api.log,
        )
    }

    @Test
    fun `play pause, volumes and grouping go to the right speaker`() = runTest {
        val api = FakeApi()
        val s = service(api, FakeSettings(), discovery = { setOf("192.168.1.39") })
        s.start(backgroundScope); runCurrent()
        api.log.clear()
        s.playPause("ARC")
        assertEquals("pause 192.168.1.39", api.log.last())
        assertFalse(s.state.value.group("ARC")!!.playback.playing) // sofort angezeigt
        s.setGroupVolume("ARC", 40)
        assertEquals("setVolume 192.168.1.39 40", api.log.last()) // Einzelraum: normale Lautstärke
        s.join("BAD", "ARC")
        assertTrue(api.log.contains("join 192.168.1.111 ARC"))
        val g = s.state.value.group("ARC")!!
        assertEquals(listOf("ARC", "BAD"), g.members.map { it.uuid })
        s.setGroupVolume("ARC", 25)
        assertEquals("setGroupVolume 192.168.1.39 25", api.log.last())
        s.setMemberVolume("BAD", 12)
        assertEquals("setVolume 192.168.1.111 12", api.log.last())
        s.leave("BAD")
        assertTrue(api.log.contains("leave 192.168.1.111"))
        assertEquals(2, s.state.value.groups.size)
    }

    @Test
    fun `nothing found, then found after rescan`() = runTest {
        val api = FakeApi()
        var hosts = emptySet<String>()
        val s = service(api, FakeSettings(), discovery = { hosts })
        s.start(backgroundScope); runCurrent()
        assertEquals(SonosLink.NOT_FOUND, s.state.value.link)
        assertFalse(s.state.value.active)
        hosts = setOf("192.168.1.111")
        s.rescan(); runCurrent()
        assertTrue(s.state.value.active)
    }

    @Test
    fun `switching off clears the state`() = runTest {
        val api = FakeApi()
        val settings = FakeSettings()
        val s = service(api, settings, discovery = { setOf("192.168.1.39") })
        s.start(backgroundScope); runCurrent()
        s.setEnabled(false); runCurrent()
        assertFalse(s.state.value.active)
        assertEquals(SonosLink.OFF, s.state.value.link)
        s.setEnabled(true); runCurrent(); advanceTimeBy(10); runCurrent()
        assertTrue(s.state.value.active)
    }

    private fun TestScope.service(api: SonosApi, settings: SonosSettings, discovery: SonosDiscovery) = SonosService(
        settings, api, discovery, EventLog(), XmlStrings("de"), io = StandardTestDispatcher(testScheduler),
        clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC),
    )

    private class FakeSettings : SonosSettings {
        private val _enabled = MutableStateFlow(true)
        override val enabled: StateFlow<Boolean> = _enabled
        override fun setEnabled(on: Boolean) { _enabled.value = on }
        override var knownHosts: Set<String> = emptySet()
        override var manualHosts: Set<String> = emptySet()
        override val onlineArt = MutableStateFlow(false)
        override fun setOnlineArt(on: Boolean) { onlineArt.value = on }
        override fun clearAll() { knownHosts = emptySet(); manualHosts = emptySet(); _enabled.value = true }
    }

    private class FakeTransport : SonosTransport {
        data class Call(val host: String, val action: String, val envelope: String)
        val calls = mutableListOf<Call>()
        val responses = mutableMapOf<String, String>()
        override fun soap(host: String, path: String, serviceType: String, action: String, envelope: String): String {
            calls += Call(host, action, envelope)
            return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"$serviceType\">${responses[action].orEmpty()}</u:${action}Response></s:Body></s:Envelope>"
        }
        override fun getBytes(host: String, path: String, maxBytes: Int): ByteArray? = null
    }

    /** Zwei Räume: Wohnzimmer (ARC, .39) spielt, Badezimmer (BAD, .111) steht. */
    private class FakeApi : SonosApi {
        val log = mutableListOf<String>()
        private val hosts = mapOf("ARC" to "192.168.1.39", "BAD" to "192.168.1.111")
        private val names = mapOf("ARC" to "Wohnzimmer", "BAD" to "Badezimmer")
        /** Mitglied → Koordinator */
        private val coordinatorOf = mutableMapOf("ARC" to "ARC", "BAD" to "BAD")
        private val playing = mutableMapOf("ARC" to true, "BAD" to false)
        private val volumes = mutableMapOf("ARC" to 30, "BAD" to 20)
        private fun uuid(host: String) = hosts.entries.first { it.value == host }.key

        override fun zoneGroups(host: String): List<ZoneGroupInfo> {
            if (host !in hosts.values) throw SonosException(SonosException.Kind.NETWORK)
            return coordinatorOf.values.distinct().map { c ->
                ZoneGroupInfo(c, coordinatorOf.filterValues { it == c }.keys.sortedBy { it != c }.map { ZoneMember(it, names[it]!!, hosts[it]!!) })
            }
        }
        override fun transportState(host: String) = if (playing[uuid(host)] == true) TransportState.PLAYING else TransportState.STOPPED
        override fun positionInfo(host: String) =
            if (uuid(host) == "ARC") PositionInfo("x-sonos-http:song", DidlEntry("1", false, "Song", creator = "Artist"), 42, 200) else PositionInfo(null, null, null, null)
        override fun mediaInfo(host: String) = MediaInfo(if (uuid(host) == "ARC") "x-rincon-queue:ARC#0" else null, null)
        override fun transportActions(host: String) = setOf("play", "pause", "next", "previous", "seek")
        override fun playMode(host: String) = "NORMAL"
        override fun groupVolume(host: String) = 25
        override fun groupMute(host: String) = false
        override fun volume(host: String) = volumes[uuid(host)]!!
        override fun mute(host: String) = false
        override fun browse(host: String, objectId: String, count: Int): List<DidlEntry> = when (objectId) {
            "FV:2" -> listOf(
                DidlEntry("FV:2/1", false, "Radio", res = "x-sonosapi-radio:1?sid=303", resMd = "<DIDL-Lite/>"),
                DidlEntry("FV:2/2", false, "Mix", res = "x-rincon-cpcontainer:mix?sid=284", resMd = "<DIDL-Lite/>"),
            )
            else -> emptyList()
        }

        override fun play(host: String) { log += "play $host"; playing[uuid(host)] = true }
        override fun pause(host: String) { log += "pause $host"; playing[uuid(host)] = false }
        override fun stop(host: String) { log += "stop $host"; playing[uuid(host)] = false }
        override fun next(host: String) { log += "next $host" }
        override fun previous(host: String) { log += "previous $host" }
        override fun seek(host: String, seconds: Int) { log += "seek $host $seconds" }
        override fun setPlayMode(host: String, mode: String) { log += "playMode $host $mode" }
        override fun setGroupVolume(host: String, volume: Int) { log += "setGroupVolume $host $volume" }
        override fun setGroupMute(host: String, muted: Boolean) { log += "setGroupMute $host $muted" }
        override fun setVolume(host: String, volume: Int) { log += "setVolume $host $volume"; volumes[uuid(host)] = volume }
        override fun setMute(host: String, muted: Boolean) { log += "setMute $host $muted" }
        override fun join(memberHost: String, coordinatorUuid: String) { log += "join $memberHost $coordinatorUuid"; coordinatorOf[uuid(memberHost)] = coordinatorUuid }
        override fun leave(host: String) { log += "leave $host"; coordinatorOf[uuid(host)] = uuid(host) }
        override fun setUri(host: String, uri: String, metadata: String) { log += "setUri $host $uri" }
        override fun clearQueue(host: String) { log += "clearQueue $host" }
        override fun addToQueue(host: String, uri: String, metadata: String) { log += "addToQueue $host $uri" }
    }
}
