package app.raum.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.Scene
import kotlinx.coroutines.delay
import java.util.UUID

/** Stimmung einer Szene: Himmel der Karte und die leuchtende Kugel darauf. */
private data class Mood(val sky: List<Color>, val orb: Brush, val moon: Boolean = false)

private fun orb(vararg stops: Pair<Float, Color>) = Brush.radialGradient(colorStops = stops)

/** Himmelsverlauf einer Stimmung – für die Stimmungswahl im Szeneneditor. */
fun moodSky(icon: String): List<Color> = moodOf(icon).sky

private fun moodOf(icon: String): Mood = when (icon) {
    "power" -> Mood(listOf(Color(0xFF15161C), Color(0xFF2A2B33)), orb(0f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.08f)))
    "movie", "tv" -> Mood(listOf(Color(0xFF1E1340), Color(0xFF5B2A7A), Color(0xFFB0507A)), orb(0f to Color(0xFFFF9BD0), 0.7f to Color(0xFF8A4DFF), 1f to Color(0x008A4DFF)))
    "night" -> Mood(listOf(Color(0xFF070B1E), Color(0xFF1B2555), Color(0xFF34396F)), orb(0f to Color(0xFFF4F1E6), 1f to Color(0xFFF4F1E6)), moon = true)
    "sunrise" -> Mood(listOf(Color(0xFF2B3566), Color(0xFFA85A7A), Color(0xFFEBA37E)), orb(0f to Color(0xFFFFF1C4), 0.55f to Color(0xFFFFC870), 0.72f to Color(0x00FFC870)))
    "away" -> Mood(listOf(Color(0xFF3A4452), Color(0xFF6F7C8A)), orb(0f to Color.White.copy(alpha = 0.35f), 0.7f to Color.Transparent))
    "relax", "reading" -> Mood(listOf(Color(0xFF1F3A3A), Color(0xFF3F6E62), Color(0xFF9CC3A0)), orb(0f to Color(0xFFE9FFE0), 0.6f to Color(0xFFA8E6B8), 0.75f to Color.Transparent))
    "dinner", "party" -> Mood(listOf(Color(0xFF3A1A12), Color(0xFF8A3B24), Color(0xFFE08A52)), orb(0f to Color(0xFFFFE3B0), 0.55f to Color(0xFFFF9D6B), 0.72f to Color.Transparent))
    "work" -> Mood(listOf(Color(0xFF1E3350), Color(0xFF3F6E9E), Color(0xFF9FC6E8)), orb(0f to Color.White, 0.55f to Color(0xFFBFE3FF), 0.72f to Color.Transparent))
    else -> Mood(listOf(Color(0xFF235C9E), Color(0xFF4C8BCB), Color(0xFF86B9E3)), orb(0f to Color(0xFFFFF1C4), 0.55f to Color(0xFFFFC870), 0.72f to Color.Transparent))
}

/**
 * Szene, die gerade fertig geworden ist – für die Bestätigung „Ausgeführt“ auf der Karte
 * (verschwindet nach gut zwei Sekunden).
 */
@Composable
fun rememberFinishedScene(runningId: UUID?): UUID? {
    var last by remember { mutableStateOf<UUID?>(null) }
    var done by remember { mutableStateOf<UUID?>(null) }
    LaunchedEffect(runningId) {
        if (runningId == null && last != null) {
            done = last
            last = null
            delay(2_200)
            done = null
        } else if (runningId != null) {
            last = runningId
            done = null
        }
    }
    return done
}

private enum class SceneRun { IDLE, RUNNING, DONE }

/** Karte einer Szene im Stil ihrer Stimmung; [tall] = große Karte der Szenenseite. */
@Composable
fun SceneMoodCard(
    scene: Scene,
    running: Boolean,
    done: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
    subtitle: String? = null,
    onMore: (() -> Unit)? = null,
    /** Große Karte in mittlerer Höhe (Szenenseite mit zwei Reihen auf dem Bildschirm) */
    medium: Boolean = false,
    menu: @Composable () -> Unit = {},
) {
    val mood = moodOf(scene.icon)
    val state = if (running) SceneRun.RUNNING else if (done) SceneRun.DONE else SceneRun.IDLE
    val t = updateTransition(state, label = "scene")
    val scale by t.animateFloat(label = "scale") { if (it == SceneRun.RUNNING) 0.97f else 1f }
    val orbY by t.animateDp({ tween(600) }, label = "orbY") {
        if (medium) (if (it == SceneRun.RUNNING) 18.dp else 34.dp) else if (it == SceneRun.RUNNING) 40.dp else 70.dp
    }
    val pulse = if (running) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Restart), label = "p").value
    } else null
    val shape = RoundedCornerShape(if (tall) 32.dp else 28.dp)
    val cta = when (state) {
        SceneRun.RUNNING -> stringResource(R.string.running)
        SceneRun.DONE -> stringResource(R.string.scene_done)
        SceneRun.IDLE -> stringResource(R.string.action_run)
    }
    val ctaIcon = when (state) {
        SceneRun.RUNNING -> Icons.Outlined.HourglassTop
        SceneRun.DONE -> Icons.Outlined.Check
        SceneRun.IDLE -> Icons.Outlined.PlayArrow
    }
    Box(
        modifier
            .height(if (tall) (if (medium) 290.dp else 430.dp) else 150.dp)
            .scale(scale)
            .drawBehind {
                // Puls um die Karte, solange die Szene läuft
                pulse?.let { p ->
                    val grow = 28.dp.toPx() * p
                    drawRoundRect(
                        Color(0xFFFFC870).copy(alpha = 0.6f * (1f - p)),
                        topLeft = Offset(-grow / 2, -grow / 2),
                        size = androidx.compose.ui.geometry.Size(size.width + grow, size.height + grow),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius((if (tall) 32.dp else 28.dp).toPx() + grow / 2),
                        style = Stroke(grow.coerceAtLeast(1f)),
                    )
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(mood.sky))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .clickable(enabled = enabled, onClick = onRun),
    ) {
        // Kugel: Sonne, Mond oder Licht der Stimmung
        val orbSize = if (tall) (if (medium) 96.dp else 120.dp) else 90.dp
        val orbMod = if (tall) Modifier.align(Alignment.TopCenter).offset(y = orbY) else Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-18).dp)
        if (mood.moon) {
            Canvas(orbMod.size(orbSize)) {
                drawCircle(Color(0xFFF4F1E6), size.minDimension * 0.37f, Offset(size.width * 0.35f, size.height * 0.35f))
            }
        } else {
            Box(orbMod.size(orbSize).clip(CircleShape).background(mood.orb))
        }
        if (tall) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(if (tall && !medium) 24.dp else 18.dp)) {
            Icon(RaumIcons.scene(scene.icon), null, Modifier.size(if (tall && !medium) 34.dp else 28.dp), tint = Color.White)
            Text(
                scene.name, color = Color.White, fontSize = if (tall) (if (medium) 24.sp else 30.sp) else 22.sp, fontWeight = FontWeight.Medium,
                lineHeight = if (tall) (if (medium) 27.sp else 33.sp) else 26.sp, maxLines = if (medium) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
            if (tall) {
                subtitle?.let {
                    Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = if (medium) 13.sp else 15.sp, maxLines = if (medium) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (medium) 4.dp else 6.dp).heightIn(min = 18.dp))
                }
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.2f)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ctaIcon, null, Modifier.size(20.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(cta, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(cta, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (onMore != null) {
            Box(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onMore),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more), tint = Color.White) }
                menu()
            }
        }
    }
}
