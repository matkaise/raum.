package app.raum.ui.overview

import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.automation.triggers.SunCalculator
import app.raum.data.preferences.SettingsStore
import app.raum.data.weather.DayForecast
import app.raum.data.weather.ForecastDays
import app.raum.data.weather.PrecipType
import app.raum.data.weather.WeatherNow
import app.raum.data.weather.WeatherService
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.SunEvent
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.find
import app.raum.platform.display.DisplayController
import app.raum.platform.display.DisplayMode
import app.raum.ui.HomeUiState
import app.raum.ui.components.rememberNow
import app.raum.ui.theme.LocalGlass
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** Alles, was Himmel und Übersicht über Zeit, Sonne und Wetter wissen müssen. */
@Immutable
data class SkyScene(
    val now: ZonedDateTime,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val phase: SkyPhase,
    /** 0 = Aufgang, 1 = Untergang; null = Sonne unter dem Horizont */
    val sunProgress: Float?,
    val weather: WeatherNow?,
    val visual: WeatherVisual?,
    /** Außentemperatur: Matter-Außensensor, sonst Vorhersage */
    val outdoorCelsius: Double?,
    /** Zeitpunkt des letzten Abrufs, wenn die Daten älter sind (sonst null) */
    val staleSince: ZonedDateTime?,
    val outdoorSensorId: UUID?,
    /** Vorhersage ab heute (nur echte, frische Daten) */
    val days: List<DayForecast>,
    val placeName: String?,
    val fetchedAt: Instant?,
    /** Animationen erlaubt: Display aktiv, Systemanimationen an */
    val motion: Boolean,
)

/** Himmel des aktuellen Bildschirms – null außerhalb von [RaumApp][app.raum.ui.RaumApp]. */
val LocalSkyScene = compositionLocalOf<SkyScene?> { null }

@Composable
fun rememberSkyScene(
    state: HomeUiState,
    settings: SettingsStore = koinInject(),
    weatherService: WeatherService = koinInject(),
    display: DisplayController = koinInject(),
): SkyScene {
    val nowLocal by rememberNow()
    val location by settings.location.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val now = nowLocal.atZone(zone)
    // Sonnenzeiten nur einmal je Tag und Standort berechnen
    val (sunrise, sunset) = remember(location, now.toLocalDate()) {
        location?.let { SunCalculator.time(SunEvent.SUNRISE, now.toLocalDate(), it, zone) to SunCalculator.time(SunEvent.SUNSET, now.toLocalDate(), it, zone) }
            ?: (null to null)
    }

    // Wetter (freiwillig, MET Norway) – aktuelle Stunde aus der Vorhersage, minütlich neu bewertet
    val weatherEnabled by settings.weatherEnabled.collectAsStateWithLifecycle()
    val weatherState by weatherService.state.collectAsStateWithLifecycle()
    val preview by weatherService.preview.collectAsStateWithLifecycle()
    val outdoorId by settings.outdoorSensorId.collectAsStateWithLifecycle()
    val placeName by settings.locationName.collectAsStateWithLifecycle()
    val minute = now.toInstant().epochSecond / 60
    val fetchedAt = weatherState.fetchedAt
    val fresh = fetchedAt != null && Duration.between(fetchedAt, now.toInstant()) < Duration.ofHours(48)
    val forecastNow = remember(weatherState.forecast, minute) {
        weatherState.forecast?.let { WeatherNow.from(it, now.toInstant(), zone) }
    }?.takeIf { weatherEnabled && fresh }
    val weather = preview?.let { previewWeather(it, now.toInstant()) } ?: forecastNow
    val outdoorSensor = outdoorId?.let { id -> state.devices.firstOrNull { it.id == id && it.isOnline } }
        ?.capabilities?.find<TemperatureSensorCapability>()?.celsius
    val stale = fetchedAt?.takeIf { weather != null && preview == null && Duration.between(it, now.toInstant()) > Duration.ofHours(3) }?.atZone(zone)
    val days = remember(weatherState.forecast, now.toLocalDate()) {
        weatherState.forecast?.let { ForecastDays.from(it, now.toInstant(), zone) }.orEmpty()
    }.takeIf { weatherEnabled && fresh }.orEmpty()

    // Animationen nur bei aktivem Display und wenn Android-Animationen nicht abgeschaltet sind
    val displayMode by display.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    return SkyScene(
        now = now, sunrise = sunrise, sunset = sunset,
        phase = Sky.phase(now, sunrise, sunset),
        sunProgress = Sky.sunProgress(now, sunrise, sunset),
        weather = weather, visual = weather?.let(::visualOf),
        outdoorCelsius = outdoorSensor ?: weather?.temperature,
        staleSince = stale, outdoorSensorId = outdoorId,
        days = days, placeName = placeName, fetchedAt = fetchedAt,
        motion = displayMode == DisplayMode.ACTIVE && !reducedMotion,
    )
}

/** Himmelsfarben je Tageszeit (oben links → unten rechts). */
internal fun skyColors(phase: SkyPhase): List<Color> = when (phase) {
    SkyPhase.NIGHT -> listOf(Color(0xFF0B1026), Color(0xFF1B2555), Color(0xFF34396F))
    SkyPhase.DAWN -> listOf(Color(0xFF2B3566), Color(0xFFA85A7A), Color(0xFFEBA37E))
    SkyPhase.DAY -> listOf(Color(0xFF235C9E), Color(0xFF4C8BCB), Color(0xFF86B9E3))
    SkyPhase.DUSK -> listOf(Color(0xFF2E2552), Color(0xFF9C4A68), Color(0xFFE08A52))
}

/**
 * Abdunkelung für Lesbarkeit: Bei Tag mit Wolken oder Nebel liegen helle Wolken hinter der weißen Schrift.
 * Klarer Himmel und Nacht bleiben (fast) unverändert.
 */
internal fun legibilityVeil(phase: SkyPhase, v: WeatherVisual?, dark: Boolean): Float {
    if (dark) return 0f
    val daylight = when (phase) { SkyPhase.DAY -> 1f; SkyPhase.DAWN, SkyPhase.DUSK -> 0.4f; SkyPhase.NIGHT -> 0f }
    val bright = 0.15f + (v?.let { it.cloudCover * 0.6f + (if (it.fog) 0.4f else 0f) } ?: 0f)
    return (daylight * bright * 0.24f).coerceIn(0f, 0.26f)
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

/**
 * Himmel als Hintergrund der ganzen App: Verlauf nach Sonnenstand und Wetter, Sonnenschein,
 * Sterne, Wetteranimation und ein heller Dunst am unteren Rand.
 */
@Composable
fun SkyBackdrop(scene: SkyScene, animate: Boolean, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    val cover = scene.visual?.cloudCover ?: 0f
    val target = overcast(skyColors(scene.phase), scene.phase, scene.visual)
        .map { if (glass.dark) lerp(it, Color.Black, 0.3f) else it }
    val c0 by animateColorAsState(target[0], tween(1500), label = "sky0")
    val c1 by animateColorAsState(target[1], tween(1500), label = "sky1")
    val c2 by animateColorAsState(target[2], tween(1500), label = "sky2")
    val sunUp = scene.sunProgress != null
    val time = rememberAnimationClock(
        animate && scene.motion && (scene.visual != null || !sunUp || scene.phase != SkyPhase.DAY),
        fast = scene.visual?.let { it.precip != PrecipType.NONE || it.thunder } == true,
    )

    Box(modifier.fillMaxSize()) {
        // Ruhende Teile (Verlauf, Sonnenschein, Dunst) als eigene Ebene: einmal gerendert, danach nur noch als fertiges
        // Bild eingesetzt – sonst rastert die GPU bei jedem Bild (Scrollen, Animation) mehrere bildschirmfüllende
        // Verläufe neu. Das kostete auf dem RK3576-Panel den Großteil der Bildzeit.
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c0, c1, c2))))
            // Sonnenschein folgt der Sonne über den Bildschirm
            scene.sunProgress?.let { p ->
                val strength = (1f - cover * 0.8f).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / 1280f
                    val c = Offset((700f + p * 500f) * sx, (420f - sin(p * PI).toFloat() * 300f) * sx)
                    val r = hypot(maxOf(c.x, size.width - c.x), maxOf(c.y, size.height - c.y)) * 0.55f
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFDCA0).copy(alpha = 0.32f * strength), Color.Transparent), c, r), r, c)
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, glass.haze))),
            )
        }
        // Bewegte Teile in eigener Ebene – ein Animationsschritt rendert nur sie neu
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            if (!sunUp || scene.phase != SkyPhase.DAY) {
                TwinklingStars(cover, time, Modifier.fillMaxSize().alpha(if (sunUp) 0.35f else 1f))
            }
            scene.visual?.let { WeatherLayer(it, night = !sunUp, time = time, modifier = Modifier.fillMaxSize()) }
        }
        // Heller Taghimmel (Nebel, Wolken): weiße Schrift und die Glasleiste verlieren den Kontrast – leicht abdunkeln
        val veil by animateFloatAsState(legibilityVeil(scene.phase, scene.visual, glass.dark), tween(1500), label = "veil")
        if (veil > 0.01f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil)))
    }
}

/** Innenklima: Mittel aller erreichbaren Temperatur- und Feuchtesensoren (ohne Außensensor). */
data class Climate(val celsius: Double?, val humidity: Double?)

fun climateOf(state: HomeUiState, outdoorSensorId: UUID?): Climate {
    val online = state.devices.filter { it.isOnline && it.id != outdoorSensorId }
    val temps = online.mapNotNull { d ->
        d.capabilities.find<TemperatureSensorCapability>()?.celsius ?: d.capabilities.find<ThermostatCapability>()?.currentCelsius
    }
    val hums = online.mapNotNull { it.capabilities.find<HumiditySensorCapability>()?.percent }
    return Climate(temps.takeIf { it.isNotEmpty() }?.average(), hums.takeIf { it.isNotEmpty() }?.average())
}
