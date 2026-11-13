package app.raum.ui.household

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.glass

/** Geräte für „Gerade aktiv“ auf der Übersicht (Home Connect und Grohe, nur eingeschaltete Dienste). */
@Composable
fun rememberHouseholdGlance(st: app.raum.homeconnect.HomeConnectState, service: app.raum.homeconnect.HomeConnectService, gs: app.raum.grohe.GroheState): List<HouseholdEntry> {
    val now = rememberNow()
    return householdEntries(st, gs, service, now, onlyActive = true)
}

/** Wie viele Geräte die Leiste zeigt; der Rest steht als „+n“ daneben. */
private const val MAX_RINGS = 6

/**
 * Übersicht: Haushaltskarte (Entwurf „raum. Übersicht Redesign“). Oben das Wichtigste – was läuft, sonst was fertig
 * ist, sonst „Alles ruhig“ –, unten die Geräteleiste wie auf der Haushaltsseite: Ringe mit Fortschritt und
 * Kurzstatus. Ein Ring öffnet sein Gerät, die Karte das wichtigste.
 */
@Composable
fun HouseholdGlanceCard(entries: List<HouseholdEntry>, modifier: Modifier, onOpen: (String) -> Unit) {
    val g = LocalGlass.current
    val lead = entries.firstOrNull { it.summary.running }
        ?: entries.firstOrNull { it.summary.alert || it.summary.subAlert }
        ?: entries.firstOrNull { it.summary.done }
        ?: entries.firstOrNull { !it.summary.calm }
    val others = activityLine(entries.filter { it !== lead })
    Column(
        modifier.glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable { onOpen((lead ?: entries.first()).id) }
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
    ) {
        if (lead != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(lead.name, color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(lead.summary.status, color = statusColor(lead.summary), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            val second = listOf(lead.summary.sub, others).filter { it.isNotBlank() }.joinToString(" · ")
            if (second.isNotBlank()) Text(second, color = subColor(lead.summary), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        } else {
            val ready = entries.count { !it.summary.offline }
            Text(stringResource(R.string.hc_all_quiet), color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(pluralStringResource(R.plurals.hc_all_ready, ready, ready), color = g.soft, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            entries.take(MAX_RINGS).forEach { e ->
                Column(
                    Modifier.width(66.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpen(e.id) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ApplianceRing(e.icon, e.summary, 56.dp, 24.dp)
                    Text(
                        e.summary.status, color = if (e.summary.calm) g.soft else statusColor(e.summary),
                        fontSize = 11.sp, fontWeight = if (e.summary.calm) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (entries.size > MAX_RINGS) {
                Text("+${entries.size - MAX_RINGS}", color = g.soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
}
