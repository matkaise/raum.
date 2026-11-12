package app.raum.ui.household

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Microwave
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.homeconnect.HcAppliance
import app.raum.homeconnect.HcKeys
import app.raum.homeconnect.HcOptionDef
import app.raum.homeconnect.HcProgram
import app.raum.homeconnect.OperationState
import app.raum.ui.theme.LocalGlass
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Dauerstufen in Minuten: fein bis zur Stunde, danach gröber. 0 = ohne Dauer. */
private val OVEN_DURATIONS = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 75, 90, 105, 120, 150, 180, 210, 240, 300, 360, 480, 600, 720)

@Composable
private fun minutesLabel(m: Int): String = when {
    m == 0 -> stringResource(R.string.hc_duration_none)
    m < 60 -> stringResource(R.string.hc_duration_min, m)
    m % 60 == 0 -> stringResource(R.string.duration_h, m / 60)
    else -> stringResource(R.string.hc_duration_hm, m / 60, m % 60)
}

@Composable
private fun idleLabel(a: HcAppliance): String = when {
    a.operation == OperationState.FINISHED -> stringResource(R.string.hc_status_finished)
    !a.poweredOn -> stringResource(if (a.power == "Standby") R.string.hc_status_standby else R.string.hc_status_off)
    else -> stringResource(R.string.hc_status_ready)
}

/** Nächster Wert im Raster [step] in Richtung [dir], innerhalb [lo]..[hi]. */
private fun stepTo(v: Double, dir: Int, step: Double, lo: Double, hi: Double): Double {
    val snapped = if (dir > 0) Math.floor(v / step + 1e-6) * step + step else Math.ceil(v / step - 1e-6) * step - step
    return snapped.coerceIn(lo, hi)
}


@Composable
fun ColumnScope.OvenPanel(a: HcAppliance, allPrograms: List<HcProgram>, vm: HouseholdViewModel, busy: Boolean, now: Instant) {
    val g = LocalGlass.current
    if (allPrograms.isEmpty()) { LoadingPrograms(); return }
    val running = a.operation.active
    // Backofen mit Mikrowelle: Leistungsstufen als eigene Ansicht statt zwischen den Heizarten
    val microwave = allPrograms.filter { isMicrowave(it.key) }
    var microwaveMode by remember(a.haId) { mutableStateOf(false) }
    LaunchedEffect(a.activeProgram, running) { if (running) microwaveMode = isMicrowave(a.activeProgram) }
    // Umschalter steht über der Illustration – gilt für beide Ansichten gleich
    val modeSwitch: (@Composable () -> Unit)? = if (microwave.isEmpty()) null else ({
        Segmented(
            listOf(stringResource(R.string.hc_oven_mode_oven), stringResource(R.string.hc_oven_mode_microwave)), if (microwaveMode) 1 else 0,
            Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = !running && !busy,
        ) { microwaveMode = it == 1 }
    })
    if (microwaveMode && modeSwitch != null) { MicrowaveView(a, microwave, vm, busy, now, modeSwitch); return }
    val programs = allPrograms.filterNot { isMicrowave(it.key) }.ifEmpty { allPrograms }
    var selKey by remember(a.haId) { mutableStateOf(programs.first().key) }
    LaunchedEffect(a.activeProgram) { a.activeProgram?.takeIf { k -> programs.any { it.key == k } }?.let { selKey = it } }
    val program = programs.firstOrNull { it.key == selKey } ?: programs.first()
    LaunchedEffect(program.key) { if (!program.detailed) vm.ensureDetail(a.haId, program.key) }

    val choices = remember(a.haId) { mutableStateMapOf<String, Map<String, JsonPrimitive>>() }
    fun value(o: HcOptionDef): JsonPrimitive? = choices[program.key]?.get(o.key) ?: o.default
    fun set(o: HcOptionDef, v: JsonPrimitive) { choices[program.key] = choices[program.key].orEmpty() + (o.key to v) }

    val setpointDef = program.option(HcKeys.OVEN_SETPOINT)
    val durationDef = program.option(HcKeys.DURATION)
    val min = setpointDef?.min ?: 30.0
    val max = setpointDef?.max ?: 275.0
    // Läuft der Ofen, zählt der Sollwert des Geräts
    val target = if (running) a.number(HcKeys.OVEN_SETPOINT) ?: setpointDef?.let { value(it)?.doubleOrNull } ?: 180.0
    else setpointDef?.let { value(it)?.doubleOrNull } ?: 180.0
    // Die Innentemperatur meldet der Ofen nur, solange er heizt – im Standby bliebe der letzte Wert vom letzten
    // Backen stehen (z. B. 49°, obwohl er längst kalt ist). Deshalb nur im Betrieb anzeigen.
    // Programme ohne Temperatur (Mikrowelle „600 Watt“): keine Zieltemperatur, kein Vorheizen – Restzeit statt Grad
    val heating = !running || a.number(HcKeys.OVEN_SETPOINT) != null || (a.activeProgram == program.key && setpointDef != null)
    val cavity = if (running && heating) a.number(HcKeys.OVEN_CAVITY) ?: 21.0 else null
    val reached = running && heating && (HcKeys.OVEN_PREHEAT_DONE in a.events || (cavity ?: 0.0) >= target - 0.5)
    val runRemaining = if (running) a.remainingSeconds(now) else null
    val status = when {
        // Im Standby steht die Heizart schon in der gewählten Pille
        !running -> ""
        !heating -> runRemaining?.let { stringResource(R.string.hc_status_remaining, hmDuration(it)) } ?: stringResource(R.string.hc_status_running)
        else -> {
            val remaining = runRemaining?.takeIf { (a.number(HcKeys.DURATION) ?: 0.0) > 0 }
            when {
                reached && remaining != null -> stringResource(R.string.hc_oven_cooking, hmDuration(remaining))
                reached -> stringResource(R.string.hc_oven_reached)
                else -> stringResource(R.string.hc_oven_preheating, (cavity ?: 21.0).roundToInt(), target.roundToInt())
            }
        }
    }
    val idle = when {
        running && !heating -> runRemaining?.let { hmDuration(it) } ?: stringResource(R.string.hc_status_running)
        else -> idleLabel(a)
    }

    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // Links: Umschalter und Illustration (Ring stellt auch die Temperatur)
        Column(Modifier.width(340.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            modeSwitch?.invoke()
            Spacer(Modifier.weight(1f))
            OvenDial(
                target = if (heating) target else min, cavity = cavity, idleLabel = idle, showTarget = heating, min = min, max = max, step = (setpointDef?.step ?: 5.0).coerceAtLeast(5.0),
                running = running, enabled = !running && !busy && setpointDef != null,
            ) { v -> setpointDef?.let { set(it, JsonPrimitive(v.roundToInt())) } }
            Text(status, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
        }
        // Rechts: Betriebsart, Einstellungen, Start
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionLabel(stringResource(R.string.hc_oven_mode))
            // Heizarten als illustrierte Karten zum Durchwischen
            ProgramCarousel(
                programs.map { ProgramCardSpec(it.key, it.name, it.available, ovenArt(it.key)) }, program.key, enabled = !running && !busy,
                Modifier.weight(1f).fillMaxWidth(),
            ) { selKey = it }
            // Temperatur stellt der Ring links – hier nur noch die Dauer
            Box(Modifier.padding(top = 12.dp).alpha(if (running) 0.45f else 1f)) {
                val editable = !running && !busy
                if (durationDef != null) {
                    val maxMin = ((durationDef.max ?: 3600.0) / 60).toInt()
                    val steps = OVEN_DURATIONS.filter { it <= maxMin }
                    // Nicht der Standardwert des Geräts (z. B. 60 s) – ohne Wahl läuft der Ofen ohne Dauer
                    val current = (if (running) a.number(HcKeys.DURATION) else choices[program.key]?.get(durationDef.key)?.doubleOrNull)?.let { (it / 60).roundToInt() } ?: 0
                    val i = steps.indexOfFirst { it >= current }.let { if (it < 0) steps.lastIndex else it }
                    Stepper(
                        stringResource(R.string.hc_duration), minutesLabel(if (running) current else steps[i]), null,
                        canDown = i > 0, canUp = i < steps.lastIndex, enabled = editable,
                        onDown = { set(durationDef, JsonPrimitive(steps[(i - 1).coerceAtLeast(0)] * 60)) },
                        onUp = { set(durationDef, JsonPrimitive(steps[(i + 1).coerceAtMost(steps.lastIndex)] * 60)) },
                        compact = true,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (running) {
                ActionButton(stringResource(R.string.hc_oven_off), Icons.Outlined.StopCircle, g.chip, g.ink, Modifier.fillMaxWidth(), height = 64.dp, enabled = !busy) {
                    vm.stop(a.haId)
                }
            } else {
                val (ok, hint) = startHint(a)
                // Nur gesendet, was hier gewählt wird: Temperatur und – falls gewählt – Dauer. Gerätestandards wie eine
                // Dauer von 60 s oder eine Startzeit würden den Ofen sonst nach einer Minute ausschalten bzw. verzögern.
                val options = buildMap {
                    setpointDef?.let { d -> put(d.key, JsonPrimitive(target.roundToInt())) }
                    durationDef?.let { d -> choices[program.key]?.get(d.key)?.takeIf { (it.doubleOrNull ?: 0.0) > 0 }?.let { put(d.key, it) } }
                }
                ActionButton(
                    stringResource(R.string.hc_start, program.name), Icons.Outlined.PlayArrow,
                    if (ok) HcColors.Oven else g.chip, if (ok) Color(0xFF2A1204) else g.soft, Modifier.fillMaxWidth(), height = 64.dp,
                    enabled = ok && !busy && program.detailed,
                ) { vm.start(a.haId, program.key, options) }
                FootNote(ok, hint)
            }
        }
    }
}

/**
 * Drehring 270°: äußerer Ring = Solltemperatur (zum Ziehen), innerer Ring = Temperatur im Ofen,
 * Mitte = Sichtfenster, das mit der Ofentemperatur glüht.
 */
@Composable
private fun OvenDial(target: Double, cavity: Double?, idleLabel: String, showTarget: Boolean, min: Double, max: Double, step: Double, running: Boolean, enabled: Boolean, onCommit: (Double) -> Unit) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(((target - min) / (max - min)).toFloat()) }
    LaunchedEffect(target) { if (!dragging) local = ((target - min) / (max - min)).toFloat().coerceIn(0f, 1f) }
    fun at(p: Offset, size: IntSize): Float {
        val a = Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), -(p.y - size.height / 2f).toDouble())).toFloat()
        return ((a + 135f) / 270f).coerceIn(0f, 1f)
    }
    // Gesten laufen über Programmwechsel hinweg – immer aktuelle Grenzen und das aktuelle Programm verwenden
    val range by rememberUpdatedState(Triple(min, max, step))
    val commit by rememberUpdatedState(onCommit)
    fun valueOf(f: Float): Double { val (lo, hi, st) = range; return (Math.round((lo + f * (hi - lo)) / st) * st).coerceIn(lo, hi) }
    val cf = cavity?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) } ?: 0f
    // Glühen nur im Betrieb – im Standby ist das Fenster dunkel
    val glow by animateFloatAsState(if (running && cavity != null) 0.25f + (cavity / 230.0).toFloat().coerceIn(0f, 1f) * 0.75f else 0f, tween(400), label = "glow")
    fun nudge(dir: Int) {
        val (lo, hi, st) = range
        val v = stepTo(valueOf(local), dir, st, lo, hi)
        local = ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        commit(v)
    }
    Box(Modifier.size(330.dp), contentAlignment = Alignment.Center) {
    Box(
        Modifier.matchParentSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> local = at(p, size); commit(valueOf(local)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { p -> dragging = true; local = at(p, size) },
                    onDragEnd = { dragging = false; commit(valueOf(local)) },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); local = at(change.position, size) }
            },
    )
        Canvas(Modifier.size(330.dp)) {
            val ring = 26.dp.toPx()
            val r = size.minDimension / 2 - ring / 2
            fun arc(radius: Float) = Offset(center.x - radius, center.y - radius) to Size(radius * 2, radius * 2)
            val (tl, sz) = arc(r)
            drawArc(g.track, 135f, 270f, false, tl, sz, style = Stroke(ring))
            drawArc(Color(0xFFFF9D6B), 135f, 270f * local, false, tl, sz, style = Stroke(ring))
            val inner = r - ring / 2 - 21.dp.toPx()
            val (tl2, sz2) = arc(inner)
            if (cf > 0f) drawArc(Color(0xFFFFC870), 135f, 270f * cf, false, tl2, sz2, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            val angle = Math.toRadians((135f + 270f * local).toDouble())
            val knob = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.25f), 15.dp.toPx(), knob + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, 14.dp.toPx(), knob)
        }
        Box(Modifier.size(226.dp).clip(CircleShape).background(Color(0xFF1B1512)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(226.dp)) {
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFFF8A3D), Color(0xFFB8401A), Color.Transparent), Offset(center.x, size.height * 0.6f), size.minDimension * 0.62f),
                    alpha = glow,
                )
                // Einschübe am Rand des Fensters – nicht durch Temperatur und „Ziel“
                listOf(0.16f, 0.84f).forEach { y ->
                    drawLine(Color(0x59FFBE8C), Offset(size.width * 0.18f, size.height * y), Offset(size.width * 0.82f, size.height * y), 3.dp.toPx(), cap = StrokeCap.Round)
                }
                drawCircle(Color.White.copy(alpha = 0.08f), size.minDimension / 2 - 3.dp.toPx(), style = Stroke(6.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (cavity != null) {
                    Text(stringResource(R.string.hc_oven_inside).uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                    Text("${cavity.roundToInt()}°", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 74.sp)
                } else {
                    Text(idleLabel, color = Color.White.copy(alpha = 0.85f), fontSize = 38.sp, fontWeight = FontWeight.Light, lineHeight = 42.sp)
                }
                // Ohne große Gradzahl rutscht „Ziel“ sonst auf die Zierlinie
                if (showTarget) Text(stringResource(R.string.hc_target, valueOf(local).roundToInt()), color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp,
                    modifier = Modifier.padding(top = if (cavity == null) 18.dp else 0.dp))
            }
        }
        // − / + in der Lücke unten im Ring – für genaue Schritte ohne Ziehen
        if (!running && showTarget) RingButtons(enabled, canDown = valueOf(local) > min, canUp = valueOf(local) < max, Modifier.align(Alignment.BottomCenter)) { nudge(it) }
    }
}

@Composable
internal fun RingButtons(enabled: Boolean, canDown: Boolean, canUp: Boolean, modifier: Modifier, onStep: (Int) -> Unit) {
    Row(modifier.padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        StepButton(Icons.Outlined.Remove, enabled && canDown, 48.dp) { onStep(-1) }
        StepButton(Icons.Outlined.Add, enabled && canUp, 48.dp) { onStep(1) }
    }
}


private fun isMicrowave(key: String?) = key != null && ".Program.Microwave." in key

/** Kochzeiten der Mikrowelle (Sekunden): fein am Anfang, gröber bei langen Zeiten. */
private val MW_DURATIONS = listOf(15, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 420, 480, 600, 720, 900, 1200, 1500, 1800)
private val MicrowaveAccent = Color(0xFFFFC870)

/** „90 Watt“ → „90 W“ für die Kacheln; „Boost“ bleibt. */
private fun shortPower(name: String) = name.replace(" Watt", " W")

private fun mmss(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)


/**
 * Mikrowelle: Umschalter und Ring links (Ring stellt auch die Dauer), rechts Leistung, Dauer mit − / + und Start.
 * Die Grenzen meldet der Ofen im Standby nicht – deshalb feste Stufen, auf gemeldete Grenzen eingepasst.
 */
@Composable
private fun ColumnScope.MicrowaveView(a: HcAppliance, programs: List<HcProgram>, vm: HouseholdViewModel, busy: Boolean, now: Instant, modeSwitch: @Composable () -> Unit) {
    val g = LocalGlass.current
    val running = a.operation.active
    var selKey by remember(a.haId) { mutableStateOf(programs.firstOrNull { it.key.endsWith("600Watt") }?.key ?: programs.last().key) }
    LaunchedEffect(a.activeProgram) { a.activeProgram?.takeIf { k -> programs.any { it.key == k } }?.let { selKey = it } }
    val program = programs.firstOrNull { it.key == selKey } ?: programs.first()
    LaunchedEffect(program.key) { if (!program.detailed) vm.ensureDetail(a.haId, program.key) }
    val durDef = program.option(HcKeys.DURATION)
    val steps = MW_DURATIONS.filter { s -> (durDef?.min == null || s >= durDef.min!!) && (durDef?.max == null || s <= durDef.max!!) }.ifEmpty { MW_DURATIONS }
    var seconds by remember(a.haId) { mutableStateOf(120) }
    val shown = if (running) (a.number(HcKeys.DURATION)?.toInt() ?: seconds) else seconds
    val i = steps.indexOfFirst { it >= shown }.let { if (it < 0) steps.lastIndex else it }
    val remaining = if (running) a.remainingSeconds(now)?.toInt() else null
    val power = shortPower(program.name)

    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(340.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            modeSwitch()
            Spacer(Modifier.weight(1f))
            MicrowaveDial(index = i, steps = steps, remaining = remaining, power = power, running = running, enabled = !running && !busy) { seconds = steps[it] }
            Text(if (running) stringResource(R.string.hc_mw_running_hint) else "", color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionLabel(stringResource(R.string.hc_mw_power))
            ProgramCarousel(
                programs.map { ProgramCardSpec(it.key, shortPower(it.name), it.available, microwaveArt(it.key)) }, program.key, enabled = !running && !busy,
                Modifier.weight(1f).fillMaxWidth().padding(bottom = 14.dp),
            ) { selKey = it }
            if (running) {
                ActionButton(stringResource(R.string.hc_mw_off), Icons.Outlined.StopCircle, g.chip, g.ink, Modifier.fillMaxWidth(), height = 64.dp, enabled = !busy) { vm.stop(a.haId) }
            } else {
                val (ok, hint) = startHint(a)
                ActionButton(
                    stringResource(R.string.hc_mw_start, power, mmss(seconds)), Icons.Outlined.PlayArrow,
                    if (ok) MicrowaveAccent else g.chip, if (ok) Color(0xFF2A1F04) else g.soft, Modifier.fillMaxWidth(), height = 64.dp,
                    enabled = ok && !busy && program.detailed,
                ) {
                    val value = durDef?.fit(JsonPrimitive(seconds)) ?: JsonPrimitive(seconds)
                    vm.start(a.haId, program.key, mapOf(HcKeys.DURATION to value))
                }
                FootNote(ok, hint)
            }
        }
    }
}

/** Ring für die Dauer (Stufen), Fenster zeigt Zeit und Leistung bzw. die Restzeit; leuchtet warm, solange es läuft. */
@Composable
private fun MicrowaveDial(index: Int, steps: List<Int>, remaining: Int?, power: String, running: Boolean, enabled: Boolean, onPick: (Int) -> Unit) {
    val g = LocalGlass.current
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(index / steps.lastIndex.coerceAtLeast(1).toFloat()) }
    val count by rememberUpdatedState(steps.size)
    val pick by rememberUpdatedState(onPick)
    LaunchedEffect(index, steps.size) { if (!dragging) local = index / steps.lastIndex.coerceAtLeast(1).toFloat() }
    fun at(p: Offset, size: IntSize): Float {
        val a = Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), -(p.y - size.height / 2f).toDouble())).toFloat()
        return ((a + 135f) / 270f).coerceIn(0f, 1f)
    }
    fun idx(f: Float) = (f * (count - 1)).roundToInt().coerceIn(0, count - 1)
    val glow by animateFloatAsState(if (running) 0.8f else 0f, tween(600), label = "mwglow")
    fun nudge(dir: Int) {
        val n = (idx(local) + dir).coerceIn(0, count - 1)
        local = n / (count - 1).coerceAtLeast(1).toFloat()
        pick(n)
    }
    Box(Modifier.size(330.dp), contentAlignment = Alignment.Center) {
    Box(
        Modifier.matchParentSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> local = at(p, size); pick(idx(local)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { p -> dragging = true; local = at(p, size) },
                    onDragEnd = { dragging = false; local = idx(local) / (count - 1).coerceAtLeast(1).toFloat(); pick(idx(local)) },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); local = at(change.position, size); pick(idx(local)) }
            },
    )
        Canvas(Modifier.size(330.dp)) {
            val ring = 26.dp.toPx()
            val r = size.minDimension / 2 - ring / 2
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            drawArc(g.track, 135f, 270f, false, tl, sz, style = Stroke(ring))
            drawArc(MicrowaveAccent, 135f, 270f * local, false, tl, sz, style = Stroke(ring))
            val angle = Math.toRadians((135f + 270f * local).toDouble())
            val knob = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
            drawCircle(Color.Black.copy(alpha = 0.25f), 15.dp.toPx(), knob + Offset(0f, 2.dp.toPx()))
            drawCircle(Color.White, 14.dp.toPx(), knob)
        }
        Box(Modifier.size(226.dp).clip(CircleShape).background(Color(0xFF16181C)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(226.dp)) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE6A8), Color(0xFFE0A33A), Color.Transparent), center, size.minDimension * 0.6f), alpha = glow * 0.55f)
                // Drehteller
                drawOval(Color.White.copy(alpha = 0.10f), Offset(size.width * 0.24f, size.height * 0.8f), Size(size.width * 0.52f, size.height * 0.07f))
                drawCircle(Color.White.copy(alpha = 0.08f), size.minDimension / 2 - 3.dp.toPx(), style = Stroke(6.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (remaining != null) R.string.hc_mw_remaining else R.string.hc_mw_duration_label).uppercase(),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                Text(mmss(remaining ?: steps[idx(local)]), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 68.sp)
                Text(power, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }
        if (!running) RingButtons(enabled, canDown = idx(local) > 0, canUp = idx(local) < count - 1, Modifier.align(Alignment.BottomCenter)) { nudge(it) }
    }
}


/** 30 → „30 s“, 90 → „1:30 Min“, 120 → „2 Min“. */
@Composable
private fun secondsLabel(s: Int): String = when {
    s < 60 -> stringResource(R.string.hc_seconds, s)
    s % 60 == 0 -> stringResource(R.string.hc_duration_min, s / 60)
    else -> stringResource(R.string.hc_mw_minsec, mmss(s))
}

