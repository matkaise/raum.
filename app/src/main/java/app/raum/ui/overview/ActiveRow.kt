package app.raum.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.raum.R
import app.raum.grohe.GroheState
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.HomeConnectState
import app.raum.sonos.SonosService
import app.raum.ui.household.HouseholdGlanceCard
import app.raum.ui.household.rememberHouseholdGlance
import app.raum.ui.music.MusicGlanceCard
import app.raum.ui.music.rememberActiveMusic

/**
 * Reihe „Gerade aktiv“ der Übersicht (Entwurf „raum. Übersicht Redesign“): links die Musik, rechts der Haushalt –
 * beides gleich unter den Favoriten, damit es ohne Scrollen sichtbar ist. Leer, wenn keins von beiden etwas hat.
 */
@Composable
fun ActiveRow(
    sonos: SonosService,
    homeConnect: HomeConnectState,
    homeConnectService: HomeConnectService,
    grohe: GroheState,
    onOpenMusic: (String) -> Unit,
    onOpenAppliance: (String) -> Unit,
) {
    val music = rememberActiveMusic(sonos)
    val household = rememberHouseholdGlance(homeConnect, homeConnectService, grohe)
    if (music.isEmpty() && household.isEmpty()) return
    SkySection(stringResource(R.string.overview_active), top = 26.dp)
    Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Fehlt eine Seite, bleibt die andere so breit wie im Paar und links bündig
        if (music.isNotEmpty()) MusicGlanceCard(music, sonos, Modifier.weight(1.36f).fillMaxHeight(), onOpenMusic)
        if (household.isNotEmpty()) HouseholdGlanceCard(household, Modifier.weight(1f).fillMaxHeight(), onOpenAppliance)
        if (music.isEmpty()) Spacer(Modifier.weight(1.36f))
        if (household.isEmpty()) Spacer(Modifier.weight(1f))
    }
}
