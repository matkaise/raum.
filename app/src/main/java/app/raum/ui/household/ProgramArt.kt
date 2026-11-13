package app.raum.ui.household

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.homeconnect.enumTail
import app.raum.ui.theme.LocalGlass

/** Eine Karte im Programm-Karussell: Illustration oben, Name darunter. */
class ProgramCardSpec(val key: String, val name: String, val available: Boolean, val art: DrawScope.() -> Unit)

/**
 * Programme als große, illustrierte Karten zum Durchwischen (wie die Wasser-Karten bei Grohe).
 * Rastet auf Karten ein; rechts weich ausgeblendet, solange es weitergeht.
 */
@Composable
fun ProgramCarousel(cards: List<ProgramCardSpec>, selectedKey: String, enabled: Boolean, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val g = LocalGlass.current
    val state = rememberLazyListState()
    // Beim Öffnen die gewählte Karte ins Bild holen
    LaunchedEffect(cards.size) { cards.indexOfFirst { it.key == selectedKey }.takeIf { it > 1 }?.let { state.scrollToItem(it - 1) } }
    LazyRow(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (state.canScrollForward) drawRect(Brush.horizontalGradient(0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
        state = state, flingBehavior = rememberSnapFlingBehavior(state),
        horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 24.dp),
    ) {
        itemsIndexed(cards, key = { _, c -> c.key }) { _, c ->
            val selected = c.key == selectedKey
            val fg = if (selected) inverseInk() else g.ink
            Column(
                Modifier.width(184.dp).fillMaxHeight().heightIn(max = 270.dp).alpha(if (c.available || selected) 1f else 0.5f)
                    .clip(RoundedCornerShape(26.dp)).background(if (selected) g.ink else g.chip)
                    .clickable(enabled = enabled) { onPick(c.key) }.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                // Illustration nimmt die Höhe, die der Name übrig lässt – Karten passen so in jede Spaltenhöhe
                Canvas(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))) { c.art(this) }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    Text(c.name, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 19.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// --- Illustrationen ------------------------------------------------------------------------------

private val Glow = Color(0xFFFF8A3D)
private val GlowHot = Color(0xFFFFC870)
private val Cold = Color(0xFFA9DBFF)

/** Backraum von vorn: dunkler Innenraum, zwei Einschubschienen. */
private fun DrawScope.cavity(base: Color = Color(0xFF1B1512)) {
    drawRect(base)
    val rail = Color.White.copy(alpha = 0.10f)
    listOf(0.36f, 0.64f).forEach { y ->
        drawLine(rail, Offset(size.width * 0.06f, size.height * y), Offset(size.width * 0.14f, size.height * y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(rail, Offset(size.width * 0.86f, size.height * y), Offset(size.width * 0.94f, size.height * y), 3.dp.toPx(), StrokeCap.Round)
    }
}

/** Heizstab oben oder unten mit weichem Schein in den Raum hinein. */
private fun DrawScope.heater(top: Boolean, strength: Float = 1f) {
    val y = if (top) size.height * 0.12f else size.height * 0.88f
    val glowH = size.height * 0.42f
    val from = if (top) y else y - glowH
    drawRect(
        Brush.verticalGradient(
            if (top) listOf(Glow.copy(alpha = 0.55f * strength), Color.Transparent) else listOf(Color.Transparent, Glow.copy(alpha = 0.55f * strength)),
            startY = from, endY = from + glowH,
        ),
        Offset(0f, from), Size(size.width, glowH),
    )
    drawLine(GlowHot.copy(alpha = 0.35f + 0.65f * strength), Offset(size.width * 0.16f, y), Offset(size.width * 0.84f, y), 5.dp.toPx(), StrokeCap.Round)
}

/** Grill: Zickzack-Heizschlange oben, glüht kräftig. */
private fun DrawScope.grill(wide: Boolean = true) {
    val y = size.height * 0.13f
    val l = size.width * (if (wide) 0.14f else 0.32f)
    val r = size.width * (if (wide) 0.86f else 0.68f)
    val n = if (wide) 7 else 4
    val p = Path().apply {
        moveTo(l, y)
        repeat(n) { i ->
            val x = l + (r - l) * (i + 0.5f) / n
            lineTo(x, y + (if (i % 2 == 0) 1 else -1) * 7.dp.toPx())
        }
        lineTo(r, y)
    }
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFF5A2A).copy(alpha = 0.6f), Color.Transparent), startY = y, endY = y + size.height * 0.5f), Offset(0f, y), Size(size.width, size.height * 0.5f))
    drawPath(p, Color(0xFFFF7A3D), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Lüfter an der Rückwand, optional mit Ringheizkörper (Heißluft) und Luftbögen. */
private fun DrawScope.fan(ring: Boolean, strength: Float = 1f, air: Boolean = true) {
    val c = Offset(size.width / 2, size.height * 0.5f)
    val r = size.minDimension * 0.2f
    if (ring) {
        drawCircle(Glow.copy(alpha = 0.25f * strength), r * 1.9f, c)
        drawCircle(GlowHot.copy(alpha = 0.4f + 0.6f * strength), r * 1.35f, c, style = Stroke(3.dp.toPx()))
    }
    drawCircle(Color.White.copy(alpha = 0.10f), r * 1.05f, c)
    repeat(4) { i ->
        rotate(i * 90f + 20f, c) {
            drawOval(Color.White.copy(alpha = 0.75f), Offset(c.x - r * 0.22f, c.y - r * 0.95f), Size(r * 0.44f, r * 0.9f))
        }
    }
    drawCircle(Color(0xFF8C96A3), r * 0.16f, c)
    if (air) {
        // Luft strömt seitlich in den Raum
        val a = Color.White.copy(alpha = 0.35f)
        listOf(-1f, 1f).forEach { s ->
            drawArc(a, if (s < 0) 150f else -30f, 60f, false, Offset(c.x - r * 2.6f, c.y - r * 2.6f), Size(r * 5.2f, r * 5.2f), style = Stroke(2.5f * density, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.snowflake(c: Offset, r: Float) {
    repeat(3) { i ->
        rotate(i * 60f, c) {
            drawLine(Cold, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 3.dp.toPx(), StrokeCap.Round)
            listOf(-1f, 1f).forEach { e ->
                val tip = Offset(c.x, c.y + e * r * 0.6f)
                drawLine(Cold, tip, tip + Offset(r * 0.28f, e * r * 0.28f), 2.5f * density, StrokeCap.Round)
                drawLine(Cold, tip, tip + Offset(-r * 0.28f, e * r * 0.28f), 2.5f * density, StrokeCap.Round)
            }
        }
    }
}

/** Aufsteigende Wärmewellen (Warmhalten). */
private fun DrawScope.warmWaves() {
    repeat(3) { i ->
        val x = size.width * (0.35f + i * 0.15f)
        val p = Path().apply {
            moveTo(x, size.height * 0.7f)
            cubicTo(x - 8.dp.toPx(), size.height * 0.6f, x + 8.dp.toPx(), size.height * 0.5f, x, size.height * 0.4f)
        }
        drawPath(p, GlowHot.copy(alpha = 0.7f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun DrawScope.pizza() {
    val c = Offset(size.width / 2, size.height * 0.74f)
    drawOval(Color(0xFFE9A04A), Offset(c.x - size.width * 0.24f, c.y - size.height * 0.06f), Size(size.width * 0.48f, size.height * 0.12f))
    drawOval(Color(0xFFD9532E), Offset(c.x - size.width * 0.2f, c.y - size.height * 0.045f), Size(size.width * 0.4f, size.height * 0.08f))
    listOf(-0.1f, 0.02f, 0.12f).forEachIndexed { i, dx -> drawCircle(Color(0xFFF6E7C8), 3.dp.toPx(), Offset(c.x + size.width * dx, c.y + (if (i == 1) -2 else 1).dp.toPx())) }
}

private fun DrawScope.plates() {
    repeat(3) { i ->
        val y = size.height * (0.78f - i * 0.05f)
        drawOval(Color.White.copy(alpha = 0.85f - i * 0.15f), Offset(size.width * 0.3f, y), Size(size.width * 0.4f, size.height * 0.06f))
    }
}

private fun DrawScope.hourglass() {
    val c = Offset(size.width / 2, size.height * 0.5f)
    val w = size.width * 0.12f; val h = size.height * 0.2f
    val p = Path().apply {
        moveTo(c.x - w, c.y - h); lineTo(c.x + w, c.y - h); lineTo(c.x - w, c.y + h); lineTo(c.x + w, c.y + h); close()
    }
    drawPath(p, Color.White.copy(alpha = 0.75f), style = Stroke(2.5f * density, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Illustration je Heizart des Backofens (nach dem Schlüsselende, z. B. „HotAirGentle“). */
fun ovenArt(programKey: String): DrawScope.() -> Unit = {
    val tail = enumTail(programKey) ?: ""
    cavity()
    when (tail) {
        "HotAir", "HotAir30Steam" -> fan(ring = true)
        "HotAirEco", "HotAirGentle" -> fan(ring = true, strength = 0.45f)
        "TopBottomHeating" -> { heater(true); heater(false) }
        "TopBottomHeatingEco" -> { heater(true, 0.45f); heater(false, 0.45f) }
        "BottomHeating" -> heater(false)
        "PizzaSetting" -> { heater(false); fan(ring = true, air = false); pizza() }
        "GrillLargeArea" -> grill()
        "GrillSmallArea" -> grill(wide = false)
        "HotAirGrilling" -> { grill(); fan(ring = false, air = true) }
        "Defrost" -> { fan(ring = false); snowflake(Offset(size.width * 0.78f, size.height * 0.24f), size.minDimension * 0.12f) }
        "FrozenHeatupSpecial" -> { fan(ring = true); snowflake(Offset(size.width * 0.8f, size.height * 0.22f), size.minDimension * 0.1f) }
        "KeepWarm", "WarmingDrawer" -> { heater(true, 0.35f); heater(false, 0.35f); warmWaves() }
        "PreheatOvenware" -> { fan(ring = true, strength = 0.4f, air = false); plates() }
        "SlowCook" -> { heater(true, 0.3f); heater(false, 0.3f); hourglass() }
        "Desiccation" -> { fan(ring = false); heater(false, 0.3f) }
        "PreHeating", "IntensiveHeat" -> { heater(true); heater(false); fan(ring = true, air = false) }
        else -> if ("Grill" in tail) grill() else if ("Bottom" in tail) heater(false) else fan(ring = "HotAir" in tail || "Hot" in tail)
    }
}

/** Mikrowelle: Innenraum mit Drehteller, Wellen je nach Leistung (Boost mit Blitz). */
fun microwaveArt(programKey: String): DrawScope.() -> Unit = {
    val tail = enumTail(programKey) ?: ""
    val watt = Regex("(\\d+)").find(tail)?.value?.toIntOrNull()
    val boost = watt == null
    val waves = when {
        boost -> 5
        watt!! <= 100 -> 1
        watt <= 200 -> 2
        watt <= 400 -> 3
        else -> 4
    }
    cavity(Color(0xFF16181C))
    drawOval(Color.White.copy(alpha = 0.14f), Offset(size.width * 0.2f, size.height * 0.8f), Size(size.width * 0.6f, size.height * 0.08f))
    val c = Offset(size.width * 0.5f, size.height * 0.5f)
    val accent = Color(0xFFFFC870)
    drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.12f * waves), Color.Transparent), c, size.minDimension * 0.6f))
    repeat(waves) { i ->
        val rr = size.minDimension * (0.12f + i * 0.075f)
        listOf(-1f, 1f).forEach { s ->
            drawArc(accent.copy(alpha = 0.9f - i * 0.12f), if (s < 0) 140f else -40f, 80f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
    if (boost) {
        val p = Path().apply {
            moveTo(c.x + 4.dp.toPx(), c.y - 16.dp.toPx()); lineTo(c.x - 6.dp.toPx(), c.y + 2.dp.toPx()); lineTo(c.x + 1.dp.toPx(), c.y + 2.dp.toPx())
            lineTo(c.x - 4.dp.toPx(), c.y + 16.dp.toPx()); lineTo(c.x + 7.dp.toPx(), c.y - 3.dp.toPx()); lineTo(c.x, c.y - 3.dp.toPx()); close()
        }
        drawPath(p, accent)
    } else drawCircle(accent, 5.dp.toPx(), c)
}

// --- Wäsche ---------------------------------------------------------------------------------------

private val Cloth = Color(0xFFF2F0EA)

/** Bullauge-Hintergrund: dunkler Trommelraum; Waschen mit Wasser unten, Trocknen mit Wärmewellen. */
private fun DrawScope.drumRoom(dryer: Boolean) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF42526E), Color(0xFF151B29))))
    if (!dryer) {
        val y = size.height * 0.78f
        drawRect(Brush.verticalGradient(listOf(Color(0x5A8CC8FF), Color(0x8C468CE6)), startY = y, endY = size.height), Offset(0f, y), Size(size.width, size.height - y))
    } else {
        listOf(0.2f, 0.8f).forEach { fx ->
            val x = size.width * fx
            val p = Path().apply { moveTo(x, size.height * 0.85f); cubicTo(x - 6.dp.toPx(), size.height * 0.72f, x + 6.dp.toPx(), size.height * 0.6f, x, size.height * 0.48f) }
            drawPath(p, GlowHot.copy(alpha = 0.55f), style = Stroke(2.5f * density, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.tshirt(c: Offset, s: Float, color: Color = Cloth) {
    val p = Path().apply {
        moveTo(c.x - s * 0.22f, c.y - s * 0.5f); lineTo(c.x - s * 0.55f, c.y - s * 0.32f); lineTo(c.x - s * 0.42f, c.y - s * 0.08f)
        lineTo(c.x - s * 0.3f, c.y - s * 0.14f); lineTo(c.x - s * 0.3f, c.y + s * 0.5f); lineTo(c.x + s * 0.3f, c.y + s * 0.5f)
        lineTo(c.x + s * 0.3f, c.y - s * 0.14f); lineTo(c.x + s * 0.42f, c.y - s * 0.08f); lineTo(c.x + s * 0.55f, c.y - s * 0.32f)
        lineTo(c.x + s * 0.22f, c.y - s * 0.5f); quadraticTo(c.x, c.y - s * 0.32f, c.x - s * 0.22f, c.y - s * 0.5f); close()
    }
    drawPath(p, color)
}

private fun DrawScope.shirt(c: Offset, s: Float) {
    tshirt(c, s, Color.White)
    // Kragen und Knopfleiste
    val dark = Color(0xFF6C93C9)
    drawLine(dark, Offset(c.x, c.y - s * 0.36f), Offset(c.x, c.y + s * 0.48f), 2.dp.toPx())
    repeat(3) { i -> drawCircle(dark, 2.dp.toPx(), Offset(c.x + 4.dp.toPx(), c.y - s * 0.15f + i * s * 0.2f)) }
    drawLine(dark, Offset(c.x - s * 0.22f, c.y - s * 0.5f), Offset(c.x, c.y - s * 0.3f), 2.dp.toPx(), StrokeCap.Round)
    drawLine(dark, Offset(c.x + s * 0.22f, c.y - s * 0.5f), Offset(c.x, c.y - s * 0.3f), 2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.pants(c: Offset, s: Float, color: Color = Color(0xFF5B7DB8)) {
    val p = Path().apply {
        moveTo(c.x - s * 0.32f, c.y - s * 0.5f); lineTo(c.x + s * 0.32f, c.y - s * 0.5f); lineTo(c.x + s * 0.36f, c.y + s * 0.5f)
        lineTo(c.x + s * 0.06f, c.y + s * 0.5f); lineTo(c.x, c.y - s * 0.12f); lineTo(c.x - s * 0.06f, c.y + s * 0.5f)
        lineTo(c.x - s * 0.36f, c.y + s * 0.5f); close()
    }
    drawPath(p, color)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(c.x - s * 0.32f, c.y - s * 0.38f), Offset(c.x + s * 0.32f, c.y - s * 0.38f), 2.dp.toPx())
}

private fun DrawScope.leaf(c: Offset, s: Float) {
    val p = Path().apply {
        moveTo(c.x - s * 0.4f, c.y + s * 0.4f)
        cubicTo(c.x - s * 0.45f, c.y - s * 0.3f, c.x + s * 0.1f, c.y - s * 0.5f, c.x + s * 0.45f, c.y - s * 0.45f)
        cubicTo(c.x + s * 0.45f, c.y + s * 0.1f, c.x + s * 0.1f, c.y + s * 0.45f, c.x - s * 0.4f, c.y + s * 0.4f); close()
    }
    drawPath(p, Color(0xFF8FC9A3))
    drawLine(Color(0xFF2E8B4E), Offset(c.x - s * 0.4f, c.y + s * 0.4f), Offset(c.x + s * 0.25f, c.y - s * 0.25f), 2.5f * density, StrokeCap.Round)
}

private fun DrawScope.yarn(c: Offset, s: Float) {
    val r = s * 0.42f
    drawCircle(Color(0xFFE8A0B4), r, c)
    val line = Color(0xFFB85C7A)
    listOf(-0.5f, 0f, 0.5f).forEach { k ->
        drawArc(line, 200f, 140f, false, Offset(c.x - r + k * r * 0.5f, c.y - r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx()))
    }
    drawLine(line, c + Offset(r * 0.7f, r * 0.7f), c + Offset(r * 1.25f, r * 1.05f), 2.5f * density, StrokeCap.Round)
}

private fun DrawScope.feather(c: Offset, s: Float) {
    val p = Path().apply {
        moveTo(c.x - s * 0.35f, c.y + s * 0.45f)
        cubicTo(c.x - s * 0.3f, c.y - s * 0.2f, c.x + s * 0.1f, c.y - s * 0.5f, c.x + s * 0.4f, c.y - s * 0.5f)
        cubicTo(c.x + s * 0.35f, c.y - s * 0.1f, c.x, c.y + s * 0.3f, c.x - s * 0.35f, c.y + s * 0.45f); close()
    }
    drawPath(p, Cloth.copy(alpha = 0.9f))
    drawLine(Color(0xFFAEB7C2), Offset(c.x - s * 0.42f, c.y + s * 0.52f), Offset(c.x + s * 0.3f, c.y - s * 0.4f), 2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.towels(c: Offset, s: Float) {
    listOf(Color(0xFFF4C04E), Color(0xFF8FC9A3), Color(0xFF6C93C9)).forEachIndexed { i, col ->
        val y = c.y + s * 0.3f - i * s * 0.26f
        drawRoundRect(col, Offset(c.x - s * 0.45f, y - s * 0.12f), Size(s * 0.9f, s * 0.24f), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    }
}

private fun DrawScope.stopwatch(c: Offset, s: Float) {
    val r = s * 0.4f
    drawCircle(Cloth, r, c, style = Stroke(4.dp.toPx()))
    drawLine(Cloth, Offset(c.x, c.y - r), Offset(c.x, c.y - r - 6.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
    drawLine(GlowHot, c, c + Offset(r * 0.45f, -r * 0.45f), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(GlowHot, 3.dp.toPx(), c)
}

private fun DrawScope.drops(c: Offset, s: Float) {
    listOf(Offset(-0.25f, 0.1f), Offset(0.2f, -0.15f), Offset(0.25f, 0.3f)).forEach { o ->
        val p0 = c + Offset(o.x * s, o.y * s)
        val r = s * 0.14f
        val p = Path().apply {
            moveTo(p0.x, p0.y - r * 1.8f); cubicTo(p0.x + r * 1.2f, p0.y - r * 0.2f, p0.x + r, p0.y + r, p0.x, p0.y + r)
            cubicTo(p0.x - r, p0.y + r, p0.x - r * 1.2f, p0.y - r * 0.2f, p0.x, p0.y - r * 1.8f); close()
        }
        drawPath(p, Cold)
    }
}

private fun DrawScope.spiral(c: Offset, s: Float) {
    val p = Path()
    var first = true
    for (i in 0..60) {
        val t = i / 60f * 4 * Math.PI.toFloat()
        val r = s * 0.06f + s * 0.36f * i / 60f
        val pt = c + Offset(kotlin.math.cos(t) * r, kotlin.math.sin(t) * r)
        if (first) { p.moveTo(pt.x, pt.y); first = false } else p.lineTo(pt.x, pt.y)
    }
    drawPath(p, Cloth, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.sparkle(c: Offset, s: Float) {
    listOf(Triple(0f, 0f, 0.36f), Triple(-0.32f, 0.28f, 0.16f), Triple(0.34f, -0.3f, 0.14f)).forEach { (dx, dy, k) ->
        val p0 = c + Offset(dx * s, dy * s); val r = k * s
        val p = Path().apply {
            moveTo(p0.x, p0.y - r); quadraticTo(p0.x, p0.y, p0.x + r, p0.y); quadraticTo(p0.x, p0.y, p0.x, p0.y + r)
            quadraticTo(p0.x, p0.y, p0.x - r, p0.y); quadraticTo(p0.x, p0.y, p0.x, p0.y - r); close()
        }
        drawPath(p, Color(0xFFFFF3C4))
    }
}

/** Illustration je Wasch- bzw. Trockenprogramm (nach Stichworten im Schlüssel, z. B. „Cotton“, „Wool“, „Super15“). */
fun laundryArt(programKey: String, dryer: Boolean): DrawScope.() -> Unit = {
    val t = (enumTail(programKey) ?: programKey).lowercase()
    drumRoom(dryer)
    val c = Offset(size.width / 2, size.height * 0.46f)
    val s = size.minDimension * 0.62f
    when {
        "eco" in t -> leaf(c, s)
        "wool" in t -> yarn(c, s)
        "jeans" in t || "dark" in t -> pants(c, s)
        "shirt" in t || "blouse" in t || "business" in t -> shirt(c, s)
        "towel" in t -> towels(c, s)
        "delicate" in t || "silk" in t || "dessous" in t || "down" in t || "feather" in t || "sensitive" in t || "chiffon" in t -> feather(c, s)
        "super" in t || "speed" in t || "quick" in t || "express" in t || "time" in t || "15" in t -> stopwatch(c, s)
        "rinse" in t -> drops(c, s)
        "spin" in t || "drain" in t -> spiral(c, s)
        "drumclean" in t || "hygiene" in t || "clean" in t || "refresh" in t || "allergy" in t -> sparkle(c, s)
        "sport" in t || "outdoor" in t || "waterproof" in t -> tshirt(c, s, Color(0xFF8FC9A3))
        "synthetic" in t || "easycare" in t -> tshirt(c, s, Color(0xFF6C93C9))
        "mix" in t -> { tshirt(c + Offset(-s * 0.18f, s * 0.05f), s * 0.8f, Color(0xFFE86A5B)); tshirt(c + Offset(s * 0.18f, -s * 0.05f), s * 0.8f) }
        "blanket" in t || "duvet" in t || "pillow" in t || "curtain" in t || "plush" in t -> towels(c, s)
        else -> tshirt(c, s)
    }
}
