package app.raum.ui.overview

import app.raum.data.weather.ForecastDays
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.automation.triggers.SunCalculator
import app.raum.data.preferences.SettingsStore
import app.raum.domain.models.Scene
import app.raum.domain.models.SunEvent
import app.raum.ui.HomeUiState
import app.raum.ui.components.DeviceCard
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.SceneTile
import app.raum.ui.components.SectionHeader
import app.raum.ui.components.rememberNow
import app.raum.ui.rooms.RoomTile
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.Duration
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import app.raum.data.weather.WeatherNow
import app.raum.data.weather.WeatherService
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.find
import app.raum.platform.display.DisplayController
import app.raum.platform.display.DisplayMode
import java.util.UUID

/** Startseite (Spez. 9.4): Hero mit Uhr, Himmel, Klima und Hinweisen – darunter Szenen, Favoriten, Räume. */
@Composable
fun OverviewScreen(
    state: HomeUiState,
    cardActions: DeviceCardActions,
    onRunScene: (Scene) -> Unit,
    onOpenRoom: (UUID) -> Unit,
    settings: SettingsStore = koinInject(),
    weatherService: WeatherService = koinInject(),
    display: DisplayController = koinInject(),
) {
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

    // 7-Tage-Vorhersage (nur echte Daten, nicht in der Vorschau)
    val days = remember(weatherState.forecast, now.toLocalDate()) {
        weatherState.forecast?.let { ForecastDays.from(it, now.toInstant(), zone) }.orEmpty()
    }.takeIf { weatherEnabled && fresh }.orEmpty()
    var forecastDay by remember { mutableStateOf<Int?>(null) }
    val placeName by settings.locationName.collectAsStateWithLifecycle()

    // Animationen nur bei aktivem Display und wenn Android-Animationen nicht abgeschaltet sind
    val displayMode by display.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 240.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 32.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
            HeroCard(
                state, now, sunrise, sunset,
                weather = weather,
                visual = weather?.let(::visualOf),
                outdoorCelsius = outdoorSensor ?: weather?.temperature,
                staleSince = stale,
                outdoorSensorId = outdoorId,
                animate = displayMode == DisplayMode.ACTIVE && !reducedMotion,
                onOpenForecast = if (days.isNotEmpty()) ({ forecastDay = 0 }) else null,
            )
        }
        if (days.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "forecast") {
                Column {
                    SectionHeader(stringResource(R.string.forecast_title), Modifier.padding(top = 8.dp))
                    ForecastRow(days) { forecastDay = it }
                }
            }
        }

        if (state.scenes.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "scenes") {
                Column {
                    SectionHeader(stringResource(R.string.nav_scenes), Modifier.padding(top = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.scenes, key = { it.id }) { scene ->
                            SceneTile(
                                scene = scene,
                                running = state.runningSceneId == scene.id,
                                enabled = state.runningSceneId == null,
                                onRun = { onRunScene(scene) },
                                modifier = Modifier.widthIn(min = 200.dp),
                            )
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "fav-header") { SectionHeader(stringResource(R.string.favorites), Modifier.padding(top = 8.dp)) }
        if (state.favorites.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "fav-empty") {
                Text(stringResource(R.string.favorites_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.favorites, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                roomName = state.roomName(device.roomId),
                onToggle = cardActions.onToggle,
                onCommand = cardActions.onCommand,
                onOpenDetails = cardActions.onOpenDetails,
            )
        }
        if (state.rooms.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "rooms-header") { SectionHeader(stringResource(R.string.nav_rooms), Modifier.padding(top = 8.dp)) }
            items(state.rooms, key = { "room-${it.room.id}" }) { summary ->
                RoomTile(summary, onClick = { onOpenRoom(summary.room.id) })
            }
        }
    }
    forecastDay?.let { start ->
        ForecastDialog(
            days = days, start = start, place = placeName,
            updated = weatherState.fetchedAt?.atZone(zone)?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            onDismiss = { forecastDay = null },
        )
    }
}

