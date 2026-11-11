package app.raum.ui.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.sonos.FavoriteKind
import app.raum.sonos.PlaySource
import app.raum.sonos.Playback
import app.raum.sonos.RepeatMode
import app.raum.sonos.SonosFavorite
import app.raum.sonos.SonosGroup
import app.raum.sonos.SonosLink
import app.raum.sonos.SonosPlayer
import app.raum.sonos.SonosService
import app.raum.sonos.SonosState
import app.raum.sonos.TransportState
import app.raum.ui.components.Dial
import app.raum.ui.household.ActionButton
import app.raum.ui.household.rememberNow
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

/**
 * „Musik“ (Entwurf „raum. Musik Redesign“): Räume als Leiste oben rechts, der laufende Titel groß direkt auf dem Himmel
 * – Cover mit farbigem Schein, Titel wie die Uhr der Übersicht –, Lautstärke als Drehring wie bei Licht und Thermostat,
 * Favoriten als Cover-Reihe, „Abspielen in“ für Gruppen. Steht der Raum still, wird die Seite zur Auswahl.
 */
@Composable
fun MusicScreen(
    requestedGroup: String?,
    onRequestHandled: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: MusicViewModel = koinViewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()

    DisposableEffect(vm.service) {
        val release = vm.service.watch(SonosService.WATCH_POLL_MS)
        onDispose { release() }
    }
    LaunchedEffect(requestedGroup) {
        if (requestedGroup != null) {
            // Gruppe des gewünschten Lautsprechers (Koordinator kann inzwischen ein anderer sein)
            (st.group(requestedGroup) ?: st.groupOf(requestedGroup))?.let { vm.select(it.id) }
            onRequestHandled()
        }
    }
    val selected = st.group(selectedId)
        ?: selectedId?.let { id -> st.groupOf(id) } // Koordinator gewechselt
        ?: st.groups.firstOrNull { it.playback.playing }
        ?: st.groups.firstOrNull { it.playback.hasContent }
        ?: st.groups.firstOrNull()
    LaunchedEffect(selected?.id) { if (selected != null && selected.id != selectedId) vm.select(selected.id) }

    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_music), style = SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
                Text(headline(st), style = SkyTextStyle, color = SkyTextSoft, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (st.groups.size > 1 && selected != null) RoomTabs(st, selected, vm::select)
        }
        when {
            selected == null -> EmptyMusic(st, onOpenSettings)
            isIdle(selected.playback) -> IdleBody(st, selected, starting, vm)
            else -> PlayingBody(st, selected, starting, vm)
        }
    }
}

/** Still = nichts geladen oder gestoppt (pausiert zählt als „läuft“: die Steuerung bleibt sichtbar). */
private fun isIdle(p: Playback) = !p.hasContent || p.state == TransportState.STOPPED || p.state == TransportState.UNKNOWN

@Composable
private fun headline(st: SonosState): String {
    val n = st.players.size
    val playing = st.groups.filter { it.playback.playing }
    return listOfNotNull(
        "Sonos",
        if (n > 0) pluralStringResource(R.plurals.sonos_speakers, n, n) else null,
        when {
            playing.size == 1 -> stringResource(R.string.sonos_headline_playing, playing.first().name)
            playing.size > 1 -> pluralStringResource(R.plurals.sonos_headline_playing_many, playing.size, playing.size)
            n > 0 -> stringResource(R.string.sonos_headline_quiet)
            else -> null
        },
    ).joinToString(" · ")
}

@Composable
private fun EmptyMusic(st: SonosState, onOpenSettings: () -> Unit) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 24.dp).glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val searching = st.link == SonosLink.SEARCHING
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (searching) CircularProgressIndicator(Modifier.size(28.dp), color = g.ink, strokeWidth = 3.dp)
            else Icon(Icons.Outlined.SpeakerGroup, null, Modifier.size(28.dp), tint = g.soft)
            Text(stringResource(if (searching) R.string.sonos_searching else R.string.sonos_none_found), color = g.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        if (!searching) Text(stringResource(R.string.sonos_none_found_text), color = g.soft, fontSize = 16.sp)
        ActionButton(stringResource(R.string.sonos_open_settings), Icons.Outlined.Settings, g.chip, g.ink, Modifier.width(320.dp), height = 52.dp, onClick = onOpenSettings)
    }
}

// --- Raumleiste --------------------------------------------------------------------------------------

@Composable
private fun RoomTabs(st: SonosState, selected: SonosGroup, onPick: (String) -> Unit) {
    val desc = stringResource(R.string.sonos_choose_room)
    Row(
        Modifier.semantics { contentDescription = desc }.clip(RoundedCornerShape(30.dp)).background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(30.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        st.groups.forEach { grp ->
            val on = grp.id == selected.id
            val bg by animateColorAsState(if (on) Color.White else Color.Transparent, label = "tab")
            val fg = if (on) Color(0xFF10131F) else Color.White
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg).clickable { onPick(grp.id) }.padding(start = 14.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (grp.members.size > 1) Icons.Outlined.SpeakerGroup else Icons.Outlined.Speaker, null, Modifier.size(20.dp), tint = fg)
                Text(grp.name, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                // Statisch statt animiert: Dauer-Animationen kosten auf dem Panel bei jedem Bild Rechenzeit
                if (grp.playback.playing) Icon(Icons.Outlined.GraphicEq, stringResource(R.string.sonos_state_playing), Modifier.size(18.dp), tint = fg)
            }
        }
    }
}

// --- Es läuft etwas ----------------------------------------------------------------------------------

@Composable
private fun PlayingBody(st: SonosState, group: SonosGroup, starting: String?, vm: MusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(384.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            HeroCover(group)
            NowPlaying(group, vm, Modifier.weight(1f))
            VolumeCard(group, vm, Modifier.width(250.dp).fillMaxHeight().padding(vertical = 14.dp))
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FavoriteShelf(st, group, starting, vm, Modifier.weight(1f).fillMaxHeight())
            if (st.players.size > 1) RoomsCard(st, group, vm, Modifier.width(250.dp).fillMaxHeight())
        }
    }
}

/** Großes Cover mit weichem Schein in seiner Farbe – so färbt die Musik den Himmel ein wenig mit. */
@Composable
private fun HeroCover(group: SonosGroup) {
    val p = group.playback
    val bitmap = rememberCover(listOfNotNull(p.track?.artUrl))
    val glow = remember(bitmap) { glowColor(bitmap) }
    val glowColor by animateColorAsState(glow, label = "glow")
    // Der Schein wird über die Ränder hinaus gezeichnet, ohne Platz in der Zeile zu belegen
    Box(
        Modifier.size(340.dp).drawBehind {
            val r = 280.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.55f), glowColor.copy(alpha = 0.18f), Color.Transparent), center, r), r, center)
        },
        contentAlignment = Alignment.Center,
    ) {
        CoverImage(
            bitmap, 340.dp, RoundedCornerShape(30.dp),
            Modifier.shadow(30.dp, RoundedCornerShape(30.dp), ambientColor = Color(0xFF0A081E), spotColor = Color(0xFF0A081E)),
            icon = sourceIcon(group),
        )
    }
}

@Composable
private fun NowPlaying(group: SonosGroup, vm: MusicViewModel, modifier: Modifier) {
    val p = group.playback
    val now = rememberNow()
    Column(modifier) {
        val status = stringResource(if (p.playing) R.string.sonos_playing_in else R.string.sonos_paused_in, group.name).uppercase()
        Text(status, style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val title = primaryLine(p)
        // Kurze Titel groß wie die Uhr der Übersicht, lange etwas kleiner auf zwei Zeilen
        val long = title.length > 16
        Text(
            title, style = SkyTextStyle, color = SkyText, fontSize = if (long) 40.sp else 54.sp, fontWeight = FontWeight.Light,
            letterSpacing = if (long) (-0.8).sp else (-1.4).sp, lineHeight = if (long) 44.sp else 58.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        artistLine(p)?.let { Text(it, style = SkyTextStyle, color = SkyText.copy(alpha = 0.9f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        sourceLine(p)?.let { line ->
            Row(
                Modifier.padding(top = 16.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp)).padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (p.source == PlaySource.STREAM) Icons.Outlined.Radio else Icons.Outlined.QueueMusic, null, Modifier.size(18.dp), tint = Color.White)
                Text(line, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Box(Modifier.padding(top = 20.dp).height(44.dp).fillMaxWidth()) {
            val duration = p.durationS
            when {
                duration != null && p.source == PlaySource.QUEUE -> {
                    val pos = livePosition(p, now) ?: 0
                    Column {
                        SkyProgress(pos.toFloat() / duration, p.canSeek) { f -> vm.seek(group.id, (f * duration).toInt()) }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(mmss(pos), style = SkyTextStyle, color = SkyTextSoft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(mmss(duration), style = SkyTextStyle, color = SkyTextSoft, fontSize = 13.sp)
                        }
                    }
                }
                p.source == PlaySource.STREAM -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF8A7A)))
                    Text(stringResource(R.string.sonos_live).uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                }
            }
        }

        val transport = p.source != PlaySource.TV && p.source != PlaySource.LINE_IN && p.hasContent
        val queue = p.source == PlaySource.QUEUE
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            SkyButton(Icons.Outlined.Shuffle, stringResource(R.string.sonos_shuffle), 52.dp, active = p.shuffle, enabled = queue) { vm.setShuffle(group.id, !p.shuffle) }
            SkyButton(Icons.Outlined.SkipPrevious, stringResource(R.string.sonos_previous), 64.dp, enabled = transport && p.canPrevious) { vm.previous(group.id) }
            val playIcon = when { !p.playing -> Icons.Outlined.PlayArrow; p.canPause -> Icons.Outlined.Pause; else -> Icons.Outlined.Stop }
            Box(
                Modifier.size(92.dp).shadow(18.dp, CircleShape, ambientColor = Color(0xFF0A081E), spotColor = Color(0xFF0A081E)).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (transport) 1f else 0.5f)).clickable(enabled = transport) { vm.playPause(group.id) },
                contentAlignment = Alignment.Center,
            ) { Icon(playIcon, stringResource(if (p.playing) R.string.sonos_pause else R.string.sonos_play), Modifier.size(46.dp), tint = Color(0xFF10131F)) }
            SkyButton(Icons.Outlined.SkipNext, stringResource(R.string.sonos_next), 64.dp, enabled = transport && p.canNext) { vm.next(group.id) }
            SkyButton(if (p.repeat == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, stringResource(R.string.sonos_repeat), 52.dp, active = p.repeat != RepeatMode.OFF, enabled = queue) {
                vm.setRepeat(group.id, when (p.repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF })
            }
        }
    }
}

/** Runde Glasknöpfe direkt auf dem Himmel (wie Einstellungen/Ruhezustand in der Leiste). */
@Composable
private fun SkyButton(icon: ImageVector, label: String, size: Dp, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = if (active) 0.42f else 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.46f), tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f)) }
}

/** Dünne weiße Fortschrittslinie auf dem Himmel; Tippen oder Ziehen spult (beim Loslassen). */
@Composable
private fun SkyProgress(fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(fraction) }
    val shown = if (dragging) local else fraction.coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(16.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> onSeek((p.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { p -> dragging = true; local = (p.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false; onSeek(local) },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); local = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.width(w * shown).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        Box(Modifier.offset(x = w * shown - 8.dp).size(16.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** Lautstärke als Drehring – dieselbe Form wie Licht und Thermostat in der Gerätekarte. */
@Composable
private fun VolumeCard(group: SonosGroup, vm: MusicViewModel, modifier: Modifier) {
    val g = LocalGlass.current
    val volume = group.volume ?: 0
    Column(
        modifier.glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.sonos_volume).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
        Dial(
            fraction = volume / 100f, accent = g.ink, enabled = true, label = "", value = { "" }, sub = { "" },
            modifier = Modifier.padding(top = 6.dp).size(196.dp), ringWidth = 14.dp,
            centerContent = { f ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((f * 100).toInt().toString(), color = if (group.muted) g.soft else g.ink, fontSize = 52.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, lineHeight = 54.sp)
                    Text(
                        if (group.muted) stringResource(R.string.sonos_muted) else if (group.members.size > 1) stringResource(R.string.sonos_volume_all_rooms) else group.name,
                        color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            onCommit = { vm.setGroupVolume(group.id, (it * 100).toInt()) },
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton(Icons.Outlined.Remove, stringResource(R.string.sonos_volume_down)) { vm.setGroupVolume(group.id, volume - 5) }
            InkButton(if (group.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, stringResource(R.string.sonos_mute), active = group.muted) { vm.setGroupMute(group.id, !group.muted) }
            InkButton(Icons.Outlined.Add, stringResource(R.string.sonos_volume_up)) { vm.setGroupVolume(group.id, volume + 5) }
        }
    }
}

@Composable
private fun InkButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) g.ink else g.chip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(24.dp), tint = if (active) (if (g.dark) Color(0xFF10131F) else Color.White) else g.ink) }
}

// --- Favoriten ---------------------------------------------------------------------------------------

@Composable
private fun FavoriteShelf(st: SonosState, group: SonosGroup, starting: String?, vm: MusicViewModel, modifier: Modifier) {
    val items = st.favorites + st.playlists
    Column(modifier) {
        Row(Modifier.padding(start = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sonos_favorites).uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text(stringResource(R.string.sonos_favorites_play_in, group.name), style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (items.isEmpty()) {
            Text(stringResource(R.string.sonos_favorites_empty), style = SkyTextStyle, color = SkyTextSoft, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp, end = 24.dp))
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // fünf Cover nebeneinander, mehr per Wischen
            val tile = ((maxWidth - 12.dp - 14.dp * 4) / 5).coerceAtMost(maxHeight - 52.dp)
            LazyRow(contentPadding = PaddingValues(start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(items, key = { it.kind.name + it.id }) { f ->
                    CoverTile(f, tile, current = group.playback.startedFrom == f.title, busy = starting == f.id) { vm.play(group.id, f) }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(f: SonosFavorite, size: Dp, current: Boolean, busy: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(size).clickable(enabled = !busy, onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(size)
                .then(if (current) Modifier.border(3.dp, Color.White, RoundedCornerShape(22.dp)).padding(5.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            SonosCover(f.artUrls, if (current) size - 10.dp else size, RoundedCornerShape(if (current) 18.dp else 22.dp), icon = if (f.isStream) Icons.Outlined.Radio else Icons.Outlined.QueueMusic)
            if (busy) CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
            if (current) Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp).height(26.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(15.dp), tint = Color(0xFF10131F))
                Text(stringResource(R.string.sonos_tile_playing), color = Color(0xFF10131F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(f.title, style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = f.subtitle ?: if (f.kind == FavoriteKind.PLAYLIST) stringResource(R.string.sonos_playlist) else null
            sub?.let { Text(it, style = SkyTextStyle, color = SkyTextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// --- Abspielen in ------------------------------------------------------------------------------------

/** Gruppieren und Lautstärke je Raum in einer Karte: Antippen holt einen Raum dazu oder löst ihn. */
@Composable
private fun RoomsCard(st: SonosState, group: SonosGroup, vm: MusicViewModel, modifier: Modifier) {
    val g = LocalGlass.current
    Column(
        modifier.glass(RoundedCornerShape(32.dp), g.glass, g.edge).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.sonos_rooms).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
        st.players.forEach { pl -> RoomRow(st, pl, group, vm) }
        Text(stringResource(R.string.sonos_rooms_hint), color = g.soft, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoomRow(st: SonosState, pl: SonosPlayer, group: SonosGroup, vm: MusicViewModel) {
    val g = LocalGlass.current
    val inGroup = group.members.any { it.uuid == pl.uuid }
    // Der einzige Raum einer Gruppe kann sie nicht verlassen
    val enabled = !(inGroup && group.members.size == 1)
    val own = st.groupOf(pl.uuid)
    val status = when {
        inGroup && group.members.size == 1 -> stringResource(if (group.playback.playing) R.string.sonos_state_playing else R.string.sonos_state_paused)
        inGroup -> stringResource(R.string.sonos_room_plays_with)
        own?.playback?.playing == true -> stringResource(R.string.sonos_room_own)
        else -> stringResource(R.string.sonos_room_free)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val label = stringResource(if (inGroup) R.string.sonos_room_leave else R.string.sonos_room_join, pl.name)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (inGroup) g.ink else g.chip)
                    .clickable(enabled = enabled) { if (inGroup) vm.leave(pl.uuid) else vm.join(pl.uuid, group.id) },
                contentAlignment = Alignment.Center,
            ) { Icon(if (inGroup) Icons.Outlined.Check else Icons.Outlined.Add, label, Modifier.size(20.dp), tint = if (inGroup) (if (g.dark) Color(0xFF10131F) else Color.White) else g.ink) }
            Column(Modifier.weight(1f)) {
                Text(pl.name, color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (inGroup && group.members.size > 1) {
            val mv = group.memberVolumes[pl.uuid]
            Row(Modifier.padding(start = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { InkSlider((mv?.volume ?: 0).toFloat()) { vm.setMemberVolume(pl.uuid, it.toInt()) } }
                Text((mv?.volume ?: 0).toString(), color = g.soft, fontSize = 13.sp, modifier = Modifier.width(24.dp))
            }
        }
    }
}

/** Schmaler Regler in der Glaskarte – sendet erst beim Loslassen. */
@Composable
private fun InkSlider(value: Float, onCommit: (Float) -> Unit) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!dragging) local = value }
    Slider(
        value = local.coerceIn(0f, 100f),
        onValueChange = { dragging = true; local = it },
        onValueChangeFinished = { dragging = false; onCommit(local) },
        valueRange = 0f..100f,
        colors = SliderDefaults.colors(thumbColor = g.ink, activeTrackColor = g.ink, inactiveTrackColor = g.track),
        modifier = Modifier.height(32.dp),
    )
}

// --- Raum ist still ----------------------------------------------------------------------------------

@Composable
private fun IdleBody(st: SonosState, group: SonosGroup, starting: String?, vm: MusicViewModel) {
    val g = LocalGlass.current
    val p = group.playback
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(200.dp).padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.sonos_room_quiet, group.name).uppercase(), style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                Text(stringResource(R.string.sonos_idle_title), style = SkyTextStyle, color = SkyText, fontSize = 54.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.4).sp, lineHeight = 58.sp, modifier = Modifier.padding(top = 10.dp))
                Text(stringResource(if (p.hasContent) R.string.sonos_idle_hint_resume else R.string.sonos_idle_hint), style = SkyTextStyle, color = SkyTextSoft, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
            }
            if (p.hasContent && p.source != PlaySource.TV) Row(
                Modifier.width(400.dp).fillMaxHeight().glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SonosCover(p.track?.artUrl, 116.dp, RoundedCornerShape(22.dp), icon = sourceIcon(group))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sonos_last).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
                    Text(primaryLine(p), color = g.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    secondaryLine(p)?.let { Text(it, color = g.soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Row(
                        Modifier.padding(top = 10.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(g.ink).clickable { vm.playPause(group.id) }.padding(start = 14.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val fg = if (g.dark) Color(0xFF10131F) else Color.White
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(24.dp), tint = fg)
                        Text(stringResource(R.string.sonos_resume), color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        val items = st.favorites + st.playlists
        Row(Modifier.padding(start = 12.dp, top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sonos_favorites).uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text(stringResource(R.string.sonos_favorites_play_in, group.name), style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp)
        }
        if (items.isEmpty()) {
            Text(stringResource(R.string.sonos_favorites_empty), style = SkyTextStyle, color = SkyTextSoft, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp, end = 24.dp))
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.kind.name + it.id }) { f -> FavoriteRowTile(f, starting == f.id) { vm.play(group.id, f) } }
            }
        }
    }
}

@Composable
private fun FavoriteRowTile(f: SonosFavorite, busy: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().height(96.dp).glass(RoundedCornerShape(24.dp), g.glass, g.edge).clickable(enabled = !busy, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            SonosCover(f.artUrls, 74.dp, RoundedCornerShape(16.dp), icon = if (f.isStream) Icons.Outlined.Radio else Icons.Outlined.QueueMusic)
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(f.title, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = f.subtitle ?: if (f.kind == FavoriteKind.PLAYLIST) stringResource(R.string.sonos_playlist) else null
            sub?.let { Text(it, color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.Outlined.PlayCircle, stringResource(R.string.sonos_play), Modifier.size(28.dp), tint = g.soft)
    }
}

internal fun sourceIcon(group: SonosGroup): ImageVector = when (group.playback.source) {
    PlaySource.TV -> Icons.Outlined.Tv
    PlaySource.STREAM -> Icons.Outlined.Radio
    else -> if (group.members.size > 1) Icons.Outlined.SpeakerGroup else Icons.Outlined.QueueMusic
}
