package app.raum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.Device
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.NetworkTransport
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.find
import app.raum.history.HistoryMath
import app.raum.history.SensorHistorySource
import app.raum.history.SensorMetric
import app.raum.ui.theme.LocalGlass
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Hat das Gerät etwas, das sich als Verlauf zeigen lässt? */
fun hasHistory(device: Device): Boolean {
    val c = device.capabilities
    return c.find<TemperatureSensorCapability>() != null || c.find<HumiditySensorCapability>() != null ||
        c.find<ContactSensorCapability>() != null || c.find<OccupancyCapability>() != null
}

private enum class Range(val step: Duration, val count: Int) {
    DAY(Duration.ofMinutes(30), 49),
    WEEK(Duration.ofHours(2), 84),
}

private val TempColor = Color(0xFFE0703F)
private val TempColorDark = Color(0xFFFF9D6B)
private val HumColor = Color(0xFF2F78C4)
private val HumColorDark = Color(0xFF9FD3FF)

/** Verlauf eines Sensors: Messkurve (Temperatur/Feuchte) oder Zeitleiste (offen/belegt). */
@Composable
fun SensorHistoryView(device: Device, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = device.capabilities
    val lineMetrics = listOfNotNull(
        SensorMetric.TEMPERATURE.takeIf { c.find<TemperatureSensorCapability>() != null || c.find<ThermostatCapability>() != null },
        SensorMetric.HUMIDITY.takeIf { c.find<HumiditySensorCapability>() != null },
    )
    // Kompakt (Thermostat-Fenster): nur die Temperaturkurve mit Zeitraum, ohne Messwert-Chips und Fußzeile
    if (compact) {
        if (lineMetrics.isNotEmpty()) LineHistory(device, lineMetrics.take(1), modifier, compact = true)
        return
    }
    Column(modifier) {
        when {
            lineMetrics.isNotEmpty() -> LineHistory(device, lineMetrics, Modifier.weight(1f))
            c.find<ContactSensorCapability>() != null -> EventHistory(device, SensorMetric.CONTACT, Modifier.weight(1f))
            c.find<OccupancyCapability>() != null -> EventHistory(device, SensorMetric.OCCUPANCY, Modifier.weight(1f))
        }
        SensorFooter(device)
    }
}

/** Neu laden, sobald ein neuer Messwert fällig ist. */
@Composable
private fun rememberRefreshTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    return tick
}

// --- Messkurve -------------------------------------------------------------------------------

private data class Series(val from: Instant, val values: List<Double?>)

@Composable
private fun LineHistory(device: Device, metrics: List<SensorMetric>, modifier: Modifier, compact: Boolean = false) {
    val g = LocalGlass.current
    val source = koinInject<SensorHistorySource>()
    var metric by remember(device.id) { mutableStateOf(metrics.first()) }
    var range by remember(device.id) { mutableStateOf(Range.DAY) }
    var series by remember(device.id, metric, range) { mutableStateOf<Series?>(null) }
    val tick = rememberRefreshTick()
    LaunchedEffect(device.id, metric, range, tick) {
        val stepMs = range.step.toMillis()
        val now = Instant.now()
        val from = Instant.ofEpochMilli(now.toEpochMilli() / stepMs * stepMs).minus(range.step.multipliedBy((range.count - 1).toLong()))
        val samples = source.samples(device.id, metric, from, now)
        series = Series(from, HistoryMath.buckets(samples, from, range.step, range.count))
    }
    val isTemp = metric == SensorMetric.TEMPERATURE
    val color = if (isTemp) (if (g.dark) TempColorDark else TempColor) else (if (g.dark) HumColorDark else HumColor)

    if (compact) {
        Column(modifier) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.history_title).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp, modifier = Modifier.weight(1f),
                )
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(g.chip).padding(3.dp)) {
                    Range.entries.forEach { r ->
                        val on = r == range
                        Box(
                            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) g.ink else Color.Transparent)
                                .clickable { range = r }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(if (r == Range.DAY) R.string.range_24h else R.string.range_7d),
                                color = if (on) g.glassSolid.copy(alpha = 1f) else g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            val s = series
            if (s != null && s.values.count { it != null } >= 2) {
                LineChart(s, range, isTemp, color, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp))
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (s != null) Text(stringResource(R.string.history_empty), fontSize = 16.sp, color = g.soft)
                }
            }
        }
        return
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.forEach { m ->
                val value = currentValue(device, m)
                val mColor = if (m == SensorMetric.TEMPERATURE) (if (g.dark) TempColorDark else TempColor) else (if (g.dark) HumColorDark else HumColor)
                MetricChip(
                    label = stringResource(if (m == SensorMetric.TEMPERATURE) R.string.metric_temperature else R.string.metric_humidity),
                    value = value, dot = mColor, selected = m == metric,
                    onClick = if (metrics.size > 1) ({ metric = m }) else null,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(28.dp)).background(g.chip).padding(4.dp)) {
                Range.entries.forEach { r ->
                    val on = r == range
                    Box(
                        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (on) g.ink else Color.Transparent)
                            .clickable { range = r }.padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(if (r == Range.DAY) R.string.range_24h else R.string.range_7d),
                            color = if (on) g.glassSolid.copy(alpha = 1f) else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val s = series
        if (s != null && s.values.count { it != null } >= 2) {
            LineChart(s, range, isTemp, color, Modifier.fillMaxWidth().padding(top = 18.dp).height(256.dp))
        } else if (s != null) {
            Column(Modifier.fillMaxWidth().height(256.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.history_empty), fontSize = 22.sp, color = g.ink)
                Text(stringResource(R.string.history_empty_text), fontSize = 15.sp, color = g.soft, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            Spacer(Modifier.height(256.dp))
        }
    }
}

@Composable
private fun currentValue(device: Device, m: SensorMetric): String {
    // Offline: letzter bekannter Wert (wie auf der Karte)
    val c = device.capabilities
    return when (m) {
        SensorMetric.TEMPERATURE -> (c.find<TemperatureSensorCapability>()?.celsius ?: c.find<ThermostatCapability>()?.currentCelsius)?.let { formatDegrees(it) } ?: "–"
        SensorMetric.HUMIDITY -> c.find<HumiditySensorCapability>()?.let { formatPercent(it.percent) } ?: "–"
        else -> "–"
    }
}

@Composable
private fun MetricChip(label: String, value: String, dot: Color, selected: Boolean, onClick: (() -> Unit)?) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.widthIn(min = 150.dp).clip(shape).background(if (selected) g.glassSolid else g.chip)
            .then(if (selected) Modifier.border(2.dp, dot, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, color = g.soft, fontSize = 13.sp, maxLines = 1)
        }
        Text(value, color = g.ink, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Liniendiagramm mit Fläche, Raster, Min/Max, aktuellem Punkt und Wert unter dem Finger. */
@Composable
private fun LineChart(s: Series, range: Range, isTemp: Boolean, color: Color, modifier: Modifier) {
    val g = LocalGlass.current
    val measurer = rememberTextMeasurer()
    val zone = ZoneId.systemDefault()
    val locale = currentLocale()
    var scrub by remember(s) { mutableStateOf<Int?>(null) }
    val values = s.values
    val present = values.filterNotNull()
    val tStep = if (isTemp) (if (present.max() - present.min() > 3) 1.0 else 0.5) else 5.0
    var lo = present.min()
    var hi = present.max()
    if (!isTemp) { lo = minOf(lo, 38.0); hi = maxOf(hi, 62.0) }
    lo = floor(lo / tStep) * tStep
    hi = ceil(hi / tStep) * tStep
    if (hi - lo < tStep * 2) hi = lo + tStep * 2
    val unitTemp = LocalTemperatureUnit.current
    val fmt: (Double) -> String = { v ->
        if (isTemp) String.format(locale, "%.1f°", Units.toDisplay(v, unitTemp)) else String.format(locale, "%.0f %%", v)
    }
    val tick: (Double) -> String = { v ->
        if (isTemp) Units.toDisplay(v, unitTemp).let { if (abs(it - it.roundToInt()) < 0.01) "${it.roundToInt()}°" else String.format(locale, "%.1f°", it) }
        else "${v.roundToInt()}"
    }
    val labelStyle = TextStyle(color = g.soft, fontSize = 13.sp)
    val markStyle = TextStyle(color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val tipStyle = TextStyle(color = if (g.dark) Color(0xFF10131F) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val today = LocalDate.now()
    val todayLabel = stringResource(R.string.forecast_today)
    val band = stringResource(R.string.comfort_band)
    val n = values.size
    fun timeOf(i: Int): Instant = s.from.plus(range.step.multipliedBy(i.toLong()))

    Canvas(
        modifier.pointerInput(s) {
            val left = 44.dp.toPx()
            fun idx(x: Float) = (((x - left) / (size.width - left)) * (n - 1)).roundToInt().coerceIn(0, n - 1)
            detectDragGestures(
                onDragStart = { scrub = idx(it.x) }, onDragEnd = { scrub = null }, onDragCancel = { scrub = null },
            ) { ch, _ -> ch.consume(); scrub = idx(ch.position.x) }
        }.pointerInput(s) {
            val left = 44.dp.toPx()
            detectTapGestures(onPress = { p ->
                scrub = (((p.x - left) / (size.width - left)) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                tryAwaitRelease(); scrub = null
            })
        },
    ) {
        val left = 44.dp.toPx()
        val w = size.width - left
        // Unten Platz für die Zeitachse; im kompakten Verlauf (Thermostat) entsprechend niedriger
        val h = (size.height - 32.dp.toPx()).coerceIn(60.dp.toPx(), 220.dp.toPx())
        fun x(i: Int) = left + i / (n - 1f) * w
        fun y(v: Double) = ((1 - (v - lo) / (hi - lo)) * h).toFloat()

        // Wohlfühlbereich (Luftfeuchte 40–60 %)
        if (!isTemp) {
            drawRect(g.chip, Offset(left, y(60.0)), Size(w, y(40.0) - y(60.0)))
            drawText(measurer, band, Offset(left + 8.dp.toPx(), y(60.0) + 4.dp.toPx()), TextStyle(color = g.soft, fontSize = 12.sp))
        }
        // Raster
        val lines = ((hi - lo) / tStep).roundToInt()
        val every = if (lines > 6) 2 else 1
        for (k in 0..lines step every) {
            val v = lo + k * tStep
            val yy = y(v)
            drawLine(g.track, Offset(left, yy), Offset(size.width, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = measurer.measure(tick(v), labelStyle)
            drawText(t, topLeft = Offset(36.dp.toPx() - t.size.width, yy - t.size.height / 2f))
        }
        // Zeitachse
        val axisY = h + 12.dp.toPx()
        if (range == Range.DAY) {
            for (i in 0 until n) {
                val t = timeOf(i).atZone(zone)
                if (t.minute == 0 && t.hour % 6 == 0) {
                    val l = measurer.measure(t.format(HM), labelStyle)
                    drawText(l, topLeft = Offset((x(i) - l.size.width / 2f).coerceIn(left, size.width - l.size.width), axisY))
                }
            }
        } else {
            for (i in 0 until n) {
                val t = timeOf(i).atZone(zone)
                if (t.hour in 12..13) {
                    val label = if (t.toLocalDate() == today) todayLabel else t.dayOfWeek.getDisplayName(JTextStyle.SHORT, locale).trimEnd('.')
                    val l = measurer.measure(label, labelStyle)
                    drawText(l, topLeft = Offset((x(i) - l.size.width / 2f).coerceIn(left, size.width - l.size.width), axisY))
                }
            }
        }
        // Kurve und Fläche – Lücken (keine Messung) unterbrechen die Linie
        clipRect(left, 0f, size.width, h) {
            var i = 0
            while (i < n) {
                if (values[i] == null) { i++; continue }
                val start = i
                while (i < n && values[i] != null) i++
                val seg = (start until i)
                val line = Path().apply { seg.forEachIndexed { k, j -> if (k == 0) moveTo(x(j), y(values[j]!!)) else lineTo(x(j), y(values[j]!!)) } }
                if (seg.count() > 1) {
                    val area = Path().apply { addPath(line); lineTo(x(seg.last), h); lineTo(x(seg.first), h); close() }
                    drawPath(area, color.copy(alpha = 0.16f))
                }
                drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Min und Max
        val last = values.indexOfLast { it != null }
        val iMin = values.indices.filter { values[it] != null }.minBy { values[it]!! }
        val iMax = values.indices.filter { values[it] != null }.maxBy { values[it]!! }
        listOf(iMin to true, iMax to false).filter { it.first != last && iMin != iMax }.forEach { (i, isMin) ->
            val p = Offset(x(i), y(values[i]!!))
            drawCircle(g.glassSolid.copy(alpha = 1f), 5.dp.toPx(), p)
            drawCircle(color, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            val l = measurer.measure(fmt(values[i]!!), markStyle)
            val ly = if (isMin) p.y + 10.dp.toPx() else p.y - 10.dp.toPx() - l.size.height
            drawText(l, topLeft = Offset((p.x - l.size.width / 2f).coerceIn(left, size.width - l.size.width), ly.coerceIn(0f, h)))
        }
        // Aktueller Wert
        if (last >= 0) {
            val p = Offset(x(last), y(values[last]!!))
            drawCircle(g.chip, 13.dp.toPx(), p)
            drawCircle(color, 7.dp.toPx(), p)
        }
        // Wert unter dem Finger
        scrub?.let { i ->
            val v = values[i] ?: return@let
            val p = Offset(x(i), y(v))
            drawLine(g.ink.copy(alpha = 0.5f), Offset(p.x, 0f), Offset(p.x, h), 2.dp.toPx())
            drawCircle(g.ink, 6.dp.toPx(), p)
            val t = timeOf(i).atZone(zone)
            val day = if (range == Range.WEEK) t.dayOfWeek.getDisplayName(JTextStyle.SHORT, locale).trimEnd('.') + " " else ""
            val l = measurer.measure("$day${t.format(HM)} · ${fmt(v)}", tipStyle)
            val bw = l.size.width + 24.dp.toPx()
            val bh = l.size.height + 12.dp.toPx()
            val bx = (p.x - bw / 2f).coerceIn(left, size.width - bw)
            val by = (p.y - bh - 14.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(g.ink, Offset(bx, by), Size(bw, bh), CornerRadius(14.dp.toPx()))
            drawText(l, topLeft = Offset(bx + 12.dp.toPx(), by + 6.dp.toPx()))
        }
    }
}

// --- Zeitleiste für offen / belegt -----------------------------------------------------------

private data class Events(
    val from: Instant,
    val now: Instant,
    val active: List<HistoryMath.Interval>,
    val offline: List<HistoryMath.Interval>,
)

@Composable
private fun EventHistory(device: Device, metric: SensorMetric, modifier: Modifier) {
    val g = LocalGlass.current
    val source = koinInject<SensorHistorySource>()
    val tick = rememberRefreshTick()
    var events by remember(device.id) { mutableStateOf<Events?>(null) }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(device.id, tick, device.isOnline, device.capabilities) {
        val now = Instant.now()
        val from = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val active = HistoryMath.intervals(source.samples(device.id, metric, from, now), source.lastBefore(device.id, metric, from), from, now)
        val online = HistoryMath.intervals(
            source.samples(device.id, SensorMetric.ONLINE, from, now), source.lastBefore(device.id, SensorMetric.ONLINE, from), from, now,
        )
        // Offline = Lücken zwischen den Online-Zeiträumen (nur wenn überhaupt aufgezeichnet)
        val offline = buildList {
            if (online.isEmpty()) return@buildList
            var cursor = from
            online.forEach { if (it.start > cursor) add(HistoryMath.Interval(cursor, it.start, false)); cursor = it.end }
            if (cursor < now) add(HistoryMath.Interval(cursor, now, true))
        }
        events = Events(from, now, active, offline)
    }
    val occ = metric == SensorMetric.OCCUPANCY
    val color = if (occ) (if (g.dark) Color(0xFFA8E6B8) else Color(0xFF2E8B4E)) else (if (g.dark) Color(0xFFFFC870) else Color(0xFFB7791F))
    val ev = events

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val nowState = when {
                !device.isOnline -> stringResource(R.string.state_offline)
                occ -> stringResource(if (device.capabilities.find<OccupancyCapability>()?.isOccupied == true) R.string.occupancy_occupied else R.string.occupancy_clear)
                else -> stringResource(if (device.capabilities.find<ContactSensorCapability>()?.isOpen == true) R.string.contact_open else R.string.contact_closed)
            }
            MetricChip(stringResource(R.string.ev_now), nowState, color, selected = true, onClick = null)
            if (ev != null) {
                val total = ev.active.fold(Duration.ZERO) { a, i -> a + i.duration }
                if (occ) {
                    MetricChip(stringResource(R.string.ev_occupied_today), durationText(total), g.track, false, null)
                } else {
                    MetricChip(stringResource(R.string.ev_opened_today), "${ev.active.size}×", g.track, false, null)
                    MetricChip(stringResource(R.string.ev_open_total), durationText(total), g.track, false, null)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (ev != null) {
            val span = Duration.between(ev.from, ev.now).toMillis().coerceAtLeast(1).toFloat()
            fun frac(t: Instant) = Duration.between(ev.from, t).toMillis() / span
            Canvas(Modifier.fillMaxWidth().padding(top = 18.dp).height(56.dp).clip(RoundedCornerShape(16.dp)).background(g.chip)) {
                ev.active.forEach { i ->
                    val x0 = frac(i.start) * size.width
                    val x1 = maxOf(frac(i.end) * size.width, x0 + 3.dp.toPx())
                    drawRoundRect(color.copy(alpha = 0.85f), Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(4.dp.toPx()))
                }
                ev.offline.forEach { i ->
                    val x0 = frac(i.start) * size.width
                    val x1 = frac(i.end) * size.width
                    clipRect(x0, 0f, x1, size.height) {
                        var s = x0 - size.height
                        while (s < x1) {
                            drawLine(g.track, Offset(s, size.height), Offset(s + size.height, 0f), 6.dp.toPx())
                            s += 12.dp.toPx()
                        }
                    }
                }
            }
            // Achse: volle Stunden 0/6/12/18 und „Jetzt“
            val nowLabel = stringResource(R.string.ev_now)
            val measurer = rememberTextMeasurer()
            val style = TextStyle(color = g.soft, fontSize = 13.sp)
            Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(22.dp)) {
                val labels = listOf(0, 6, 12, 18).map { h -> ev.from.atZone(zone).withHour(h).toInstant() }
                    .filter { it < ev.now && frac(it) < 0.9f }.map { it.atZone(zone).format(HM) to frac(it) } + (nowLabel to 1f)
                labels.forEach { (text, f) ->
                    val l = measurer.measure(text, style)
                    drawText(l, topLeft = Offset((f * size.width - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), 0f))
                }
            }
            // Letzte Ereignisse
            val recent = ev.active.takeLast(4).reversed()
            if (recent.isEmpty()) {
                Text(stringResource(R.string.ev_none_today), color = g.soft, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp))
            } else {
                Column(Modifier.padding(top = 14.dp)) {
                    recent.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            row.forEach { i -> EventRow(i, occ, color, zone, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(i: HistoryMath.Interval, occ: Boolean, color: Color, zone: ZoneId, modifier: Modifier) {
    val g = LocalGlass.current
    Column(modifier) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(i.start.atZone(zone).format(HM), color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
            Icon(if (occ) Icons.AutoMirrored.Outlined.DirectionsWalk else Icons.Outlined.Window, null, Modifier.size(20.dp), tint = color)
            Text(stringResource(if (occ) R.string.ev_presence else R.string.contact_open), color = g.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            val d = durationText(i.duration)
            Text(if (i.ongoing) stringResource(R.string.ev_since, d) else d, color = g.soft, fontSize = 14.sp)
        }
        HorizontalDivider(color = g.track)
    }
}

@Composable
private fun durationText(d: Duration): String {
    val min = d.toMinutes().coerceAtLeast(if (d.isZero) 0 else 1)
    return when {
        min < 60 -> stringResource(R.string.duration_min, min)
        min % 60 == 0L -> stringResource(R.string.duration_h, min / 60)
        else -> stringResource(R.string.duration_h_min, min / 60, min % 60)
    }
}

/** Verbindung und Batterie – bzw. seit wann das Gerät nicht erreichbar ist. */
@Composable
private fun SensorFooter(device: Device) {
    val g = LocalGlass.current
    val text = if (!device.isOnline) {
        stringResource(R.string.device_offline_since, formatAgo(device.lastSeenAt))
    } else listOfNotNull(
        device.network?.transport?.let {
            stringResource(when (it) { NetworkTransport.THREAD -> R.string.transport_thread; NetworkTransport.WIFI -> R.string.transport_wifi; NetworkTransport.ETHERNET -> R.string.transport_ethernet })
        },
        device.capabilities.find<BatteryCapability>()?.let { stringResource(R.string.battery_value, "${it.percent} %") },
    ).joinToString(" · ")
    if (text.isEmpty()) return
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (device.isOnline) Icons.Outlined.Sensors else Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = if (device.isOnline) g.soft else g.warn)
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (device.isOnline) g.soft else g.warn, fontSize = 14.sp)
    }
}
