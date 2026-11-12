package app.raum.ui.household

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.ui.draw.alpha
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
import androidx.compose.material.icons.outlined.BubbleChart
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

    // Menge am Drehring: fein bei Gläsern, gröber bei Karaffen
    val i = GROHE_AMOUNTS.indexOfFirst { it >= ml }.let { if (it < 0) GROHE_AMOUNTS.lastIndex else it }
    val shownType = if (active) pouring!!.type else type
    val shownMl = if (active) pouring!!.ml else GROHE_AMOUNTS[i]

    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // Links: Glas im Drehring – der Ring stellt die Menge, im Betrieb zeigt er den Fortschritt
        Column(Modifier.width(340.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            DetentDial(
                count = GROHE_AMOUNTS.size, index = i, progress = if (active) progress else null, ring = GroheColors.Water,
                enabled = !active && !busy, onPick = { ml = GROHE_AMOUNTS[it] },
            ) {
                // Füllhöhe zeigt die gewählte Menge – beim Zapfen steigt das Wasser
                WaterGlass(shownType, if (active) progress else 0.25f + 0.7f * i / GROHE_AMOUNTS.lastIndex, animate = active, modifier = Modifier.size(150.dp, 196.dp))
            }
            Text(amountLabel(shownMl), color = g.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 14.dp))
            Text(
                ((if (active) stringResource(R.string.grohe_pouring) + " · " else "") + stringResource(shownType.labelRes)).uppercase(),
                color = g.soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.weight(1f))
        }
        // Rechts: Wasserart, Füllstände, Zapfen
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionLabel(stringResource(R.string.grohe_water))
            Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                WaterType.entries.forEach { t ->
                    WaterCard(t, selected = t == shownType, available = t == WaterType.STILL || !d.co2Empty, enabled = !active && !busy, Modifier.weight(1f).fillMaxHeight()) { type = t }
                }
            }
            if (active) {
                ActionButton(stringResource(R.string.grohe_pouring_amount, pouring!!.ml), Icons.Outlined.HourglassTop, GroheColors.Water, GroheColors.OnWater,
                    Modifier.fillMaxWidth(), height = 64.dp, enabled = false, progress = progress) {}
            } else {
                val blocked = d.filterEmpty || (type != WaterType.STILL && d.co2Empty)
                ActionButton(
                    // im Satz klein: „250 ml sprudelnd zapfen“
                    stringResource(R.string.grohe_dispense, shownMl, stringResource(type.labelRes).lowercase()), GroheIcon,
                    if (blocked) g.chip else GroheColors.Water, if (blocked) g.soft else GroheColors.OnWater,
                    Modifier.fillMaxWidth(), height = 64.dp, enabled = !busy && !blocked,
                ) { vm.dispense(d.id, type, shownMl) }
                FootNote(!blocked, stringResource(if (blocked) R.string.grohe_blocked else R.string.grohe_glass_hint))
            }
        }
    }
}

/** Zapfmengen am Ring (ml): Glas, Becher, Flasche, Karaffe. */
private val GROHE_AMOUNTS = listOf(50, 100, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000)

private fun amountLabel(ml: Int) = if (ml >= 1000) (if (ml % 1000 == 0) "${ml / 1000} l" else "%.1f l".format(ml / 1000.0)) else "$ml ml"

@Composable
private fun groheChips(d: GroheDevice): List<ChipSpec> = buildList {
    if (!d.online) add(ChipSpec(Icons.Outlined.CloudOff, stringResource(R.string.hc_status_offline), warn = true))
    // Füllstände als Tags: leer bzw. bald leer in Warnfarbe
    if (d.filterEmpty) add(ChipSpec(Icons.Outlined.FilterAlt, stringResource(R.string.grohe_filter_empty), warn = true))
    else d.filterPercent?.let { add(ChipSpec(Icons.Outlined.FilterAlt, stringResource(R.string.grohe_filter_short, it.roundToInt()), warn = d.filterLow)) }
    if (d.co2Empty) add(ChipSpec(Icons.Outlined.BubbleChart, stringResource(R.string.grohe_co2_empty), warn = true))
    else d.co2Percent?.let { add(ChipSpec(Icons.Outlined.BubbleChart, stringResource(R.string.grohe_co2_short, it.roundToInt()), warn = d.co2Low)) }
    if (d.cleaningNeeded) add(ChipSpec(Icons.Outlined.NotificationsActive, stringResource(R.string.grohe_cleaning_needed), warn = true))
}

/** Große Karte je Wasserart: Blase mit Perlen (still: keine), Name und kurze Beschreibung. */
@Composable
private fun WaterCard(type: WaterType, selected: Boolean, available: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    val fg = if (selected) inverseInk() else g.ink
    Column(
        modifier.alpha(if (available || selected) 1f else 0.45f).clip(RoundedCornerShape(28.dp)).background(if (selected) g.ink else g.chip)
            .clickable(enabled = enabled && available, onClick = onClick).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val bubbles = when (type) { WaterType.STILL -> 0; WaterType.MEDIUM -> 7; WaterType.SPARKLING -> 16 }
        Canvas(Modifier.size(104.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.verticalGradient(listOf(GroheColors.Water.copy(alpha = 0.55f), Color(0xFF3E9BE0).copy(alpha = 0.85f))), r)
            // Lichtkante oben links
            drawCircle(Color.White.copy(alpha = 0.35f), r * 0.22f, center + Offset(-r * 0.38f, -r * 0.4f))
            repeat(bubbles) { i ->
                val a = i * 2.39996f
                val rr = r * 0.78f * kotlin.math.sqrt((i + 0.5f) / bubbles)
                drawCircle(Color.White.copy(alpha = 0.8f), (2.5f + i % 3 * 1.5f).dp.toPx(), center + Offset(kotlin.math.cos(a) * rr, kotlin.math.sin(a) * rr + r * 0.08f))
            }
            drawCircle(Color.White.copy(alpha = 0.7f), r - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
        Text(stringResource(type.labelRes), color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(type.descRes()), color = if (selected) fg.copy(alpha = 0.7f) else g.soft, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun WaterType.descRes() = when (this) {
    WaterType.STILL -> R.string.grohe_desc_still
    WaterType.MEDIUM -> R.string.grohe_desc_medium
    WaterType.SPARKLING -> R.string.grohe_desc_sparkling
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
