package app.raum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.domain.models.CoverMovement
import app.raum.ui.overview.LocalSkyScene
import app.raum.ui.overview.SkyPhase
import app.raum.ui.overview.skyColors
import app.raum.ui.theme.LocalGlass
import kotlin.math.min

/*
 * Bilder der Gerätekacheln (Entwurf „raum. Übersicht Redesign“, Board „Kacheln“): oben zeigt ein gezeichnetes Bild
 * den Zustand – Pendelleuchte mit Lichtkegel, Fenster mit Lamellen vor dem echten Himmel, Steckdose, Thermostat-Ring.
 * Gezeichnet wird in einem festen Entwurfsraster, das auf die verfügbare Fläche skaliert wird.
 */

/** Warmes Licht ohne eigene Farbe. */
val LampWarm = Color(0xFFFFC861)

/** Zeichnet [block] in einem [w]×[h]-Raster, eingepasst und waagrecht mittig; [top] = oben bündig statt mittig. */
private fun DrawScope.fitted(w: Float, h: Float, top: Boolean = false, block: DrawScope.() -> Unit) {
    val s = min(size.width / w, size.height / h)
    val dx = (size.width - w * s) / 2f
    val dy = if (top) 0f else (size.height - h * s) / 2f
    translate(dx, dy) { scale(s, s, Offset.Zero) { block() } }
}

/** Pendelleuchte: aus grau, an mit Lichtkegel, dessen Stärke der Helligkeit folgt; Farblicht in seiner Farbe. */
@Composable
fun LampArt(on: Boolean, level: Float, color: Color?, online: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val light = color ?: LampWarm
    Canvas(modifier) {
        fitted(130f, 104f, top = true) {
            val lit = on && online
            drawLine(if (lit) Color(0xFF7A5A2C) else g.soft, Offset(65f, 0f), Offset(65f, 22f), 2f)
            if (lit) {
                val a = 0.3f + 0.55f * level.coerceIn(0f, 1f)
                val cone = Path().apply { moveTo(44f, 44f); lineTo(86f, 44f); lineTo(122f, 104f); lineTo(8f, 104f); close() }
                drawPath(cone, Brush.verticalGradient(listOf(light.copy(alpha = a), light.copy(alpha = 0f)), 44f, 104f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF1C4).copy(alpha = 0.9f), Color.Transparent), Offset(65f, 46f), 28f), 28f, Offset(65f, 46f))
            }
            val shade = Path().apply {
                moveTo(42f, 44f); cubicTo(42f, 30f, 52f, 22f, 65f, 22f); cubicTo(78f, 22f, 88f, 30f, 88f, 44f); close()
            }
            drawPath(shade, if (lit) Color(0xFF2F2A3A) else if (g.dark) Color(0xFF8A8396) else Color(0xFF9B93A3))
            drawCircle(if (lit) Color(0xFFFFF6D8) else if (g.dark) Color(0xFF55506A) else Color(0xFFE6E0EA), 7f, Offset(65f, 46f))
        }
    }
}

/** Fenster mit Rollladen: dahinter der Himmel der aktuellen Tageszeit, Lamellen bis zur Schließhöhe. */
@Composable
fun WindowArt(openPercent: Int, movement: CoverMovement, online: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val phase = LocalSkyScene.current?.phase ?: SkyPhase.DAY
    val sky = skyColors(phase)
    Canvas(modifier) {
        fitted(120f, 96f) {
            drawRoundRect(if (g.dark) Color(0xFF2A2E44) else Color.White, Offset(4f, 2f), Size(112f, 92f), CornerRadius(12f))
            drawRoundRect(g.track, Offset(4f, 2f), Size(112f, 92f), CornerRadius(12f), style = Stroke(1f))
            val pane = if (online) Brush.verticalGradient(sky, 10f, 86f) else Brush.verticalGradient(listOf(Color(0xFFC9C3CF), Color(0xFFB7B0BE)), 10f, 86f)
            drawRoundRect(pane, Offset(12f, 10f), Size(96f, 76f), CornerRadius(6f))
            val closedH = 76f * (1f - openPercent.coerceIn(0, 100) / 100f)
            // Sonne nur, wo sie nicht hinter dem Laden steckt
            if (online && closedH < 46f && (phase == SkyPhase.DAY || phase == SkyPhase.DAWN || phase == SkyPhase.DUSK)) {
                drawCircle(Color(0xFFFFE08A), 7f, Offset(36f, 66f))
            }
            drawLine(Color.White, Offset(60f, 10f), Offset(60f, 86f), 3f)
            // Lamellen: oben immer der aufgerollte Rand, darunter so weit, wie der Laden geschlossen ist
            val slat = if (g.dark) Color(0xFF7D768C) else Color(0xFFCFC6D6)
            val gap = if (g.dark) Color(0xFF5E5870) else Color(0xFFB4AABD)
            drawRoundRect(slat, Offset(12f, 10f), Size(96f, 6f), CornerRadius(1f))
            if (closedH > 0.5f) drawRect(gap, Offset(12f, 10f), Size(96f, closedH))
            var y = 10f
            while (y < 10f + closedH - 0.5f) {
                val h = min(7.5f, 10f + closedH - y)
                drawRoundRect(slat, Offset(12f, y), Size(96f, h), CornerRadius(1f))
                y += 8.5f
            }
            if (movement != CoverMovement.STOPPED) {
                val up = movement == CoverMovement.OPENING
                val cy = 70f
                val p = Path().apply {
                    if (up) { moveTo(52f, cy + 4f); lineTo(60f, cy - 4f); lineTo(68f, cy + 4f) } else { moveTo(52f, cy - 4f); lineTo(60f, cy + 4f); lineTo(68f, cy - 4f) }
                }
                drawPath(p, Color.White, style = Stroke(3f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Steckdose / Schalter: Ring grün, wenn an. */
@Composable
fun SocketArt(on: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Canvas(modifier) {
        fitted(90f, 90f) {
            drawCircle(if (g.dark) Color(0xFF2A2E44) else Color.White, 36f, Offset(45f, 45f))
            drawCircle(if (on) Color(0xFF5FCB86) else g.track, 36f, Offset(45f, 45f), style = Stroke(5f))
            val dot = if (on) Color(0xFF2F6B45) else g.soft
            drawCircle(dot, 5f, Offset(35f, 45f))
            drawCircle(dot, 5f, Offset(55f, 45f))
        }
    }
}

/**
 * Thermostat-Ring wie im Haushalt: 270°-Bogen mit dem Sollwert, in der Mitte die Zahl, −/+ in der Lücke unten.
 * [level] 0…1 auf dem Bogen; null = aus (nur die Spur).
 */
@Composable
fun ThermoRing(
    level: Float?,
    accent: Color,
    text: String,
    textDim: Boolean,
    decLabel: String,
    incLabel: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlass.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = min(maxWidth.value, maxHeight.value).dp
        val button = (d * 0.29f).coerceIn(36.dp, 44.dp)
        Box(Modifier.size(d)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.075f
                val inset = stroke / 2f
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(g.track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (level != null) {
                    val sweep = 270f * level.coerceIn(0f, 1f)
                    drawArc(accent, 135f, sweep.coerceAtLeast(0.5f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    val r = arc.width / 2f
                    val a = Math.toRadians((135f + sweep).toDouble())
                    val c = Offset(size.width / 2f + r * kotlin.math.cos(a).toFloat(), size.height / 2f + r * kotlin.math.sin(a).toFloat())
                    drawCircle(if (g.dark) Color(0xFF14182C) else Color.White, stroke * 0.85f, c)
                    drawCircle(accent, stroke * 0.85f, c, style = Stroke(stroke * 0.3f))
                }
            }
            Text(
                text, color = if (textDim) g.soft else g.ink,
                fontSize = if (d >= 140.dp) 30.sp else 22.sp, fontWeight = if (textDim) FontWeight.Normal else FontWeight.Light,
                letterSpacing = (-0.8).sp, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).offset(y = -d * 0.06f),
            )
            RingButton(Icons.Outlined.Remove, decLabel, button, Modifier.align(Alignment.BottomCenter).offset(x = -(button / 2 + 6.dp)), onDec)
            RingButton(Icons.Outlined.Add, incLabel, button, Modifier.align(Alignment.BottomCenter).offset(x = button / 2 + 6.dp), onInc)
        }
    }
}

@Composable
private fun RingButton(icon: ImageVector, label: String, size: Dp, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.size(size).shadow(3.dp, CircleShape).clip(CircleShape).background(g.glassSolid).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.48f), tint = g.ink) }
}
