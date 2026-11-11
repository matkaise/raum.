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

/** Übersicht: Reihe „Haushalt“ unter den Favoriten – Status und Restzeit, antippen öffnet das Gerät. */
@Composable
fun HouseholdRow(st: HomeConnectState, service: HomeConnectService, onOpen: (String) -> Unit) {
    if (!st.active || st.appliances.isEmpty()) return
    val g = LocalGlass.current
    val now = rememberNow()
    Row(Modifier.padding(start = 20.dp, top = 34.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_household).uppercase(), style = app.raum.ui.theme.SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text(stringResource(R.string.hc_row_hint), style = app.raum.ui.theme.SkyTextStyle, color = SkyText.copy(alpha = 0.7f), fontSize = 14.sp)
    }
    FixedGrid(st.appliances, columns = 5) { a, mod ->
        val s = summarize(a, service, now)
        Row(
            mod.height(132.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable { onOpen(a.haId) }.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ApplianceRing(a.type, s, 48.dp, 22.dp)
            Column(Modifier.weight(1f)) {
                // Lange Namen („Kaffeevollautomat“) dürfen umbrechen statt abgeschnitten zu werden
                Text(a.name, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.status, color = statusColor(s), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (s.sub.isNotBlank()) Text(s.sub, color = subColor(s), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
