package app.raum.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.ui.theme.LocalGlass
import kotlinx.coroutines.launch

/*
 * Leiste aus Ring-Tasten wie die Geräteleiste im Haushalt (Entwurf „raum. Übersicht Redesign“): Glasschiene, jede
 * Taste mit Ring und Symbol oben, Name und Zustand darunter, die gewählte weiß. Passen nicht alle, wischt die Leiste.
 */

private val TabInk = Color(0xFF10131F)

/** Eine Taste der Leiste. [ring] 0…1 füllt den Ring in [ringColor]; [dashed] = gestrichelter Ring (Hinzufügen). */
data class RingTabSpec(
    val key: String,
    val label: String,
    val status: String,
    val icon: ImageVector,
    val iconTint: Color = TabInk.copy(alpha = 0.7f),
    val ring: Float? = null,
    val ringColor: Color = Color.Transparent,
    /** Aktiv (Licht an …): Kern warm getönt, Zustand in [accentOn]/[accentOff] betont */
    val active: Boolean = false,
    val core: Color? = null,
    val accentOn: Color? = null,
    val accentOff: Color? = null,
    val badge: Boolean = false,
    val dashed: Boolean = false,
)

@Composable
fun RingTabStrip(tabs: List<RingTabSpec>, selected: String?, description: String, onPick: (String) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val index = tabs.indexOfFirst { it.key == selected }
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null || item.offset < 0 || item.offset + item.size > info.viewportEndOffset) list.animateScrollToItem(index)
    }
    Box {
        LazyRow(
            state = list,
            modifier = Modifier.semantics { contentDescription = description }.clip(RoundedCornerShape(36.dp)).background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(36.dp)),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(tabs, key = { it.key }) { t -> RingTab(t, t.key == selected) { onPick(t.key) } }
        }
        if (list.canScrollBackward) {
            StripButton(Icons.Outlined.ChevronLeft, Modifier.align(Alignment.CenterStart).offset(x = (-12).dp)) {
                scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
            }
        }
        if (list.canScrollForward) {
            StripButton(Icons.Outlined.ChevronRight, Modifier.align(Alignment.CenterEnd).offset(x = (-6).dp)) {
                scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex + 3).coerceAtMost(tabs.lastIndex)) }
            }
        }
    }
}

@Composable
private fun StripButton(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.size(56.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(g.glassSolid).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(28.dp), tint = g.ink) }
}

@Composable
private fun RingTab(t: RingTabSpec, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Color.White else Color.Transparent, label = "ringTab")
    val statusColor = when {
        t.badge -> if (on) Color(0xFFB3261E) else Color(0xFFFFB4A8)
        t.active && on -> t.accentOn ?: TabInk
        t.active -> t.accentOff ?: Color.White
        on -> TabInk.copy(alpha = 0.62f)
        else -> Color.White.copy(alpha = 0.75f)
    }
    Column(
        Modifier.width(120.dp).height(124.dp)
            .then(if (on) Modifier.shadow(14.dp, RoundedCornerShape(30.dp), ambientColor = Color(0xFF0A081E), spotColor = Color(0xFF0A081E)) else Modifier)
            .clip(RoundedCornerShape(30.dp)).background(bg).clickable(onClickLabel = t.label, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val inset = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                if (t.dashed) {
                    drawArc(if (on) TabInk.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.55f), 0f, 360f, false, inset, arc,
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))))
                } else {
                    drawArc(if (on) TabInk.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.28f), 0f, 360f, false, inset, arc, style = Stroke(stroke))
                    t.ring?.takeIf { it > 0f }?.let { f ->
                        drawArc(t.ringColor, -90f, 360f * f.coerceIn(0f, 1f), false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
            }
            val core = when {
                t.dashed -> if (on) TabInk.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.18f)
                else -> t.core ?: Color.White.copy(alpha = 0.94f)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(core), contentAlignment = Alignment.Center) {
                Icon(t.icon, null, Modifier.size(22.dp), tint = if (t.dashed) (if (on) TabInk else Color.White) else t.iconTint)
            }
            if (t.badge) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(14.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(0xFFE5484D)))
            }
        }
        Text(t.label, color = if (on) TabInk else Color.White, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(t.status, color = statusColor, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (t.active || t.badge) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
