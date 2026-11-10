package app.raum.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.data.weather.Intensity
import app.raum.data.weather.PrecipOutlook
import app.raum.data.weather.PrecipType
import app.raum.data.weather.SkyCover
import app.raum.data.weather.WeatherCondition
import app.raum.ui.HomeNotice
import app.raum.ui.HomeUiState
import app.raum.ui.NoticeKind
import app.raum.ui.components.WeatherIcon
import app.raum.ui.components.currentLocale
import app.raum.ui.components.formatPercent
import app.raum.ui.components.formatDegrees
import app.raum.ui.components.rememberLongDateFormatter
import app.raum.ui.lightsStatus
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.glass
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.cos
import kotlin.math.sin

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Kopf der Übersicht direkt auf dem Himmel: links Begrüßung, große Uhr, Datum und Hinweise,
 * rechts eine Glaskarte mit Außen-/Innenklima und Vorhersage, darunter der Sonnenbogen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeHero(state: HomeUiState, scene: SkyScene, onOpenForecast: ((Int) -> Unit)?) {
    val now = scene.now
    val climate = climateOf(state, scene.outdoorSensorId)
    Row(Modifier.fillMaxWidth().heightIn(min = 350.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, top = 20.dp)) {
            Text(
                listOfNotNull(stringResource(Sky.greetingRes(now.hour)), state.home?.name).joinToString(" · "),
                color = SkyTextSoft, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            )
            Text(now.format(TimeFormat), color = SkyText, fontSize = 148.sp, fontWeight = FontWeight.Light, lineHeight = 150.sp, letterSpacing = (-4).sp)
            Text(now.format(rememberLongDateFormatter()), color = SkyTextSoft, fontSize = 22.sp)
            Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkyChip(Icons.Outlined.Lightbulb, Accent.Light, lightsStatus(state.lightsOn))
                scene.weather?.outlook?.let { OutlookChip(it) }
                val notices = state.notices
                notices.take(2).forEach { NoticeChip(it) }
                if (notices.size > 2) SkyChip(null, SkyText, pluralStringResource(R.plurals.notices_more, notices.size - 2, notices.size - 2), warning = true)
            }
        }
        Column(Modifier.width(500.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ClimateCard(scene, climate, onOpenForecast)
            SunArc(scene)
        }
    }
}

@Composable
private fun ClimateCard(scene: SkyScene, climate: Climate, onOpenForecast: ((Int) -> Unit)?) {
    val g = LocalGlass.current
    val weather = scene.weather
    val hasOutdoor = weather != null || scene.outdoorCelsius != null
    if (!hasOutdoor && climate.celsius == null) return
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), g.glass, g.edge).padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (hasOutdoor) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .then(if (onOpenForecast != null) Modifier.clickable { onOpenForecast(0) } else Modifier),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (weather != null) WeatherIcon(weather.condition, night = scene.sunProgress == null, modifier = Modifier.size(22.dp))
                        else Icon(Icons.Outlined.Home, null, Modifier.size(20.dp), tint = g.soft)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(stringResource(R.string.hero_outdoor), scene.placeName).joinToString(" · "),
                            color = g.soft, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        scene.outdoorCelsius?.let { formatDegrees(it) } ?: "–",
                        color = g.ink, fontSize = 64.sp, fontWeight = FontWeight.Light, lineHeight = 66.sp, letterSpacing = (-2).sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    weather?.let { w ->
                        val range = if (w.todayMax != null && w.todayMin != null) "↑ ${formatDegrees(w.todayMax)} ↓ ${formatDegrees(w.todayMin)}" else null
                        val stale = scene.staleSince?.let { stringResource(R.string.hero_weather_stale, it.format(TimeFormat)) }
                        Text(
                            listOfNotNull(conditionLabel(w.condition), range, stale).joinToString(" · "),
                            color = g.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (climate.celsius != null) {
                Row(Modifier.weight(1.1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    climate.humidity?.let { HumidityRing(it) }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Home, null, Modifier.size(20.dp), tint = g.soft)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.hero_indoor), color = g.soft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            formatDegrees(climate.celsius), color = g.ink, fontSize = 48.sp, fontWeight = FontWeight.Light,
                            lineHeight = 52.sp, letterSpacing = (-1.5).sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        val next = scene.days.drop(1).take(6)
        if (next.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 16.dp), color = g.edge)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                val locale = currentLocale()
                next.forEachIndexed { i, d ->
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .then(if (onOpenForecast != null) Modifier.clickable { onOpenForecast(i + 1) } else Modifier)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.'), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        WeatherIcon(d.symbol, Modifier.padding(horizontal = 4.dp).size(22.dp))
                        Text(d.max?.let { formatDegrees(it, decimals = false) } ?: "–", color = g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HumidityRing(percent: Double) {
    val g = LocalGlass.current
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(88.dp)) {
            val stroke = 9.dp.toPx()
            val inset = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(g.track, 0f, 360f, false, inset, arc, style = Stroke(stroke))
            drawArc(g.cool, -90f, 360f * (percent / 100.0).toFloat().coerceIn(0f, 1f), false, inset, arc, style = Stroke(stroke))
        }
        Column(
            Modifier.size(70.dp).clip(CircleShape).background(g.glassSolid),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.WaterDrop, null, Modifier.size(18.dp), tint = g.cool)
            Text(formatPercent(percent), color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Sonnenbogen von Aufgang bis Untergang, Sonne an ihrer aktuellen Position – nachts der Mond. */
@Composable
private fun SunArc(scene: SkyScene) {
    val sunrise = scene.sunrise
    val sunset = scene.sunset
    if (sunrise == null || sunset == null) {
        Text(stringResource(R.string.hero_no_location), color = SkyTextSoft, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
        return
    }
    val progress = scene.sunProgress
    val sunAlpha = 1f - (scene.visual?.cloudCover ?: 0f).coerceIn(0f, 1f) * 0.6f
    Column(Modifier.width(300.dp).padding(end = 10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val r = 112.dp.toPx()
            val center = Offset(size.width / 2f, 126.dp.toPx())
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2, r * 2)
            drawArc(Color.White.copy(alpha = 0.4f), 180f, 180f, false, topLeft, arcSize,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
            drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
            if (progress != null) {
                drawArc(Color.White.copy(alpha = 0.92f), 180f, 180f * progress, false, topLeft, arcSize, style = Stroke(3.dp.toPx()))
                val angle = Math.toRadians(180.0 + 180.0 * progress)
                val sun = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE7A8).copy(alpha = 0.5f * sunAlpha), Color.Transparent), sun, 40.dp.toPx()), 40.dp.toPx(), sun)
                drawCircle(Color(0xFFFFE08A).copy(alpha = sunAlpha), 12.dp.toPx(), sun)
            } else {
                val moon = Offset(center.x, center.y - 62.dp.toPx())
                val full = Path().apply { addOval(Rect(moon, 13.dp.toPx())) }
                val cut = Path().apply { addOval(Rect(moon + Offset(8.dp.toPx(), -4.dp.toPx()), 12.dp.toPx())) }
                drawPath(Path.combine(PathOperation.Difference, full, cut), Color(0xFFF4F1E6))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(R.string.hero_sunrise, sunrise.format(TimeFormat)), color = SkyTextSoft, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.hero_sunset, sunset.format(TimeFormat)), color = SkyTextSoft, fontSize = 16.sp)
        }
    }
}

@Composable
private fun NoticeChip(n: HomeNotice) {
    val (icon, text) = when (n.kind) {
        NoticeKind.OFFLINE -> Icons.Outlined.CloudOff to stringResource(R.string.notice_offline, n.deviceName)
        NoticeKind.OPEN -> Icons.Outlined.DoorFront to stringResource(R.string.notice_open, n.deviceName)
        NoticeKind.LOW_BATTERY -> Icons.Outlined.BatteryAlert to stringResource(R.string.notice_low_battery, n.deviceName)
        NoticeKind.BORDER_ROUTER -> Icons.Outlined.Router to stringResource(R.string.notice_border_router)
    }
    SkyChip(icon, Accent.Warn, text, warning = true)
}

/** Glas-Chip direkt auf dem Himmel. */
@Composable
fun SkyChip(icon: ImageVector?, iconTint: Color, text: String, warning: Boolean = false, height: Int = 44) {
    Row(
        Modifier.height(height.dp).clip(RoundedCornerShape(50))
            .background(if (warning) Color(0xFFFFBE6E).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.16f))
            .padding(horizontal = if (height >= 44) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(if (height >= 44) 20.dp else 18.dp), tint = iconTint)
            Spacer(Modifier.width(if (height >= 44) 8.dp else 6.dp))
        }
        Text(text, color = SkyText, fontSize = if (height >= 44) 16.sp else 15.sp, maxLines = 1)
    }
}

@Composable
private fun OutlookChip(o: PrecipOutlook) {
    val what = stringResource(
        when (o.type) { PrecipType.SNOW -> R.string.precip_snow; PrecipType.SLEET -> R.string.precip_sleet; else -> R.string.precip_rain },
    )
    val zone = java.time.ZoneId.systemDefault()
    val text = when {
        o.raining && o.endsAt != null -> stringResource(R.string.outlook_until, what, o.endsAt.atZone(zone).format(TimeFormat))
        o.raining -> stringResource(R.string.outlook_ongoing, what)
        o.startsAt != null -> stringResource(R.string.outlook_from, what, o.startsAt.atZone(zone).format(TimeFormat))
        else -> return
    }
    SkyChip(if (o.type == PrecipType.SNOW) Icons.Outlined.AcUnit else Icons.Outlined.Umbrella, Color(0xFFBFE3FF), text)
}

@Composable
fun conditionLabel(c: WeatherCondition): String = stringResource(
    when {
        c.thunder -> R.string.weather_thunder
        c.precip == PrecipType.RAIN && c.showers -> R.string.weather_showers
        c.precip == PrecipType.RAIN -> when (c.intensity) { Intensity.LIGHT -> R.string.weather_rain_light; Intensity.HEAVY -> R.string.weather_rain_heavy; else -> R.string.weather_rain }
        c.precip == PrecipType.SLEET -> R.string.weather_sleet
        c.precip == PrecipType.SNOW -> when (c.intensity) { Intensity.LIGHT -> R.string.weather_snow_light; Intensity.HEAVY -> R.string.weather_snow_heavy; else -> R.string.weather_snow }
        c.sky == SkyCover.CLEAR -> R.string.weather_clear
        c.sky == SkyCover.FAIR -> R.string.weather_fair
        c.sky == SkyCover.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
        c.sky == SkyCover.FOG -> R.string.weather_fog
        else -> R.string.weather_cloudy
    },
)
