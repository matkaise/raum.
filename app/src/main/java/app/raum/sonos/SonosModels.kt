package app.raum.sonos

import java.time.Instant

/** Ein Lautsprecher (bzw. Raum – ein Stereopaar oder Heimkino mit Sub zählt als einer). */
data class SonosPlayer(val uuid: String, val name: String, val host: String)

enum class TransportState { PLAYING, PAUSED, STOPPED, TRANSITIONING, UNKNOWN }

/** Woher die Gruppe gerade spielt – bestimmt, welche Knöpfe sinnvoll sind. */
enum class PlaySource { NONE, QUEUE, STREAM, TV, LINE_IN, OTHER }

enum class RepeatMode { OFF, ALL, ONE }

data class SonosTrack(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** Absolute Adresse (relative Adressen des Lautsprechers sind schon aufgelöst). */
    val artUrl: String? = null,
)

data class Playback(
    val state: TransportState = TransportState.UNKNOWN,
    val source: PlaySource = PlaySource.NONE,
    val track: SonosTrack? = null,
    /** Name des Senders bzw. der Wiedergabeliste bei Streams */
    val stationName: String? = null,
    val positionS: Int? = null,
    val durationS: Int? = null,
    /** Zeitpunkt, zu dem [positionS] galt – die Oberfläche rechnet beim Spielen weiter. */
    val positionAt: Instant? = null,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val canPause: Boolean = true,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    val playing: Boolean get() = state == TransportState.PLAYING || state == TransportState.TRANSITIONING
    /** Etwas geladen, das sich fortsetzen lässt */
    val hasContent: Boolean get() = track != null || stationName != null || source == PlaySource.TV || source == PlaySource.LINE_IN
}

data class MemberVolume(val volume: Int, val muted: Boolean)

/** Gruppe = Koordinator plus Mitglieder; ein einzelner Raum ist eine Gruppe mit einem Mitglied. */
data class SonosGroup(
    val coordinator: SonosPlayer,
    val members: List<SonosPlayer>,
    val playback: Playback = Playback(),
    val volume: Int? = null,
    val muted: Boolean = false,
    val memberVolumes: Map<String, MemberVolume> = emptyMap(),
) {
    val id: String get() = coordinator.uuid
    val name: String get() = members.sortedBy { it.uuid != coordinator.uuid }.joinToString(" + ") { it.name }
}

enum class FavoriteKind { FAVORITE, PLAYLIST }

data class SonosFavorite(
    val id: String,
    val title: String,
    val uri: String,
    /** DIDL-Metadaten, wie Sonos sie zum Abspielen erwartet */
    val metadata: String,
    /** Cover-Kandidaten in Reihenfolge: lokal über den Lautsprecher, dann (nur wenn erlaubt) aus dem Internet. */
    val artUrls: List<String> = emptyList(),
    val subtitle: String? = null,
    val kind: FavoriteKind = FavoriteKind.FAVORITE,
) {
    /** Radio und Streams laufen direkt; Titel, Alben und Listen über die Warteschlange. */
    val isStream: Boolean get() = STREAM_PREFIXES.any { uri.startsWith(it) }

    companion object {
        val STREAM_PREFIXES = listOf(
            "x-sonosapi-stream:", "x-sonosapi-radio:", "x-sonosapi-hls:", "x-rincon-mp3radio:", "pndrradio:", "aac:", "hls-radio:",
        )
    }
}

enum class SonosLink { OFF, SEARCHING, CONNECTED, NOT_FOUND, ERROR }

data class SonosState(
    val enabled: Boolean = true,
    val link: SonosLink = SonosLink.OFF,
    val players: List<SonosPlayer> = emptyList(),
    val groups: List<SonosGroup> = emptyList(),
    val favorites: List<SonosFavorite> = emptyList(),
    val playlists: List<SonosFavorite> = emptyList(),
    val error: String? = null,
    val updatedAt: Instant? = null,
) {
    val active: Boolean get() = enabled && players.isNotEmpty()
    fun group(id: String?) = groups.firstOrNull { it.id == id }
    fun groupOf(playerUuid: String) = groups.firstOrNull { g -> g.members.any { it.uuid == playerUuid } }
}
