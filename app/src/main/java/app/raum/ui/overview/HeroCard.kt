package app.raum.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.ui.graphics.lerp
import app.raum.data.weather.Intensity
import app.raum.data.weather.PrecipOutlook
import app.raum.data.weather.PrecipType
import app.raum.data.weather.SkyCover
import app.raum.data.weather.WeatherCondition
import app.raum.data.weather.WeatherNow
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.find
import app.raum.ui.HomeNotice
import app.raum.ui.HomeUiState
import app.raum.ui.NoticeKind
import app.raum.ui.components.formatPercent
import app.raum.ui.components.formatTemperature
import app.raum.ui.components.rememberLongDateFormatter
import app.raum.ui.lightsStatus
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val HeroText = Color.White
private val HeroTextSoft = Color.White.copy(alpha = 0.82f)
private val ARC_WIDTH = 260.dp
private val ARC_HEIGHT = 124.dp

/** Himmelsfarben je Tageszeit (oben links → unten rechts). */
private fun skyColors(phase: SkyPhase): List<Color> = when (phase) {
    SkyPhase.NIGHT -> listOf(Color(0xFF0B1026), Color(0xFF1B2555), Color(0xFF34396F))
    SkyPhase.DAWN -> listOf(Color(0xFF2B3566), Color(0xFFA85A7A), Color(0xFFEBA37E))
    SkyPhase.DAY -> listOf(Color(0xFF235C9E), Color(0xFF4C8BCB), Color(0xFF86B9E3))
    SkyPhase.DUSK -> listOf(Color(0xFF2E2552), Color(0xFF9C4A68), Color(0xFFE08A52))
}

/** Innenklima: Mittel aller erreichbaren Temperatur- und Feuchtesensoren. */
private data class Climate(val celsius: Double?, val humidity: Double?)

private fun climateOf(state: HomeUiState, outdoorSensorId: java.util.UUID?): Climate {
    val online = state.devices.filter { it.isOnline && it.id != outdoorSensorId }
    val temps = online.mapNotNull { d ->
        d.capabilities.find<TemperatureSensorCapability>()?.celsius ?: d.capabilities.find<ThermostatCapability>()?.currentCelsius
    }
    val hums = online.mapNotNull { it.capabilities.find<HumiditySensorCapability>()?.percent }
    return Climate(temps.takeIf { it.isNotEmpty() }?.average(), hums.takeIf { it.isNotEmpty() }?.average())
}

/**
 * Kopf der Übersicht: Himmel passend zum echten Sonnenstand, große Uhr, Begrüßung,
 * Innenklima, Sonnenbogen und Hinweise als Chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroCard(
    state: HomeUiState,
    now: ZonedDateTime,
    sunrise: ZonedDateTime?,
    sunset: ZonedDateTime?,
    modifier: Modifier = Modifier,
    weather: WeatherNow? = null,
    visual: WeatherVisual? = null,
    /** Außentemperatur: Matter-Außensensor, sonst Vorhersage */
    outdoorCelsius: Double? = null,
    /** Zeitpunkt des letzten Abrufs, wenn die Daten älter sind (sonst null) */
    staleSince: ZonedDateTime? = null,
    /** Außensensor aus dem Innenmittel herausrechnen */
    outdoorSensorId: java.util.UUID? = null,
    animate: Boolean = true,
    /** Tipp auf den Außenblock öffnet die Vorhersage */
    onOpenForecast: (() -> Unit)? = null,
) {
    val phase = Sky.phase(now, sunrise, sunset)
    val cover = visual?.cloudCover ?: 0f
    val target = overcast(skyColors(phase), phase, visual)
    val c0 by animateColorAsState(target[0], tween(1500), label = "sky0")
    val c1 by animateColorAsState(target[1], tween(1500), label = "sky1")
    val c2 by animateColorAsState(target[2], tween(1500), label = "sky2")
    val climate = climateOf(state, outdoorSensorId)
    val time = rememberAnimationClock(animate && (visual != null || phase == SkyPhase.NIGHT))

    Box(
        modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(36.dp))
            .background(Brush.linearGradient(listOf(c0, c1, c2))),
    ) {
        if (phase == SkyPhase.NIGHT) TwinklingStars(cover, time, Modifier.fillMaxSize())
        else Glow(Modifier.fillMaxSize(), phase, strength = 1f - cover * 0.8f)
        if (visual != null) WeatherLayer(visual, night = phase == SkyPhase.NIGHT, time = time, modifier = Modifier.fillMaxSize())
        // Abdunkler hinter Uhr und Temperaturen: weiße Schrift bleibt auch bei hellem, grauem Himmel lesbar
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.22f), 0.45f to Color.Transparent,
                    0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f),
                ),
            ),
        )

        Row(Modifier.fillMaxSize().padding(36.dp)) {
            // Links: Begrüßung, Uhr, Datum, Hinweise
            Column(Modifier.weight(1f).fillMaxSize()) {
                Text(
                    listOfNotNull(stringResource(Sky.greetingRes(now.hour)), state.home?.name).joinToString(" · "),
                    style = MaterialTheme.typography.titleLarge, color = HeroTextSoft,
                )
                Text(now.format(TimeFormat), color = HeroText, fontSize = 104.sp, fontWeight = FontWeight.Light, lineHeight = 108.sp)
                Text(now.format(rememberLongDateFormatter()), style = MaterialTheme.typography.titleLarge, color = HeroTextSoft)
                Spacer(Modifier.weight(1f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroChip(Icons.Outlined.Lightbulb, lightsStatus(state.lightsOn))
                    val notices = state.notices
                    weather?.outlook?.let { OutlookChip(it) }
                    if (notices.isEmpty()) HeroChip(Icons.Outlined.CheckCircle, stringResource(R.string.notice_all_ok))
                    notices.take(3).forEach { NoticeChip(it) }
                    if (notices.size > 3) HeroChip(null, pluralStringResource(R.plurals.notices_more, notices.size - 3, notices.size - 3))
                }
            }
            // Rechts: Außen (Wetter/Sensor), Innenklima und Sonnenbogen
            Column(Modifier.width(400.dp).fillMaxSize(), horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    if (outdoorCelsius != null || weather != null) {
                        Column(
                            Modifier.clip(RoundedCornerShape(16.dp)).then(if (onOpenForecast != null) Modifier.clickable(onClick = onOpenForecast) else Modifier),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text(stringResource(R.string.hero_outdoor), style = MaterialTheme.typography.titleMedium, color = HeroTextSoft)
                            Text(outdoorCelsius?.let { formatTemperature(it) } ?: "–", color = HeroText, fontSize = 52.sp, fontWeight = FontWeight.Light, lineHeight = 56.sp)
                            weather?.let { w ->
                                Text(conditionLabel(w.condition), style = MaterialTheme.typography.titleMedium, color = HeroTextSoft, maxLines = 1)
                                val range = if (w.todayMax != null && w.todayMin != null) "↑ ${formatTemperature(w.todayMax)}  ↓ ${formatTemperature(w.todayMin)}" else null
                                val stale = staleSince?.let { stringResource(R.string.hero_weather_stale, it.format(TimeFormat)) }
                                listOfNotNull(range, stale).takeIf { it.isNotEmpty() }?.let {
                                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = HeroTextSoft, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (climate.celsius != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stringResource(R.string.hero_indoor), style = MaterialTheme.typography.titleMedium, color = HeroTextSoft)
                            Text(formatTemperature(climate.celsius), color = HeroText, fontSize = 52.sp, fontWeight = FontWeight.Light, lineHeight = 56.sp)
                            climate.humidity?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.WaterDrop, null, Modifier.size(18.dp), tint = HeroTextSoft)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.hero_humidity, formatPercent(it)), style = MaterialTheme.typography.titleMedium, color = HeroTextSoft)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // mit Wetterblock etwas kleiner, damit die Sonnenzeiten nicht abgeschnitten werden
                if (sunrise != null && sunset != null) SunArc(now, sunrise, sunset, compact = weather != null || outdoorCelsius != null, cloudCover = cover)
                else Text(stringResource(R.string.hero_no_location), style = MaterialTheme.typography.bodyMedium, color = HeroTextSoft)
            }
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
    HeroChip(icon, text, warning = n.isWarning)
}

@Composable
private fun HeroChip(icon: ImageVector?, text: String, warning: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (warning) Color(0xFFFFC56B).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (warning) Color(0xFFFFD38F) else HeroText)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = HeroText, maxLines = 1)
    }
}

/** Sonnenbogen von Aufgang bis Untergang mit der Sonne an ihrer aktuellen Position. */
@Composable
private fun SunArc(now: ZonedDateTime, sunrise: ZonedDateTime, sunset: ZonedDateTime, compact: Boolean = false, cloudCover: Float = 0f) {
    val sunAlpha = 1f - cloudCover.coerceIn(0f, 1f) * 0.6f
    val arcWidth = if (compact) 220.dp else ARC_WIDTH
    val arcHeight = if (compact) 100.dp else ARC_HEIGHT
    val progress = Sky.sunProgress(now, sunrise, sunset)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(arcWidth).height(arcHeight)) {
            // Halbkreis muss vollständig in die Fläche passen (Platz für den Sonnenschein oben)
            val r = minOf(size.width / 2f - 12.dp.toPx(), size.height - 18.dp.toPx())
            val center = Offset(size.width / 2f, size.height - 4.dp.toPx())
            val arcSize = Size(r * 2, r * 2)
            val topLeft = Offset(center.x - r, center.y - r)
            // gesamter Bogen gestrichelt, zurückgelegter Teil durchgezogen
            drawArc(Color.White.copy(alpha = 0.35f), 180f, 180f, false, topLeft, arcSize,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f))))
            drawLine(Color.White.copy(alpha = 0.35f), Offset(center.x - r - 8.dp.toPx(), center.y), Offset(center.x + r + 8.dp.toPx(), center.y), 1.dp.toPx())
            if (progress != null) {
                drawArc(Color.White.copy(alpha = 0.9f), 180f, 180f * progress, false, topLeft, arcSize, style = Stroke(3.dp.toPx()))
                val angle = Math.toRadians(180.0 + 180.0 * progress)
                val sun = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
                drawCircle(Brush.radialGradient(listOf(Color(0x99FFE7A8).copy(alpha = 0.6f * sunAlpha * sunAlpha), Color.Transparent), sun, 34.dp.toPx()), 34.dp.toPx(), sun)
                drawCircle(Color(0xFFFFE08A).copy(alpha = sunAlpha), 11.dp.toPx(), sun)
            } else {
                // Nacht: Mondsichel über dem Bogen
                val moon = Offset(center.x, center.y - r * 0.55f)
                val full = Path().apply { addOval(Rect(moon, 13.dp.toPx())) }
                val cut = Path().apply { addOval(Rect(moon + Offset(7.dp.toPx(), -5.dp.toPx()), 12.dp.toPx())) }
                drawPath(Path.combine(PathOperation.Difference, full, cut), Color(0xFFF4F1E6))
            }
        }
        Row(Modifier.width(arcWidth)) {
            Text(stringResource(R.string.hero_sunrise, sunrise.format(TimeFormat)), style = MaterialTheme.typography.bodyMedium, color = HeroTextSoft)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.hero_sunset, sunset.format(TimeFormat)), style = MaterialTheme.typography.bodyMedium, color = HeroTextSoft)
        }
    }
}

@Composable
private fun Glow(modifier: Modifier, phase: SkyPhase, strength: Float) {
    val base = when (phase) {
        SkyPhase.DAY -> Color(0x33FFFFFF)
        else -> Color(0x40FFD3A1)
    }
    val color = base.copy(alpha = base.alpha * strength.coerceIn(0f, 1f))
    Canvas(modifier) {
        val c = Offset(size.width * 0.82f, size.height * 1.05f)
        drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), c, size.height * 1.1f), size.height * 1.1f, c)
    }
}

/** Bewölkung und Niederschlag trüben den Himmel ein (bis max. 80 %). */
private fun overcast(colors: List<Color>, phase: SkyPhase, v: WeatherVisual?): List<Color> {
    if (v == null) return colors
    val grey = when (phase) {
        SkyPhase.NIGHT -> Color(0xFF1C2029)
        SkyPhase.DAY -> Color(0xFF6F7C8A)
        else -> Color(0xFF5E5A66)
    }
    val wet = if (v.precip != PrecipType.NONE) 0.15f else 0f
    val f = (v.cloudCover * 0.55f + wet + (if (v.thunder) 0.1f else 0f) + (if (v.fog) 0.2f else 0f)).coerceAtMost(0.8f)
    return colors.map { lerp(it, grey, f) }
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
    HeroChip(if (o.type == PrecipType.SNOW) Icons.Outlined.AcUnit else Icons.Outlined.Umbrella, text)
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
