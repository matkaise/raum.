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

/** Cover laden (lokal zuerst, Internet nur mit Einwilligung) – null, solange nichts da ist. */
@Composable
fun rememberCover(urls: List<String>, art: SonosArt = koinInject()): android.graphics.Bitmap? {
    val online by koinInject<app.raum.sonos.SonosService>().onlineArt.collectAsStateWithLifecycle()
    val bitmap by produceState(urls.firstNotNullOfOrNull(art::cached), urls, online) {
        value = urls.firstNotNullOfOrNull(art::cached) ?: withContext(Dispatchers.IO) { urls.firstNotNullOfOrNull(art::load) }
    }
    return bitmap
}

/** Cover mit Platzhalter (Verlauf + Symbol), solange nichts geladen ist. */
@Composable
fun SonosCover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.MusicNote) =
    SonosCover(listOfNotNull(url), size, shape, modifier, icon)

/** Wie oben, mit mehreren Kandidaten – der erste, der lädt, gewinnt. */
@Composable
fun SonosCover(urls: List<String>, size: Dp, shape: Shape, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.MusicNote) =
    CoverImage(rememberCover(urls), size, shape, modifier, icon)

@Composable
fun CoverImage(bitmap: android.graphics.Bitmap?, size: Dp, shape: Shape, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.MusicNote) {
    Box(
        modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3B4A7A), Color(0xFF7A5C9E)))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(icon, null, Modifier.size(size * 0.42f), tint = Color.White.copy(alpha = 0.85f))
    }
}

/**
 * Leuchtfarbe eines Covers für den Schein dahinter: Mittel über ein 12×12-Raster, kräftige Pixel zählen mehr; dann
 * aufgehellt und gesättigt, damit der Schein auf jedem Himmel sichtbar ist.
 */
fun glowColor(bitmap: android.graphics.Bitmap?): Color {
    if (bitmap == null) return Color(0xFF8C7BD0)
    val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 12, 12, true)
    var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
    val hsv = FloatArray(3)
    for (x in 0 until 12) for (y in 0 until 12) {
        val p = small.getPixel(x, y)
        android.graphics.Color.colorToHSV(p, hsv)
        val weight = 0.05 + hsv[1] * hsv[1] * hsv[2]
        r += android.graphics.Color.red(p) * weight; g += android.graphics.Color.green(p) * weight; b += android.graphics.Color.blue(p) * weight; w += weight
    }
    if (small != bitmap) small.recycle()
    android.graphics.Color.RGBToHSV((r / w).toInt(), (g / w).toInt(), (b / w).toInt(), hsv)
    hsv[1] = hsv[1].coerceIn(0.35f, 0.75f); hsv[2] = hsv[2].coerceAtLeast(0.8f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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

/** Interpret · Album (bei Radio nur der Interpret). */
fun artistLine(p: Playback): String? =
    listOfNotNull(p.track?.artist, p.track?.album?.takeIf { p.source == PlaySource.QUEUE }).joinToString(" · ").ifBlank { null }

/** Woher die Musik kommt: „aus ‚Hits 2026‘ · YouTube Music“, bei Radio „Hit List · Sonos Radio“. */
@Composable
fun sourceLine(p: Playback): String? {
    val from = p.startedFrom ?: p.stationName?.takeIf { p.source == PlaySource.STREAM }
    return when {
        from == null -> p.service
        p.source == PlaySource.STREAM -> listOfNotNull(from, p.service).distinct().joinToString(" · ")
        else -> listOfNotNull(stringResource(R.string.sonos_from, from), p.service).joinToString(" · ")
    }
}

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

