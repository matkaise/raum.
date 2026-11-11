package app.raum.sonos

import android.content.Context
import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant

interface SonosSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(on: Boolean)
    /** Zuletzt bekannte Adressen – damit raum. nach dem Start nicht erst suchen muss. */
    var knownHosts: Set<String>
    /** Von Hand eingetragene Adressen (z. B. Lautsprecher in einem anderen Netzsegment). */
    var manualHosts: Set<String>
    /** Cover der Favoriten von den Servern der Musikdienste laden (Internet) – ab Werk aus. */
    val onlineArt: StateFlow<Boolean>
    fun setOnlineArt(on: Boolean)
    fun clearAll()
}

class SonosPrefs(context: Context) : SonosSettings {
    private val prefs = context.getSharedPreferences("raum_sonos", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override fun setEnabled(on: Boolean) { prefs.edit().putBoolean("enabled", on).apply(); _enabled.value = on }
    override var knownHosts: Set<String>
        get() = prefs.getStringSet("known_hosts", null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet("known_hosts", v).apply() }
    override var manualHosts: Set<String>
        get() = prefs.getStringSet("manual_hosts", null)?.toSet().orEmpty()
        set(v) { prefs.edit().putStringSet("manual_hosts", v).apply() }
    private val _onlineArt = MutableStateFlow(prefs.getBoolean("online_art", false))
    override val onlineArt: StateFlow<Boolean> = _onlineArt.asStateFlow()
    override fun setOnlineArt(on: Boolean) { prefs.edit().putBoolean("online_art", on).apply(); _onlineArt.value = on }
    override fun clearAll() { prefs.edit().clear().apply(); _enabled.value = true; _onlineArt.value = false }
}

/**
 * Sonos über die lokale UPnP-Schnittstelle – ohne Cloud und ohne Konto. raum. liest die Gruppen, was läuft und die
 * Lautstärken (alle 2 s, solange eine Musik-Ansicht offen ist, sonst alle 15 s) und steuert Wiedergabe, Lautstärke,
 * Gruppen und Sonos-Favoriten. Musikdienste wie YouTube Music spielt der Lautsprecher selbst über das in Sonos
 * verknüpfte Konto; raum. reicht nur Favoriten und Wiedergabelisten weiter.
 */
class SonosService(
    private val settings: SonosSettings,
    private val api: SonosApi,
    private val discovery: SonosDiscovery,
    private val log: EventLog,
    private val strings: Strings,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _state = MutableStateFlow(SonosState(enabled = settings.enabled.value))
    val state: StateFlow<SonosState> = _state.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Offene Ansichten → gewünschter Abfrageabstand */
    private val watchers = java.util.concurrent.ConcurrentHashMap<Any, Long>()
    private val lock = Mutex()

    /** Von raum. gestarteter Favorit je Gruppe – gilt, solange dieselbe Warteschlange bzw. derselbe Sender läuft. */
    private class Started(val title: String, val streamUri: String?, var tracks: Int? = null)
    private val started = java.util.concurrent.ConcurrentHashMap<String, Started>()
    private var scope: CoroutineScope? = null

    @Volatile private var topologyDue = true
    @Volatile private var favoritesDue = true
    private var lastTopology: Instant = Instant.EPOCH
    private var lastFavorites: Instant = Instant.EPOCH
    private var lastDiscovery: Instant = Instant.EPOCH

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            settings.enabled.collectLatest { on ->
                if (!on) {
                    _state.value = SonosState(enabled = false, link = SonosLink.OFF)
                    return@collectLatest
                }
                _state.update { it.copy(enabled = true, link = if (it.players.isEmpty()) SonosLink.SEARCHING else it.link) }
                topologyDue = true; favoritesDue = true
                while (true) {
                    tick()
                    val wait = when {
                        _state.value.players.isEmpty() -> NOT_FOUND_POLL_MS
                        else -> watchers.values.minOrNull() ?: IDLE_POLL_MS
                    }
                    withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        }
    }

    /**
     * Eine Ansicht zeigt Sonos – dann häufiger abfragen (Musikseite alle 2 s, Übersicht alle 5 s). Rückgabe gibt die
     * Anmeldung wieder frei.
     */
    fun watch(intervalMs: Long = WATCH_POLL_MS): () -> Unit {
        val token = Any()
        watchers[token] = intervalMs
        if (clock.instant().isAfter(lastFavorites.plusSeconds(60))) favoritesDue = true
        wake.trySend(Unit)
        return { watchers.remove(token) }
    }

    /** Neu suchen (Einstellungen). */
    fun rescan() {
        lastDiscovery = Instant.EPOCH
        topologyDue = true; favoritesDue = true
        _state.update { if (it.players.isEmpty()) it.copy(link = SonosLink.SEARCHING) else it }
        wake.trySend(Unit)
    }

    private suspend fun tick() = lock.withLock {
        withContext(io) {
            val now = clock.instant()
            if (topologyDue || now.isAfter(lastTopology.plusMillis(TOPOLOGY_MS))) refreshTopology()
            if (_state.value.players.isEmpty()) return@withContext
            if (favoritesDue || now.isAfter(lastFavorites.plusMillis(FAVORITES_MS))) refreshFavorites()
            refreshPlayback()
        }
    }

    // --- Lesen -------------------------------------------------------------------------------------------

    private fun refreshTopology() {
        val now = clock.instant()
        val candidates = LinkedHashSet<String>().apply {
            _state.value.players.forEach { add(it.host) }
            addAll(settings.knownHosts); addAll(settings.manualHosts)
        }
        var groups = firstTopology(candidates)
        if (groups == null && now.isAfter(lastDiscovery.plusMillis(DISCOVERY_MS))) {
            lastDiscovery = now
            groups = firstTopology(discovery.discover(DISCOVERY_TIMEOUT_MS) - candidates)
        }
        lastTopology = now
        topologyDue = false
        if (groups == null) {
            _state.update {
                if (it.players.isEmpty()) it.copy(link = SonosLink.NOT_FOUND)
                else it.copy(link = SonosLink.ERROR, error = "unreachable")
            }
            return
        }
        val before = _state.value
        val players = groups.flatMap { g -> g.members.map { SonosPlayer(it.uuid, it.name, it.host) } }.distinctBy { it.uuid }.sortedBy { it.name.lowercase() }
        val byUuid = players.associateBy { it.uuid }
        val newGroups = groups.mapNotNull { g ->
            val coordinator = byUuid[g.coordinatorUuid] ?: return@mapNotNull null
            val members = g.members.mapNotNull { byUuid[it.uuid] }
            val old = before.group(coordinator.uuid)
            SonosGroup(coordinator, members, old?.playback ?: Playback(), old?.volume, old?.muted ?: false, old?.memberVolumes.orEmpty())
        }.sortedBy { it.coordinator.name.lowercase() }
        if (players.map { it.uuid }.toSet() != before.players.map { it.uuid }.toSet()) {
            log.info(LogCategory.SYSTEM, strings.get(R.string.sonos_log_found, players.size, players.joinToString(", ") { it.name }))
        }
        settings.knownHosts = players.map { it.host }.toSet()
        _state.update { it.copy(players = players, groups = newGroups, link = SonosLink.CONNECTED, error = null) }
    }

    private fun firstTopology(hosts: Collection<String>): List<ZoneGroupInfo>? {
        for (h in hosts) {
            val groups = runCatching { api.zoneGroups(h) }.getOrNull()
            if (!groups.isNullOrEmpty()) return groups
        }
        return null
    }

    private fun refreshFavorites() {
        val host = _state.value.groups.firstOrNull()?.coordinator?.host ?: return
        val favorites = runCatching { api.browse(host, "FV:2") }.getOrNull()
        val playlists = runCatching { api.browse(host, "SQ:") }.getOrNull()
        lastFavorites = clock.instant()
        favoritesDue = false
        _state.update { st ->
            st.copy(
                favorites = favorites?.let { favoritesFrom(it, host) } ?: st.favorites,
                playlists = playlists?.let { playlistsFrom(it, host) } ?: st.playlists,
            )
        }
    }

    private fun refreshPlayback() {
        val st = _state.value
        var failures = 0
        val groups = st.groups.map { g ->
            runCatching { steady(g, readGroup(g)) }.getOrElse { failures++; g }
        }
        if (failures > 0) topologyDue = true // vielleicht hat sich die Gruppierung oder eine Adresse geändert
        _state.update { cur ->
            val merged = cur.groups.map { g -> groups.firstOrNull { it.id == g.id && it.members == g.members } ?: g }
            val link = if (failures == groups.size && groups.isNotEmpty()) SonosLink.ERROR else SonosLink.CONNECTED
            // Unverändert → gleiches Objekt, damit die Oberfläche nicht neu zeichnet
            if (merged == cur.groups && link == cur.link) cur else cur.copy(groups = merged, link = link, updatedAt = clock.instant())
        }
    }

    /**
     * Nur die Uhr ist weitergelaufen (Position wie erwartet)? Dann das alte Objekt behalten – sonst zeichnet die
     * Oberfläche bei jeder Abfrage neu, obwohl sich nichts geändert hat.
     */
    private fun steady(old: SonosGroup, new: SonosGroup): SonosGroup {
        val o = old.playback; val n = new.playback
        if (old.copy(playback = n) != new) return new
        if (o.copy(positionS = n.positionS, positionAt = n.positionAt) != n) return new
        val expected = o.positionS?.let { p ->
            val at = o.positionAt ?: return new
            p + if (o.playing) java.time.Duration.between(at, n.positionAt ?: at).seconds.toInt() else 0
        }
        return if (expected == n.positionS || (expected != null && n.positionS != null && kotlin.math.abs(expected - n.positionS) <= 2)) old else new
    }

    private fun startedFrom(groupId: String, source: PlaySource, media: MediaInfo): String? {
        val s = started[groupId]
        val valid = when {
            s == null -> false
            s.streamUri != null -> media.currentUri == s.streamUri
            source != PlaySource.QUEUE -> false
            s.tracks == null -> { s.tracks = media.tracks; true }
            else -> s.tracks == media.tracks
        }
        if (valid) return s!!.title
        started.remove(groupId)
        // Sender, die als Favorit gespeichert sind, erkennt raum. auch, wenn sie anderswo gestartet wurden
        return if (source == PlaySource.STREAM) _state.value.favorites.firstOrNull { it.uri == media.currentUri }?.title else null
    }

    private fun readGroup(g: SonosGroup): SonosGroup {
        val host = g.coordinator.host
        val transport = api.transportState(host)
        val position = api.positionInfo(host)
        val media = api.mediaInfo(host)
        val actions = runCatching { api.transportActions(host) }.getOrDefault(emptySet())
        val (shuffle, repeat) = SonosXml.playMode(runCatching { api.playMode(host) }.getOrNull())
        val source = SonosXml.source(media.currentUri ?: position.trackUri)
        var track = trackOf(position, source, host)
        // Radio ohne eigenes Cover (oder gestoppt): Cover des passenden Favoriten
        if (source == PlaySource.STREAM && track?.artUrl == null) {
            _state.value.favorites.firstOrNull { it.uri == media.currentUri }?.artUrls?.firstOrNull()?.let { art ->
                track = track?.copy(artUrl = art) ?: SonosTrack("", artUrl = art)
            }
        }
        val playback = Playback(
            service = SonosXml.serviceName(position.trackUri) ?: SonosXml.serviceName(media.currentUri),
            startedFrom = startedFrom(g.id, source, media),
            state = transport,
            source = source,
            track = track,
            stationName = if (source == PlaySource.STREAM) media.metadata?.title?.ifBlank { null } else null,
            positionS = position.positionS,
            durationS = position.durationS,
            positionAt = clock.instant(),
            canNext = "next" in actions,
            canPrevious = "previous" in actions,
            canSeek = "seek" in actions && position.durationS != null && source == PlaySource.QUEUE,
            canPause = actions.isEmpty() || "pause" in actions,
            shuffle = shuffle,
            repeat = repeat,
        )
        val volumes = if (g.members.size == 1) {
            val m = g.members.first()
            mapOf(m.uuid to MemberVolume(api.volume(m.host), api.mute(m.host)))
        } else {
            g.members.associate { m -> m.uuid to runCatching { MemberVolume(api.volume(m.host), api.mute(m.host)) }.getOrElse { g.memberVolumes[m.uuid] ?: MemberVolume(0, false) } }
        }
        val groupVolume = if (g.members.size == 1) volumes.values.first().volume else api.groupVolume(host)
        val groupMuted = if (g.members.size == 1) volumes.values.first().muted else api.groupMute(host)
        return g.copy(playback = playback, volume = groupVolume, muted = groupMuted, memberVolumes = volumes)
    }

    // --- Steuern -----------------------------------------------------------------------------------------

    suspend fun playPause(groupId: String): Result<Unit> = command(groupId) { g ->
        val p = g.playback
        when {
            p.playing && p.canPause -> api.pause(g.coordinator.host)
            p.playing -> api.stop(g.coordinator.host)
            else -> api.play(g.coordinator.host)
        }
        g.copy(playback = p.copy(state = if (p.playing) TransportState.PAUSED else TransportState.PLAYING, positionAt = clock.instant()))
    }

    suspend fun next(groupId: String) = command(groupId) { g -> api.next(g.coordinator.host); g }
    suspend fun previous(groupId: String) = command(groupId) { g -> api.previous(g.coordinator.host); g }

    suspend fun seek(groupId: String, seconds: Int) = command(groupId) { g ->
        api.seek(g.coordinator.host, seconds)
        g.copy(playback = g.playback.copy(positionS = seconds, positionAt = clock.instant()))
    }

    suspend fun setShuffle(groupId: String, on: Boolean) = command(groupId) { g ->
        api.setPlayMode(g.coordinator.host, SonosXml.playModeName(on, g.playback.repeat))
        g.copy(playback = g.playback.copy(shuffle = on))
    }

    suspend fun setRepeat(groupId: String, mode: RepeatMode) = command(groupId) { g ->
        api.setPlayMode(g.coordinator.host, SonosXml.playModeName(g.playback.shuffle, mode))
        g.copy(playback = g.playback.copy(repeat = mode))
    }

    suspend fun setGroupVolume(groupId: String, volume: Int) = command(groupId) { g ->
        val v = volume.coerceIn(0, 100)
        if (g.members.size == 1) api.setVolume(g.coordinator.host, v) else api.setGroupVolume(g.coordinator.host, v)
        g.copy(volume = v)
    }

    suspend fun setGroupMute(groupId: String, muted: Boolean) = command(groupId) { g ->
        if (g.members.size == 1) api.setMute(g.coordinator.host, muted) else api.setGroupMute(g.coordinator.host, muted)
        g.copy(muted = muted)
    }

    suspend fun setMemberVolume(playerUuid: String, volume: Int): Result<Unit> {
        val g = _state.value.groupOf(playerUuid) ?: return notFound()
        val player = g.members.first { it.uuid == playerUuid }
        return command(g.id) { cur ->
            val v = volume.coerceIn(0, 100)
            api.setVolume(player.host, v)
            val old = cur.memberVolumes[playerUuid] ?: MemberVolume(v, false)
            cur.copy(memberVolumes = cur.memberVolumes + (playerUuid to old.copy(volume = v)), volume = if (cur.members.size == 1) v else cur.volume)
        }
    }

    /** Lautsprecher in die Gruppe holen. */
    suspend fun join(playerUuid: String, groupId: String): Result<Unit> {
        val st = _state.value
        val player = st.players.firstOrNull { it.uuid == playerUuid } ?: return notFound()
        val g = st.group(groupId) ?: return notFound()
        return topologyCommand { api.join(player.host, g.coordinator.uuid) }
    }

    /** Lautsprecher aus seiner Gruppe lösen. */
    suspend fun leave(playerUuid: String): Result<Unit> {
        val player = _state.value.players.firstOrNull { it.uuid == playerUuid } ?: return notFound()
        return topologyCommand { api.leave(player.host) }
    }

    /** Favorit oder Sonos-Wiedergabeliste auf der Gruppe abspielen. */
    suspend fun play(groupId: String, item: SonosFavorite) = command(groupId) { g ->
        val host = g.coordinator.host
        if (item.isStream) {
            api.setUri(host, item.uri, item.metadata)
        } else {
            api.clearQueue(host)
            api.addToQueue(host, item.uri, item.metadata)
            api.setUri(host, "x-rincon-queue:${g.coordinator.uuid}#0", "")
        }
        api.play(host)
        started[g.id] = Started(item.title, item.uri.takeIf { item.isStream })
        log.info(LogCategory.DEVICE, strings.get(R.string.sonos_log_play, item.title), g.name)
        g.copy(playback = g.playback.copy(state = TransportState.TRANSITIONING, stationName = item.title.takeIf { item.isStream }, track = SonosTrack(item.title, artUrl = item.artUrls.firstOrNull()), startedFrom = item.title))
    }

    private suspend fun command(groupId: String, action: (SonosGroup) -> SonosGroup): Result<Unit> {
        val g = _state.value.group(groupId) ?: return notFound()
        return try {
            val updated = lock.withLock { withContext(io) { action(g) } }
            _state.update { st -> st.copy(groups = st.groups.map { if (it.id == groupId) updated else it }) }
            scope?.launch { kotlinx.coroutines.delay(CONFIRM_DELAY_MS); wake.trySend(Unit) }
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            topologyDue = true
            wake.trySend(Unit)
            Result.failure(e)
        }
    }

    private suspend fun topologyCommand(action: () -> Unit): Result<Unit> = try {
        lock.withLock { withContext(io) { action(); refreshTopology(); refreshPlayback() } }
        Result.success(Unit)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        topologyDue = true
        wake.trySend(Unit)
        Result.failure(e)
    }

    private fun notFound(): Result<Unit> = Result.failure(SonosException(SonosException.Kind.NETWORK, "group not found"))

    // --- Einstellungen -----------------------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        if (on == settings.enabled.value) return
        settings.setEnabled(on)
        log.info(LogCategory.SYSTEM, strings.get(if (on) R.string.sonos_log_on else R.string.sonos_log_off))
    }

    val manualHosts: Set<String> get() = settings.manualHosts
    val onlineArt: StateFlow<Boolean> get() = settings.onlineArt

    fun setOnlineArt(on: Boolean) {
        settings.setOnlineArt(on)
        log.info(LogCategory.SYSTEM, strings.get(if (on) R.string.sonos_log_online_art_on else R.string.sonos_log_online_art_off))
    }

    fun addManualHost(host: String): Boolean {
        val h = host.trim()
        val ok = runCatching { SocketSonosTransport.resolveLocal(h) }.isSuccess
        if (!ok) return false
        settings.manualHosts = settings.manualHosts + h
        rescan()
        return true
    }

    fun removeManualHost(host: String) {
        settings.manualHosts = settings.manualHosts - host
    }

    /** Werksreset / Übergabe. */
    fun clearAll() {
        settings.clearAll()
        _state.value = SonosState(enabled = settings.enabled.value, link = SonosLink.SEARCHING)
        rescan()
    }

    companion object {
        const val WATCH_POLL_MS = 2_000L
        const val GLANCE_POLL_MS = 5_000L
        const val IDLE_POLL_MS = 15_000L
        const val NOT_FOUND_POLL_MS = 60_000L
        const val TOPOLOGY_MS = 30_000L
        const val FAVORITES_MS = 10 * 60_000L
        const val DISCOVERY_MS = 60_000L
        const val DISCOVERY_TIMEOUT_MS = 3_000
        const val CONFIRM_DELAY_MS = 400L

        internal fun favoritesFrom(entries: List<DidlEntry>, host: String): List<SonosFavorite> = entries.mapNotNull { e ->
            val uri = e.res ?: return@mapNotNull null // Sonos-Radio-Verknüpfungen ohne Adresse lassen sich so nicht starten
            SonosFavorite(
                id = e.id, title = e.title, uri = uri, metadata = e.resMd.orEmpty(),
                artUrls = artCandidates(e.artUri, uri, host), subtitle = e.description, kind = FavoriteKind.FAVORITE,
            )
        }

        internal fun playlistsFrom(entries: List<DidlEntry>, host: String): List<SonosFavorite> = entries.mapNotNull { e ->
            val uri = e.res ?: return@mapNotNull null
            val md = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
                """<item id="${SonosXml.escape(e.id)}" parentID="SQ:" restricted="true"><dc:title>${SonosXml.escape(e.title)}</dc:title>""" +
                """<upnp:class>object.container.playlistContainer</upnp:class><desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">RINCON_AssociatedZPUDN</desc></item></DIDL-Lite>"""
            SonosFavorite(id = e.id, title = e.title, uri = uri, metadata = md, artUrls = artCandidates(e.artUri, uri, host), kind = FavoriteKind.PLAYLIST)
        }

        /**
         * Cover eines Favoriten: liefert der Musikdienst eine Internet-Adresse, zuerst das Cover über den Lautsprecher
         * versuchen („/getaa“, klappt z. B. bei Radio) – die Internet-Adresse nur als zweite Wahl.
         */
        internal fun artCandidates(art: String?, uri: String, host: String): List<String> {
            val local = SonosXml.absoluteArt(art, host)?.takeIf { it.startsWith("http://") }
            if (local != null) return listOf(local)
            val proxy = SonosXml.absoluteArt("/getaa?s=1&u=" + java.net.URLEncoder.encode(uri, "UTF-8"), host)
            return listOfNotNull(proxy, art?.takeIf { it.startsWith("https://") })
        }

        /** Was läuft: bei Radio steckt „Interpret - Titel“ in r:streamContent, sonst Titel/Interpret/Album. */
        internal fun trackOf(p: PositionInfo, source: PlaySource, host: String): SonosTrack? {
            if (source == PlaySource.TV || source == PlaySource.LINE_IN || source == PlaySource.NONE) return null
            val md = p.metadata ?: return null
            val art = SonosXml.absoluteArt(md.artUri, host)
            val stream = md.streamContent?.takeUnless { it.startsWith("ZPSTR_") }
            if (stream != null) {
                val i = stream.indexOf(" - ")
                return if (i > 0) SonosTrack(stream.substring(i + 3).trim(), stream.substring(0, i).trim(), artUrl = art) else SonosTrack(stream, artUrl = art)
            }
            val title = md.title.takeUnless { it.isBlank() || it.startsWith("x-sonos") || it == p.trackUri } ?: return if (art != null) SonosTrack("", artUrl = art) else null
            return SonosTrack(title, md.creator, md.album, art)
        }
    }
}
