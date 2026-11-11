package app.raum.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.sonos.PlaySource
import app.raum.sonos.SonosGroup
import app.raum.sonos.SonosService
import app.raum.sonos.TransportState
import app.raum.ui.components.FixedGrid
import app.raum.ui.household.rememberNow
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import kotlinx.coroutines.launch

/**
 * Übersicht: Reihe „Musik“ (Entwurf „Mini-Player“). Der erste spielende Raum bekommt eine breite Karte mit Fortschritt,
 * Zurück/Pause/Weiter und Lautstärke, weitere Räume kompakte Karten. Gestoppte Räume erscheinen nicht.
 */
@Composable
fun MusicRow(service: SonosService, onOpen: (String) -> Unit) {
    val st by service.state.collectAsStateWithLifecycle()
    if (!st.active) return
    DisposableEffect(service) {
        val release = service.watch(SonosService.GLANCE_POLL_MS)
        onDispose { release() }
    }
    val groups = st.groups.filter {
        it.playback.hasContent && it.playback.source != PlaySource.TV && it.playback.state in setOf(TransportState.PLAYING, TransportState.TRANSITIONING, TransportState.PAUSED)
    }.sortedByDescending { it.playback.playing }
    if (groups.isEmpty()) return
    val scope = rememberCoroutineScope()
    Row(Modifier.padding(start = 20.dp, top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_music).uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text(stringResource(R.string.sonos_row_hint), style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp)
    }
    val first = groups.first()
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        WideCard(first, service, Modifier.weight(2f), onOpen) { a -> scope.launch { a() } }
        groups.getOrNull(1)?.let { CompactCard(it, Modifier.weight(1f), onOpen) { scope.launch { service.playPause(it.id) } } } ?: Spacer(Modifier.weight(1f))
    }
    if (groups.size > 2) {
        FixedGrid(groups.drop(2), columns = 3, modifier = Modifier.padding(top = 14.dp)) { grp, mod ->
            CompactCard(grp, mod, onOpen) { scope.launch { service.playPause(grp.id) } }
        }
    }
}

@Composable
private fun WideCard(group: SonosGroup, service: SonosService, modifier: Modifier, onOpen: (String) -> Unit, run: (suspend () -> Unit) -> Unit) {
    val g = LocalGlass.current
    val p = group.playback
    val now = rememberNow()
    Row(
        modifier.height(150.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable { onOpen(group.id) }.padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SonosCover(p.track?.artUrl, 122.dp, RoundedCornerShape(20.dp), icon = sourceIcon(group))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.playing) Icon(Icons.Outlined.GraphicEq, null, Modifier.size(16.dp), tint = g.ink)
                Text(group.name, color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(primaryLine(p), color = g.ink, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            val sub = listOfNotNull(p.track?.artist, p.startedFrom?.let { stringResource(R.string.sonos_from, it) } ?: p.stationName?.takeIf { it != p.track?.title })
                .joinToString(" · ").ifBlank { null }
            sub?.let { Text(it, color = g.soft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val duration = p.durationS
            if (duration != null && p.source == PlaySource.QUEUE) {
                val f = ((livePosition(p, now) ?: 0).toFloat() / duration).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(g.track)) {
                    Box(Modifier.fillMaxWidth(f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(g.ink))
                }
            }
        }
        val transport = p.hasContent && p.source != PlaySource.LINE_IN
        RowButton(Icons.Outlined.SkipPrevious, stringResource(R.string.sonos_previous), 52.dp, enabled = transport && p.canPrevious) { run { service.previous(group.id) } }
        val icon = when { !p.playing -> Icons.Outlined.PlayArrow; p.canPause -> Icons.Outlined.Pause; else -> Icons.Outlined.Stop }
        RowButton(icon, stringResource(if (p.playing) R.string.sonos_pause else R.string.sonos_play), 68.dp, primary = true, enabled = transport) { run { service.playPause(group.id) } }
        RowButton(Icons.Outlined.SkipNext, stringResource(R.string.sonos_next), 52.dp, enabled = transport && p.canNext) { run { service.next(group.id) } }
        Box(Modifier.width(1.dp).height(44.dp).background(g.track))
        // Lautstärke: Anzeige; antippen öffnet die Musik-Seite mit dem Drehring
        Column(
            Modifier.size(52.dp).clip(CircleShape).background(g.chip).clickable { onOpen(group.id) },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.VolumeUp, stringResource(R.string.sonos_volume), Modifier.size(20.dp), tint = g.ink)
            Text((group.volume ?: 0).toString(), color = g.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun CompactCard(group: SonosGroup, modifier: Modifier, onOpen: (String) -> Unit, onPlayPause: () -> Unit) {
    val g = LocalGlass.current
    val p = group.playback
    Row(
        modifier.height(150.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable { onOpen(group.id) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SonosCover(p.track?.artUrl, 88.dp, RoundedCornerShape(18.dp), icon = sourceIcon(group))
        Column(Modifier.weight(1f)) {
            Text("${group.name} · ${statusLine(group)}", color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(primaryLine(p), color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            secondaryLine(p)?.let { Text(it, color = g.soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val icon = when { !p.playing -> Icons.Outlined.PlayArrow; p.canPause -> Icons.Outlined.Pause; else -> Icons.Outlined.Stop }
        RowButton(icon, stringResource(if (p.playing) R.string.sonos_pause else R.string.sonos_play), 56.dp, primary = true, onClick = onPlayPause)
    }
}

@Composable
private fun RowButton(icon: ImageVector, label: String, size: Dp, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    val fg = if (primary) (if (g.dark) Color(0xFF10131F) else Color.White) else g.ink
    Box(
        Modifier.size(size).clip(CircleShape).background(if (primary) g.ink else g.chip).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.5f), tint = fg.copy(alpha = if (enabled) 1f else 0.4f)) }
}
