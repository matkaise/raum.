package app.raum.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.LightCapability
import app.raum.domain.models.RgbColor
import app.raum.ui.theme.LocalGlass
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Was der große Drehregler einer Leuchte gerade einstellt. */
enum class LightDialMode(@StringRes val labelRes: Int) {
    BRIGHTNESS(R.string.brightness),
    WHITE(R.string.light_mode_white),
    COLOR(R.string.color),
}

/** Verfügbare Modi einer Leuchte; nur Helligkeit = keine Umschaltung nötig. */
fun lightDialModes(light: LightCapability): List<LightDialMode> = listOfNotNull(
    LightDialMode.BRIGHTNESS.takeIf { light.isDimmable },
    LightDialMode.WHITE.takeIf { light.supportsColorTemperature && (light.colorTemperatureRange?.let { it.last > it.first } == true) },
    LightDialMode.COLOR.takeIf { light.supportsColor },
)

/** Umschalter über dem Drehregler. */
@Composable
fun LightModeTabs(modes: List<LightDialMode>, selected: LightDialMode, onSelect: (LightDialMode) -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.clip(RoundedCornerShape(26.dp)).background(g.chip).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        modes.forEach { m ->
            val on = m == selected
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (on) g.ink else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(m.labelRes), color = if (on) g.glassSolid else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Verlauf entlang des 270°-Bogens (Start unten links, im Uhrzeigersinn): [colorAt] liefert die Farbe bei Anteil 0…1.
 * Die Lücke unten (45°…135°) wird nicht gezeichnet.
 */
fun arcGradient(center: Offset, colorAt: (Float) -> Color): Brush {
    val steps = 36
    val stops = (0..steps).map { i ->
        val t = i / steps.toFloat()
        val angle = (135f + 270f * t) % 360f
        angle / 360f to colorAt(t)
    }.sortedBy { it.first }.toMutableList()
    // Ränder des Kreises schließen: 0° liegt mitten im Bogen (t = 225/270)
    val atZero = colorAt(225f / 270f)
    stops.add(0, 0f to atZero)
    stops.add(1f to atZero)
    return Brush.sweepGradient(*stops.toTypedArray(), center = center)
}

/** Ungefähre Lichtfarbe einer Farbtemperatur (Tanner Helland), für Verlauf und Anzeige. */
fun kelvinColor(kelvin: Int): Color {
    val t = kelvin / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    fun c(v: Double) = v.coerceIn(0.0, 255.0).toFloat() / 255f
    return Color(c(r), c(g), c(b))
}

/** Farbton (0…360) und Sättigung (0…1) einer Farbe; Helligkeit regelt die Leuchte separat. */
data class HueSat(val hue: Float, val saturation: Float) {
    fun toRgb(): RgbColor {
        val hsv = floatArrayOf(hue.coerceIn(0f, 359.9f), saturation.coerceIn(0f, 1f), 1f)
        val argb = android.graphics.Color.HSVToColor(hsv)
        return RgbColor((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
    fun color(): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 359.9f), saturation.coerceIn(0f, 1f), 1f)))

    /** Nahe genug für „ausgewählt“ – gemeldete Farben sind durch XY/HS-Umrechnung nie exakt gleich. */
    fun near(other: HueSat): Boolean {
        val dh = abs(hue - other.hue).let { min(it, 360f - it) }
        return dh < 12f && abs(saturation - other.saturation) < 0.15f
    }
}

fun RgbColor.hueSat(): HueSat {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(red, green, blue, hsv)
    return HueSat(hsv[0], hsv[1])
}

/** Schnellwahl im Farbmodus: kräftige und zarte Töne. */
val ColorQuickPicks = listOf(
    HueSat(28f, 0.65f), HueSat(8f, 0.85f), HueSat(340f, 0.6f), HueSat(280f, 0.7f),
    HueSat(225f, 0.75f), HueSat(175f, 0.7f), HueSat(110f, 0.6f), HueSat(50f, 0.5f),
)

/** Weißtöne relativ zum Bereich der Leuchte (0 = wärmster, 1 = kältester Wert). */
val WhitePresets = listOf(R.string.ct_candle to 0f, R.string.ct_warm to 0.2f, R.string.ct_neutral to 0.5f, R.string.ct_day to 0.9f)

fun kelvinAt(range: IntRange, fraction: Float): Int = (range.first + fraction * (range.last - range.first)).roundToInt() / 50 * 50

fun fractionOf(range: IntRange, kelvin: Int): Float = ((kelvin - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)

/** Name des nächstgelegenen Weißtons, z. B. „Warm“. */
@Composable
fun whiteName(range: IntRange, kelvin: Int): String {
    val f = fractionOf(range, kelvin)
    return stringResource(WhitePresets.minBy { abs(it.second - f) }.first)
}
