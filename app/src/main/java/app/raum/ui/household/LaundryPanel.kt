package app.raum.ui.household

import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.atan2
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.homeconnect.HcAppliance
import app.raum.homeconnect.HcKeys
import app.raum.homeconnect.HcOptionDef
import app.raum.homeconnect.HcProgram
import app.raum.homeconnect.HcType
import app.raum.homeconnect.OperationState
import app.raum.ui.theme.LocalGlass
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

private data class Phase(val labelRes: Int, val from: Float, val to: Float)

private fun phases(type: HcType): List<Phase> = when (type) {
    HcType.DISHWASHER -> listOf(Phase(R.string.hc_phase_prerinse, 0f, .12f), Phase(R.string.hc_phase_clean, .12f, .6f), Phase(R.string.hc_phase_rinse_aid, .6f, .78f), Phase(R.string.hc_phase_dry, .78f, 1f))
    HcType.WASHER -> listOf(Phase(R.string.hc_phase_wash, 0f, .6f), Phase(R.string.hc_phase_rinse, .6f, .85f), Phase(R.string.hc_phase_spin, .85f, 1f))
    HcType.WASHER_DRYER -> listOf(Phase(R.string.hc_phase_wash, 0f, .45f), Phase(R.string.hc_phase_rinse, .45f, .6f), Phase(R.string.hc_phase_spin, .6f, .7f), Phase(R.string.hc_phase_dry, .7f, 1f))
    else -> listOf(Phase(R.string.hc_phase_dry, 0f, .85f), Phase(R.string.hc_phase_cool, .85f, 1f))
}

@Composable
fun ColumnScope.LaundryPanel(a: HcAppliance, programs: List<HcProgram>, vm: HouseholdViewModel, busy: Boolean, now: Instant) {
    val g = LocalGlass.current
    val accent = accentFor(a.type)
    val op = a.operation
    val running = op == OperationState.RUN || op == OperationState.ACTION_REQUIRED
    val paused = op == OperationState.PAUSE
    val waiting = op == OperationState.DELAYED_START
    val finished = op == OperationState.FINISHED
    val active = op.active

    var selKey by remember(a.haId) { mutableStateOf<String?>(null) }
    val program = programs.firstOrNull { it.key == selKey }
        ?: programs.firstOrNull { it.key == a.selectedProgram } ?: programs.firstOrNull { it.key == a.activeProgram } ?: programs.firstOrNull()
    LaunchedEffect(program?.key) { program?.takeIf { !it.detailed }?.let { vm.ensureDetail(a.haId, it.key) } }
    val choices = remember(a.haId) { mutableStateMapOf<String, Map<String, JsonPrimitive>>() }
    fun value(o: HcOptionDef): JsonPrimitive? = program?.let { choices[it.key]?.get(o.key) } ?: o.default
    fun set(o: HcOptionDef, v: JsonPrimitive) { program?.let { choices[it.key] = choices[it.key].orEmpty() + (o.key to v) } }
    // Startzeit als Uhrzeit statt als Abstand – „23:30“ bleibt 23:30, auch wenn man erst später auf Start tippt
    var startAt by remember(a.haId) { mutableStateOf<Instant?>(null) }
    // Waschen/Trocknen: Wunschzeit fürs Ende („fertig um“), ebenfalls als Uhrzeit
    var finishAt by remember(a.haId) { mutableStateOf<Instant?>(null) }
    val laundry = a.type != HcType.DISHWASHER
    val finishDef = program?.option(HcKeys.FINISH_IN_RELATIVE)?.takeIf { laundry }
    // Frühestes Ende = Programmdauer (Grenze bzw. Standardwert des Geräts)
    val finishMin = finishDef?.let { maxOf(it.min ?: 0.0, it.default?.doubleOrNull ?: 0.0).toLong() } ?: 0L
    val finishMax = finishDef?.max?.toLong() ?: 86_340L

    val remaining = a.remainingSeconds(now)
    val p = when {
        finished -> 1f
        active -> (a.progressPercent ?: 0) / 100f
        else -> 0f
    }
    val ph = phases(a.type)
    val cur = ph.firstOrNull { p >= it.from && p < it.to } ?: ph.last()
    val activeName = vm.service.programName(a.haId, a.activeProgram)

    val offline = !a.connected
    val editable = !active && !offline
    val ringColor = if (finished) HcColors.Done else accent

    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // Links: das Gerät als Illustration, darunter Zustand und – im Betrieb – die Restzeit
        Column(Modifier.width(340.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            // Drehring um das Gerät: im Leerlauf Programmwahl (Rasten je Programm), im Betrieb Fortschritt
            val idx = programs.indexOfFirst { it.key == program?.key }.coerceAtLeast(0)
            if (laundry && editable) {
                val drum: @Composable () -> Unit = {
                    DrumVisual(0f, ringColor, running = false, fast = false, water = false, dryer = a.type == HcType.DRYER, diameter = 190.dp, frame = false)
                }
                if (finishDef != null) {
                    TimeRing(
                        now, finishAt?.takeIf { it.isAfter(now.plusSeconds(finishMin + 59)) }, accent, enabled = !busy, onClear = { finishAt = null },
                        ringSize = 330.dp, minSeconds = finishMin, centerContent = drum,
                    ) { t -> finishAt = snapFinish(t, now, finishMin, finishMax) }
                } else {
                    // Gerät meldet kein „fertig in“ (z. B. ohne Fernstart) – dann nur die Trommel, kein leerer Ring
                    Box(Modifier.size(330.dp), contentAlignment = Alignment.Center) {
                        DrumVisual(0f, ringColor, running = false, fast = false, water = false, dryer = a.type == HcType.DRYER, diameter = 250.dp, frame = false)
                    }
                }
            } else Box(Modifier.alpha(if (offline) 0.4f else 1f)) {
                DetentDial(
                    count = programs.size, index = idx, progress = if (editable) null else p, ring = ringColor, dashed = waiting,
                    enabled = editable && !busy && programs.isNotEmpty(), onPick = { i -> programs.getOrNull(i)?.let { selKey = it.key } },
                ) {
                    if (a.type == HcType.DISHWASHER) DishVisual(p, ringColor, spraying = running && cur.labelRes != R.string.hc_phase_dry, steaming = running && cur.labelRes == R.string.hc_phase_dry, diameter = 196.dp, frame = false)
                    else DrumVisual(p, ringColor, running = running, fast = running && cur.labelRes == R.string.hc_phase_spin, water = running && a.type != HcType.DRYER && cur.labelRes != R.string.hc_phase_spin && cur.labelRes != R.string.hc_phase_dry, dryer = a.type == HcType.DRYER, diameter = 218.dp, frame = false)
                }
            }
            if (editable && program != null) {
                Text(program.name, color = g.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 14.dp))
                if (!program.available) Text(stringResource(R.string.hc_program_unavailable_short), color = g.warn, fontSize = 13.sp, maxLines = 1)
            }
            if (laundry && editable && finishDef != null) {
                // Ende: gewählte Uhrzeit oder – ohne Wahl – jetzt plus Programmdauer
                val at = finishAt?.takeIf { it.isAfter(now.plusSeconds(finishMin + 59)) }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.hc_finish_at, hm(at ?: now.plusSeconds(finishMin))).uppercase(),
                        color = if (at != null) g.ink else g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, maxLines = 1,
                    )
                    if (at != null) Box(
                        Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(g.chip).clickable(enabled = !busy) { finishAt = null }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.hc_asap), color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            val stateLabel = when {
                offline -> stringResource(R.string.hc_status_offline)
                waiting -> stringResource(R.string.hc_state_delayed)
                paused -> stringResource(R.string.hc_status_paused)
                op == OperationState.ACTION_REQUIRED -> stringResource(R.string.hc_status_action)
                running -> stringResource(R.string.hc_state_running, stringResource(cur.labelRes))
                finished -> a.finishedAt?.let { stringResource(R.string.hc_status_finished_since, hm(it)) } ?: stringResource(R.string.hc_status_finished)
                !a.poweredOn -> stringResource(if (a.power == "Standby") R.string.hc_status_standby else R.string.hc_status_off)
                else -> stringResource(R.string.hc_status_ready)
            }
            val planning = laundry && editable && finishDef != null
            // Beim Planen nur „fertig seit …“ zeigen, sonst steht der Zustand ohnehin oben im Tab
            if (!planning || finished) Text(stateLabel.uppercase(), color = if (finished) (if (g.dark) HcColors.Done else HcColors.DoneInk) else g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp, maxLines = 1, modifier = Modifier.padding(top = if (editable) 4.dp else 14.dp))
            if (active || (finished && !planning)) {
                val startAt = a.delayedStartAt()
                val big = when {
                    waiting -> startAt?.let(::hm) ?: "–"
                    active -> remaining?.let(::hmDuration) ?: "–"
                    else -> a.finishedAt?.let(::hm) ?: "✓"
                }
                Text(big, color = g.ink, fontSize = 48.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 52.sp, maxLines = 1)
                Text(
                    when {
                        waiting && startAt != null -> remaining?.let { stringResource(R.string.hc_sub_delayed, hm(startAt.plusSeconds(it))) } ?: stringResource(R.string.hc_sub_delayed_plain)
                        active -> remaining?.let { stringResource(R.string.hc_sub_remaining, hm(now.plusSeconds(it))) } ?: ""
                        else -> stringResource(if (a.type == HcType.WASHER) R.string.hc_sub_finished_take_out else R.string.hc_sub_finished)
                    },
                    color = g.soft, fontSize = 15.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
        }

        // Rechts: Programm und Optionen bzw. Ablauf, unten die Knöpfe
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when {
                offline -> {
                    Text(stringResource(R.string.hc_offline_explain), color = g.soft, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(1f))
                }
                editable && program != null -> {
                    // Scrollt, damit der Startknopf immer sichtbar bleibt; unten weich ausblenden statt hart abschneiden
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                if (scroll.canScrollForward) drawRect(Brush.verticalGradient(0.85f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                            }
                            .verticalScroll(scroll),
                    ) {
                        if (laundry) {
                            // Programme als illustrierte Karten – bei 15+ Programmen findet man so, was man sucht
                            SectionLabel(stringResource(R.string.hc_program))
                            ProgramCarousel(
                                programs.map { ProgramCardSpec(it.key, it.name, it.available, laundryArt(it.key, dryer = a.type == HcType.DRYER)) }, program.key,
                                enabled = !busy, Modifier.fillMaxWidth().height(214.dp),
                            ) { selKey = it }
                        }
                        OptionGroups(program, ::value, ::set, enabled = !busy, now = now, startAt = startAt, accent = accent) { startAt = it }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                editable -> { LoadingPrograms(); Spacer(Modifier.weight(1f)) }
                else -> {
                    // Ablauf: Programm mit Optionen, darunter die Phasen
                    SectionLabel(stringResource(R.string.hc_program))
                    val line = listOf(activeName) + optionSummary(a, programs.firstOrNull { it.key == a.activeProgram } ?: program, a.values, programs)
                    Text(line.filter { it.isNotBlank() }.joinToString(" · "), color = g.ink, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ph.forEach { phase ->
                            val f = ((p - phase.from) / (phase.to - phase.from)).coerceIn(0f, 1f)
                            val isCur = running && phase == cur
                            // Mindestbreite, damit kurze Phasen („Vorspülen“, „Schleudern“) lesbar bleiben
                            Column(Modifier.weight(maxOf(phase.to - phase.from, 0.17f))) {
                                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(g.chip)) {
                                    Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (finished) HcColors.Done else accent))
                                }
                                Text(stringResource(phase.labelRes), color = if (isCur) g.ink else g.soft, fontSize = 13.sp, fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                                    softWrap = false, overflow = TextOverflow.Visible, modifier = Modifier.padding(top = 6.dp).wrapContentWidth(Alignment.Start, unbounded = true))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            if (!offline) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        waiting -> {
                            ActionButton(stringResource(R.string.hc_start_now), Icons.Outlined.PlayArrow, accent, HcColors.OnAccent, Modifier.weight(2f), height = 64.dp, enabled = !busy) { vm.startNow(a.haId) }
                            ActionButton(stringResource(R.string.hc_clear_delay), Icons.Outlined.Schedule, g.chip, g.ink, Modifier.weight(1f), height = 64.dp, enabled = !busy) { vm.stop(a.haId) }
                        }
                        active -> {
                            val canPause = a.commands == null || HcKeys.PAUSE in a.commands
                            if (canPause) ActionButton(
                                stringResource(if (paused) R.string.hc_resume else R.string.hc_pause), if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                accent, HcColors.OnAccent, Modifier.weight(2f), height = 64.dp, enabled = !busy,
                            ) { vm.pause(a.haId, !paused) }
                            ActionButton(stringResource(R.string.hc_abort), Icons.Outlined.StopCircle, g.chip, g.ink, Modifier.weight(1f), height = 64.dp, enabled = !busy) { vm.stop(a.haId) }
                        }
                        program != null -> {
                            val (ok, _) = startHint(a)
                            // Startzeit 0 = „Jetzt“ nicht mitsenden (manche Geräte lehnen 0 ab)
                            val delayDef = program.option(HcKeys.START_IN_RELATIVE)
                            // Bis zur gewählten Uhrzeit, auf die Minute – ist sie schon vorbei, startet er sofort
                            val delay = startAt?.takeIf { delayDef != null }?.let { java.time.Duration.between(now, it).seconds }?.takeIf { it >= 60 }?.let { it / 60 * 60 } ?: 0L
                            val opts = program.options.mapNotNull { o -> value(o)?.let { o.key to it } }.toMap()
                                .filterKeys { it != HcKeys.START_IN_RELATIVE }
                                .let { m -> if (delay > 0 && delayDef != null) m + (delayDef.key to (delayDef.fit(JsonPrimitive(delay)) ?: JsonPrimitive(delay))) else m }
                                // „Fertig um“ nur senden, wenn gewählt – sonst startet das Gerät sofort
                                .filterKeys { it != HcKeys.FINISH_IN_RELATIVE }
                                .let { m ->
                                    val f = finishAt?.let { java.time.Duration.between(now, it).seconds }?.takeIf { finishDef != null && it > finishMin + 59 }
                                    if (f != null) m + (finishDef!!.key to (finishDef.fit(JsonPrimitive(f)) ?: JsonPrimitive(f))) else m
                                }
                            val finishSel = finishAt?.takeIf { finishDef != null && it.isAfter(now.plusSeconds(finishMin + 59)) }
                            val label = if (finishSel != null) stringResource(R.string.hc_start_finish, program.name, hm(finishSel))
                            else if (delay > 0) stringResource(R.string.hc_start_at, program.name, hm(startAt!!))
                            else stringResource(R.string.hc_start, program.name)
                            ActionButton(label, if (delay > 0 || finishSel != null) Icons.Outlined.Schedule else Icons.Outlined.PlayArrow,
                                if (ok) accent else g.chip, if (ok) HcColors.OnAccent else g.soft, Modifier.weight(1f), height = 64.dp,
                                enabled = ok && !busy && program.detailed) { vm.start(a.haId, program.key, opts) }
                        }
                    }
                }
                if (editable) startHint(a).let { (ok, hint) -> FootNote(ok, hint) }
            }
        }
    }
}

/** Kurzbeschreibung der Optionen: „40° · 1400 U/min“, „Schranktrocken“, „Extra trocken“. */
@Composable
private fun optionSummary(a: HcAppliance, program: HcProgram?, values: Map<String, JsonPrimitive>, programs: List<HcProgram> = emptyList()): List<String> {
    val out = mutableListOf<String>()
    fun enumLabel(key: String): String? {
        val v = values[key]?.contentOrNull ?: return null
        // Anzeigename aus dem Programm – nach Programmende aus einem anderen geladenen Programm mit derselben Option
        val def = program?.option(key)?.takeIf { v in it.allowed } ?: programs.firstNotNullOfOrNull { p -> p.option(key)?.takeIf { v in it.allowed } }
        return def?.label(v) ?: app.raum.homeconnect.prettify(app.raum.homeconnect.enumTail(v) ?: v)
    }
    when (a.type) {
        HcType.WASHER, HcType.WASHER_DRYER -> {
            enumLabel(HcKeys.WASHER_TEMP)?.let { out += it }
            values[HcKeys.WASHER_SPIN]?.contentOrNull?.let { v ->
                val label = enumLabel(HcKeys.WASHER_SPIN) ?: v
                out += if (v.endsWith(".Off")) stringResource(R.string.hc_no_spin) else if (label.all { it.isDigit() }) stringResource(R.string.hc_rpm, label) else label
            }
        }
        HcType.DRYER -> enumLabel(HcKeys.DRYER_TARGET)?.let { out += it }
        else -> program?.options?.filter { it.isBoolean && values[it.key]?.booleanOrNull == true }?.forEach { out += it.name }
    }
    return out
}

/** Gemeinsame Endung aller Beschriftungen („400 U/min“, „600 U/min“ → „ U/min“), sonst leer. */
private fun commonUnit(labels: List<String>): String {
    if (labels.size < 2) return ""
    val tail = labels.first().substringAfterLast(' ', "").takeIf { it.isNotEmpty() && it.none(Char::isDigit) } ?: return ""
    return if (labels.all { it.endsWith(" $tail") && it.length > tail.length + 1 }) tail else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionGroups(program: HcProgram, value: (HcOptionDef) -> JsonPrimitive?, set: (HcOptionDef, JsonPrimitive) -> Unit, enabled: Boolean, now: Instant, startAt: Instant?, accent: Color, onStartAt: (Instant?) -> Unit) {
    val g = LocalGlass.current
    val enums = program.options.filter { it.isEnum }.let { list ->
        // Bekannte zuerst (Temperatur, Schleudern, Trockenziel), höchstens zwei Gruppen
        list.sortedBy { if (it.key in setOf(HcKeys.WASHER_TEMP, HcKeys.WASHER_SPIN, HcKeys.DRYER_TARGET)) 0 else 1 }.take(2)
    }
    val bools = program.options.filter { it.isBoolean }
    val delayDef = program.option(HcKeys.START_IN_RELATIVE)
    enums.forEach { o ->
        // Einheit nur einmal in der Überschrift („Schleudern · U/min“), nicht in jedem Feld
        val labels = o.allowed.map(o::label)
        val unit = commonUnit(labels)
        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            SectionLabel(if (unit.isNotEmpty()) "${o.name} · $unit" else o.name)
            Segmented(labels.map { if (unit.isNotEmpty()) it.removeSuffix(" $unit") else it }, o.allowed.indexOf(value(o)?.contentOrNull).coerceAtLeast(0), enabled = enabled) {
                set(o, JsonPrimitive(o.allowed[it]))
            }
        }
    }
    if (bools.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        SectionLabel(stringResource(R.string.hc_options))
        // Ein/Aus-Optionen als Chips, die umbrechen – lange Namen („Halbe Beladung“) bleiben lesbar
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bools.forEach { o ->
                val on = value(o)?.booleanOrNull == true
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) g.ink else g.chip)
                        .clickable(enabled = enabled) { set(o, JsonPrimitive(!on)) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(o.name, color = if (on) inverseInk() else g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
    if (delayDef != null) StartTimePicker(now, startAt, (delayDef.max ?: 86_340.0).toLong(), enabled, accent, onStartAt)
}

/** „Fertig um“: auf Viertelstunden, nicht vor Ende der Programmdauer (sonst sofort), nicht über die Grenze. */
private fun snapFinish(t: Instant, now: Instant, minSeconds: Long, maxSeconds: Long): Instant? {
    val earliest = now.plusSeconds(minSeconds)
    if (t.isBefore(earliest.plusSeconds(QUARTER / 2))) return null
    val s = Instant.ofEpochSecond((t.epochSecond + QUARTER / 2) / QUARTER * QUARTER)
    val limit = Instant.ofEpochSecond((now.epochSecond + maxSeconds) / QUARTER * QUARTER)
    return if (s.isAfter(limit)) limit else if (!s.isAfter(earliest.plusSeconds(59))) null else s
}

/** Raster der Startzeit: Viertelstunden auf der Uhr. */
private const val QUARTER = 900L
private const val DAY = 86_400L

/**
 * Startzeit am Uhr-Ring: der Ring zeigt die nächsten 24 Stunden, oben ist jetzt. Ziehen oder Tippen setzt die
 * Uhrzeit (auf Viertelstunden), der Bogen zeigt die Wartezeit. Links: Jetzt/Uhrzeit und − / + für Feinschritte.
 * Gespeichert wird die Uhrzeit, nicht der Abstand.
 */
@Composable
private fun StartTimePicker(now: Instant, startAt: Instant?, maxSeconds: Long, enabled: Boolean, accent: Color, onStartAt: (Instant?) -> Unit) {
    val g = LocalGlass.current
    val at = startAt?.takeIf { it.isAfter(now.plusSeconds(59)) }
    val limit = Instant.ofEpochSecond((now.epochSecond + maxSeconds) / QUARTER * QUARTER)
    /** Auf die nächste Viertelstunde, nicht vor jetzt + 1 min (sonst „Jetzt“), nicht über die Grenze des Geräts. */
    fun snap(t: Instant): Instant? {
        val s = Instant.ofEpochSecond((t.epochSecond + QUARTER / 2) / QUARTER * QUARTER)
        return if (!s.isAfter(now.plusSeconds(59))) null else if (s.isAfter(limit)) limit else s
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionLabel(stringResource(R.string.hc_start_time))
            Text(
                at?.let { stringResource(R.string.hc_starts_at, hm(it), hmDuration(java.time.Duration.between(now, it).seconds)) } ?: stringResource(R.string.hc_starts_now),
                color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            )
            Text(stringResource(R.string.hc_ring_explain), color = g.soft, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
        TimeRing(now, at, accent, enabled, onClear = { onStartAt(null) }) { t -> onStartAt(snap(t)) }
    }
}

/** 24-Stunden-Ring ab jetzt (oben), im Uhrzeigersinn. */
@Composable
private fun TimeRing(
    now: Instant, at: Instant?, accent: Color, enabled: Boolean, onClear: () -> Unit,
    ringSize: androidx.compose.ui.unit.Dp = 250.dp, minSeconds: Long = 0, centerContent: (@Composable () -> Unit)? = null, onPick: (Instant) -> Unit,
) {
    val g = LocalGlass.current
    val zone = ZoneId.systemDefault()
    val measurer = rememberTextMeasurer()
    val base by rememberUpdatedState(now)
    val pick by rememberUpdatedState(onPick)
    val current by rememberUpdatedState(at)
    fun offsetAt(p: Offset, size: IntSize): Long {
        val deg = Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), -(p.y - size.height / 2f).toDouble()))
        return (((deg + 360) % 360) / 360 * DAY).toLong()
    }
    // Über „oben“ hinweg nicht von 23:59 Std auf 0 springen – am Ende festhalten
    fun unwrap(off: Long): Long {
        val last = current?.let { java.time.Duration.between(base, it).seconds } ?: 0L
        return if (kotlin.math.abs(off - last) > DAY / 2) (if (last > DAY / 2) DAY - 1 else 0L) else off
    }
    val labelStyle = androidx.compose.ui.text.TextStyle(color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val off = at?.let { java.time.Duration.between(now, it).seconds } ?: 0L
    val sweep by animateFloatAsState(off / DAY.toFloat() * 360f, tween(120), label = "sweep")
    Box(
        Modifier.size(ringSize)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p -> pick(base.plusSeconds(offsetAt(p, size))) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ -> change.consume(); pick(base.plusSeconds(unwrap(offsetAt(change.position, size)))) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = 14.dp.toPx()
            val r = size.minDimension / 2 - 16.dp.toPx()
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            drawCircle(g.track, r, style = Stroke(w))
            // Stunden: Punkt je Stunde, Zahl alle drei Stunden
            val local = now.atZone(zone)
            val firstHour = local.withMinute(0).withSecond(0).withNano(0).plusHours(1)
            repeat(24) { k ->
                val h = firstHour.plusHours(k.toLong())
                val f = java.time.Duration.between(local, h).seconds / DAY.toFloat()
                val ang = Math.toRadians((-90f + 360f * f).toDouble())
                val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
                drawCircle(g.soft.copy(alpha = 0.5f), 2.dp.toPx(), center + dir * r)
                if (h.hour % 3 == 0) {
                    val tl2 = measurer.measure("%02d".format(h.hour), labelStyle)
                    val pos = center + dir * (r - 26.dp.toPx())
                    drawText(tl2, topLeft = Offset(pos.x - tl2.size.width / 2f, pos.y - tl2.size.height / 2f))
                }
            }
            // Jetzt-Marke oben
            drawCircle(accent, 4.dp.toPx(), Offset(center.x, center.y - r))
            // Programmdauer ab jetzt: so früh kann es frühestens fertig sein
            if (minSeconds > 0) drawArc(accent.copy(alpha = 0.35f), -90f, minSeconds / DAY.toFloat() * 360f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            if (at != null || minSeconds > 0) {
                if (at != null) drawArc(accent, -90f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                val knobSweep = if (at != null) sweep else minSeconds / DAY.toFloat() * 360f
                val ang = Math.toRadians((-90f + knobSweep).toDouble())
                val k = center + Offset(cos(ang).toFloat(), sin(ang).toFloat()) * r
                drawCircle(Color.Black.copy(alpha = 0.22f), 15.dp.toPx(), k + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, 14.dp.toPx(), k)
                drawCircle(accent, 6.dp.toPx(), k)
            }
        }
        if (centerContent != null) centerContent() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(at?.let(::hm) ?: stringResource(R.string.hc_now), color = if (at != null) g.ink else g.soft, fontSize = 34.sp, fontWeight = FontWeight.Light, maxLines = 1)
            Text(
                at?.let { stringResource(R.string.hc_start_in, hmDuration(java.time.Duration.between(now, it).seconds)) } ?: stringResource(R.string.hc_ring_hint),
                color = g.soft, fontSize = 12.sp, maxLines = 1,
            )
            // Zurück auf sofort – ohne den Knopf bis ganz nach oben zu ziehen
            if (at != null) Box(
                Modifier.padding(top = 8.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(g.chip)
                    .clickable(enabled = enabled, onClick = onClear).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.hc_now), color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Drehring (270°) um die Illustration. Leerlauf: eine Raste je Programm, Ziehen oder Tippen wählt,
 * − / + in der Lücke unten blättern. Im Betrieb zeigt derselbe Ring den Fortschritt.
 */
@Composable
internal fun DetentDial(count: Int, index: Int, progress: Float?, ring: Color, dashed: Boolean = false, enabled: Boolean, onPick: (Int) -> Unit, content: @Composable () -> Unit) {
    val g = LocalGlass.current
    val n by rememberUpdatedState(count)
    val pick by rememberUpdatedState(onPick)
    val cur by rememberUpdatedState(index)
    fun frac(i: Int) = if (count <= 1) 0f else i / (count - 1).toFloat()
    fun at(p: Offset, size: IntSize): Int {
        val a = Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), -(p.y - size.height / 2f).toDouble())).toFloat()
        val f = ((a + 135f) / 270f).coerceIn(0f, 1f)
        return (f * (n - 1)).roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0))
    }
    val knob by animateFloatAsState(frac(index), tween(220), label = "knob")
    Box(Modifier.size(330.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { p -> pick(at(p, size)) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, _ -> change.consume(); at(change.position, size).let { if (it != cur) pick(it) } }
                },
        )
        Canvas(Modifier.matchParentSize()) {
            val w = 16.dp.toPx()
            val r = size.minDimension / 2 - 14.dp.toPx()
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            drawArc(g.track, 135f, 270f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            if (progress != null) {
                // Startzeitvorwahl: gestrichelt wie in der Geräteleiste
                if (dashed) drawArc(ring, 135f, 270f, false, tl, sz, style = Stroke(w * 0.5f, cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 12.dp.toPx()))))
                else if (progress > 0f) drawArc(ring, 135f, 270f * progress, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            } else {
                // Rasten je Programm, die gewählte trägt den Knopf
                repeat(count) { i ->
                    val ang = Math.toRadians((135f + 270f * frac(i)).toDouble())
                    drawCircle(g.soft.copy(alpha = 0.6f), 3.dp.toPx(), Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat()))
                }
                val ang = Math.toRadians((135f + 270f * knob).toDouble())
                val k = Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat())
                drawCircle(Color.Black.copy(alpha = 0.22f), 15.dp.toPx(), k + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, 14.dp.toPx(), k)
                drawCircle(ring, 6.dp.toPx(), k)
            }
        }
        content()
        if (progress == null && count > 1) RingButtons(enabled, canDown = index > 0, canUp = index < count - 1, Modifier.align(Alignment.BottomCenter)) { pick((index + it).coerceIn(0, count - 1)) }
    }
}

/** Trommel: dreht sich beim Waschen/Trocknen, schneller beim Schleudern; Wasserstand beim Waschen. */
@Composable
private fun DrumVisual(progress: Float, ring: Color, running: Boolean, fast: Boolean, water: Boolean, dryer: Boolean, diameter: androidx.compose.ui.unit.Dp = 280.dp, frame: Boolean = true) {
    val g = LocalGlass.current
    val period = if (fast) 700 else if (dryer) 1800 else 2600
    // Animation nur, solange die Trommel läuft – sonst kostet sie auf dem Panel jedes Bild Rechenzeit
    val angle = if (running) rememberInfiniteTransition(label = "drum")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "angle") else null
    val laundry = listOf(Color(0xFFE86A5B), Color(0xFFF2F0EA), Color(0xFF6C93C9), Color(0xFFF4C04E), Color(0xFF8FC9A3), Color(0xFFF2F0EA))
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 12.dp.toPx()
            if (frame) drawArc(g.track, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
            if (frame && progress > 0f) drawArc(ring, -90f, 360f * progress, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
            val body = size.minDimension / 2 - w
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFDCE1E7), Color(0xFFBCC4CE)), center + Offset(-body * .3f, -body * .44f), body * 1.6f), body)
            val door = body - 24.dp.toPx()
            drawCircle(Color(0xFFAEB7C2), door)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF42526E), Color(0xFF151B29)), center + Offset(-door * .16f, -door * .28f), door * 1.2f), door - 8.dp.toPx())
        }
        // Innenleben in eigener Ebene drehen
        Canvas(Modifier.size(diameter * (200f / 280f)).clip(androidx.compose.foundation.shape.CircleShape)) {
            val r = size.minDimension / 2
            if (water) drawRect(Brush.verticalGradient(listOf(Color(0x528CC8FF), Color(0x8C468CE6))), Offset(0f, size.height * 0.62f), Size(size.width, size.height * 0.38f))
            rotate(angle?.value ?: 0f) {
                laundry.forEachIndexed { i, c ->
                    val a = i / laundry.size.toFloat() * 2 * Math.PI.toFloat() + 0.6f
                    val rr = if (running) r * 0.6f else r * 0.44f
                    val cx = center.x + cos(a) * rr
                    val cy = (if (running) center.y else center.y + r * 0.28f) + sin(a) * (if (running) rr else r * 0.2f)
                    blob(c, Offset(cx, cy), (22 + (i % 3) * 6).dp.toPx(), (16 + (i % 2) * 6).dp.toPx(), i * 47f)
                }
            }
            drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), Offset(r * 0.74f, r * 0.5f), r * 0.5f), Offset(r * 0.32f, r * 0.24f), Size(r * 0.84f, r * 0.52f))
        }
    }
}

private fun DrawScope.blob(color: Color, c: Offset, w: Float, h: Float, deg: Float) {
    rotate(deg, c) { drawRoundRect(color, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(w * 0.45f, h * 0.5f)) }
}

/** Geschirrspüler von innen: Tassen oben, Teller unten; Sprühstrahlen beim Spülen, Dampf beim Trocknen. */
@Composable
private fun DishVisual(progress: Float, ring: Color, spraying: Boolean, steaming: Boolean, diameter: androidx.compose.ui.unit.Dp = 280.dp, frame: Boolean = true) {
    val g = LocalGlass.current
    val phase = if (spraying) rememberInfiniteTransition(label = "spray")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "p") else null
    // In Referenzgröße zeichnen und skalieren – die Innenansicht ist in festen Maßen gezeichnet
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
    Box(Modifier.requiredSize(280.dp).graphicsLayer { scaleX = diameter / 280.dp; scaleY = diameter / 280.dp }) {
        Canvas(Modifier.fillMaxSize()) {
            // Fortschritt als Rahmen um das Gerät (von oben im Uhrzeigersinn)
            val w = 10.dp.toPx()
            if (frame) drawRoundRect(g.track, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(34.dp.toPx()), style = Stroke(w))
            if (frame && progress > 0f) rotate(-90f) {
                drawRoundRect(
                    Brush.sweepGradient(0f to ring, progress to ring, progress to Color.Transparent, 1f to Color.Transparent, center = center),
                    Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(34.dp.toPx()), style = Stroke(w),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().padding(14.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFD9DEE4), Color(0xFFBCC4CE)))),
        ) {
            Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFAEB7C2)))
            Canvas(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 36.dp, bottom = 18.dp).clip(RoundedCornerShape(16.dp))) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF3A4A63), Color(0xFF151B29))))
                val cupW = 22.dp.toPx(); val cupH = 20.dp.toPx()
                repeat(5) { i ->
                    val x = size.width * (0.1f + i * 0.2f) - cupW / 2
                    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(x, 34.dp.toPx()), Size(cupW, cupH), CornerRadius(4.dp.toPx()))
                    drawRoundRect(Color.White.copy(alpha = 0.75f), Offset(x, 34.dp.toPx()), Size(cupW, cupH), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
                drawRect(Color(0xFF8C96A3), Offset(8.dp.toPx(), 58.dp.toPx()), Size(size.width - 16.dp.toPx(), 3.dp.toPx()))
                val plates = listOf(46, 54, 58, 58, 54, 46, 40, 48, 52)
                plates.forEachIndexed { i, h ->
                    val x = 14.dp.toPx() + i * (size.width - 28.dp.toPx() - 9.dp.toPx()) / (plates.size - 1)
                    val ph = h.dp.toPx()
                    drawRoundRect(Brush.horizontalGradient(listOf(Color.White, Color(0xFFDCE3EA))), Offset(x, size.height - 26.dp.toPx() - ph), Size(9.dp.toPx(), ph), CornerRadius(5.dp.toPx()))
                }
                drawRect(Color(0xFF8C96A3), Offset(8.dp.toPx(), size.height - 25.dp.toPx()), Size(size.width - 16.dp.toPx(), 3.dp.toPx()))
                if (phase != null) repeat(14) { i ->
                    val p = (phase.value * (1f + (i % 4) * 0.18f) + (i % 5) * 0.17f) % 1f
                    val x = size.width * (0.06f + i * 0.066f)
                    val y = size.height - 10.dp.toPx() - p * 150.dp.toPx()
                    val a = if (p < 0.15f) p / 0.15f else 1f - p
                    drawRoundRect(Color(0xCCA0D7FF).copy(alpha = 0.8f * a), Offset(x, y - 14.dp.toPx()), Size(3.dp.toPx(), 14.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
                if (steaming) drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.6f to Color.White.copy(alpha = 0.05f)))
            }
        }
    }
    }
}
