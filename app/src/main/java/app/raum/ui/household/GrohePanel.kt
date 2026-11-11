package app.raum.ui.household

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.grohe.GroheDevice
import app.raum.grohe.GroheService
import app.raum.grohe.Pouring
import app.raum.grohe.WaterType
import app.raum.grohe.labelRes
import app.raum.ui.theme.LocalGlass
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

object GroheColors {
    val Water = Color(0xFF7FC8F8)
    val OnWater = Color(0xFF07263A)
}

val GroheIcon = Icons.Outlined.WaterDrop

/** Kurzstatus für Karte und Übersicht. */
@Composable
fun summarizeGrohe(d: GroheDevice, pouring: Pouring?, now: Instant): ApplianceSummary {
    val accent = GroheColors.Water
    if (!d.online) return ApplianceSummary(stringResource(R.string.hc_status_offline), "", 0f, accent, false, false, false, offline = true)
    if (pouring != null && now.isBefore(pouring.end)) {
        val total = Duration.between(pouring.start, pouring.end).toMillis().coerceAtLeast(1)
        val p = (Duration.between(pouring.start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
        return ApplianceSummary(stringResource(R.string.grohe_pouring), stringResource(R.string.grohe_amount_type, pouring.ml, stringResource(pouring.type.labelRes).lowercase()), p, accent, true, false, false)
    }
    val warning = when {
        d.filterEmpty -> stringResource(R.string.grohe_filter_empty)
        d.co2Empty -> stringResource(R.string.grohe_co2_empty)
        d.cleaningNeeded -> stringResource(R.string.grohe_cleaning_needed)
        d.filterLow -> stringResource(R.string.grohe_filter_low)
        d.co2Low -> stringResource(R.string.grohe_co2_low)
        else -> null
    }
    val levels = listOfNotNull(
        d.filterPercent?.let { stringResource(R.string.grohe_filter_short, it.roundToInt()) },
        d.co2Percent?.let { stringResource(R.string.grohe_co2_short, it.roundToInt()) },
    ).joinToString(" · ")
    return ApplianceSummary(stringResource(R.string.hc_status_ready), warning ?: levels, 0f, accent, false, false, false, subAlert = warning != null)
}

/** Detailansicht: Glas, Wasserart, Menge, Füllstände. */
@Composable
fun ColumnScope.GrohePanel(d: GroheDevice, pouring: Pouring?, vm: HouseholdViewModel, busy: Boolean, now: Instant) {
    val g = LocalGlass.current
    var type by remember(d.id) { mutableStateOf(WaterType.SPARKLING) }
    var ml by remember(d.id) { mutableIntStateOf(250) }
    val active = pouring != null && now.isBefore(pouring.end)
    val progress = if (active) {
        val total = Duration.between(pouring!!.start, pouring.end).toMillis().coerceAtLeast(1)
        (Duration.between(pouring.start, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
    } else 0f

    // Kopf
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(GroheIcon, null, Modifier.size(30.dp), tint = g.soft)
        Column(Modifier.weight(1f)) {
            Text(d.name, color = g.ink, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Grohe", color = g.soft, fontSize = 14.sp)
        }
        groheChips(d).forEach { HeadChip(it) }
    }
    if (!d.online) {
        Text(stringResource(R.string.hc_offline_explain), color = g.soft, fontSize = 18.sp, modifier = Modifier.padding(top = 24.dp))
        Spacer(Modifier.weight(1f))
        return
    }

    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            WaterGlass(if (active) pouring!!.type else type, if (active) progress else 0.8f, animate = active, modifier = Modifier.size(220.dp, 300.dp))
            if (active) Text(
                stringResource(R.string.grohe_amount_type, pouring!!.ml, stringResource(pouring.type.labelRes).lowercase()),
                color = g.soft, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionLabel(stringResource(R.string.grohe_water))
            Segmented(WaterType.entries.map { stringResource(it.labelRes) }, WaterType.entries.indexOf(type), enabled = !active && !busy) { type = WaterType.entries[it] }
            SectionLabel(stringResource(R.string.hc_amount), Modifier.padding(top = 18.dp), trailing = "50–2000")
            AmountSlider(ml.toDouble(), 50.0, 2000.0, 50.0, enabled = !active && !busy) { ml = it.roundToInt() }
            val quick = listOf(150, 250, 500, 1000)
            Segmented(quick.map { if (it >= 1000) "${it / 1000} l" else "$it ml" }, quick.indexOf(ml), Modifier.padding(top = 10.dp), enabled = !active && !busy) { ml = quick[it] }
            Spacer(Modifier.height(20.dp))
            Level(stringResource(R.string.grohe_filter), d.filterPercent, d.filterLiters, low = d.filterLow)
            Level(stringResource(R.string.grohe_co2), d.co2Percent, d.co2Liters, low = d.co2Low)
            Spacer(Modifier.weight(1f))
            if (active) {
                ActionButton(stringResource(R.string.grohe_pouring_amount, pouring!!.ml), Icons.Outlined.HourglassTop, GroheColors.Water, GroheColors.OnWater,
                    Modifier.fillMaxWidth(), height = 68.dp, enabled = false, progress = progress) {}
            } else {
                val blocked = d.filterEmpty || (type != WaterType.STILL && d.co2Empty)
                ActionButton(
                    // im Satz klein: „250 ml sprudelnd zapfen“
                    stringResource(R.string.grohe_dispense, ml, stringResource(type.labelRes).lowercase()), GroheIcon,
                    if (blocked) g.chip else GroheColors.Water, if (blocked) g.soft else GroheColors.OnWater,
                    Modifier.fillMaxWidth(), height = 68.dp, enabled = !busy && !blocked,
                ) { vm.dispense(d.id, type, ml) }
                FootNote(!blocked, stringResource(if (blocked) R.string.grohe_blocked else R.string.grohe_glass_hint))
            }
        }
    }
}

@Composable
private fun groheChips(d: GroheDevice): List<ChipSpec> = buildList {
    if (!d.online) add(ChipSpec(Icons.Outlined.CloudOff, stringResource(R.string.hc_status_offline), warn = true))
    if (d.filterEmpty) add(ChipSpec(Icons.Outlined.WaterDrop, stringResource(R.string.grohe_filter_empty), warn = true))
    if (d.co2Empty) add(ChipSpec(Icons.Outlined.NotificationsActive, stringResource(R.string.grohe_co2_empty), warn = true))
    if (d.cleaningNeeded) add(ChipSpec(Icons.Outlined.NotificationsActive, stringResource(R.string.grohe_cleaning_needed), warn = true))
}

/** Füllstand Filter bzw. CO₂ als Balken mit Prozent und Litern. */
@Composable
private fun Level(label: String, percent: Double?, liters: Double?, low: Boolean) {
    val g = LocalGlass.current
    if (percent == null && liters == null) return
    val f = ((percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(g.chip)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(5.dp)).background(if (low) g.warn else GroheColors.Water))
        }
        Text(
            listOfNotNull(percent?.let { "${it.roundToInt()} %" }, liters?.let { stringResource(R.string.grohe_liters, it.roundToInt()) }).joinToString(" · "),
            color = if (low) g.warn else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

/** Trinkglas: Wasser steigt beim Zapfen, Bläschen je nach Wasserart (still: keine). */
@Composable
private fun WaterGlass(type: WaterType, fill: Float, animate: Boolean, modifier: Modifier) {
    val phase = if (animate) rememberInfiniteTransition(label = "bubbles").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "b") else null
    val bubbles = when (type) { WaterType.STILL -> 0; WaterType.MEDIUM -> 10; WaterType.SPARKLING -> 24 }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val glass = Path().apply {
            moveTo(w * 0.12f, h * 0.04f); lineTo(w * 0.88f, h * 0.04f); lineTo(w * 0.8f, h * 0.96f)
            quadraticTo(w * 0.79f, h * 0.99f, w * 0.75f, h * 0.99f); lineTo(w * 0.25f, h * 0.99f)
            quadraticTo(w * 0.21f, h * 0.99f, w * 0.2f, h * 0.96f); close()
        }
        drawPath(glass, Color.White.copy(alpha = 0.28f))
        clipPath(glass) {
            val top = h * (1f - 0.92f * fill.coerceIn(0f, 1f))
            drawRect(Brush.verticalGradient(listOf(GroheColors.Water.copy(alpha = 0.55f), Color(0xFF3E9BE0).copy(alpha = 0.75f)), startY = top, endY = h), Offset(0f, top), Size(w, h - top))
            drawRect(Color.White.copy(alpha = 0.55f), Offset(0f, top), Size(w, 3.dp.toPx()))
            val t = phase?.value ?: 0f
            repeat(bubbles) { i ->
                val x = w * (0.25f + ((i * 37) % 50) / 100f)
                val base = ((i * 53) % 100) / 100f
                val y = top + (h - top) * (((base - t * (0.6f + (i % 3) * 0.2f)) % 1f + 1f) % 1f)
                if (y > top + 4.dp.toPx()) drawCircle(Color.White.copy(alpha = 0.7f), (2 + i % 3).dp.toPx(), Offset(x, y))
            }
        }
        drawPath(glass, Color.White.copy(alpha = 0.95f), style = Stroke(3.dp.toPx()))
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent)), Offset(w * 0.22f, h * 0.1f), Size(w * 0.06f, h * 0.7f), CornerRadius(8.dp.toPx()))
    }
}
