package app.raum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.BuildConfig
import app.raum.R
import app.raum.data.weather.Intensity
import app.raum.data.weather.PrecipType
import app.raum.data.weather.SkyCover
import app.raum.data.weather.WeatherCondition
import app.raum.data.weather.WeatherNow
import app.raum.data.weather.labelRes
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.find
import app.raum.ui.HomeUiState
import app.raum.ui.components.formatTemperature
import app.raum.ui.overview.conditionLabel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/** Vorschau-Lagen für den Debug-Build (Animationen ohne passendes Wetter prüfen). */
private val PREVIEWS = listOf(
    R.string.weather_clear to WeatherCondition(SkyCover.CLEAR),
    R.string.weather_partly_cloudy to WeatherCondition(SkyCover.PARTLY_CLOUDY),
    R.string.weather_cloudy to WeatherCondition(SkyCover.CLOUDY),
    R.string.weather_rain_light to WeatherCondition(SkyCover.CLOUDY, PrecipType.RAIN, Intensity.LIGHT),
    R.string.weather_rain_heavy to WeatherCondition(SkyCover.CLOUDY, PrecipType.RAIN, Intensity.HEAVY),
    R.string.weather_sleet to WeatherCondition(SkyCover.CLOUDY, PrecipType.SLEET),
    R.string.weather_snow to WeatherCondition(SkyCover.CLOUDY, PrecipType.SNOW),
    R.string.weather_fog to WeatherCondition(SkyCover.FOG),
    R.string.weather_thunder to WeatherCondition(SkyCover.CLOUDY, PrecipType.RAIN, Intensity.HEAVY, thunder = true),
)

/** Kurzstatus für die Kategorienliste. */
@Composable
fun weatherSummary(vm: SettingsViewModel): String {
    val enabled by vm.weatherEnabled.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val st by vm.weatherState.collectAsStateWithLifecycle()
    val now = st.forecast?.let { WeatherNow.from(it, Instant.now(), ZoneId.systemDefault()) }
    return when {
        !enabled -> stringResource(R.string.weather_off_short)
        location == null -> stringResource(R.string.location_missing_short)
        now != null -> listOfNotNull(now.temperature?.let { formatTemperature(it) }, conditionLabel(now.condition)).joinToString(" · ")
        st.error != null -> stringResource(st.error!!.labelRes)
        else -> stringResource(R.string.weather_loading)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherSection(state: HomeUiState, vm: SettingsViewModel, onOpenRegion: () -> Unit) {
    val enabled by vm.weatherEnabled.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val st by vm.weatherState.collectAsStateWithLifecycle()
    val outdoor by vm.outdoorSensorId.collectAsStateWithLifecycle()
    val preview by vm.weatherPreview.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()

    Group {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.weather_show), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = vm::setWeatherEnabled)
        }
        Text(stringResource(R.string.weather_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled && location == null) {
            Text(stringResource(R.string.weather_needs_location), color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = onOpenRegion, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Outlined.Place, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.weather_set_location))
            }
        }
    }

    if (enabled && location != null) {
        Group(stringResource(R.string.weather_status)) {
            val now = st.forecast?.let { WeatherNow.from(it, Instant.now(), zone) }
            now?.let { w ->
                Text(
                    listOfNotNull(w.temperature?.let { formatTemperature(it) }, conditionLabel(w.condition)).joinToString(" · "),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            st.fetchedAt?.let {
                Text(stringResource(R.string.weather_fetched_at, it.atZone(zone).format(HH_MM)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            st.error?.let { Text(stringResource(R.string.weather_error, stringResource(it.labelRes)), color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { vm.refreshWeather() } }, enabled = !st.fetching, modifier = Modifier.height(48.dp)) {
                    if (st.fetching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.weather_refresh))
                }
            }
        }
    }

    // Außensensor – auch ohne Internet nutzbar
    val sensors = state.devices.filter { it.capabilities.find<TemperatureSensorCapability>() != null }
    Group(stringResource(R.string.weather_outdoor_sensor)) {
        Text(stringResource(R.string.weather_outdoor_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = outdoor == null, onClick = { vm.setOutdoorSensor(null) }, label = { Text(stringResource(R.string.weather_outdoor_none)) }, modifier = Modifier.height(48.dp))
            sensors.forEach { d ->
                FilterChip(
                    selected = outdoor == d.id, onClick = { vm.setOutdoorSensor(d.id) },
                    label = { Text(listOfNotNull(d.displayName, state.roomName(d.roomId)).joinToString(" · ")) },
                    modifier = Modifier.height(48.dp),
                )
            }
        }
        if (sensors.isEmpty()) Text(stringResource(R.string.weather_outdoor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Group(stringResource(R.string.weather_source)) {
        Text(stringResource(R.string.settings_license_met), style = MaterialTheme.typography.bodyLarge)
    }

    if (BuildConfig.DEBUG) {
        Group(stringResource(R.string.weather_preview)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = preview == null, onClick = { vm.setWeatherPreview(null) }, label = { Text(stringResource(R.string.weather_preview_off)) }, modifier = Modifier.height(48.dp))
                PREVIEWS.forEach { (label, c) ->
                    FilterChip(selected = preview == c, onClick = { vm.setWeatherPreview(c) }, label = { Text(stringResource(label)) }, modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}
