package app.raum.ui.household

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.homeconnect.HcAppliance
import app.raum.homeconnect.HcKeys
import app.raum.homeconnect.HcOptionDef
import app.raum.homeconnect.HcProgram
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.OperationState
import app.raum.homeconnect.SavedDrink
import app.raum.homeconnect.enumTail
import app.raum.ui.theme.LocalGlass
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Cup(val w: Dp, val h: Dp, val bottom: Dp, val handle: Boolean, val saucer: Boolean) {
    DEMI(84.dp, 66.dp, 36.dp, true, true),
    CUP(122.dp, 88.dp, 46.dp, true, true),
    MUG(104.dp, 120.dp, 26.dp, true, false),
    GLASS(80.dp, 196.dp, 16.dp, false, false),
}

private class Look(val cup: Cup, val layers: List<Pair<Color, Float>>, val descRes: Int?)

private fun c(hex: Long) = Color(0xFF000000 or hex)

/** Tasse und Schichten je Getränk (unten → oben), aus dem Entwurf; unbekannte Getränke nach Gefäß geschätzt. */
private fun look(key: String): Look = when (enumTail(key)) {
    "Ristretto" -> Look(Cup.DEMI, listOf(c(0x2E190E) to .82f, c(0xB97A45) to .18f), R.string.hc_bev_ristretto)
    "Espresso" -> Look(Cup.DEMI, listOf(c(0x3B2214) to .84f, c(0xC98B4E) to .16f), R.string.hc_bev_espresso)
    "EspressoDoppio", "Doppio" -> Look(Cup.DEMI, listOf(c(0x3B2214) to .86f, c(0xC98B4E) to .14f), R.string.hc_bev_doppio)
    "EspressoMacchiato" -> Look(Cup.DEMI, listOf(c(0x3B2214) to .6f, c(0xF6EFE6) to .4f), R.string.hc_bev_macchiato)
    "Cortado", "CafeCortado" -> Look(Cup.DEMI, listOf(c(0x5A3720) to .55f, c(0xE9D7C0) to .45f), null)
    "Coffee", "CaffeGrande", "XLCoffee", "Verlaengerter", "KleinerBrauner", "GrosserBrauner" ->
        Look(Cup.CUP, listOf(c(0x4A2C1A) to .88f, c(0xC98B4E) to .12f), R.string.hc_bev_coffee)
    "Cappuccino" -> Look(Cup.CUP, listOf(c(0x6B4128) to .44f, c(0xD4AE86) to .14f, c(0xFBF6EE) to .42f), R.string.hc_bev_cappuccino)
    "FlatWhite" -> Look(Cup.CUP, listOf(c(0x7A4C2E) to .78f, c(0xF1E6D6) to .22f), R.string.hc_bev_flat_white)
    "WienerMelange", "CafeAuLait", "CafeConLeche" -> Look(Cup.CUP, listOf(c(0x8A5A38) to .6f, c(0xF6EEE2) to .4f), null)
    "LatteMacchiato", "Galao" -> Look(Cup.GLASS, listOf(c(0xF3E9DB) to .44f, c(0x8A5A38) to .2f, c(0xFCF8F1) to .36f), R.string.hc_bev_latte_macchiato)
    "CaffeLatte" -> Look(Cup.GLASS, listOf(c(0xB07C55) to .74f, c(0xFBF6EE) to .26f), R.string.hc_bev_caffe_latte)
    "Americano", "RedEye", "BlackEye", "DeadEye", "Kaffeekanne" -> Look(Cup.MUG, listOf(c(0x3A2416) to .92f, c(0xA36F42) to .08f), R.string.hc_bev_americano)
    "HotWater" -> Look(Cup.MUG, listOf(c(0xBFDDEE) to 1f), R.string.hc_bev_hot_water)
    "MilkFroth" -> Look(Cup.GLASS, listOf(c(0xFBF6EE) to 1f), R.string.hc_bev_milk_froth)
    "WarmMilk" -> Look(Cup.MUG, listOf(c(0xF6EFE6) to 1f), R.string.hc_bev_warm_milk)
    else -> Look(Cup.CUP, listOf(c(0x5A3720) to .8f, c(0xD4AE86) to .2f), null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.CoffeePanel(a: HcAppliance, programs: List<HcProgram>, vm: HouseholdViewModel, busy: Boolean) {
    val g = LocalGlass.current
    val drinks by vm.drinks.collectAsStateWithLifecycle()
    if (programs.isEmpty()) { LoadingPrograms(); return }

    val brewing = a.operation == OperationState.RUN || a.operation == OperationState.PAUSE || a.operation == OperationState.ACTION_REQUIRED
    // Spülen beim Ein-/Ausschalten, Reinigen, Entkalken: läuft, ist aber kein Getränk – nicht als Bezug zeigen
    val care = brewing && app.raum.homeconnect.isCareProgram(a.type, a.activeProgram)
    val done = !brewing && (a.operation == OperationState.FINISHED || HcKeys.PROGRAM_FINISHED in a.events)
    var index by remember(a.haId) { mutableIntStateOf(programs.indexOfFirst { it.key.endsWith("Cappuccino") }.coerceAtLeast(0)) }
    val activeIndex = a.activeProgram?.let { k -> programs.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
    LaunchedEffect(activeIndex, brewing) { if (brewing && activeIndex != null) index = activeIndex }
    index = index.coerceIn(0, programs.lastIndex)
    val program = programs[index]
    LaunchedEffect(program.key) { if (!program.detailed) vm.ensureDetail(a.haId, program.key) }
    // Echte Grenzen erst nach Auswahl am Gerät – wenn das Karussell kurz stehen bleibt, einmal je Getränk
    val ready = a.connected && a.operation == OperationState.READY
    LaunchedEffect(program.key, program.refined, ready) {
        if (ready && !program.refined) { kotlinx.coroutines.delay(1200); vm.refine(a.haId, program.key) }
    }

    // Gewählte Optionen je Getränk (bleiben beim Durchblättern erhalten)
    val choices = remember(a.haId) { mutableStateMapOf<String, Map<String, JsonPrimitive>>() }
    // Immer auf das (ggf. nachgeladene) Raster bringen – eine vorher gewählte Menge kann außerhalb liegen
    fun value(o: HcOptionDef): JsonPrimitive? = (choices[program.key]?.get(o.key) ?: o.default)?.let { o.fit(it) ?: o.default }
    fun set(o: HcOptionDef, v: JsonPrimitive) { choices[program.key] = choices[program.key].orEmpty() + (o.key to v) }

    val beans = program.option(HcKeys.COFFEE_BEANS)
    val fill = program.option(HcKeys.COFFEE_FILL)
    val temp = program.option(HcKeys.COFFEE_TEMP) ?: program.option(HcKeys.HOT_WATER_TEMP)
    val progress = if (brewing) (a.progressPercent ?: 0) / 100f else 0f
    val warning = a.warnings.firstOrNull()
    val locked = brewing || busy

    val (ok, hint) = startHint(a)
    // Zwei Spalten: links Getränkewahl (Meine Getränke, Karussell, Name), rechts Einstellungen und Start –
    // so bleibt der Startknopf immer voll sichtbar, auch wenn oben die Geräteleiste Höhe braucht
    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hc_my_drinks).uppercase(), color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(end = 4.dp))
                drinks.forEach { d ->
                    DrinkChip(Icons.Outlined.LocalCafe, d.label, enabled = !locked, onLongClick = { vm.removeDrink(d) }) {
                        val i = programs.indexOfFirst { it.key == d.programKey }
                        if (i >= 0) { index = i; choices[d.programKey] = d.options }
                    }
                }
                DrinkChip(Icons.Outlined.Add, stringResource(R.string.hc_save_drink), enabled = !locked && program.detailed) {
                    val opts = program.options.mapNotNull { o -> value(o)?.let { o.key to it } }.toMap()
                    val strength = beans?.let { b -> value(b)?.contentOrNull?.let(b::label) }
                    val ml = fill?.let { f -> value(f)?.doubleOrNull?.toInt()?.let { "$it ml" } }
                    vm.saveDrink(SavedDrink(program.key, listOfNotNull(program.name, strength ?: ml).joinToString(" · "), opts))
                }
            }
            Spacer(Modifier.weight(1f))
            CupCarousel(programs, index, progress, brewing && !care, done, enabled = !locked) { index = it }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(program.name, color = g.ink, fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp, maxLines = 1)
                look(program.key).descRes?.let { Text(stringResource(it), color = g.soft, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
            Spacer(Modifier.weight(1f))
        }

        // Einstellungen: je eine Zeile mit − / + wie bei Rollladen und Thermostat, darunter der Start
        Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.alpha(if (brewing) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (beans != null && beans.allowed.isNotEmpty()) {
                    val sel = beans.allowed.indexOf(value(beans)?.contentOrNull).coerceAtLeast(0)
                    Stepper(
                        beans.name, beans.label(beans.allowed[sel]), null,
                        canDown = sel > 0, canUp = sel < beans.allowed.lastIndex, enabled = !locked,
                        onDown = { set(beans, JsonPrimitive(beans.allowed[sel - 1])) },
                        onUp = { set(beans, JsonPrimitive(beans.allowed[sel + 1])) },
                    ) {
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            beans.allowed.indices.forEach { i -> Bean(on = i <= sel, small = true) }
                        }
                    }
                }
                if (fill != null && fill.min != null && fill.max != null) {
                    val ml = value(fill)?.doubleOrNull ?: fill.min
                    // Mindestens 10 ml je Tipp – die allgemeine Schrittweite (5 ml) wäre mühsam
                    val st = (fill.step ?: 10.0).coerceAtLeast(10.0)
                    Stepper(
                        stringResource(R.string.hc_amount), "${ml.toInt()} ml", "${fill.min.toInt()}–${fill.max.toInt()} ml",
                        canDown = ml > fill.min, canUp = ml < fill.max, enabled = !locked,
                        onDown = { set(fill, fill.fit(JsonPrimitive((ml - st).coerceAtLeast(fill.min))) ?: JsonPrimitive(fill.min)) },
                        onUp = { set(fill, fill.fit(JsonPrimitive((ml + st).coerceAtMost(fill.max))) ?: JsonPrimitive(fill.max)) },
                    )
                }
                if (temp != null && temp.allowed.isNotEmpty()) Column {
                    SectionLabel(temp.name)
                    Segmented(temp.allowed.map(temp::label), temp.allowed.indexOf(value(temp)?.contentOrNull).coerceAtLeast(0), enabled = !locked) {
                        set(temp, JsonPrimitive(temp.allowed[it]))
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            val button = Modifier.fillMaxWidth()
            when {
                warning != null -> ActionButton(stringResource(R.string.hc_fix_warning, stringResource(HomeConnectService.warningLabel(warning))), Icons.Outlined.ErrorOutline,
                    g.chip, g.soft, button, height = 72.dp, enabled = false) {}
                care -> ActionButton(stringResource(R.string.hc_care_running), Icons.Outlined.HourglassTop,
                    g.chip, g.soft, button, height = 72.dp, enabled = false) {}
                brewing -> ActionButton(stringResource(R.string.hc_brewing_cancel, (progress * 100).roundToInt()), Icons.Outlined.StopCircle,
                    HcColors.Coffee, Color(0xFF1F1206), button, height = 72.dp, enabled = !busy, progress = progress) { vm.stop(a.haId) }
                done -> ActionButton(stringResource(R.string.hc_brew_done, a.activeProgram?.let { k -> programs.firstOrNull { it.key == k }?.name } ?: program.name), Icons.Outlined.Check,
                    HcColors.Done, Color(0xFF0F2E1A), button, height = 72.dp) {}
                // Menge steht direkt darüber – im Knopf nur das Getränk, damit nichts abgeschnitten wird
                else -> ActionButton(
                    stringResource(R.string.hc_brew, program.name),
                    Icons.Outlined.PlayArrow, if (ok) HcColors.Coffee else g.chip, if (ok) Color(0xFF1F1206) else g.soft,
                    button, height = 72.dp, enabled = ok && !busy && program.detailed,
                ) { vm.start(a.haId, program.key, program.options.mapNotNull { o -> value(o)?.let { o.key to it } }.toMap()) }
            }
            if (!ok && warning == null && !brewing) FootNote(false, hint)
        }
    }
}

/** Einstellung als Zeile: − Wert + (wie die Favoriten-Karten), Bezeichnung und Bereich darüber. */
@Composable
fun LoadingPrograms() {
    val g = LocalGlass.current
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.hc_loading_programs), color = g.soft, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrinkChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(g.chip)
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFFB7791F))
        Text(label, color = g.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Kaffeebohne: gefüllt bis zur gewählten Stärke. */
@Composable
private fun Bean(on: Boolean, small: Boolean = false) {
    val g = LocalGlass.current
    Canvas(Modifier.size(if (small) 8.dp else 14.dp, if (small) 12.dp else 20.dp).rotate(25f)) {
        drawOval(if (on) Color(0xFF6B4128) else g.track)
        if (on) drawLine(Color(0xFFC98B4E), Offset(size.width / 2, size.height * 0.15f), Offset(size.width / 2, size.height * 0.85f), strokeWidth = 2.dp.toPx())
    }
}

/** Menge als Schieber: tippen oder ziehen, auf die Schrittweite der Maschine gerundet. */
@Composable
internal fun AmountSlider(value: Double, min: Double, max: Double, step: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    val g = LocalGlass.current
    var width by remember { mutableFloatStateOf(1f) }
    // Die Gesten laufen über Getränkewechsel hinweg weiter – immer die aktuellen Grenzen und das aktuelle Getränk
    // verwenden, sonst landet die Menge beim zuvor gezeigten Getränk
    val range by rememberUpdatedState(Triple(min, max, step))
    val change by rememberUpdatedState(onChange)
    fun at(x: Float): Double {
        val (lo, hi, st) = range
        val f = (x / width).coerceIn(0f, 1f)
        return (Math.round((lo + f * (hi - lo)) / st) * st).coerceIn(lo, hi)
    }
    val frac = ((value - min) / (max - min).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(g.chip)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { change(at(it.x)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { c, _ -> c.consume(); change(at(c.position.x)) }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.2f)).clip(RoundedCornerShape(26.dp)).background(Color(0xFFE8C9A0)))
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${value.toInt()} ml", color = if (g.dark && frac > 0.3f) Color(0xFF10131F) else g.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.WaterDrop, null, Modifier.size(20.dp), tint = g.soft)
        }
    }
}

private val SLOT = 150.dp

/**
 * Tassen-Karussell: Die Tassen folgen dem Finger, beim Loslassen rastet es auf das nächste Getränk ein
 * (schon ein kurzer Wisch wechselt um eins). Position als Animatable, damit nichts zurückspringt.
 */
@Composable
private fun CupCarousel(programs: List<HcProgram>, index: Int, progress: Float, brewing: Boolean, done: Boolean, enabled: Boolean, onPick: (Int) -> Unit) {
    val slotPx = with(LocalDensity.current) { SLOT.toPx() }
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val currentIndex by rememberUpdatedState(index)
    val pick by rememberUpdatedState(onPick)
    val last by rememberUpdatedState(programs.lastIndex)
    LaunchedEffect(index) { if (!dragging) pos.animateTo(index.toFloat(), tween(380)) }
    // Nur die Tassen in Sichtweite zusammensetzen; verschoben wird in der Zeichenebene (kein Neuaufbau je Bild)
    val center by remember { derivedStateOf { pos.value.roundToInt() } }
    Box(
        Modifier.fillMaxWidth().height(250.dp).padding(top = 6.dp)
            // Ränder ausblenden wie im Entwurf (Maske)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var start = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; start = pos.value },
                    onDragEnd = {
                        dragging = false
                        var target = pos.value.roundToInt()
                        val moved = pos.value - start
                        if (target == currentIndex && abs(moved) > 0.15f) target += if (moved > 0) 1 else -1
                        target = target.coerceIn(0, last)
                        scope.launch { pos.animateTo(target.toFloat(), tween(320)) }
                        if (target != currentIndex) pick(target)
                    },
                    onDragCancel = { dragging = false; scope.launch { pos.animateTo(currentIndex.toFloat(), tween(320)) } },
                ) { c, dx ->
                    c.consume()
                    scope.launch { pos.snapTo((pos.value - dx / slotPx).coerceIn(-0.4f, last + 0.4f)) }
                }
            },
    ) {
        programs.forEachIndexed { i, p ->
            if (abs(i - center) > 4) return@forEachIndexed
            key(p.key) {
                val selected = i == index
                val l = look(p.key)
                val scale = if (selected) (if (l.cup == Cup.GLASS) 1f else 1.18f) else (if (l.cup == Cup.GLASS) 0.62f else 0.72f)
                val animScale by animateFloatAsState(scale, tween(400), label = "scale")
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .graphicsLayer { translationX = (i - pos.value) * slotPx }
                        .padding(bottom = 20.dp).width(SLOT).height(226.dp)
                        .clickable(enabled = enabled, indication = null, interactionSource = null) { pick(i) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(Modifier.graphicsLayer {
                        scaleX = animScale; scaleY = animScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = if (selected) 1f else 0.55f
                    }) {
                        CupView(l, fill = if (selected && brewing) progress else 1f, steam = selected && ((brewing && progress > 0.3f) || done))
                    }
                }
            }
        }
        Arrow(Icons.Outlined.ChevronLeft, Modifier.align(Alignment.CenterStart).padding(start = 6.dp), enabled && index > 0) { pick(index - 1) }
        Arrow(Icons.Outlined.ChevronRight, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), enabled && index < programs.lastIndex) { pick(index + 1) }
    }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.size(52.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(g.glassSolid).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(28.dp), tint = g.ink) }
}

/** Tasse/Glas mit Schichten, Untertasse, Henkel und Dampf. */
@Composable
private fun CupView(l: Look, fill: Float, steam: Boolean) {
    val cup = l.cup
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = cup.bottom, bottomEnd = cup.bottom)
    val animFill by animateFloatAsState(fill, tween(250, easing = LinearEasing), label = "fill")
    Box(Modifier.size(cup.w, cup.h)) {
        if (cup.saucer) Box(
            Modifier.align(Alignment.BottomCenter).offset(y = 9.dp).size(cup.w * 1.44f, 14.dp)
                .shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.92f)),
        )
        if (cup.handle) Canvas(Modifier.align(Alignment.TopEnd).offset(x = 24.dp, y = cup.h * 0.16f).size(26.dp, cup.h * 0.4f)) {
            val w = 6.dp.toPx()
            drawArc(Color.White.copy(alpha = 0.95f), -90f, 180f, false, Offset(-size.width + w / 2, w / 2), Size(size.width * 2 - w, size.height - w), style = Stroke(w))
        }
        if (steam) Steam(Modifier.align(Alignment.TopCenter).offset(y = (-14).dp))
        Box(
            Modifier.fillMaxSize().clip(shape).background(Color.White.copy(alpha = 0.28f)).border(3.dp, Color.White.copy(alpha = 0.96f), shape),
        ) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f * animFill)) {
                l.layers.asReversed().forEach { (color, f) -> Box(Modifier.fillMaxWidth().weight(f).background(color)) }
            }
            Box(
                Modifier.padding(start = cup.w * 0.12f, top = cup.h * 0.08f).size(cup.w * 0.12f, cup.h * 0.7f).clip(RoundedCornerShape(8.dp))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)))),
            )
        }
    }
}

@Composable
private fun Steam(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "steam")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "s1")
    Row(modifier.width(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(a, (a + 0.46f) % 1f).forEach { p ->
            Box(
                Modifier.graphicsLayer { translationY = -38.dp.toPx() * p; scaleX = 1f + 0.6f * p; alpha = (if (p < 0.3f) p / 0.3f else 1f - (p - 0.3f) / 0.7f) * 0.7f }
                    .size(10.dp, 26.dp).blur(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.65f)),
            )
        }
    }
}
