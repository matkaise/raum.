package app.raum.ui.household

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.HomeConnectState
import app.raum.ui.components.FixedGrid
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.glass

/** Eintrag der Übersichtsreihe: Home-Connect-Gerät oder Grohe Blue. */
private class RowEntry(val id: String, val name: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val summary: ApplianceSummary)

/** Übersicht: Reihe „Haushalt“ unter den Favoriten – Status und Restzeit, antippen öffnet das Gerät. */
@Composable
fun HouseholdRow(st: HomeConnectState, service: HomeConnectService, gs: app.raum.grohe.GroheState, onOpen: (String) -> Unit) {
    val now = rememberNow()
    val entries = (if (st.active) st.appliances.map { RowEntry(it.haId, it.name, iconFor(it.type), summarize(it, service, now)) } else emptyList()) +
        (if (gs.active) gs.devices.map { RowEntry(it.id, it.name, GroheIcon, summarizeGrohe(it, gs.pouring[it.id], now)) } else emptyList())
    if (entries.isEmpty()) return
    val g = LocalGlass.current
    Row(Modifier.padding(start = 20.dp, top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_household).uppercase(), style = app.raum.ui.theme.SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text(stringResource(R.string.hc_row_hint_short), style = app.raum.ui.theme.SkyTextStyle, color = SkyText.copy(alpha = 0.7f), fontSize = 14.sp)
    }
    FixedGrid(entries, columns = 5) { e, mod ->
        val s = e.summary
        Row(
            mod.height(132.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable { onOpen(e.id) }.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ApplianceRing(e.icon, s, 48.dp, 22.dp)
            Column(Modifier.weight(1f)) {
                // Lange Namen („Kaffeevollautomat“) dürfen umbrechen statt abgeschnitten zu werden
                Text(e.name, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.status, color = statusColor(s), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (s.sub.isNotBlank()) Text(s.sub, color = subColor(s), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
