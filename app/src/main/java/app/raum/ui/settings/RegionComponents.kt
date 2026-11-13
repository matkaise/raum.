package app.raum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.raum.ui.theme.LocalGlass
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
import app.raum.data.geo.PlaceSearch
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

/** Ort der Zeitzone in der App-Sprache („Zürich“ statt „Zurich“), sonst der Teil der Kennung. */
fun zoneCity(id: String, locale: Locale): String =
    runCatching { android.icu.text.TimeZoneNames.getInstance(locale).getExemplarLocationName(id) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: id.substringAfterLast('/').replace('_', ' ')

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
 * Standort über die Ortssuche (Spez. 7.8) – keine Ortung. Gesucht wird online (nur der Suchbegriff), ohne Netz in
 * der eingebauten Ortsliste. Vor der Eingabe werden die größten Orte der eingestellten Zeitzone vorgeschlagen.
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
    places: PlaceSearch = koinInject(),
) {
    val g = LocalGlass.current
    val locale = currentLocale()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<City>>(emptyList()) }
    var online by remember { mutableStateOf<Boolean?>(null) }
    var searching by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<City>>(emptyList()) }
    var manual by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<City?>(null) }
    // Feld und Treffer gemeinsam über die Tastatur holen (Querformat: Tastatur > halbe Höhe)
    val searchArea = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, results) { if (focused) { delay(250); searchArea.bringIntoView() } }

    LaunchedEffect(zone) { suggestions = withContext(Dispatchers.Default) { runCatching { index.largestIn(zone) }.getOrDefault(emptyList()) } }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); online = null; return@LaunchedEffect }
        delay(350) // tippen abwarten – online nicht bei jedem Buchstaben fragen
        searching = true
        val r = places.search(query, zone, locale.language)
        results = r.cities; online = r.online; searching = false
    }

    fun choose(city: City) {
        picked = city
        onSelect(city.location, city.name)
        query = ""; focus.clearFocus()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Aktueller Standort mit Sonnenzeiten
        if (location != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFFFE1A3)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val ink = Color(0xFF2E1D05)
                Icon(Icons.Outlined.Place, null, tint = ink)
                Column(Modifier.weight(1f)) {
                    Text(
                        locationName ?: "%.3f°, %.3f°".format(Locale.ROOT, location.latitude, location.longitude),
                        color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    picked?.let { p -> cityDetail(p, locale).takeIf { it.isNotBlank() }?.let { Text(it, color = ink.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1) } }
                }
                val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
                val today = LocalDate.now(z)
                SunChip(Icons.Outlined.WbSunny, SunCalculator.time(SunEvent.SUNRISE, today, location, z)?.let(HH_MM::format) ?: "–", stringResource(R.string.sunrise))
                SunChip(Icons.Outlined.WbTwilight, SunCalculator.time(SunEvent.SUNSET, today, location, z)?.let(HH_MM::format) ?: "–", stringResource(R.string.sunset))
                if (onClear != null) {
                    IconButton(onClick = { picked = null; onClear() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.location_remove), tint = ink)
                    }
                }
            }
        }
        // Zeitzone passt nicht zum gewählten Ort?
        val p = picked
        if (p != null && onUseZone != null && p.timeZone != zone) {
            ActionPill(stringResource(R.string.location_use_zone, zoneLabel(p.timeZone)), Icons.Outlined.Schedule) { onUseZone(p.timeZone) }
        }

        Column(Modifier.bringIntoViewRequester(searchArea), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillField(
                query, { query = it }, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                placeholder = stringResource(R.string.location_search_hint),
                leading = { Icon(Icons.Outlined.Search, null, tint = g.ink) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = g.ink, strokeWidth = 2.dp)
                        online?.let { SourceTag(it) }
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear), tint = g.ink) }
                    }
                },
            )

            if (query.isBlank()) {
                if (suggestions.isNotEmpty()) {
                    Text(stringResource(R.string.location_suggestions), color = g.soft, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { c -> ChoicePill(c.name, locationName == c.name && location == c.location) { choose(c) } }
                    }
                }
            } else if (results.isEmpty() && !searching) {
                Text(stringResource(R.string.location_no_results), color = g.soft)
            } else {
                // Treffer zweispaltig: Name groß, Region und Land darunter
                results.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { c ->
                            Row(
                                Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(18.dp)).background(g.ink.copy(alpha = 0.05f))
                                    .clickable { choose(c) }.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Place, null, Modifier.size(20.dp), tint = g.soft)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(c.name, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(cityDetail(c, locale), color = g.soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // Rückfall: Koordinaten von Hand
        TextButton(onClick = { manual = !manual }) {
            Icon(Icons.Outlined.EditLocationAlt, null, tint = g.soft); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.location_manual), color = g.soft)
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
                ActionPill(stringResource(R.string.action_apply), primary = true, enabled = parsed != null && parsed != location) {
                    parsed?.let { picked = null; onSelect(it, null); manual = false; focus.clearFocus() }
                }
            }
        }
    }
}

/** Sonnenzeit als kleiner Chip im Standortbanner. */
@Composable
private fun SunChip(icon: androidx.compose.ui.graphics.vector.ImageVector, time: String, label: String) {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(Color.White.copy(alpha = 0.55f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint = Color(0xFF2E1D05))
        Spacer(Modifier.width(6.dp))
        Text(time, color = Color(0xFF2E1D05), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Woher die Treffer kommen: online oder aus der eingebauten Liste. */
@Composable
private fun SourceTag(online: Boolean) {
    val bg = if (online) Color(0xFFE9FBEF) else LocalGlass.current.ink.copy(alpha = 0.07f)
    val fg = if (online) Color(0xFF2F6B45) else LocalGlass.current.soft
    Row(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (online) Icons.Outlined.Language else Icons.Outlined.CloudOff, null, Modifier.size(14.dp), tint = fg)
        Spacer(Modifier.width(5.dp))
        Text(stringResource(if (online) R.string.location_online else R.string.location_offline_list), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
