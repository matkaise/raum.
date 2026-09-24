package app.raum.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.raum.data.weather.PrecipType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Was der Himmel zeigen soll – aus Vorhersage oder Vorschau. */
data class WeatherVisual(
    /** 0 – 1 */
    val cloudCover: Float,
    val precip: PrecipType = PrecipType.NONE,
    /** mm/h */
    val rate: Float = 0f,
    val thunder: Boolean = false,
    val fog: Boolean = false,
    /** m/s */
    val wind: Float = 2f,
    /** Richtung, in die der Wind weht: -1 nach links, +1 nach rechts */
    val windDirection: Float = 1f,
)

/**
 * Sekunden seit Start, ~30 Bilder/s. Läuft nur, solange [running] – im Ruhezustand, außerhalb der
 * Übersicht oder bei abgeschalteten Systemanimationen steht das Bild.
 */
@Composable
fun rememberAnimationClock(running: Boolean): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t.floatValue += (now - last) / 1_000_000_000f; last = now }
            delay(30)
        }
    }
    return t
}

private class Particle(val x: Float, val y: Float, val speed: Float, val size: Float, val phase: Float)

private fun particles(seed: Int, n: Int) = Random(seed).let { r ->
    List(n) { Particle(r.nextFloat(), r.nextFloat(), 0.7f + r.nextFloat() * 0.6f, r.nextFloat(), r.nextFloat() * 2f * PI.toFloat()) }
}

/** Wolke aus weichen Kugeln (relativ zur Wolkengröße). */
private val PUFFS = listOf(
    Triple(0.00f, 0.10f, 0.34f), Triple(0.26f, -0.12f, 0.42f), Triple(0.55f, 0.02f, 0.36f),
    Triple(0.78f, 0.14f, 0.26f), Triple(0.38f, 0.22f, 0.30f),
)

/** Wettereffekte über dem Himmel der Hero-Karte. [time] wird nur beim Zeichnen gelesen (kein Recompose). */
@Composable
fun WeatherLayer(visual: WeatherVisual, night: Boolean, time: State<Float>, modifier: Modifier = Modifier) {
    val far = remember { particles(11, 6) }
    val near = remember { particles(23, 5) }
    val drops = remember { particles(37, 180) }
    val flakes = remember { particles(41, 160) }
    val fogBlobs = remember { particles(53, 6) }

    Canvas(modifier) {
        val t = time.value
        val cover = visual.cloudCover.coerceIn(0f, 1f)
        val cloudTint = when {
            visual.precip != PrecipType.NONE || visual.thunder -> if (night) Color(0xFF5A6273) else Color(0xFFB9C1CB)
            night -> Color(0xFF8E97AD)
            else -> Color.White
        }
        val drift = (6f + visual.wind * 2.5f).dp.toPx() * visual.windDirection

        // Wolken: hintere Ebene kleiner, langsamer, blasser
        clouds(far.take((cover * far.size + 0.3f).toInt()), t, drift * 0.5f, cloudTint.copy(alpha = 0.28f + cover * 0.2f), scale = 0.55f, band = 0.05f..0.45f)
        clouds(near.take((cover * near.size + 0.2f).toInt()), t, drift, cloudTint.copy(alpha = 0.40f + cover * 0.3f), scale = 0.85f, band = 0.00f..0.55f)

        if (visual.fog) fog(fogBlobs, t)

        when (visual.precip) {
            PrecipType.RAIN -> rain(drops, count(visual.rate, 45f, 30, 180), t, visual)
            PrecipType.SNOW -> snow(flakes, count(visual.rate, 60f, 40, 160), t, visual)
            PrecipType.SLEET -> {
                rain(drops, count(visual.rate, 25f, 20, 90), t, visual)
                snow(flakes, count(visual.rate, 30f, 20, 80), t, visual)
            }
            PrecipType.NONE -> Unit
        }

        if (visual.thunder) {
            // Doppelblitz etwa alle 7 s
            val p = t % 7.3f
            val a = when { p < 0.07f -> 0.38f; p in 0.15f..0.22f -> 0.26f; else -> 0f }
            if (a > 0f) drawRect(Color.White.copy(alpha = a))
        }
    }
}

private fun count(rate: Float, perMm: Float, min: Int, max: Int) = (rate * perMm).toInt().coerceIn(min, max)

private fun wrap(v: Float, m: Float) = ((v % m) + m) % m

private fun DrawScope.clouds(list: List<Particle>, t: Float, drift: Float, color: Color, scale: Float, band: ClosedFloatingPointRange<Float>) {
    val w = size.width
    val cloudW = size.height * scale * 1.5f
    list.forEachIndexed { i, p ->
        val x = wrap(p.x * (w + cloudW) + t * drift * p.speed, w + cloudW) - cloudW
        val y = size.height * (band.start + (band.endInclusive - band.start) * ((i * 0.37f + p.y) % 1f))
        val s = cloudW * (0.7f + p.size * 0.5f)
        PUFFS.forEach { (dx, dy, r) ->
            val c = Offset(x + dx * s, y + dy * s)
            val radius = r * s
            drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * 0.55f), Color.Transparent), c, radius), radius, c)
        }
    }
}

private fun DrawScope.rain(list: List<Particle>, n: Int, t: Float, v: WeatherVisual) {
    val h = size.height
    val slant = (v.wind * 0.045f).coerceAtMost(0.55f) * v.windDirection
    val stroke = 1.4.dp.toPx()
    for (i in 0 until n) {
        val p = list[i]
        val len = (12f + p.size * 12f).dp.toPx()
        val speed = (650f + p.speed * 350f).dp.toPx()
        val y = wrap(p.y * (h + len) + t * speed, h + len) - len
        val x = wrap(p.x * size.width + y * slant, size.width)
        drawLine(
            Color.White.copy(alpha = 0.22f + p.size * 0.25f),
            Offset(x, y), Offset(x + len * slant, y + len), stroke, StrokeCap.Round,
        )
    }
}

private fun DrawScope.snow(list: List<Particle>, n: Int, t: Float, v: WeatherVisual) {
    val h = size.height
    val side = v.wind * 3f * v.windDirection
    for (i in 0 until n) {
        val p = list[i]
        val r = (1.4f + p.size * 2.4f).dp.toPx()
        val speed = (26f + p.speed * 40f).dp.toPx()
        val y = wrap(p.y * (h + r * 2) + t * speed, h + r * 2) - r
        val sway = sin(t * (0.6f + p.speed) + p.phase) * 10.dp.toPx()
        val x = wrap(p.x * size.width + sway + t * side.dp.toPx(), size.width)
        drawCircle(Color.White.copy(alpha = 0.55f + p.size * 0.35f), r, Offset(x, y))
    }
}

private fun DrawScope.fog(list: List<Particle>, t: Float) {
    val w = size.width
    list.forEachIndexed { i, p ->
        val bw = w * (0.45f + p.size * 0.3f)
        val bh = size.height * (0.25f + p.speed * 0.15f)
        val x = wrap(p.x * (w + bw) + t * (8f + i * 3f).dp.toPx(), w + bw) - bw / 2
        val y = size.height * (0.35f + 0.55f * p.y)
        val c = Offset(x, y)
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent), c, bw / 2),
            topLeft = Offset(x - bw / 2, y - bh / 2), size = Size(bw, bh),
        )
    }
}

/** Sterne – funkeln leicht und verschwinden hinter Wolken. */
@Composable
fun TwinklingStars(cloudCover: Float, time: State<Float>, modifier: Modifier = Modifier) {
    val stars = remember { particles(7, 46) }
    Canvas(modifier) {
        val t = time.value
        val visible = (1f - cloudCover * 1.1f).coerceIn(0f, 1f)
        if (visible <= 0f) return@Canvas
        stars.forEach { s ->
            val twinkle = 0.7f + 0.3f * abs(sin(t * (0.4f + s.speed) + s.phase))
            val r = (0.6f + s.size * 1.4f).dp.toPx()
            drawCircle(Color.White.copy(alpha = (0.3f + s.size * 0.5f) * twinkle * visible), r, Offset(s.x * size.width, s.y * 0.8f * size.height))
        }
    }
}

/** Vorhersage → Darstellung. Symbol und Menge ergänzen sich (Symbol „Regen“ bei 0 mm → leichter Regen). */
fun visualOf(w: app.raum.data.weather.WeatherNow): WeatherVisual {
    val c = w.condition
    val precip = c.precip.takeIf { it != PrecipType.NONE } ?: if (w.precipitationRate >= 0.1) PrecipType.RAIN else PrecipType.NONE
    val minRate = when (c.intensity) {
        app.raum.data.weather.Intensity.LIGHT -> 0.5
        app.raum.data.weather.Intensity.MODERATE -> 2.0
        app.raum.data.weather.Intensity.HEAVY -> 6.0
    }
    val rate = if (precip == PrecipType.NONE) 0.0 else maxOf(w.precipitationRate, minRate)
    return WeatherVisual(
        cloudCover = w.cloudCover.toFloat(),
        precip = precip,
        rate = rate.toFloat(),
        thunder = c.thunder,
        fog = c.sky == app.raum.data.weather.SkyCover.FOG,
        wind = w.windSpeed.toFloat(),
        // „weht aus Westen“ (270°) → Bewegung nach rechts
        windDirection = if (-sin(Math.toRadians(w.windFrom)) >= 0) 1f else -1f,
    )
}

/** Nur Debug-Build: Wetterlage ohne Abruf vorführen. */
fun previewWeather(c: app.raum.data.weather.WeatherCondition, now: java.time.Instant): app.raum.data.weather.WeatherNow {
    val cover = when (c.sky) {
        app.raum.data.weather.SkyCover.CLEAR -> 0.0
        app.raum.data.weather.SkyCover.FAIR -> 0.2
        app.raum.data.weather.SkyCover.PARTLY_CLOUDY -> 0.5
        else -> 0.95
    }
    return app.raum.data.weather.WeatherNow(
        temperature = if (c.precip == PrecipType.SNOW) -2.0 else 12.0, condition = c, cloudCover = cover,
        windSpeed = if (c.thunder) 9.0 else 3.0, windFrom = 250.0, precipitationRate = 0.0,
        todayMin = if (c.precip == PrecipType.SNOW) -5.0 else 8.0, todayMax = if (c.precip == PrecipType.SNOW) 1.0 else 16.0,
        outlook = null, updatedAt = now,
    )
}
