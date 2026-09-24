package app.raum.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.raum.R
import app.raum.data.weather.DayForecast
import app.raum.data.weather.WeatherCondition
import app.raum.ui.components.RaumDialog
import app.raum.ui.components.WeatherIcon
import app.raum.ui.components.currentLocale
import app.raum.ui.components.formatTemperature
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val CELL = 68.dp

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate, locale: Locale): String = when (date) {
    today -> stringResource(R.string.forecast_today)
    today.plusDays(1) -> stringResource(R.string.forecast_tomorrow)
    else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth}."
}

private fun mm(v: Double, locale: Locale) = String.format(locale, if (v < 10) "%.1f mm" else "%.0f mm", v)

/** 7-Tage-Leiste unter dem Hero. */
@Composable
fun ForecastRow(days: List<DayForecast>, onOpen: (Int) -> Unit) {
    val locale = currentLocale()
    val today = LocalDate.now()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(days, key = { _, d -> d.date.toString() }) { i, d ->
            Surface(onClick = { onOpen(i) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.width(140.dp)) {
                Column(Modifier.padding(vertical = 14.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(d.date, today, locale), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    WeatherIcon(d.symbol, Modifier.size(52.dp).padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(d.max?.let { formatTemperature(it) } ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(d.min?.let { formatTemperature(it) } ?: "–", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PrecipLine(d.precipitation, locale)
                }
            }
        }
    }
}

@Composable
private fun PrecipLine(v: Double, locale: Locale) {
    Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (v >= 0.5) {
            Icon(Icons.Outlined.WaterDrop, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(mm(v, locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Detail: Tage als Reiter, Stundenwerte mit Temperaturkurve. */
@Composable
fun ForecastDialog(days: List<DayForecast>, start: Int, place: String?, updated: String?, onDismiss: () -> Unit) {
    if (days.isEmpty()) return
    val locale = currentLocale()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    var selected by remember { mutableIntStateOf(start.coerceIn(0, days.lastIndex)) }
    val day = days[selected]

    RaumDialog(
        title = listOfNotNull(stringResource(R.string.forecast_title), place).joinToString(" · "),
        onDismiss = onDismiss,
        width = 1040.dp,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { i, d ->
                    FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text(dayLabel(d.date, today, locale)) }, modifier = Modifier.height(48.dp))
                }
            }
            // Zusammenfassung des Tages
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(day.symbol, Modifier.size(64.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        day.symbol?.let { conditionLabel(WeatherCondition.fromSymbol(it)) } ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        listOfNotNull(
                            day.max?.let { "↑ ${formatTemperature(it)}" }, day.min?.let { "↓ ${formatTemperature(it)}" },
                            if (day.precipitation >= 0.1) mm(day.precipitation, locale) else stringResource(R.string.forecast_dry),
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Stunden (ab Tag 3 in 6-Stunden-Schritten) mit Temperaturkurve
            val hours = day.hours
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                TemperatureCurve(hours.map { it.temperature })
                Row {
                    hours.forEach { h ->
                        Column(Modifier.width(CELL), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.temperature?.let { formatTemperature(it) } ?: "–", style = MaterialTheme.typography.titleSmall)
                            WeatherIcon(h.symbol, Modifier.size(44.dp).padding(vertical = 2.dp))
                            Text(h.time.atZone(zone).format(HOUR), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val p = (h.precipitation ?: 0.0) * h.periodHours
                            Text(
                                if (p >= 0.1) mm(p, locale) else " ",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center, maxLines = 1,
                            )
                        }
                    }
                }
            }
            updated?.let {
                Text(stringResource(R.string.forecast_source, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TemperatureCurve(temps: List<Double?>) {
    val line = MaterialTheme.colorScheme.primary
    val values = temps.filterNotNull()
    if (values.size < 2) return
    val lo = values.min(); val hi = values.max()
    Canvas(Modifier.width(CELL * temps.size).height(56.dp)) {
        val cell = size.width / temps.size
        val range = (hi - lo).takeIf { it > 0.5 } ?: 1.0
        fun y(t: Double) = (size.height - 8.dp.toPx()) - ((t - lo) / range).toFloat() * (size.height - 16.dp.toPx())
        val path = Path()
        var started = false
        temps.forEachIndexed { i, t ->
            t ?: return@forEachIndexed
            val p = Offset(cell * (i + 0.5f), y(t))
            if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
        }
        drawPath(path, line, style = Stroke(3.dp.toPx()))
        temps.forEachIndexed { i, t -> t?.let { drawCircle(line, 4.dp.toPx(), Offset(cell * (i + 0.5f), y(it))) } }
    }
}
