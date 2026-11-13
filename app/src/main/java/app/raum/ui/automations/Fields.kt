package app.raum.ui.automations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.Device
import app.raum.domain.models.Weekdays
import app.raum.ui.components.FixedGrid
import app.raum.ui.theme.LocalGlass
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val DAYS = listOf(
    1 to R.string.day_mon, 2 to R.string.day_tue, 3 to R.string.day_wed, 4 to R.string.day_thu,
    5 to R.string.day_fri, 6 to R.string.day_sat, 7 to R.string.day_sun,
)

/** Violett der Uhrzeit-Schritte – auch für den 24-Stunden-Ring. */
private val Clock = Color(0xFF8C82D8)

/** Schrift auf dunkler Auswahl (Ink-Fläche) – im Dunkelmodus ist Ink hell. */
@Composable
internal fun onInk(): Color = if (LocalGlass.current.dark) Color(0xFF10131F) else Color.White

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = LocalGlass.current.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

/** Pille wie in den Detailfenstern: gewählt dunkel, sonst leicht getönt. */
@Composable
fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, outlined: Boolean = false, onClick: () -> Unit) {
    val g = LocalGlass.current
    val bg = when {
        selected && !outlined -> g.ink
        outlined && selected -> Color.Transparent
        else -> g.ink.copy(alpha = 0.07f)
    }
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg)
            .then(if (outlined && selected) Modifier.border(2.dp, g.ink, RoundedCornerShape(24.dp)) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected && !outlined) onInk() else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Wochentage als runde Tasten, darunter Schnellwahl; leere Menge = täglich. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekdayChips(selected: Weekdays, onChange: (Weekdays) -> Unit) {
    val g = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DAYS.forEach { (day, label) ->
                val on = selected.isEmpty() || day in selected
                Box(
                    Modifier.size(56.dp, 52.dp).clip(RoundedCornerShape(26.dp)).background(if (on) g.ink else g.ink.copy(alpha = 0.07f))
                        .clickable(role = Role.Checkbox) {
                            val base = selected.ifEmpty { (1..7).toSet() }
                            val next = if (day in base) base - day else base + day
                            // Alle 7 = täglich; keiner ausgewählt ist nicht sinnvoll → täglich
                            onChange(if (next.size == 7 || next.isEmpty()) emptySet() else next)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(label), color = if (on) onInk() else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.days_daily), selected.isEmpty(), outlined = true) { onChange(emptySet()) }
            Pill(stringResource(R.string.days_workdays), selected == (1..5).toSet(), outlined = true) { onChange((1..5).toSet()) }
            Pill(stringResource(R.string.days_weekend), selected == setOf(6, 7), outlined = true) { onChange(setOf(6, 7)) }
        }
    }
}

private fun norm(m: Int) = ((m % 1440) + 1440) % 1440

/**
 * 24-Stunden-Ring wie die Startzeit beim Geschirrspüler: 00 oben, im Uhrzeigersinn.
 * Ziehen oder Tippen auf den Ring setzt die Zeit (5-Minuten-Raster), −/+ darunter in 5-Minuten-Schritten.
 * Der farbige Bogen läuft von [arcStart] bis zum Knopf – beim Zeitfenster vom Anfang bis zum Ende.
 */
@Composable
fun ClockRing(
    minute: Int,
    arcStart: Int,
    arcEnd: Int,
    onChange: (Int) -> Unit,
    otherMarker: Int? = null,
    diameter: Dp = 330.dp,
    centerContent: @Composable () -> Unit,
) {
    val g = LocalGlass.current
    val ringW = 18.dp
    fun at(p: Offset, w: Int, h: Int): Int? {
        val dx = p.x - w / 2f
        val dy = p.y - h / 2f
        // Nur am Ring selbst – die Mitte gehört der Zeitanzeige und den Knöpfen
        if (hypot(dx, dy) < w * 0.32f) return null
        val deg = (Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())) + 360.0) % 360.0
        return norm(((deg / 360.0 * 1440.0) / 5.0).roundToInt() * 5)
    }
    Box(
        Modifier.size(diameter)
            .pointerInput(Unit) { detectTapGestures { p -> at(p, this.size.width, this.size.height)?.let(onChange) } }
            .pointerInput(Unit) {
                var active = false
                detectDragGestures(
                    onDragStart = { p -> active = at(p, this.size.width, this.size.height)?.also(onChange) != null },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, _ ->
                    if (active) { change.consume(); at(change.position, this.size.width, this.size.height)?.let(onChange) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = ringW.toPx()
            val r = this.size.minDimension / 2f - w / 2f - 10.dp.toPx()
            val tl = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            drawCircle(g.ink.copy(alpha = 0.08f), r, center, style = Stroke(w))
            val sweep = norm(arcEnd - arcStart) / 1440f * 360f
            if (sweep > 0f) drawArc(Clock, -90f + arcStart / 4f, sweep, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
            fun point(m: Int): Offset {
                val a = Math.toRadians((-90.0 + m / 4.0))
                return Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            }
            otherMarker?.let { drawCircle(Color.White, 7.dp.toPx(), point(it)); drawCircle(Clock, 7.dp.toPx(), point(it), style = Stroke(3.dp.toPx())) }
            val k = point(minute)
            drawCircle(Color.Black.copy(alpha = 0.16f), 17.dp.toPx(), k + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, 15.dp.toPx(), k)
            drawCircle(Clock, 15.dp.toPx(), k, style = Stroke(4.dp.toPx()))
            drawCircle(Color(0xFFF0EEFA).copy(alpha = if (g.dark) 0.16f else 1f), diameter.toPx() * 0.315f, center)
        }
        // Stundenmarken innen am Ring
        val labelR = diameter * 0.37f
        listOf("00" to (0.dp to -labelR), "06" to (labelR to 0.dp), "12" to (0.dp to labelR), "18" to (-labelR to 0.dp)).forEach { (t, xy) ->
            Text(t, color = g.ink.copy(alpha = 0.45f), fontSize = 13.sp, modifier = Modifier.offset(xy.first, xy.second))
        }
        Box(Modifier.offset(y = (-14).dp), contentAlignment = Alignment.Center) { centerContent() }
        listOf(Triple(Icons.Outlined.Remove, R.string.time_earlier, -5) to (-44).dp, Triple(Icons.Outlined.Add, R.string.time_later, 5) to 44.dp).forEach { (t, x) ->
            val label = stringResource(t.second)
            Box(
                Modifier.offset(x = x, y = diameter * 0.25f).size(56.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White)
                    .clickable(onClickLabel = label) { onChange(norm(minute + t.third)) },
                contentAlignment = Alignment.Center,
            ) { Icon(t.first, label, Modifier.size(24.dp), tint = Color(0xFF10131F)) }
        }
    }
}

/** Uhrzeit groß in der Ringmitte. */
@Composable
fun ClockCenter(minute: Int, sub: String?) {
    val g = LocalGlass.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%02d:%02d".format(minute / 60, minute % 60), color = g.ink, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
        sub?.let { Text(it, color = g.soft, fontSize = 15.sp, maxLines = 1) }
    }
}

@Composable
fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean = true, plusEnabled: Boolean = true, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundStep(Icons.Outlined.Remove, stringResource(R.string.less), minusEnabled, onMinus)
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        RoundStep(Icons.Outlined.Add, stringResource(R.string.more), plusEnabled, onPlus)
    }
}

@Composable
private fun RoundStep(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(56.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(g.ink.copy(alpha = 0.07f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(24.dp), tint = g.ink) }
}

/** Geräteauswahl als Kacheln mit Symbol, Name und Raum; [devices] ist bereits nach Eignung gefiltert. */
@Composable
fun DeviceRadioList(devices: List<Device>, roomName: (UUID?) -> String?, selected: UUID?, onSelect: (Device) -> Unit) {
    val g = LocalGlass.current
    if (devices.isEmpty()) {
        Text(stringResource(R.string.no_matching_device), color = g.soft)
        return
    }
    FixedGrid(devices, columns = 3, spacing = 8.dp) { d, mod ->
        val on = d.id == selected
        val st = FlowLabels.deviceKind(d).style()
        Row(
            mod.height(72.dp).clip(RoundedCornerShape(22.dp)).background(if (on) g.ink else g.ink.copy(alpha = 0.06f))
                .clickable(role = Role.RadioButton, onClickLabel = d.displayName) { onSelect(d) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(st.bg), contentAlignment = Alignment.Center) {
                Icon(st.icon, null, Modifier.size(20.dp), tint = st.fg)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, color = if (on) onInk() else g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (!d.isOnline) stringResource(R.string.state_offline) else roomName(d.roomId)
                sub?.let {
                    Text(
                        it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (!d.isOnline) Color(0xFFE5675C) else if (on) onInk().copy(alpha = 0.7f) else g.soft,
                    )
                }
            }
        }
    }
}

/** Auswahl aus wenigen Optionen als Pillen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> Pill(label, value == selected) { onSelect(value) } }
    }
}
