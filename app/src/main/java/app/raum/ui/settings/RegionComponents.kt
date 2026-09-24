package app.raum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.raum.R
import app.raum.automation.triggers.GeoLocation
import app.raum.automation.triggers.SunCalculator
import app.raum.data.geo.City
import app.raum.data.geo.CityIndex
import app.raum.domain.models.SunEvent
import app.raum.ui.components.currentLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** Häufige Zeitzonen; die aktuelle wird ergänzt, falls sie fehlt. */
private val ZONES = listOf(
    "Europe/Berlin", "Europe/Vienna", "Europe/Zurich", "Europe/Amsterdam", "Europe/Brussels", "Europe/Paris",
    "Europe/London", "Europe/Madrid", "Europe/Rome", "Europe/Warsaw", "UTC",
)

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

fun zoneLabel(id: String): String {
    if (id == "UTC") return "UTC"
    val offset = ZonedDateTime.now(ZoneId.of(id)).offset.id.let { if (it == "Z") "±00:00" else it }
    return "${id.substringAfterLast('/').replace('_', ' ')} (UTC$offset)"
}

/** Ausgeschriebener Name inkl. Sommerzeit, z. B. „Mitteleuropäische Sommerzeit“. */
fun zoneName(id: String, locale: Locale): String {
    val tz = TimeZone.getTimeZone(ZoneId.of(id))
    return tz.getDisplayName(tz.inDaylightTime(java.util.Date()), TimeZone.LONG, locale)
}

/** „Zürich · Zurich, Schweiz“ – Land in der App-Sprache. */
fun cityDetail(city: City, locale: Locale): String =
    listOf(city.region, Locale("", city.countryCode).getDisplayCountry(locale)).filter { it.isNotBlank() && it != city.name }.joinToString(", ")

/** Zeitzone wählen – nur als Geräteeigentümer änderbar, sonst gilt die Android-Einstellung. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeZonePicker(selected: String, canSet: Boolean, onSelect: (String) -> Unit) {
    val locale = currentLocale()
    if (canSet) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (ZONES + selected).distinct().forEach { id ->
                FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(zoneLabel(id)) }, modifier = Modifier.height(48.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(zoneName(selected, locale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("${zoneLabel(selected)} · ${zoneName(selected, locale)}", style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.onb_timezone_system), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun parse(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

/**
 * Standort über die Offline-Ortssuche (Spez. 7.8) – keine Ortung, kein Netz.
 * Vor der Eingabe werden die größten Orte der eingestellten Zeitzone vorgeschlagen.
 *
 * @param onUseZone wird angeboten, wenn der gewählte Ort in einer anderen Zeitzone liegt (null = nicht anbieten)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationPicker(
    location: GeoLocation?,
    locationName: String?,
    zone: String,
    onSelect: (GeoLocation, String?) -> Unit,
    onClear: (() -> Unit)?,
    onUseZone: ((String) -> Unit)? = null,
    index: CityIndex = koinInject(),
) {
    val locale = currentLocale()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<City>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<City>>(emptyList()) }
    var manual by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<City?>(null) }
    // Feld und Treffer gemeinsam über die Tastatur holen (Querformat: Tastatur > halbe Höhe)
    val searchArea = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, results) { if (focused) { delay(250); searchArea.bringIntoView() } }

    LaunchedEffect(zone) { suggestions = withContext(Dispatchers.Default) { runCatching { index.largestIn(zone) }.getOrDefault(emptyList()) } }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(200) // tippen abwarten
        results = withContext(Dispatchers.Default) { runCatching { index.search(query, zone, limit = 8) }.getOrDefault(emptyList()) }
    }

    fun choose(city: City) {
        picked = city
        onSelect(city.location, city.name)
        query = ""; focus.clearFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Aktueller Standort mit Sonnenzeiten
        if (location != null) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            locationName ?: "%.3f°, %.3f°".format(Locale.ROOT, location.latitude, location.longitude),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
                        val today = LocalDate.now(z)
                        val rise = SunCalculator.time(SunEvent.SUNRISE, today, location, z)
                        val set = SunCalculator.time(SunEvent.SUNSET, today, location, z)
                        Text(
                            stringResource(R.string.location_sun_today, rise?.let(HH_MM::format) ?: "–", set?.let(HH_MM::format) ?: "–"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (onClear != null) {
                        IconButton(onClick = { picked = null; onClear() }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.location_remove), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
        // Zeitzone passt nicht zum gewählten Ort?
        val p = picked
        if (p != null && onUseZone != null && p.timeZone != zone) {
            FilledTonalButton(onClick = { onUseZone(p.timeZone) }, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Outlined.Schedule, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.location_use_zone, zoneLabel(p.timeZone)))
            }
        }

        Column(Modifier.bringIntoViewRequester(searchArea), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            query, { query = it },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear)) } },
            placeholder = { Text(stringResource(R.string.location_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // „Suchen“ schließt die Tastatur – dann sind alle Treffer sichtbar
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )

        if (query.isBlank()) {
            if (suggestions.isNotEmpty()) {
                Text(stringResource(R.string.location_suggestions), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { c ->
                        FilterChip(selected = locationName == c.name && location == c.location, onClick = { choose(c) }, label = { Text(c.name) }, modifier = Modifier.height(48.dp))
                    }
                }
            }
        } else if (results.isEmpty()) {
            Text(stringResource(R.string.location_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column {
                    results.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().clickable { choose(c) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                Text(cityDetail(c, locale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        }

        // Rückfall: Koordinaten von Hand
        TextButton(onClick = { manual = !manual }) {
            Icon(Icons.Outlined.EditLocationAlt, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.location_manual))
        }
        if (manual) {
            var lat by remember(location) { mutableStateOf(location?.latitude?.toString() ?: "") }
            var lon by remember(location) { mutableStateOf(location?.longitude?.toString() ?: "") }
            val parsed = runCatching { GeoLocation(parse(lat)!!, parse(lon)!!) }.getOrNull()
            Row(Modifier.widthIn(max = 640.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.latitude)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(lon, { lon = it }, label = { Text(stringResource(R.string.longitude)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { parsed?.let { picked = null; onSelect(it, null); manual = false; focus.clearFocus() } },
                    enabled = parsed != null && parsed != location, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.action_apply)) }
            }
        }
    }
}
