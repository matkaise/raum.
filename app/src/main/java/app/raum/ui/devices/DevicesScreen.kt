package app.raum.ui.devices

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.BlindsClosed
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LightbulbCircle
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCategory
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.Room
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import app.raum.ui.HomeUiState
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.EmptyState
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.GlassDeviceTile
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.RingTabSpec
import app.raum.ui.components.RingTabStrip
import app.raum.ui.components.formatDegrees
import app.raum.ui.overview.SkyChip
import app.raum.ui.rooms.PillButton
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import java.util.UUID

private const val ALL = "all"
private const val OFFLINE = "offline"
private const val ADD = "add"

/** Was gerade „aktiv“ ist: Licht oder Schalter an, Storen offen, Thermostat nicht aus. */
private fun Device.isOnNow(): Boolean {
    if (!isOnline) return false
    val c = capabilities
    c.find<LightCapability>()?.let { return it.isOn }
    c.find<SwitchCapability>()?.let { return it.isOn }
    c.find<CoverCapability>()?.let { return it.openPercent > 5 }
    c.find<ThermostatCapability>()?.let { return it.mode != ThermostatMode.OFF }
    return false
}

/**
 * Alle Geräte (Entwurf „raum. Übersicht Redesign“, Reihe „Geräte“): Kopf wie Haushalt und Räume mit den Kategorien als
 * Ring-Leiste (Ring = Anteil aktiv), darunter je Raum ein Abschnitt mit Sprung in den Raum. Mit Filter wirken
 * „Alle an/aus“ bzw. „Öffnen/Schließen“ auf alle gezeigten Geräte im Haus. Die Kacheln sind dieselben wie überall.
 */
@Composable
fun DevicesScreen(
    state: HomeUiState,
    cardActions: DeviceCardActions,
    openAddDialog: Boolean = false,
    onAddDialogOpened: () -> Unit = {},
    onGroupAction: (List<Device>, DeviceCommand, String) -> Unit = { _, _, _ -> },
    onOpenRoom: (UUID) -> Unit = {},
) {
    var filterKey by rememberSaveable { mutableStateOf(ALL) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // Direkt nach der Einrichtung („Erstes Gerät hinzufügen“)
    LaunchedEffect(openAddDialog) { if (openAddDialog) { adding = true; onAddDialogOpened() } }

    val available = DeviceCategory.entries.filter { c -> state.devices.any { it.category == c } }
    val category = available.firstOrNull { it.name == filterKey }
    // Gewählter Filter verschwunden (letztes Gerät der Kategorie entfernt, alle wieder online): zurück zu „Alle“
    val shown = when {
        filterKey == OFFLINE && state.offline.isNotEmpty() -> OFFLINE
        category != null -> filterKey
        else -> ALL
    }
    val visible = when (shown) {
        ALL -> state.devices
        OFFLINE -> state.offline
        else -> state.devices.filter { it.category == category }
    }

    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 24.dp, top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Titel mit Zusammenfassung als Tag daneben; ist etwas offline, wird der Tag zur Warnung
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.nav_devices), style = SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
                val offline = state.offline.isNotEmpty()
                val reach = if (!offline) stringResource(R.string.devices_all_reachable) else stringResource(R.string.offline_count, state.offline.size)
                SkyChip(
                    if (offline) Icons.Outlined.CloudOff else Icons.Outlined.CheckCircleOutline, if (offline) Accent.Warn else Color(0xFFA8E6B8),
                    pluralStringResource(R.plurals.devices_count, state.devices.size, state.devices.size) + " · " + reach,
                    warning = offline, height = 40,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                RingTabStrip(categoryTabs(state, available), shown, stringResource(R.string.devices_choose_category)) { k ->
                    if (k == ADD) adding = true else filterKey = k
                }
            }
        }

        if (state.devices.isEmpty()) {
            EmptyState(Icons.Outlined.Devices, stringResource(R.string.devices_empty), stringResource(R.string.devices_empty_text)) {
                FilledTonalButton(onClick = { adding = true }) { Text(stringResource(R.string.device_add)) }
            }
        } else {
            // Kein Lazy-Grid: Das baute neue Kacheln erst beim Hineinscrollen – auf dem Panel bis 60 ms pro Reihe, sichtbar
            // als Ruckeln. Wie die Übersicht: alle Kacheln einmal aufbauen, beim Scrollen nur verschieben.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                if (shown != ALL) FilterRow(shown, category, visible, onGroupAction)
                val groups: List<Pair<Room?, List<Device>>> =
                    state.rooms.map { it.room to visible.filter { d -> d.roomId == it.room.id } } + (null to visible.filter { d -> d in state.unassigned })
                groups.filter { it.second.isNotEmpty() }.forEachIndexed { i, (room, devices) ->
                    RoomSection(room, devices, top = if (i == 0) (if (shown == ALL) 22.dp else 6.dp) else 26.dp, onOpenRoom = onOpenRoom)
                    FixedGrid(devices, columns = 4) { device, mod ->
                        key(device.id) { GlassDeviceTile(device, cardActions, mod.height(230.dp)) }
                    }
                }
            }
        }
    }

    if (adding) {
        CommissioningDialog(rooms = state.rooms.map { it.room }, onClose = { adding = false })
    }
}

/** Tasten der Leiste: Alle, je vorhandene Kategorie, Offline (nur wenn nötig), Hinzufügen. */
@Composable
private fun categoryTabs(state: HomeUiState, available: List<DeviceCategory>): List<RingTabSpec> = buildList {
    val ink = Color(0xFF10131F)
    add(RingTabSpec(ALL, stringResource(R.string.filter_all), pluralStringResource(R.plurals.devices_count, state.devices.size, state.devices.size), Icons.Outlined.Devices))
    available.forEach { c ->
        val list = state.devices.filter { it.category == c }
        val on = list.count { it.isOnNow() }
        val share = if (list.isEmpty()) 0f else on.toFloat() / list.size
        val label = stringResource(c.labelRes)
        add(
            when (c) {
                DeviceCategory.LIGHT -> RingTabSpec(
                    c.name, label, stringResource(R.string.devices_n_of_m_on, on, list.size), Icons.Outlined.Lightbulb,
                    iconTint = if (on > 0) Color(0xFF9A6424) else ink.copy(alpha = 0.7f), ring = share, ringColor = Color(0xFFFFC24F),
                    active = on > 0, core = if (on > 0) Color(0xFFFFF3DC) else null, accentOn = Color(0xFF9A6424), accentOff = Color(0xFFFFE1A3),
                )
                DeviceCategory.SWITCH -> RingTabSpec(
                    c.name, label, stringResource(R.string.devices_n_of_m_on, on, list.size), Icons.Outlined.PowerSettingsNew,
                    iconTint = if (on > 0) Color(0xFF2F6B45) else ink.copy(alpha = 0.7f), ring = share, ringColor = Color(0xFF5FCB86),
                    active = on > 0, core = if (on > 0) Color(0xFFE9FBEF) else null, accentOn = Color(0xFF2F6B45), accentOff = Color(0xFFC8F3D2),
                )
                DeviceCategory.CLIMATE -> {
                    val temps = list.filter { it.isOnline }.mapNotNull { it.capabilities.find<ThermostatCapability>()?.currentCelsius }
                    val status = if (temps.isNotEmpty()) stringResource(R.string.climate_avg, formatDegrees(temps.average())) else pluralStringResource(R.plurals.devices_count, list.size, list.size)
                    RingTabSpec(c.name, label, status, Icons.Outlined.Thermostat, iconTint = Color(0xFFB4561F), ring = share, ringColor = Color(0xFFF0894A))
                }
                DeviceCategory.COVER -> RingTabSpec(
                    c.name, label, stringResource(R.string.devices_n_of_m_open, on, list.size), Icons.Outlined.Blinds,
                    iconTint = Color(0xFF1D6F99), ring = share, ringColor = Color(0xFF7EC8F8),
                )
                DeviceCategory.SENSOR -> RingTabSpec(c.name, label, pluralStringResource(R.plurals.devices_count, list.size, list.size), Icons.Outlined.Sensors)
                DeviceCategory.OTHER -> RingTabSpec(c.name, label, pluralStringResource(R.plurals.devices_count, list.size, list.size), Icons.Outlined.Widgets)
            },
        )
    }
    if (state.offline.isNotEmpty()) {
        add(RingTabSpec(OFFLINE, stringResource(R.string.filter_offline), stringResource(R.string.offline_count, state.offline.size), Icons.Outlined.CloudOff, badge = true))
    }
    add(RingTabSpec(ADD, stringResource(R.string.device_add_short), "Matter", Icons.Outlined.Add, dashed = true))
}

/** Gefiltert: Name der Kategorie, Zustand, Gruppenaktionen für alle gezeigten Geräte im Haus. */
@Composable
private fun FilterRow(key: String, category: DeviceCategory?, visible: List<Device>, onGroupAction: (List<Device>, DeviceCommand, String) -> Unit) {
    val g = LocalGlass.current
    val title = when {
        key == OFFLINE -> stringResource(R.string.filter_offline)
        category == DeviceCategory.LIGHT -> stringResource(R.string.devices_all_lights)
        category != null -> stringResource(category.labelRes)
        else -> ""
    }
    val on = visible.count { it.isOnNow() }
    val rooms = visible.filter { it.isOnNow() }.mapNotNull { it.roomId }.distinct().size
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 22.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = SkyTextStyle, color = SkyText, fontSize = 34.sp, letterSpacing = (-0.5).sp, maxLines = 1)
        val chip = when (category) {
            DeviceCategory.LIGHT, DeviceCategory.SWITCH ->
                stringResource(R.string.devices_n_of_m_on, on, visible.size) + if (rooms > 0) " · " + pluralStringResource(R.plurals.devices_in_rooms, rooms, rooms) else ""
            DeviceCategory.COVER -> stringResource(R.string.devices_n_of_m_open, on, visible.size)
            else -> pluralStringResource(R.plurals.devices_count, visible.size, visible.size)
        }
        Box(Modifier.padding(start = 4.dp)) { SkyChip(if (category == DeviceCategory.LIGHT) Icons.Outlined.Lightbulb else null, Accent.Light, chip, height = 36) }
        Spacer(Modifier.weight(1f))
        val online = visible.filter { it.isOnline }
        when (category) {
            DeviceCategory.LIGHT, DeviceCategory.SWITCH -> {
                val onLabel = stringResource(R.string.group_lights_on)
                val offLabel = stringResource(R.string.group_lights_off)
                if (on > 0) {
                    PillButton(Icons.Outlined.Lightbulb, stringResource(R.string.group_all_on), Color.White.copy(alpha = 0.3f), SkyText, border = Color.White.copy(alpha = 0.4f)) {
                        onGroupAction(online, DeviceCommand.SetOn(true), onLabel)
                    }
                    PillButton(Icons.Outlined.LightbulbCircle, stringResource(R.string.group_all_off), Color(0xFF10131F), Color.White) {
                        onGroupAction(online, DeviceCommand.SetOn(false), offLabel)
                    }
                } else {
                    PillButton(Icons.Outlined.Lightbulb, stringResource(R.string.group_all_on), Accent.Light, Accent.OnLight) {
                        onGroupAction(online, DeviceCommand.SetOn(true), onLabel)
                    }
                    PillButton(Icons.Outlined.LightbulbCircle, stringResource(R.string.group_all_off), g.glassStrong, g.ink, border = g.edge) {
                        onGroupAction(online, DeviceCommand.SetOn(false), offLabel)
                    }
                }
            }
            DeviceCategory.COVER -> {
                val open = stringResource(R.string.group_covers_open)
                val close = stringResource(R.string.group_covers_close)
                PillButton(Icons.Outlined.Blinds, open, g.glassStrong, g.ink, border = g.edge) { onGroupAction(online, DeviceCommand.OpenCover, open) }
                PillButton(Icons.Outlined.BlindsClosed, close, g.glassStrong, g.ink, border = g.edge) { onGroupAction(online, DeviceCommand.CloseCover, close) }
            }
            else -> Unit
        }
    }
}

/** Abschnitt je Raum: Symbol, Name, Anzahl und Lichter, rechts der Sprung in den Raum. */
@Composable
private fun RoomSection(room: Room?, devices: List<Device>, top: androidx.compose.ui.unit.Dp, onOpenRoom: (UUID) -> Unit) {
    val lights = devices.filter { it.capabilities.find<LightCapability>() != null }
    val lit = lights.count { it.isOnNow() }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = top, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(if (room != null) RaumIcons.room(room.icon) else Icons.Outlined.Devices, null, Modifier.size(22.dp), tint = if (lit > 0) Accent.Light else SkyText)
        }
        Text(room?.name ?: stringResource(R.string.no_room), style = SkyTextStyle, color = SkyText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
        val detail = pluralStringResource(R.plurals.devices_count, devices.size, devices.size) +
            if (lights.isNotEmpty()) " · " + stringResource(R.string.lights_n_of_m_on, lit, lights.size) else ""
        Text(detail, style = SkyTextStyle, color = SkyTextSoft, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (room != null) {
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.14f)).clickable { onOpenRoom(room.id) }.padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.devices_open_room), color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = SkyText)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}
