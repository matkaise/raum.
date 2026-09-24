package app.raum.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Warme, wohnraumtaugliche Palette (Spez. 9.1): Papier, Holz, Messing.
private val Paper = Color(0xFFF5F2EC)
private val PaperRaised = Color(0xFFFCFAF6)
private val PaperSunken = Color(0xFFEAE5DB)
private val Ink = Color(0xFF1C1B18)
private val InkSoft = Color(0xFF6B665C)
private val Brass = Color(0xFF9A6424)
private val BrassLight = Color(0xFFF3D9B1)

private val Night = Color(0xFF131311)
private val NightRaised = Color(0xFF1D1C19)
private val NightSunken = Color(0xFF282621)
private val Linen = Color(0xFFEDE8DF)
private val LinenSoft = Color(0xFFA39D92)
private val Amber = Color(0xFFE6B06B)
private val AmberDeep = Color(0xFF4A3519)

val LightColors = lightColorScheme(
    primary = Brass,
    onPrimary = Color.White,
    primaryContainer = BrassLight,
    onPrimaryContainer = Color(0xFF3A2507),
    secondary = Color(0xFF53634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E4D2),
    onSecondaryContainer = Color(0xFF17231A),
    tertiary = Color(0xFF3F5E7A),
    tertiaryContainer = Color(0xFFD3E3F3),
    onTertiaryContainer = Color(0xFF0E2233),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperSunken,
    onSurfaceVariant = InkSoft,
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = Color(0xFFF1EDE5),
    surfaceContainerHighest = PaperSunken,
    outline = Color(0xFFCBC4B6),
    outlineVariant = Color(0xFFE0DAD0),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2E1D05),
    primaryContainer = AmberDeep,
    onPrimaryContainer = Color(0xFFFBE3C0),
    secondary = Color(0xFFB7C8B1),
    onSecondary = Color(0xFF233020),
    secondaryContainer = Color(0xFF354332),
    onSecondaryContainer = Color(0xFFD8E4D2),
    tertiary = Color(0xFFA9C7E3),
    tertiaryContainer = Color(0xFF26415A),
    onTertiaryContainer = Color(0xFFD3E3F3),
    background = Night,
    onBackground = Linen,
    surface = NightRaised,
    onSurface = Linen,
    surfaceVariant = NightSunken,
    onSurfaceVariant = LinenSoft,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = Color(0xFF23221E),
    surfaceContainerHighest = NightSunken,
    outline = Color(0xFF4A473F),
    outlineVariant = Color(0xFF34322C),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
)
