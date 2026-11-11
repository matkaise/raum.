package app.raum.ui.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.sonos.FavoriteKind
import app.raum.sonos.PlaySource
import app.raum.sonos.RepeatMode
import app.raum.sonos.SonosFavorite
import app.raum.sonos.SonosGroup
import app.raum.sonos.SonosLink
import app.raum.sonos.SonosPlayer
import app.raum.sonos.SonosService
import app.raum.sonos.SonosState
import app.raum.ui.household.ActionButton
import app.raum.ui.household.rememberNow
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel

/**
 * „Musik“: Sonos-Räume links, in der Mitte was läuft (Cover, Steuerung, Lautstärke, Gruppieren), rechts die
 * Sonos-Favoriten und -Wiedergabelisten. YouTube Music & Co. kommen über die Favoriten aus der Sonos-App.
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
        Column(Modifier.padding(start = 20.dp, bottom = 20.dp)) {
            Text(stringResource(R.string.nav_music), style = SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
            Text(headline(st), style = SkyTextStyle, color = SkyTextSoft, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (selected == null) {
            EmptyMusic(st, onOpenSettings)
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupList(st, selected.id, vm::select)
                NowPlayingPanel(st, selected, vm, Modifier.weight(1.15f).fillMaxHeight())
                FavoritesPanel(st, selected, starting, vm, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

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
            else -> null
        },
    ).joinToString(" · ")
}

@Composable
private fun EmptyMusic(st: SonosState, onOpenSettings: () -> Unit) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp).glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(32.dp),
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

// --- Räume -------------------------------------------------------------------------------------------

@Composable
private fun GroupList(st: SonosState, selected: String, onPick: (String) -> Unit) {
    Column(
        Modifier.width(276.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        st.groups.forEach { g -> GroupCard(g, g.id == selected) { onPick(g.id) } }
    }
}

@Composable
private fun GroupCard(group: SonosGroup, on: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    val bg by animateColorAsState(if (on) g.glassSolid else g.glass, label = "group")
    val p = group.playback
    Row(
        Modifier.fillMaxWidth().height(104.dp)
            .then(if (on) Modifier.shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.16f)) else Modifier)
            .clip(RoundedCornerShape(28.dp)).background(bg)
            .border(1.dp, if (on) Color.White.copy(alpha = 0.95f) else g.edge, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SonosCover(p.track?.artUrl, 64.dp, RoundedCornerShape(16.dp), icon = sourceIcon(group))
        Column(Modifier.weight(1f)) {
            Text(group.name, color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Text(
                if (p.hasContent) primaryLine(p) else statusLine(group),
                color = if (p.playing) g.ink else g.soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (p.hasContent) Text(statusLine(group), color = g.soft, fontSize = 13.sp, maxLines = 1)
        }
    }
}

private fun sourceIcon(group: SonosGroup): ImageVector = when (group.playback.source) {
    PlaySource.TV -> Icons.Outlined.Tv
    PlaySource.STREAM -> Icons.Outlined.Radio
    else -> if (group.members.size > 1) Icons.Outlined.SpeakerGroup else androidx.compose.material.icons.Icons.Outlined.QueueMusic
}

// --- Was läuft ---------------------------------------------------------------------------------------

@Composable
private fun NowPlayingPanel(st: SonosState, group: SonosGroup, vm: MusicViewModel, modifier: Modifier) {
    val g = LocalGlass.current
    val p = group.playback
    val now = rememberNow()
    Column(
        modifier.glass(RoundedCornerShape(32.dp), g.glass, g.edge).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SonosCover(p.track?.artUrl, 168.dp, RoundedCornerShape(24.dp), icon = sourceIcon(group))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.name.uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(primaryLine(p), color = g.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                secondaryLine(p)?.let { Text(it, color = g.soft, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                p.track?.album?.let { Text(it, color = g.soft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        // Fortschritt (nur bei Titeln mit Länge)
        val duration = p.durationS
        if (duration != null && p.source == PlaySource.QUEUE) {
            val pos = livePosition(p, now) ?: 0
            Column {
                GlassSlider(pos.toFloat(), 0f..duration.toFloat(), enabled = p.canSeek) { vm.seek(group.id, it.toInt()) }
                Row {
                    Text(mmss(pos), color = g.soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(mmss(duration), color = g.soft, fontSize = 13.sp)
                }
            }
        }

        // Steuerung
        val transport = p.source != PlaySource.TV && p.source != PlaySource.LINE_IN && p.hasContent
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val queue = p.source == PlaySource.QUEUE
            RoundButton(Icons.Outlined.Shuffle, stringResource(R.string.sonos_shuffle), 52.dp, active = p.shuffle, enabled = queue) { vm.setShuffle(group.id, !p.shuffle) }
            RoundButton(Icons.Outlined.SkipPrevious, stringResource(R.string.sonos_previous), 60.dp, enabled = transport && p.canPrevious) { vm.previous(group.id) }
            val playIcon = when { !p.playing -> Icons.Outlined.PlayArrow; p.canPause -> Icons.Outlined.Pause; else -> Icons.Outlined.Stop }
            RoundButton(playIcon, stringResource(if (p.playing) R.string.sonos_pause else R.string.sonos_play), 84.dp, primary = true, enabled = transport) { vm.playPause(group.id) }
            RoundButton(Icons.Outlined.SkipNext, stringResource(R.string.sonos_next), 60.dp, enabled = transport && p.canNext) { vm.next(group.id) }
            val repeatIcon = if (p.repeat == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat
            RoundButton(repeatIcon, stringResource(R.string.sonos_repeat), 52.dp, active = p.repeat != RepeatMode.OFF, enabled = queue) {
                vm.setRepeat(group.id, when (p.repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF })
            }
        }
        if (!p.hasContent) Text(stringResource(R.string.sonos_pick_favorite), color = g.soft, fontSize = 15.sp)

        // Lautstärke
        Section(stringResource(R.string.sonos_volume)) {
            VolumeRow(if (group.members.size > 1) stringResource(R.string.sonos_volume_all) else null, group.volume ?: 0, group.muted,
                onMute = { vm.setGroupMute(group.id, !group.muted) }, onVolume = { vm.setGroupVolume(group.id, it) })
            if (group.members.size > 1) group.members.forEach { m ->
                val mv = group.memberVolumes[m.uuid]
                VolumeRow(m.name, mv?.volume ?: 0, mv?.muted ?: false, onMute = null, onVolume = { vm.setMemberVolume(m.uuid, it) })
            }
        }

        // Gruppieren
        if (st.players.size > 1) Section(stringResource(R.string.sonos_rooms)) {
            RoomChips(st.players, group, onJoin = { vm.join(it.uuid, group.id) }, onLeave = { vm.leave(it.uuid) })
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        content()
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, primary: Boolean = false, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    val bg = when { primary -> g.ink; active -> g.ink.copy(alpha = 0.16f); else -> Color.Transparent }
    val fg = if (primary) (if (g.dark) Color(0xFF10131F) else Color.White) else g.ink
    Box(
        Modifier.size(size).clip(CircleShape).background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.4f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.5f), tint = fg.copy(alpha = if (enabled) 1f else 0.35f)) }
}

@Composable
private fun VolumeRow(label: String?, volume: Int, muted: Boolean, onMute: (() -> Unit)?, onVolume: (Int) -> Unit) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onMute != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onMute), contentAlignment = Alignment.Center) {
                Icon(if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, stringResource(R.string.sonos_mute), tint = if (muted) g.warn else g.ink)
            }
        } else {
            Spacer(Modifier.width(44.dp))
        }
        if (label != null) Text(label, color = g.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
        Box(Modifier.weight(1f)) { GlassSlider(volume.toFloat(), 0f..100f, enabled = true) { onVolume(it.toInt()) } }
        Text(volume.toString(), color = g.soft, fontSize = 15.sp, modifier = Modifier.width(36.dp))
    }
}

/** Regler, der beim Ziehen nur lokal folgt und erst beim Loslassen sendet. */
@Composable
private fun GlassSlider(value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onCommit: (Float) -> Unit) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!dragging) local = value }
    Slider(
        value = local.coerceIn(range),
        onValueChange = { dragging = true; local = it },
        onValueChangeFinished = { dragging = false; onCommit(local) },
        valueRange = range,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = g.ink, activeTrackColor = g.ink, inactiveTrackColor = g.track,
            disabledThumbColor = g.ink.copy(alpha = 0.5f), disabledActiveTrackColor = g.ink.copy(alpha = 0.4f), disabledInactiveTrackColor = g.track,
        ),
        modifier = Modifier.height(40.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomChips(players: List<SonosPlayer>, group: SonosGroup, onJoin: (SonosPlayer) -> Unit, onLeave: (SonosPlayer) -> Unit) {
    val g = LocalGlass.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        players.forEach { pl ->
            val inGroup = group.members.any { it.uuid == pl.uuid }
            // Der einzige Raum einer Gruppe kann sie nicht verlassen
            val enabled = !(inGroup && group.members.size == 1)
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (inGroup) g.ink else g.chip)
                    .clickable(enabled = enabled) { if (inGroup) onLeave(pl) else onJoin(pl) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val fg = if (inGroup) (if (g.dark) Color(0xFF10131F) else Color.White) else g.ink
                Icon(if (inGroup) Icons.Outlined.Check else Icons.Outlined.Add, null, Modifier.size(18.dp), tint = fg)
                Text(pl.name, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

// --- Favoriten ---------------------------------------------------------------------------------------

@Composable
private fun FavoritesPanel(st: SonosState, group: SonosGroup, starting: String?, vm: MusicViewModel, modifier: Modifier) {
    val g = LocalGlass.current
    Column(modifier.glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(top = 22.dp, start = 18.dp, end = 18.dp)) {
        if (st.favorites.isEmpty() && st.playlists.isEmpty()) {
            Text(stringResource(R.string.sonos_favorites).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 6.dp))
            Text(stringResource(R.string.sonos_favorites_empty), color = g.soft, fontSize = 15.sp, modifier = Modifier.padding(6.dp, 12.dp))
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp),
        ) {
            if (st.favorites.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) { GridTitle(stringResource(R.string.sonos_favorites)) }
                items(st.favorites, key = { "f" + it.id }) { f -> FavoriteTile(f, starting == f.id) { vm.play(group.id, f) } }
            }
            if (st.playlists.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) { GridTitle(stringResource(R.string.sonos_playlists), top = 12.dp) }
                items(st.playlists, key = { "p" + it.id }) { f -> FavoriteTile(f, starting == f.id) { vm.play(group.id, f) } }
            }
            item(span = { GridItemSpan(2) }) {
                Text(stringResource(R.string.sonos_favorites_hint), color = g.soft, fontSize = 13.sp, modifier = Modifier.padding(6.dp, 8.dp))
            }
        }
    }
}

@Composable
private fun GridTitle(text: String, top: Dp = 0.dp) {
    val g = LocalGlass.current
    Text(text.uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 6.dp, top = top, bottom = 2.dp))
}

@Composable
private fun FavoriteTile(f: SonosFavorite, busy: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(20.dp)).background(g.chip).clickable(enabled = !busy, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            SonosCover(f.artUrls, 60.dp, RoundedCornerShape(14.dp), icon = if (f.isStream) Icons.Outlined.Radio else Icons.Outlined.QueueMusic)
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(f.title, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            val sub = f.subtitle ?: if (f.kind == FavoriteKind.PLAYLIST) stringResource(R.string.sonos_playlist) else null
            sub?.let { Text(it, color = g.soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
