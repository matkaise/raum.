package app.raum.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.raum.ui.household.rememberNow
import kotlinx.coroutines.launch

/** Räume, die gerade etwas spielen oder pausiert sind (ohne Fernseher) – für „Gerade aktiv“ auf der Übersicht. */
@Composable
fun rememberActiveMusic(service: SonosService): List<SonosGroup> {
    val st by service.state.collectAsStateWithLifecycle()
    if (!st.active) return emptyList()
    DisposableEffect(service) {
        val release = service.watch(SonosService.GLANCE_POLL_MS)
        onDispose { release() }
    }
    return st.groups.filter {
        it.playback.hasContent && it.playback.source != PlaySource.TV && it.playback.state in setOf(TransportState.PLAYING, TransportState.TRANSITIONING, TransportState.PAUSED)
    }.sortedByDescending { it.playback.playing }
}

private val CardInk = Color(0xFF2A2238)

/**
 * Übersicht: Musikkarte (Entwurf „raum. Übersicht Redesign“) – dunkel wie die Musikseite, hinter dem Cover dessen
 * Leuchtfarbe. Titel, Fortschritt, Zurück/Pause/Weiter und Lautstärke −/+; antippen öffnet die Musik.
 * Spielen mehrere Räume, zeigt die Karte den ersten und „+1“.
 */
@Composable
fun MusicGlanceCard(groups: List<SonosGroup>, service: SonosService, modifier: Modifier, onOpen: (String) -> Unit) {
    val group = groups.first()
    val p = group.playback
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    val cover = rememberCover(listOfNotNull(p.track?.artUrl))
    val glow = remember(cover) { glowColor(cover) }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(CardInk).clickable { onOpen(group.id) }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(90.dp.toPx(), size.height * 0.45f)
            val r = 210.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.7f), glow.copy(alpha = 0f)), c, r), r, c)
        }
        Row(
            Modifier.fillMaxSize().padding(start = 18.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CoverImage(cover, 156.dp, RoundedCornerShape(18.dp), Modifier.shadow(14.dp, RoundedCornerShape(18.dp)), icon = sourceIcon(group))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.playing) Icon(Icons.Outlined.GraphicEq, null, Modifier.size(15.dp), tint = Color.White)
                    val where = stringResource(if (p.playing) R.string.sonos_playing_in else R.string.sonos_paused_in, group.name).uppercase()
                    Text(
                        if (groups.size > 1) "$where  +${groups.size - 1}" else where,
                        color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(primaryLine(p), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                secondaryLine(p)?.let { Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val duration = p.durationS
                if (duration != null && p.source == PlaySource.QUEUE) {
                    val f = ((livePosition(p, now) ?: 0).toFloat() / duration).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val transport = p.hasContent && p.source != PlaySource.LINE_IN
                    DarkButton(Icons.Outlined.SkipPrevious, stringResource(R.string.sonos_previous), 46.dp, enabled = transport && p.canPrevious) { scope.launch { service.previous(group.id) } }
                    val icon = when { !p.playing -> Icons.Outlined.PlayArrow; p.canPause -> Icons.Outlined.Pause; else -> Icons.Outlined.Stop }
                    DarkButton(icon, stringResource(if (p.playing) R.string.sonos_pause else R.string.sonos_play), 58.dp, primary = true, enabled = transport) { scope.launch { service.playPause(group.id) } }
                    DarkButton(Icons.Outlined.SkipNext, stringResource(R.string.sonos_next), 46.dp, enabled = transport && p.canNext) { scope.launch { service.next(group.id) } }
                    Spacer(Modifier.weight(1f))
                    val vol = group.volume
                    if (vol != null) {
                        DarkButton(Icons.Outlined.Remove, stringResource(R.string.sonos_volume_down), 40.dp) { scope.launch { service.setGroupVolume(group.id, (vol - 5).coerceAtLeast(0)) } }
                        VolumeRing(vol)
                        DarkButton(Icons.Outlined.Add, stringResource(R.string.sonos_volume_up), 40.dp) { scope.launch { service.setGroupVolume(group.id, (vol + 5).coerceAtMost(100)) } }
                    }
                }
            }
        }
    }
}

/** Kleiner Lautstärkering (270°) mit Zahl. */
@Composable
private fun VolumeRing(volume: Int) {
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.5.dp.toPx()
            val inset = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.2f), 135f, 270f, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (volume > 0) drawArc(Color.White, 135f, 270f * volume / 100f, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(volume.toString(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DarkButton(icon: ImageVector, label: String, size: Dp, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.46f), tint = (if (primary) CardInk else Color.White).copy(alpha = if (enabled) 1f else 0.4f)) }
}
