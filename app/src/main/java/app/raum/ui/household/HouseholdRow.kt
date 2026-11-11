package app.raum.ui.household

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.HomeConnectState
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass

private const val COLUMNS = 5
private val GAP = 14.dp
private val CARD_HEIGHT = 132.dp

/** Eine Zelle der Reihe: ein Gerät oder die ruhigen Geräte zusammen, [span] Spalten breit. */
private sealed class Cell(val span: Int) {
    class Running(val e: HouseholdEntry) : Cell(2)
    class Single(val e: HouseholdEntry) : Cell(1)
    class Calm(val entries: List<HouseholdEntry>) : Cell(1)
}

/**
 * Übersicht: Reihe „Haushalt“ (Entwurf „raum. Haushalt Redesign“). Zuerst, was zählt: Laufende Geräte als breite Karte
 * wie der Mini-Player bei Musik, Fertiges grün getönt, Geplantes und Hinweise einzeln. Geräte ohne etwas zu melden
 * werden zu einer Karte „3 bereit“; läuft gar nichts, bleibt eine ruhige Zeile statt lauter „Standby“-Karten.
 */
@Composable
fun HouseholdRow(st: HomeConnectState, service: HomeConnectService, gs: app.raum.grohe.GroheState, onOpen: (String) -> Unit) {
    val now = rememberNow()
    val entries = householdEntries(st, gs, service, now, onlyActive = true)
    if (entries.isEmpty()) return
    val activity = activityLine(entries)
    Row(Modifier.padding(start = 20.dp, top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_household).uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text(
            listOf(activity, stringResource(R.string.hc_row_hint_short)).filter { it.isNotBlank() }.joinToString(" · "),
            style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp,
        )
    }
    val calm = entries.filter { it.summary.calm }
    if (calm.size == entries.size) {
        QuietLine(entries) { onOpen(entries.first().id) }
        return
    }
    val cells = buildList {
        entries.filter { it.summary.running }.forEach { add(Cell.Running(it)) }
        entries.filter { it.summary.done }.forEach { add(Cell.Single(it)) }
        entries.filter { !it.summary.running && !it.summary.done && !it.summary.calm }.forEach { add(Cell.Single(it)) }
        when (calm.size) {
            0 -> Unit
            1 -> add(Cell.Single(calm.first()))
            else -> add(Cell.Calm(calm))
        }
    }
    // Zeilen auffüllen: eine breite Karte, die nicht mehr passt, beginnt eine neue Zeile
    val rows = mutableListOf<MutableList<Cell>>()
    cells.forEach { c ->
        val last = rows.lastOrNull()
        if (last != null && last.sumOf { it.span } + c.span <= COLUMNS) last += c else rows += mutableListOf(c)
    }
    // Spaltenbreite wie das Favoritenraster darüber, damit die Kanten fluchten
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val unit = (maxWidth - GAP * (COLUMNS - 1)) / COLUMNS
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    row.forEach { c ->
                        val mod = Modifier.width(unit * c.span + GAP * (c.span - 1)).height(CARD_HEIGHT)
                        when (c) {
                            is Cell.Running -> RunningCard(c.e, mod) { onOpen(c.e.id) }
                            is Cell.Single -> SingleCard(c.e, mod) { onOpen(c.e.id) }
                            is Cell.Calm -> CalmCard(c.entries, mod) { onOpen(c.entries.first().id) }
                        }
                    }
                }
            }
        }
    }
}

/** Laufendes Gerät: groß die Restzeit, darunter die Fertig-Uhrzeit, unten der Fortschritt. */
@Composable
private fun RunningCard(e: HouseholdEntry, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    val s = e.summary
    Box(modifier.glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxHeight().padding(start = 16.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ApplianceRing(e.icon, s, 72.dp, 28.dp)
            Column(Modifier.weight(1f)) {
                Text(e.name, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.status, color = statusColor(s), fontSize = 32.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.6).sp, lineHeight = 38.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.sub.isNotBlank()) Text(s.sub, color = subColor(s), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(g.chip), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(26.dp), tint = g.ink)
            }
        }
        if (s.progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(g.track)) {
                Box(Modifier.fillMaxWidth(s.progress.coerceIn(0f, 1f)).height(4.dp).background(if (s.paused) s.accent.copy(alpha = 0.5f) else s.accent))
            }
        }
    }
}

/** Fertig, geplant oder mit Hinweis: Ring, Name, Zustand. Fertiges grün getönt wie eine eingeschaltete Lampe warm. */
@Composable
private fun SingleCard(e: HouseholdEntry, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    val s = e.summary
    val tint = when {
        !s.done -> g.glass
        g.dark -> HcColors.Done.copy(alpha = 0.22f)
        else -> Color(0xFFD6F0DC).copy(alpha = 0.78f)
    }
    Row(
        modifier.glass(RoundedCornerShape(28.dp), tint, g.edge).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ApplianceRing(e.icon, s, 56.dp, 24.dp)
        Column(Modifier.weight(1f)) {
            Text(e.name, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.status, color = statusColor(s), fontSize = 20.sp, fontWeight = if (s.done) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (s.sub.isNotBlank()) Text(s.sub, color = subColor(s), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Mehrere ruhige Geräte in einer Karte: überlappende Symbole, „3 bereit“, die Namen. */
@Composable
private fun CalmCard(entries: List<HouseholdEntry>, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    val ready = entries.count { !it.summary.offline }
    val offline = entries.size - ready
    Column(
        modifier.glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        MiniStack(entries.take(4), 40.dp)
        Text(
            listOfNotNull(
                if (ready > 0) pluralStringResource(R.plurals.hc_group_ready, ready, ready) else null,
                if (offline > 0) "$offline ${stringResource(R.string.hc_status_offline).lowercase()}" else null,
            ).joinToString(" · "),
            color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        Text(entries.joinToString(" · ") { it.name }, color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Nichts läuft: eine schmale Zeile mit allen Geräten. */
@Composable
private fun QuietLine(entries: List<HouseholdEntry>, onClick: () -> Unit) {
    val g = LocalGlass.current
    val ready = entries.count { !it.summary.offline }
    Row(
        Modifier.fillMaxWidth().height(84.dp).glass(RoundedCornerShape(42.dp), g.glass, g.edge).clickable(onClick = onClick).padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MiniStack(entries.take(8), 52.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.hc_all_quiet), color = g.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                pluralStringResource(R.plurals.hc_all_ready, ready, ready) + " · " + entries.joinToString(", ") { it.name },
                color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(26.dp), tint = g.soft)
    }
}

/** Überlappende kleine Ringe. */
@Composable
private fun MiniStack(entries: List<HouseholdEntry>, size: Dp) {
    val overlap = size / 5
    Box(Modifier.width(size * entries.size - overlap * (entries.size - 1)).height(size)) {
        entries.forEachIndexed { i, e ->
            ApplianceRing(e.icon, e.summary, size, size * 0.42f, modifier = Modifier.offset(x = (size - overlap) * i))
        }
    }
}
