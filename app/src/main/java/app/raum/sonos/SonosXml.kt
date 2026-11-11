package app.raum.sonos

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Eintrag einer DIDL-Lite-Liste (Favoriten, Wiedergabelisten, Titel-Metadaten). */
data class DidlEntry(
    val id: String,
    val isContainer: Boolean,
    val title: String,
    val upnpClass: String? = null,
    val creator: String? = null,
    val album: String? = null,
    val artUri: String? = null,
    val res: String? = null,
    /** Bei Favoriten: die eigentlichen Abspiel-Metadaten (r:resMD) */
    val resMd: String? = null,
    val description: String? = null,
    /** Bei Radio: „Interpret - Titel“ des laufenden Stücks */
    val streamContent: String? = null,
    val radioShow: String? = null,
)

data class ZoneMember(val uuid: String, val name: String, val host: String)
data class ZoneGroupInfo(val coordinatorUuid: String, val members: List<ZoneMember>)

/** Zerlegt die XML-Antworten der Lautsprecher (DOM, ohne DTDs und externe Entitäten). */
object SonosXml {

    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    fun parse(xml: String): Document? = runCatching {
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    /** Text des ersten Elements mit diesem lokalen Namen (Namensraum egal). */
    fun value(doc: Document?, localName: String): String? {
        doc ?: return null
        val list = doc.getElementsByTagNameNS("*", localName)
        return if (list.length > 0) list.item(0).textContent else null
    }

    /** Wert eines SOAP-Ausgabearguments. */
    fun soapValue(response: String, name: String): String? = value(parse(response), name)

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /** „0:03:25“ → 205; „NOT_IMPLEMENTED“ und leer → null. */
    fun seconds(hms: String?): Int? {
        val parts = hms?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val (h, m, s) = parts.map { it.substringBefore('.').toIntOrNull() ?: return null }
        return h * 3600 + m * 60 + s
    }

    fun hms(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    fun transportState(s: String?): TransportState = when (s?.trim()) {
        "PLAYING" -> TransportState.PLAYING
        "PAUSED_PLAYBACK" -> TransportState.PAUSED
        "STOPPED" -> TransportState.STOPPED
        "TRANSITIONING" -> TransportState.TRANSITIONING
        else -> TransportState.UNKNOWN
    }

    /** Sonos-Wiedergabemodus → (Zufall, Wiederholen). */
    fun playMode(s: String?): Pair<Boolean, RepeatMode> = when (s?.trim()) {
        "SHUFFLE" -> true to RepeatMode.ALL
        "SHUFFLE_NOREPEAT" -> true to RepeatMode.OFF
        "SHUFFLE_REPEAT_ONE" -> true to RepeatMode.ONE
        "REPEAT_ALL" -> false to RepeatMode.ALL
        "REPEAT_ONE" -> false to RepeatMode.ONE
        else -> false to RepeatMode.OFF
    }

    fun playModeName(shuffle: Boolean, repeat: RepeatMode): String = when {
        shuffle && repeat == RepeatMode.ALL -> "SHUFFLE"
        shuffle && repeat == RepeatMode.ONE -> "SHUFFLE_REPEAT_ONE"
        shuffle -> "SHUFFLE_NOREPEAT"
        repeat == RepeatMode.ALL -> "REPEAT_ALL"
        repeat == RepeatMode.ONE -> "REPEAT_ONE"
        else -> "NORMAL"
    }

    fun source(uri: String?): PlaySource = when {
        uri.isNullOrBlank() -> PlaySource.NONE
        uri.startsWith("x-rincon-queue:") -> PlaySource.QUEUE
        uri.startsWith("x-sonos-htastream:") -> PlaySource.TV
        uri.startsWith("x-rincon-stream:") -> PlaySource.LINE_IN
        SonosFavorite.STREAM_PREFIXES.any { uri.startsWith(it) } -> PlaySource.STREAM
        else -> PlaySource.OTHER
    }

    /** DIDL-Lite (bereits entschlüsselt) → Einträge. Leere Eingabe und „NOT_IMPLEMENTED“ → leere Liste. */
    fun didl(xml: String?): List<DidlEntry> {
        if (xml.isNullOrBlank() || !xml.trimStart().startsWith("<")) return emptyList()
        val doc = parse(xml) ?: return emptyList()
        val root = doc.documentElement ?: return emptyList()
        return root.childNodes.elements().mapNotNull { e ->
            val container = when (e.localName ?: e.nodeName) { "item" -> false; "container" -> true; else -> return@mapNotNull null }
            DidlEntry(
                id = e.getAttribute("id"),
                isContainer = container,
                title = e.child("title")?.textContent?.trim().orEmpty(),
                upnpClass = e.child("class")?.textContent?.trim(),
                creator = e.child("creator")?.textContent?.trim()?.ifBlank { null },
                album = e.child("album")?.textContent?.trim()?.ifBlank { null },
                artUri = e.child("albumArtURI")?.textContent?.trim()?.ifBlank { null },
                res = e.child("res")?.textContent?.trim()?.ifBlank { null },
                resMd = e.child("resMD")?.textContent?.trim()?.ifBlank { null },
                description = e.child("description")?.textContent?.trim()?.ifBlank { null },
                streamContent = e.child("streamContent")?.textContent?.trim()?.ifBlank { null },
                radioShow = e.child("radioShowMd")?.textContent?.trim()?.ifBlank { null },
            )
        }
    }

    /** Antwort von GetZoneGroupState (bzw. das Attribut ZoneGroupState) → Gruppen; unsichtbare Mitglieder (Stereo-Partner, Sub) fallen weg. */
    fun zoneGroups(zoneGroupStateXml: String?): List<ZoneGroupInfo> {
        if (zoneGroupStateXml.isNullOrBlank()) return emptyList()
        val doc = parse(zoneGroupStateXml) ?: return emptyList()
        val groups = doc.getElementsByTagNameNS("*", "ZoneGroup")
        return (0 until groups.length).mapNotNull { i ->
            val g = groups.item(i) as Element
            val members = g.childNodes.elements().filter { (it.localName ?: it.nodeName) == "ZoneGroupMember" }.mapNotNull { m ->
                if (m.getAttribute("Invisible") == "1" || m.getAttribute("IsZoneBridge") == "1") return@mapNotNull null
                val host = hostOf(m.getAttribute("Location")) ?: return@mapNotNull null
                ZoneMember(m.getAttribute("UUID"), m.getAttribute("ZoneName"), host)
            }
            val coordinator = g.getAttribute("Coordinator")
            if (members.isEmpty() || coordinator.isBlank()) null else ZoneGroupInfo(coordinator, members)
        }
    }

    /** „http://192.168.1.39:1400/xml/device_description.xml“ → „192.168.1.39“. */
    fun hostOf(location: String?): String? {
        val m = Regex("^https?://(\\[[^\\]]+]|[^:/]+)").find(location?.trim() ?: return null) ?: return null
        return m.groupValues[1].removePrefix("[").removeSuffix("]").ifBlank { null }
    }

    /** Relative Cover-Adressen („/getaa?…“) liegen auf dem Lautsprecher. */
    fun absoluteArt(art: String?, host: String): String? = when {
        art.isNullOrBlank() -> null
        art.startsWith("http://") || art.startsWith("https://") -> art
        art.startsWith("/") -> "http://${if (host.contains(':')) "[$host]" else host}:${SocketSonosTransport.PORT}$art"
        else -> null
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.child(localName: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName.substringAfter(':')) == localName) return n as Element
        }
        return null
    }
}
