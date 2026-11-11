package app.raum.ui.overview

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.PointMode
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
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
 * Sekunden seit Start, ~30 Bilder/s. Läuft nur, solange [running] – im Ruhezustand oder bei
 * abgeschalteten Systemanimationen steht das Bild.
 */
@Composable
/**
 * Uhr für Himmelsanimationen. [fast] = Regen/Schnee (schnelle Teilchen); sonst bewegt sich nur langsam Wolke und Funkeln,
 * dafür reichen ~12 Bilder/s – spart auf dem Panel spürbar Rechenzeit.
 */
fun rememberAnimationClock(running: Boolean, fast: Boolean = true): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, fast) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            // Während der Nutzer scrollt, steht der Himmel – jedes Bild gehört dann der Bedienung
            if (SkyMotion.paused) {
                while (SkyMotion.paused) {
                    kotlinx.coroutines.withTimeoutOrNull(300) { snapshotFlow { SkyMotion.paused }.first { !it } }
                    // Sicherheitsnetz: endet ein Wischen ohne Fling-Meldung, läuft der Himmel nach kurzer Ruhe weiter
                    if (android.os.SystemClock.uptimeMillis() - SkyMotion.lastScroll > SkyMotion.RESUME_AFTER_MS) SkyMotion.paused = false
                }
                last = withFrameNanos { it }
            }
            withFrameNanos { now -> t.floatValue += (now - last) / 1_000_000_000f; last = now }
            delay(if (fast) 30 else 70)
        }
    }
    return t
}

/**
 * Pausiert die Himmelsanimation, solange irgendeine Seite scrollt – jedes Bild gehört dann der Bedienung. Gemeldet über
 * [scrollPause], das RaumApp um den ganzen Inhaltsbereich legt.
 */
object SkyMotion {
    var paused by mutableStateOf(false)
    @Volatile var lastScroll = 0L
    const val RESUME_AFTER_MS = 600L

    val scrollPause = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            lastScroll = android.os.SystemClock.uptimeMillis()
            if (!paused) paused = true
            return androidx.compose.ui.geometry.Offset.Zero
        }

        override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            paused = false
            return androidx.compose.ui.unit.Velocity.Zero
        }
    }
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

    Spacer(modifier.drawWithCache {
        val cover = visual.cloudCover.coerceIn(0f, 1f)
        val cloudTint = when {
            visual.precip != PrecipType.NONE || visual.thunder -> if (night) Color(0xFF5A6273) else Color(0xFFB9C1CB)
            night -> Color(0xFF8E97AD)
            else -> Color.White
        }
        val farTint = cloudTint.copy(alpha = 0.28f + cover * 0.2f)
        val nearTint = cloudTint.copy(alpha = 0.40f + cover * 0.3f)
        val drift = (6f + visual.wind * 2.5f).dp.toPx() * visual.windDirection
        onDrawBehind {
            val t = time.value
            // Wolken: hintere Ebene kleiner, langsamer, blasser
            clouds(far.take((cover * far.size + 0.3f).toInt()), t, drift * 0.5f, farTint, scale = 0.55f, band = 0.05f..0.45f)
            clouds(near.take((cover * near.size + 0.2f).toInt()), t, drift, nearTint, scale = 0.85f, band = 0.00f..0.55f)

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
    })
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

// Regen, Schnee und Sterne in wenigen gebündelten Zeichenbefehlen (drawPoints) statt je Teilchen einem: Auf dem Panel
// kostete jeder Einzelbefehl im Render-Thread spürbar Zeit (bis zu 180 Striche pro Bild).
private const val ALPHA_LEVELS = 3

private fun DrawScope.rain(list: List<Particle>, n: Int, t: Float, v: WeatherVisual) {
    val h = size.height
    val slant = (v.wind * 0.045f).coerceAtMost(0.55f) * v.windDirection
    val stroke = 1.4.dp.toPx()
    val groups = Array(ALPHA_LEVELS) { ArrayList<Offset>(2 * n / ALPHA_LEVELS + 2) }
    for (i in 0 until n) {
        val p = list[i]
        val len = (12f + p.size * 12f).dp.toPx()
        val speed = (650f + p.speed * 350f).dp.toPx()
        val y = wrap(p.y * (h + len) + t * speed, h + len) - len
        val x = wrap(p.x * size.width + y * slant, size.width)
        val g = groups[(p.size * ALPHA_LEVELS).toInt().coerceAtMost(ALPHA_LEVELS - 1)]
        g.add(Offset(x, y)); g.add(Offset(x + len * slant, y + len))
    }
    groups.forEachIndexed { level, pts ->
        val size = (level + 0.5f) / ALPHA_LEVELS
        if (pts.isNotEmpty()) drawPoints(pts, PointMode.Lines, Color.White.copy(alpha = 0.22f + size * 0.25f), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.snow(list: List<Particle>, n: Int, t: Float, v: WeatherVisual) {
    val h = size.height
    val side = v.wind * 3f * v.windDirection
    val groups = Array(ALPHA_LEVELS) { ArrayList<Offset>(n / ALPHA_LEVELS + 1) }
    for (i in 0 until n) {
        val p = list[i]
        val level = (p.size * ALPHA_LEVELS).toInt().coerceAtMost(ALPHA_LEVELS - 1)
        val r = (1.4f + (level + 0.5f) / ALPHA_LEVELS * 2.4f).dp.toPx()
        val speed = (26f + p.speed * 40f).dp.toPx()
        val y = wrap(p.y * (h + r * 2) + t * speed, h + r * 2) - r
        val sway = sin(t * (0.6f + p.speed) + p.phase) * 10.dp.toPx()
        val x = wrap(p.x * size.width + sway + t * side.dp.toPx(), size.width)
        groups[level].add(Offset(x, y))
    }
    groups.forEachIndexed { level, pts ->
        val sz = (level + 0.5f) / ALPHA_LEVELS
        val r = (1.4f + sz * 2.4f).dp.toPx()
        if (pts.isNotEmpty()) drawPoints(pts, PointMode.Points, Color.White.copy(alpha = 0.55f + sz * 0.35f), r * 2, StrokeCap.Round)
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
        // Gebündelt nach Größe und Helligkeitsstufe des Funkelns – wenige Befehle statt einem je Stern
        val groups = HashMap<Int, ArrayList<Offset>>()
        stars.forEach { s ->
            val twinkle = 0.7f + 0.3f * abs(sin(t * (0.4f + s.speed) + s.phase))
            val sizeLevel = (s.size * ALPHA_LEVELS).toInt().coerceAtMost(ALPHA_LEVELS - 1)
            val twinkleLevel = ((twinkle - 0.7f) / 0.3f * 3f).roundToInt().coerceIn(0, 3)
            groups.getOrPut(sizeLevel * 10 + twinkleLevel) { ArrayList() }.add(Offset(s.x * size.width, s.y * 0.8f * size.height))
        }
        groups.forEach { (key, pts) ->
            val sz = (key / 10 + 0.5f) / ALPHA_LEVELS
            val twinkle = 0.7f + 0.3f * (key % 10) / 3f
            val r = (0.6f + sz * 1.4f).dp.toPx()
            drawPoints(pts, PointMode.Points, Color.White.copy(alpha = (0.3f + sz * 0.5f) * twinkle * visible), r * 2, StrokeCap.Round)
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
