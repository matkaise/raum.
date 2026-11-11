package app.raum.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.sonos.PlaySource
import app.raum.sonos.SonosService
import app.raum.sonos.TransportState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.ui.components.FixedGrid
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import kotlinx.coroutines.launch

/** Übersicht: Reihe „Musik“ – Räume, in denen etwas läuft oder pausiert, mit Wiedergabe-Knopf. Antippen öffnet die Seite. */
@Composable
fun MusicRow(service: SonosService, onOpen: (String) -> Unit) {
    val st by service.state.collectAsStateWithLifecycle()
    if (!st.active) return
    DisposableEffect(service) {
        val release = service.watch(SonosService.GLANCE_POLL_MS)
        onDispose { release() }
    }
    // Gestoppte Räume (oft mit altem Inhalt) gehören nicht auf die Übersicht
    val groups = st.groups.filter {
        it.playback.hasContent && it.playback.source != PlaySource.TV && it.playback.state in setOf(TransportState.PLAYING, TransportState.TRANSITIONING, TransportState.PAUSED)
    }
    if (groups.isEmpty()) return
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    Text(
        stringResource(R.string.nav_music).uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 20.dp, top = 34.dp, bottom = 12.dp),
    )
    FixedGrid(groups, columns = 3) { group, mod ->
        val p = group.playback
        Row(
            mod.height(112.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable { onOpen(group.id) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SonosCover(p.track?.artUrl, 88.dp, RoundedCornerShape(18.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(primaryLine(p), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                secondaryLine(p)?.let { Text(it, color = g.soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            val icon = when { !p.playing -> Icons.Outlined.PlayArrow; p.canPause -> Icons.Outlined.Pause; else -> Icons.Outlined.Stop }
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(g.ink).clickable { scope.launch { service.playPause(group.id) } },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, stringResource(if (p.playing) R.string.sonos_pause else R.string.sonos_play), Modifier.size(28.dp), tint = if (g.dark) Color(0xFF10131F) else Color.White) }
        }
    }
}
