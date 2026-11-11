package app.raum.ui.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.raum.R
import app.raum.sonos.PlaySource
import app.raum.sonos.Playback
import app.raum.sonos.SonosArt
import app.raum.sonos.SonosGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant

/** Cover mit Platzhalter (Verlauf + Symbol), solange nichts geladen ist. */
@Composable
fun SonosCover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.MusicNote) =
    SonosCover(listOfNotNull(url), size, shape, modifier, icon)

/** Wie oben, mit mehreren Kandidaten – der erste, der lädt, gewinnt. */
@Composable
fun SonosCover(urls: List<String>, size: Dp, shape: Shape, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.MusicNote, art: SonosArt = koinInject()) {
    val online by koinInject<app.raum.sonos.SonosService>().onlineArt.collectAsStateWithLifecycle()
    val bitmap by produceState(urls.firstNotNullOfOrNull(art::cached), urls, online) {
        value = urls.firstNotNullOfOrNull(art::cached) ?: withContext(Dispatchers.IO) { urls.firstNotNullOfOrNull(art::load) }
    }
    Box(
        modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3B4A7A), Color(0xFF7A5C9E)))),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(icon, null, Modifier.size(size * 0.42f), tint = Color.White.copy(alpha = 0.85f))
    }
}

/** Erste Zeile: Titel – bei Radio der Sender, beim Fernseher „Fernseher“. */
@Composable
fun primaryLine(p: Playback): String = when {
    p.source == PlaySource.TV -> stringResource(R.string.sonos_source_tv)
    p.source == PlaySource.LINE_IN -> stringResource(R.string.sonos_source_line_in)
    !p.track?.title.isNullOrBlank() -> p.track!!.title
    !p.stationName.isNullOrBlank() -> p.stationName!!
    else -> stringResource(R.string.sonos_nothing_playing)
}

/** Zweite Zeile: Interpret (und Sender bei Radio). */
fun secondaryLine(p: Playback): String? = listOfNotNull(
    p.track?.artist,
    p.stationName?.takeIf { it != p.track?.title && !p.track?.title.isNullOrBlank() },
).joinToString(" · ").ifBlank { null }

/** Kurzstatus für Karten: „Spielt“, „Pausiert“ … */
@Composable
fun statusLine(g: SonosGroup): String = when {
    g.playback.playing -> stringResource(R.string.sonos_state_playing)
    g.playback.hasContent -> stringResource(R.string.sonos_state_paused)
    else -> stringResource(R.string.sonos_state_idle)
}

/** Position jetzt – beim Spielen ab dem letzten Stand weitergezählt. */
fun livePosition(p: Playback, now: Instant): Int? {
    val pos = p.positionS ?: return null
    val at = p.positionAt ?: return pos
    val extra = if (p.playing) Duration.between(at, now).seconds.coerceAtLeast(0).toInt() else 0
    return (pos + extra).let { v -> p.durationS?.let { v.coerceAtMost(it) } ?: v }
}

fun mmss(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

