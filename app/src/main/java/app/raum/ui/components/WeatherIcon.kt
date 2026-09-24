package app.raum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.raum.data.weather.PrecipType
import app.raum.data.weather.SkyCover
import app.raum.data.weather.WeatherCondition
import kotlin.math.cos
import kotlin.math.sin

private val Sun = Color(0xFFFFC23D)
private val Moon = Color(0xFFD4B95E) // mattes Gold: auf hellem und dunklem Grund sichtbar
private val Rain = Color(0xFF3D8BD9)
private val Snow = Color(0xFF8DB9E6)
private val Bolt = Color(0xFFF2A922)

/** Wettersymbol aus MET-Norway-Code (z. B. „lightrain“, „partlycloudy_night“). */
@Composable
fun WeatherIcon(symbol: String?, modifier: Modifier = Modifier) {
    val c = symbol?.let(WeatherCondition::fromSymbol) ?: WeatherCondition(SkyCover.CLOUDY)
    WeatherIcon(c, night = symbol?.endsWith("_night") == true, modifier = modifier)
}

/** Gezeichnetes Wettersymbol – einheitlich, scharf in jeder Größe, passend zu Hell/Dunkel. */
@Composable
fun WeatherIcon(condition: WeatherCondition, night: Boolean, modifier: Modifier = Modifier) {
    val cloud = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    val cloudBack = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    Canvas(modifier) {
        val s = size.minDimension
        val c = condition
        val wet = c.precip != PrecipType.NONE || c.thunder
        when {
            // reiner Himmel
            !wet && c.sky == SkyCover.CLEAR -> body(Offset(s * 0.5f, s * 0.5f), s * 0.24f, night)
            !wet && c.sky == SkyCover.FAIR -> {
                body(Offset(s * 0.42f, s * 0.42f), s * 0.22f, night)
                cloud(Offset(s * 0.62f, s * 0.74f), s * 0.42f, cloud)
            }
            !wet && c.sky == SkyCover.PARTLY_CLOUDY -> {
                body(Offset(s * 0.36f, s * 0.36f), s * 0.2f, night)
                cloud(Offset(s * 0.56f, s * 0.66f), s * 0.62f, cloud)
            }
            !wet && c.sky == SkyCover.FOG -> {
                cloud(Offset(s * 0.5f, s * 0.46f), s * 0.62f, cloudBack)
                listOf(0.66f, 0.78f, 0.9f).forEachIndexed { i, y ->
                    val inset = if (i == 1) 0.14f else 0.2f
                    drawLine(cloud, Offset(s * inset, s * y), Offset(s * (1 - inset), s * y), s * 0.06f, StrokeCap.Round)
                }
            }
            !wet -> {
                cloud(Offset(s * 0.62f, s * 0.46f), s * 0.52f, cloudBack)
                cloud(Offset(s * 0.46f, s * 0.6f), s * 0.7f, cloud)
            }
            else -> {
                // Niederschlag: Wolke oben, bei Schauern mit Sonne/Mond dahinter
                if (c.showers) body(Offset(s * 0.68f, s * 0.24f), s * 0.16f, night)
                cloud(Offset(s * 0.5f, s * 0.4f), s * 0.72f, cloud)
                val n = when (c.intensity) { app.raum.data.weather.Intensity.LIGHT -> 2; app.raum.data.weather.Intensity.HEAVY -> 4; else -> 3 }
                val xs = (0 until n).map { s * (0.3f + 0.4f * (it + 0.5f) / n) }
                xs.forEachIndexed { i, x ->
                    val snowflake = c.precip == PrecipType.SNOW || (c.precip == PrecipType.SLEET && i % 2 == 1)
                    val y0 = s * (0.7f + (i % 2) * 0.06f)
                    if (snowflake) drawCircle(Snow, s * 0.045f, Offset(x, y0 + s * 0.05f))
                    else if (c.precip != PrecipType.NONE) drawLine(Rain, Offset(x + s * 0.03f, y0), Offset(x - s * 0.03f, y0 + s * 0.13f), s * 0.05f, StrokeCap.Round)
                }
                if (c.thunder) bolt(s)
            }
        }
    }
}

/** Sonne mit Strahlen oder Mondsichel. */
private fun DrawScope.body(center: Offset, r: Float, night: Boolean) {
    if (night) {
        val full = Path().apply { addOval(Rect(center, r)) }
        val cut = Path().apply { addOval(Rect(center + Offset(r * 0.55f, -r * 0.4f), r * 0.9f)) }
        drawPath(Path.combine(PathOperation.Difference, full, cut), Moon)
        return
    }
    drawCircle(Sun, r * 0.72f, center)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val from = center + Offset((cos(a) * r * 0.98f).toFloat(), (sin(a) * r * 0.98f).toFloat())
        val to = center + Offset((cos(a) * r * 1.32f).toFloat(), (sin(a) * r * 1.32f).toFloat())
        drawLine(Sun, from, to, r * 0.18f, StrokeCap.Round)
    }
}

/** Wolke aus drei Kreisen und einem abgerundeten Sockel; [w] = Breite. */
private fun DrawScope.cloud(center: Offset, w: Float, color: Color) {
    val h = w * 0.34f
    val base = RoundRect(Rect(center.x - w / 2, center.y - h / 2 + h * 0.1f, center.x + w / 2, center.y + h / 2 + h * 0.1f), CornerRadius(h / 2))
    val path = Path().apply {
        addRoundRect(base)
        addOval(Rect(Offset(center.x - w * 0.2f, center.y - h * 0.15f), w * 0.2f))
        addOval(Rect(Offset(center.x + w * 0.1f, center.y - h * 0.35f), w * 0.26f))
    }
    drawPath(path, color)
}

private fun DrawScope.bolt(s: Float) {
    val p = Path().apply {
        moveTo(s * 0.54f, s * 0.58f); lineTo(s * 0.4f, s * 0.8f); lineTo(s * 0.5f, s * 0.8f)
        lineTo(s * 0.44f, s * 0.98f); lineTo(s * 0.64f, s * 0.72f); lineTo(s * 0.53f, s * 0.72f); close()
    }
    drawPath(p, Bolt)
}
