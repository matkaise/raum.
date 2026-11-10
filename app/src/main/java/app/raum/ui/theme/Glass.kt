package app.raum.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Farben für die Himmel-Oberfläche (Entwurf „raum B Himmel v2“): halbtransparente Glasflächen
 * über dem Himmel, eigene Werte für hell und dunkel.
 */
@Immutable
data class GlassColors(
    val dark: Boolean,
    /** Schrift auf Glas */
    val ink: Color,
    val soft: Color,
    /** Karten */
    val glass: Color,
    /** fast deckend: Kerne von Ringen, aktive Auswahl */
    val glassSolid: Color,
    /** Navigation, Dialoge */
    val glassStrong: Color,
    /** Detailfenster: ohne Weichzeichner deckend, sonst scheint der Inhalt dahinter unruhig durch */
    val sheet: Color,
    val edge: Color,
    /** Knöpfe und Flächen auf Glas */
    val chip: Color,
    val track: Color,
    /** Dunst am unteren Bildrand */
    val haze: Color,
    val cool: Color,
    val warn: Color,
)

val LightGlass = GlassColors(
    dark = false,
    ink = Color(0xFF10131F),
    soft = Color(0xFF10131F).copy(alpha = 0.62f),
    glass = Color.White.copy(alpha = 0.58f),
    glassSolid = Color.White.copy(alpha = 0.92f),
    glassStrong = Color.White.copy(alpha = 0.78f),
    sheet = Color(0xFFF6F7FA),
    edge = Color.White.copy(alpha = 0.65f),
    chip = Color(0xFF10131F).copy(alpha = 0.07f),
    track = Color(0xFF10131F).copy(alpha = 0.10f),
    haze = Color.White.copy(alpha = 0.18f),
    cool = Color(0xFF2F78C4),
    warn = Color(0xFFB3261E),
)

val DarkGlass = GlassColors(
    dark = true,
    ink = Color(0xFFF3F4F8),
    soft = Color(0xFFF3F4F8).copy(alpha = 0.66f),
    glass = Color(0xFF0C0E1C).copy(alpha = 0.42f),
    glassSolid = Color(0xFF14182C).copy(alpha = 0.85f),
    glassStrong = Color(0xFF101324).copy(alpha = 0.72f),
    sheet = Color(0xFF14182C),
    edge = Color.White.copy(alpha = 0.12f),
    chip = Color.White.copy(alpha = 0.10f),
    track = Color.White.copy(alpha = 0.14f),
    haze = Color(0xFF040610).copy(alpha = 0.55f),
    cool = Color(0xFF9FD3FF),
    warn = Color(0xFFFFB4A8),
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

/** Akzentfarben je Gerätetyp. */
object Accent {
    val Light = Color(0xFFFFC870)
    val LightDeep = Color(0xFF9A6424)
    val OnLight = Color(0xFF2E1D05)
    val Heat = Color(0xFFFF9D6B)
    val OnHeat = Color(0xFF2E1405)
    val Cover = Color(0xFF9FD3FF)
    val Sensor = Color(0xFFA8E6B8)
    val Power = Color(0xFF8FE0A4)
    val Warn = Color(0xFFFFD38F)
}

/** Weiß auf Himmel. */
val SkyText = Color.White
val SkyTextSoft = Color.White.copy(alpha = 0.85f)

/** Glasfläche mit feiner heller Kante. */
fun Modifier.glass(shape: Shape, color: Color, edge: Color): Modifier =
    clip(shape).background(color, shape).border(1.dp, edge, shape)

/**
 * Material-Farben für Inhalte auf dem Himmel: Karten (surface) werden Glas, Text außerhalb von
 * Karten ist weiß, Dialoge und Menüs bleiben deckend. So übernehmen auch Formulare und Listen
 * den Himmel-Stil, ohne jede Komponente einzeln umzubauen.
 */
@androidx.compose.runtime.Composable
fun SkyContentTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val g = LocalGlass.current
    val base = androidx.compose.material3.MaterialTheme.colorScheme
    val scheme = base.copy(
        background = Color.Transparent,
        onBackground = SkyText,
        surface = g.glass,
        onSurface = g.ink,
        surfaceVariant = g.chip,
        onSurfaceVariant = g.soft,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = g.sheet,
        surfaceContainerLow = g.sheet,
        surfaceContainer = g.sheet,
        surfaceContainerHigh = g.sheet,
        surfaceContainerHighest = if (g.dark) Color(0xFF262B42) else Color(0xFFE4E6EC),
        outline = g.ink.copy(alpha = 0.28f),
        outlineVariant = g.track,
        primaryContainer = if (g.dark) Color(0xFFFFBE64).copy(alpha = 0.3f) else Color(0xFFFFECC8),
        onPrimaryContainer = if (g.dark) Color(0xFFFBE3C0) else Color(0xFF3A2507),
        secondaryContainer = if (g.dark) Accent.Light.copy(alpha = 0.32f) else Color(0xFFFFD58C),
        onSecondaryContainer = if (g.dark) Color.White else Accent.OnLight,
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = scheme,
        typography = androidx.compose.material3.MaterialTheme.typography,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides SkyText, content = content)
    }
}

/**
 * Für Bedienelemente direkt auf dem Himmel (Filterchips, Umriss-Knöpfe außerhalb von Karten):
 * Schrift und Umrisse weiß statt dunkel.
 */
@androidx.compose.runtime.Composable
fun OnSky(content: @androidx.compose.runtime.Composable () -> Unit) {
    val base = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.MaterialTheme(
        colorScheme = base.copy(
            onSurface = SkyText,
            onSurfaceVariant = SkyTextSoft,
            outline = Color.White.copy(alpha = 0.5f),
            outlineVariant = Color.White.copy(alpha = 0.3f),
            primary = SkyText,
        ),
        typography = androidx.compose.material3.MaterialTheme.typography,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides SkyText, content = content)
    }
}
