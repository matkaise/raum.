package app.raum.sonos

/** Was raum. für die Wiedergabe eines Koordinators liest. */
data class PositionInfo(val trackUri: String?, val metadata: DidlEntry?, val positionS: Int?, val durationS: Int?)
data class MediaInfo(val currentUri: String?, val metadata: DidlEntry?, val tracks: Int? = null)

/**
 * Lokale UPnP-Schnittstelle der Sonos-Lautsprecher (Port 1400). Sonos dokumentiert sie nicht offiziell, sie ist aber
 * seit Jahren stabil (Home Assistant/SoCo nutzen dieselben Aufrufe). Alle Aufrufe blockieren – nur aus IO-Threads.
 */
interface SonosApi {
    fun zoneGroups(host: String): List<ZoneGroupInfo>
    fun transportState(host: String): TransportState
    fun positionInfo(host: String): PositionInfo
    fun mediaInfo(host: String): MediaInfo
    fun transportActions(host: String): Set<String>
    fun playMode(host: String): String?
    fun groupVolume(host: String): Int
    fun groupMute(host: String): Boolean
    fun volume(host: String): Int
    fun mute(host: String): Boolean
    fun browse(host: String, objectId: String, count: Int = 100): List<DidlEntry>

    fun play(host: String)
    fun pause(host: String)
    fun stop(host: String)
    fun next(host: String)
    fun previous(host: String)
    fun seek(host: String, seconds: Int)
    fun setPlayMode(host: String, mode: String)
    fun setGroupVolume(host: String, volume: Int)
    fun setGroupMute(host: String, muted: Boolean)
    fun setVolume(host: String, volume: Int)
    fun setMute(host: String, muted: Boolean)
    /** Mitglied tritt der Gruppe des Koordinators bei. */
    fun join(memberHost: String, coordinatorUuid: String)
    /** Lautsprecher verlässt seine Gruppe und spielt allein weiter. */
    fun leave(host: String)
    fun setUri(host: String, uri: String, metadata: String)
    fun clearQueue(host: String)
    fun addToQueue(host: String, uri: String, metadata: String)
}

class UpnpSonosApi(private val transport: SonosTransport) : SonosApi {

    private enum class Svc(val path: String, val type: String) {
        AV("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
        RENDER("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
        GROUP_RENDER("/MediaRenderer/GroupRenderingControl/Control", "urn:schemas-upnp-org:service:GroupRenderingControl:1"),
        TOPOLOGY("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
        CONTENT("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1"),
    }

    private fun call(host: String, svc: Svc, action: String, vararg args: Pair<String, Any>): String {
        val body = args.joinToString("") { (k, v) -> "<$k>${SonosXml.escape(v.toString())}</$k>" }
        val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${svc.type}">$body</u:$action></s:Body></s:Envelope>"""
        return transport.soap(host, svc.path, svc.type, action, envelope)
    }

    private val instance = "InstanceID" to 0
    private val master = "Channel" to "Master"

    override fun zoneGroups(host: String): List<ZoneGroupInfo> =
        SonosXml.zoneGroups(SonosXml.soapValue(call(host, Svc.TOPOLOGY, "GetZoneGroupState"), "ZoneGroupState"))

    override fun transportState(host: String) =
        SonosXml.transportState(SonosXml.soapValue(call(host, Svc.AV, "GetTransportInfo", instance), "CurrentTransportState"))

    override fun positionInfo(host: String): PositionInfo {
        val doc = SonosXml.parse(call(host, Svc.AV, "GetPositionInfo", instance))
        return PositionInfo(
            trackUri = SonosXml.value(doc, "TrackURI")?.ifBlank { null },
            metadata = SonosXml.didl(SonosXml.value(doc, "TrackMetaData")).firstOrNull(),
            positionS = SonosXml.seconds(SonosXml.value(doc, "RelTime")),
            durationS = SonosXml.seconds(SonosXml.value(doc, "TrackDuration"))?.takeIf { it > 0 },
        )
    }

    override fun mediaInfo(host: String): MediaInfo {
        val doc = SonosXml.parse(call(host, Svc.AV, "GetMediaInfo", instance))
        return MediaInfo(
            SonosXml.value(doc, "CurrentURI")?.ifBlank { null }, SonosXml.didl(SonosXml.value(doc, "CurrentURIMetaData")).firstOrNull(),
            SonosXml.value(doc, "NrTracks")?.trim()?.toIntOrNull(),
        )
    }

    override fun transportActions(host: String): Set<String> =
        SonosXml.soapValue(call(host, Svc.AV, "GetCurrentTransportActions", instance), "Actions")
            ?.split(',')?.map { it.trim().removePrefix("X_DLNA_").lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    override fun playMode(host: String): String? = SonosXml.soapValue(call(host, Svc.AV, "GetTransportSettings", instance), "PlayMode")

    override fun groupVolume(host: String) =
        SonosXml.soapValue(call(host, Svc.GROUP_RENDER, "GetGroupVolume", instance), "CurrentVolume")?.trim()?.toIntOrNull() ?: 0

    override fun groupMute(host: String) =
        SonosXml.soapValue(call(host, Svc.GROUP_RENDER, "GetGroupMute", instance), "CurrentMute")?.trim() == "1"

    override fun volume(host: String) =
        SonosXml.soapValue(call(host, Svc.RENDER, "GetVolume", instance, master), "CurrentVolume")?.trim()?.toIntOrNull() ?: 0

    override fun mute(host: String) =
        SonosXml.soapValue(call(host, Svc.RENDER, "GetMute", instance, master), "CurrentMute")?.trim() == "1"

    override fun browse(host: String, objectId: String, count: Int): List<DidlEntry> {
        val r = call(
            host, Svc.CONTENT, "Browse",
            "ObjectID" to objectId, "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
            "StartingIndex" to 0, "RequestedCount" to count, "SortCriteria" to "",
        )
        return SonosXml.didl(SonosXml.soapValue(r, "Result"))
    }

    override fun play(host: String) { call(host, Svc.AV, "Play", instance, "Speed" to 1) }
    override fun pause(host: String) { call(host, Svc.AV, "Pause", instance) }
    override fun stop(host: String) { call(host, Svc.AV, "Stop", instance) }
    override fun next(host: String) { call(host, Svc.AV, "Next", instance) }
    override fun previous(host: String) { call(host, Svc.AV, "Previous", instance) }
    override fun seek(host: String, seconds: Int) { call(host, Svc.AV, "Seek", instance, "Unit" to "REL_TIME", "Target" to SonosXml.hms(seconds)) }
    override fun setPlayMode(host: String, mode: String) { call(host, Svc.AV, "SetPlayMode", instance, "NewPlayMode" to mode) }

    override fun setGroupVolume(host: String, volume: Int) {
        // Ohne Schnappschuss verteilt Sonos die Änderung nach einem veralteten Verhältnis
        call(host, Svc.GROUP_RENDER, "SnapshotGroupVolume", instance)
        call(host, Svc.GROUP_RENDER, "SetGroupVolume", instance, "DesiredVolume" to volume.coerceIn(0, 100))
    }

    override fun setGroupMute(host: String, muted: Boolean) { call(host, Svc.GROUP_RENDER, "SetGroupMute", instance, "DesiredMute" to if (muted) 1 else 0) }
    override fun setVolume(host: String, volume: Int) { call(host, Svc.RENDER, "SetVolume", instance, master, "DesiredVolume" to volume.coerceIn(0, 100)) }
    override fun setMute(host: String, muted: Boolean) { call(host, Svc.RENDER, "SetMute", instance, master, "DesiredMute" to if (muted) 1 else 0) }

    override fun join(memberHost: String, coordinatorUuid: String) = setUri(memberHost, "x-rincon:$coordinatorUuid", "")
    override fun leave(host: String) { call(host, Svc.AV, "BecomeCoordinatorOfStandaloneGroup", instance) }

    override fun setUri(host: String, uri: String, metadata: String) {
        call(host, Svc.AV, "SetAVTransportURI", instance, "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    }

    override fun clearQueue(host: String) { call(host, Svc.AV, "RemoveAllTracksFromQueue", instance) }

    override fun addToQueue(host: String, uri: String, metadata: String) {
        call(
            host, Svc.AV, "AddURIToQueue", instance,
            "EnqueuedURI" to uri, "EnqueuedURIMetaData" to metadata, "DesiredFirstTrackNumberEnqueued" to 0, "EnqueueAsNext" to 0,
        )
    }
}
