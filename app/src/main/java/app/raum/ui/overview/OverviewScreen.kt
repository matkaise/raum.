package app.raum.ui.overview

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
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
import app.raum.ui.components.formatDegrees
import app.raum.ui.components.rememberFinishedScene
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.glass
import java.util.UUID

/**
 * Startseite (Spez. 9.4) auf dem Himmel, Entwurf „raum. Übersicht Redesign“ Variante A: kompakter Kopf, gleich
 * darunter die Favoriten und „Gerade aktiv“ (Musik, Haushalt) – alles Bedienbare auf dem ersten Bildschirm.
 * Szenen und Räume folgen beim Scrollen.
 */
@Composable
fun OverviewScreen(
    state: HomeUiState,
    scene: SkyScene,
    cardActions: DeviceCardActions,
    onRunScene: (Scene) -> Unit,
    onOpenRoom: (UUID) -> Unit,
    /** Reihe „Gerade aktiv“ (Musik, Haushalt) unter den Favoriten – leer, wenn nichts läuft oder alles aus ist. */
    activeRow: @Composable () -> Unit = {},
) {
    var forecastDay by remember { mutableStateOf<Int?>(null) }
    val finished = rememberFinishedScene(state.runningSceneId)
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
    ) {
        HomeHero(state, scene, onOpenForecast = if (scene.days.isNotEmpty()) ({ forecastDay = it }) else null)

        SkySection(stringResource(R.string.favorites), top = 24.dp)
        if (state.favorites.isEmpty()) {
            Text(stringResource(R.string.favorites_empty), style = app.raum.ui.theme.SkyTextStyle, color = SkyTextSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp))
        } else {
            FixedGrid(state.favorites, columns = 5) { device, mod ->
                GlassDeviceTile(device, cardActions, mod.height(240.dp), roomName = state.roomName(device.roomId))
            }
        }

        activeRow()

        if (state.scenes.isNotEmpty()) {
            SkySection(stringResource(R.string.nav_scenes), top = 26.dp)
            FixedGrid(state.scenes, columns = 5) { s, mod ->
                SceneMoodCard(
                    s, running = state.runningSceneId == s.id, done = finished == s.id,
                    enabled = state.runningSceneId == null, onRun = { onRunScene(s) }, modifier = mod,
                )
            }
        }

        if (state.rooms.isNotEmpty()) {
            SkySection(stringResource(R.string.nav_rooms), top = 26.dp)
            FixedGrid(state.rooms, columns = 4) { r, mod ->
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

private val LampOn = Color(0xFFFFB938)

/**
 * Raumkarte der Übersicht: Icon und Temperatur oben, Name unten, darunter ein Punkt je Licht – eingeschaltete
 * leuchten. Brennt Licht im Raum, wird die ganze Karte warm.
 */
@Composable
private fun RoomGlassCard(r: RoomSummary, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    val lit = r.lightsOn > 0
    val shape = RoundedCornerShape(28.dp)
    val bg = when {
        lit && g.dark -> Modifier.background(Brush.verticalGradient(listOf(LampOn.copy(alpha = 0.24f), LampOn.copy(alpha = 0.12f))), shape).border(1.dp, g.edge, shape)
        lit -> Modifier.background(Brush.linearGradient(listOf(Color(0xFFFFF3DC).copy(alpha = 0.9f), Color(0xFFFFD896).copy(alpha = 0.8f))), shape).border(1.dp, g.edge, shape)
        else -> Modifier.glass(shape, g.glass, g.edge)
    }
    val warmInk = lit && !g.dark
    Column(
        modifier.height(132.dp).clip(shape).then(bg).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(RaumIcons.room(r.room.icon), null, Modifier.size(26.dp), tint = if (warmInk) Color(0xFFB5761D) else if (lit) LampOn else g.soft)
            Spacer(Modifier.weight(1f))
            r.temperatureCelsius?.let { Text(formatDegrees(it), color = g.ink, fontSize = 26.sp, fontWeight = FontWeight.Light, lineHeight = 28.sp) }
        }
        Spacer(Modifier.weight(1f))
        Text(r.room.name, color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val dots = r.lightsTotal.coerceAtMost(8)
            repeat(dots) { i ->
                val on = i < r.lightsOn
                Box(
                    Modifier.padding(end = 5.dp).size(10.dp)
                        .then(if (on) Modifier.shadow(4.dp, CircleShape, ambientColor = LampOn, spotColor = LampOn) else Modifier)
                        .clip(CircleShape).background(if (on) LampOn else g.track),
                )
            }
            val status = when {
                r.lightsTotal == 0 -> null
                lit -> pluralStringResource(R.plurals.lights_on, r.lightsOn, r.lightsOn)
                else -> stringResource(R.string.room_lights_off_short)
            }
            status?.let {
                Text(it, color = if (warmInk) Color(0xFF7A5A2C) else g.soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp).weight(1f, fill = false))
            }
            if (r.offlineCount > 0) {
                Text(
                    (if (status != null) " · " else "") + stringResource(R.string.offline_count, r.offlineCount),
                    color = g.warn, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
    }
}
