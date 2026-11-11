package app.raum.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.Scene
import app.raum.ui.HomeUiState
import app.raum.ui.RoomSummary
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.GlassDeviceTile
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.SceneMoodCard
import app.raum.ui.components.formatPercent
import app.raum.ui.components.formatDegrees
import app.raum.ui.components.rememberFinishedScene
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.glass
import java.util.UUID

/** Startseite (Spez. 9.4) auf dem Himmel: Uhr und Klima, Favoriten, Szenen, Räume. */
@Composable
fun OverviewScreen(
    state: HomeUiState,
    scene: SkyScene,
    cardActions: DeviceCardActions,
    onRunScene: (Scene) -> Unit,
    onOpenRoom: (UUID) -> Unit,
    /** Reihe „Haushalt“ (Home Connect) unter den Favoriten – leer, wenn ausgeschaltet. */
    householdRow: @Composable () -> Unit = {},
) {
    var forecastDay by remember { mutableStateOf<Int?>(null) }
    val finished = rememberFinishedScene(state.runningSceneId)
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
    ) {
        HomeHero(state, scene, onOpenForecast = if (scene.days.isNotEmpty()) ({ forecastDay = it }) else null)

        SkySection(stringResource(R.string.favorites), top = 26.dp)
        if (state.favorites.isEmpty()) {
            Text(stringResource(R.string.favorites_empty), style = app.raum.ui.theme.SkyTextStyle, color = SkyTextSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp))
        } else {
            FixedGrid(state.favorites, columns = 5) { device, mod ->
                GlassDeviceTile(device, cardActions, mod.height(200.dp), roomName = state.roomName(device.roomId))
            }
        }

        householdRow()

        if (state.scenes.isNotEmpty()) {
            SkySection(stringResource(R.string.nav_scenes))
            FixedGrid(state.scenes, columns = 5) { s, mod ->
                SceneMoodCard(
                    s, running = state.runningSceneId == s.id, done = finished == s.id,
                    enabled = state.runningSceneId == null, onRun = { onRunScene(s) }, modifier = mod,
                )
            }
        }

        if (state.rooms.isNotEmpty()) {
            SkySection(stringResource(R.string.nav_rooms))
            FixedGrid(state.rooms, columns = 3) { r, mod ->
                RoomGlassCard(r, mod) { onOpenRoom(r.room.id) }
            }
        }
    }

    forecastDay?.let { start ->
        ForecastDialog(
            days = scene.days, start = start, place = scene.placeName,
            updated = scene.fetchedAt?.atZone(java.time.ZoneId.systemDefault())?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            onDismiss = { forecastDay = null },
        )
    }
}

/** Abschnittsüberschrift in Weiß auf dem Himmel. */
@Composable
fun SkySection(title: String, top: androidx.compose.ui.unit.Dp = 34.dp) {
    Text(
        title.uppercase(), color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 20.dp, top = top, bottom = 12.dp),
    )
}

/** Raumkarte der Übersicht: Icon, Temperatur, Name und Kurzstatus. */
@Composable
private fun RoomGlassCard(r: RoomSummary, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    val lit = r.lightsOn > 0
    Column(
        modifier.height(150.dp).glass(RoundedCornerShape(28.dp), g.glass, g.edge).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Row(
                Modifier.size(52.dp).clip(CircleShape).background(if (lit) Accent.Light.copy(alpha = 0.3f) else g.chip),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RaumIcons.room(r.room.icon), null, Modifier.size(26.dp), tint = if (lit) (if (g.dark) Accent.Light else Accent.LightDeep) else g.soft)
            }
            Spacer(Modifier.weight(1f))
            r.temperatureCelsius?.let { Text(formatDegrees(it), color = g.ink, fontSize = 28.sp, fontWeight = FontWeight.Light) }
        }
        Spacer(Modifier.weight(1f))
        Text(r.room.name, color = g.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (r.lightsTotal > 0) {
                Text(stringResource(R.string.lights_n_of_m_on, r.lightsOn, r.lightsTotal), color = g.soft, fontSize = 14.sp, maxLines = 1)
            }
            r.humidityPercent?.let { Text(formatPercent(it), color = g.soft, fontSize = 14.sp) }
            if (r.offlineCount > 0) Text(stringResource(R.string.offline_count, r.offlineCount), color = g.warn, fontSize = 14.sp, maxLines = 1)
        }
    }
}
