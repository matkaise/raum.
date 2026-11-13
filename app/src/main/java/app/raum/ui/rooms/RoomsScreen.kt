package app.raum.ui.rooms

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.raum.ui.lightsStatus
import app.raum.ui.theme.SkyTextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.BlindsClosed
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LightbulbCircle
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.Room
import app.raum.domain.models.find
import app.raum.ui.HomeUiState
import app.raum.ui.RoomSummary
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.GlassDeviceTile
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.formatPercent
import app.raum.ui.components.formatDegrees
import app.raum.ui.overview.SkyChip
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.glass
import kotlinx.coroutines.launch
import java.util.UUID

/** Räume (Spez. 9.5, ROM-001 – ROM-004): Raumleiste oben, darunter der gewählte Raum mit seinen Geräten. */
@Composable
fun RoomsScreen(
    state: HomeUiState,
    initialRoomId: UUID?,
    cardActions: DeviceCardActions,
    onGroupAction: (List<Device>, DeviceCommand, String) -> Unit,
    onAddRoom: (String, String) -> Unit,
    onUpdateRoom: (Room) -> Unit,
    onDeleteRoom: (Room) -> Unit,
    onMoveRoom: (Room, Int) -> Unit,
) {
    var selectedId by rememberSaveable(initialRoomId) { mutableStateOf(initialRoomId?.toString()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var dialogRoom by remember { mutableStateOf<Room?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Room?>(null) }
    val selected = state.rooms.firstOrNull { it.room.id.toString() == selectedId } ?: state.rooms.firstOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
    ) {
        when {
            editing -> EditRooms(state, onDone = { editing = false }, onAdd = { creating = true }, onEdit = { dialogRoom = it }, onDelete = { deleting = it }, onMove = onMoveRoom)
            selected == null -> NoRooms { creating = true }
            else -> {
                RoomsHeader(state.rooms, selected.room.id, state.lightsOn) { selectedId = it.toString() }
                RoomRow(selected, onGroupAction, onEdit = { editing = true })
                if (selected.devices.isEmpty()) {
                    Text(stringResource(R.string.room_devices_empty_text), color = SkyTextSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp))
                } else {
                    FixedGrid(selected.devices, columns = 4) { d, mod -> GlassDeviceTile(d, cardActions, mod.height(230.dp)) }
                }
            }
        }
    }

    if (creating) {
        RoomEditDialog(null, onDismiss = { creating = false }) { name, icon -> onAddRoom(name, icon); creating = false }
    }
    dialogRoom?.let { room ->
        RoomEditDialog(room, onDismiss = { dialogRoom = null }) { name, icon ->
            onUpdateRoom(room.copy(name = name.trim(), icon = icon)); dialogRoom = null
        }
    }
    deleting?.let { room ->
        RaumAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_named_title, room.name)) },
            text = { Text(stringResource(R.string.room_delete_text)) },
            confirmButton = {
                TextButton(onClick = { onDeleteRoom(room); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Kopf wie im Haushalt (Entwurf „raum. Übersicht Redesign“, Reihe „Räume“): Titel links, rechts die Raumleiste –
 * jede Taste wie ein Haushaltsgerät aufgebaut (Ring oben, Name und Zustand darunter), die gewählte weiß.
 */
@Composable
private fun RoomsHeader(rooms: List<RoomSummary>, selected: UUID, lightsOn: Int, onPick: (UUID) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.widthIn(min = 200.dp, max = 300.dp)) {
            Text(stringResource(R.string.nav_rooms), style = SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
            Text(
                listOf(pluralStringResource(R.plurals.rooms_count, rooms.size, rooms.size), lightsStatus(lightsOn)).joinToString(" · "),
                style = SkyTextStyle, color = SkyTextSoft, fontSize = 17.sp, lineHeight = 22.sp, maxLines = 2, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { RoomTabs(rooms, selected, onPick) }
    }
}

/** Raumleiste: Glasschiene mit Tasten; passen nicht alle, wischt sie waagrecht und Pfeile springen weiter. */
@Composable
private fun RoomTabs(rooms: List<RoomSummary>, selected: UUID, onPick: (UUID) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val index = rooms.indexOfFirst { it.room.id == selected }
    // Aus der Übersicht geöffnet: gewählten Raum ins Bild holen, falls er außerhalb liegt
    LaunchedEffect(index) {
        if (index < 0) return@LaunchedEffect
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null || item.offset < 0 || item.offset + item.size > info.viewportEndOffset) list.animateScrollToItem(index)
    }
    val desc = stringResource(R.string.room_choose)
    Box {
        LazyRow(
            state = list,
            modifier = Modifier.semantics { contentDescription = desc }.clip(RoundedCornerShape(36.dp)).background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(36.dp)),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rooms, key = { it.room.id }) { r -> RoomTab(r, r.room.id == selected) { onPick(r.room.id) } }
        }
        if (list.canScrollBackward) {
            StripArrow(Icons.Outlined.ChevronLeft, Modifier.align(Alignment.CenterStart).offset(x = (-12).dp)) {
                scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
            }
        }
        if (list.canScrollForward) {
            StripArrow(Icons.Outlined.ChevronRight, Modifier.align(Alignment.CenterEnd).offset(x = (-6).dp)) {
                scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex + 3).coerceAtMost(rooms.lastIndex)) }
            }
        }
    }
}

private val TabInk = Color(0xFF10131F)
private val LampAmber = Color(0xFFFFC24F)

@Composable
private fun RoomTab(r: RoomSummary, on: Boolean, onClick: () -> Unit) {
    val lit = r.lightsOn > 0
    val bg by animateColorAsState(if (on) Color.White else Color.Transparent, label = "roomTab")
    val (status, color, strong) = when {
        r.offlineCount > 0 -> Triple(stringResource(R.string.offline_count, r.offlineCount), if (on) Color(0xFFB3261E) else Color(0xFFFFB4A8), false)
        lit -> Triple(pluralStringResource(R.plurals.lights_on, r.lightsOn, r.lightsOn), if (on) Color(0xFF9A6424) else Color(0xFFFFE1A3), true)
        r.temperatureCelsius != null -> Triple(formatDegrees(r.temperatureCelsius), if (on) TabInk.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.75f), false)
        r.lightsTotal > 0 -> Triple(stringResource(R.string.lights_off), if (on) TabInk.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.75f), false)
        else -> Triple(pluralStringResource(R.plurals.devices_count, r.devices.size, r.devices.size), if (on) TabInk.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.75f), false)
    }
    Column(
        Modifier.width(120.dp).height(124.dp)
            .then(if (on) Modifier.shadow(14.dp, RoundedCornerShape(30.dp), ambientColor = Color(0xFF0A081E), spotColor = Color(0xFF0A081E)) else Modifier)
            .clip(RoundedCornerShape(30.dp)).background(bg).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        RoomRing(r, on)
        Text(r.room.name, color = if (on) TabInk else Color.White, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(status, color = color, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ring um das Raumsymbol: Anteil der eingeschalteten Lichter; roter Punkt, wenn etwas offline ist. */
@Composable
private fun RoomRing(r: RoomSummary, on: Boolean) {
    val lit = r.lightsOn > 0
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(if (on) TabInk.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.28f), 0f, 360f, false, inset, arc, style = Stroke(stroke))
            if (lit && r.lightsTotal > 0) {
                drawArc(LampAmber, -90f, 360f * (r.lightsOn.toFloat() / r.lightsTotal).coerceIn(0f, 1f), false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (lit) Color(0xFFFFF3DC) else Color.White.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center,
        ) { Icon(RaumIcons.room(r.room.icon), null, Modifier.size(22.dp), tint = if (lit) Color(0xFF9A6424) else TabInk.copy(alpha = 0.62f)) }
        if (r.offlineCount > 0) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(14.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Color(0xFFE5484D)))
        }
    }
}

@Composable
private fun StripArrow(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.size(56.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(g.glassSolid).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(28.dp), tint = g.ink) }
}

/**
 * Gewählter Raum als kompakte Zeile: Name und Zustand links, Gruppenaktionen rechts – damit die Kacheln hoch beginnen.
 * Brennt Licht, ist „Alle aus“ betont, sonst „Alle an“.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomRow(s: RoomSummary, onGroupAction: (List<Device>, DeviceCommand, String) -> Unit, onEdit: () -> Unit) {
    val g = LocalGlass.current
    val lights = s.devices.filter { it.capabilities.find<LightCapability>() != null }
    val hasCovers = s.devices.any { it.capabilities.find<CoverCapability>() != null }
    val anyOn = s.lightsOn > 0
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.room.name, style = SkyTextStyle, color = SkyText, fontSize = 34.sp, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
        FlowRow(Modifier.weight(1f).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.temperatureCelsius?.let { SkyChip(Icons.Outlined.Thermostat, SkyText, formatDegrees(it), height = 36) }
            s.humidityPercent?.let { SkyChip(Icons.Outlined.WaterDrop, SkyText, formatPercent(it), height = 36) }
            if (s.lightsTotal > 0) SkyChip(Icons.Outlined.Lightbulb, Accent.Light, stringResource(R.string.lights_n_of_m_on, s.lightsOn, s.lightsTotal), height = 36)
            if (s.occupied == true) SkyChip(Icons.AutoMirrored.Outlined.DirectionsWalk, SkyText, stringResource(R.string.room_occupied_chip), height = 36)
            if (s.offlineCount > 0) SkyChip(Icons.Outlined.CloudOff, Accent.Warn, stringResource(R.string.offline_count, s.offlineCount), warning = true, height = 36)
        }
        if (lights.isNotEmpty()) {
            val on = stringResource(R.string.group_lights_on)
            val off = stringResource(R.string.group_lights_off)
            if (anyOn) {
                PillButton(Icons.Outlined.Lightbulb, stringResource(R.string.group_all_on), Color.White.copy(alpha = 0.3f), SkyText, border = Color.White.copy(alpha = 0.4f)) {
                    onGroupAction(lights, DeviceCommand.SetOn(true), on)
                }
                PillButton(Icons.Outlined.LightbulbCircle, stringResource(R.string.group_all_off), TabInk, Color.White) {
                    onGroupAction(lights, DeviceCommand.SetOn(false), off)
                }
            } else {
                PillButton(Icons.Outlined.Lightbulb, stringResource(R.string.group_all_on), Accent.Light, Accent.OnLight) {
                    onGroupAction(lights, DeviceCommand.SetOn(true), on)
                }
                PillButton(Icons.Outlined.LightbulbCircle, stringResource(R.string.group_all_off), g.glassStrong, g.ink, border = g.edge) {
                    onGroupAction(lights, DeviceCommand.SetOn(false), off)
                }
            }
        }
        if (hasCovers) {
            val open = stringResource(R.string.group_covers_open)
            val close = stringResource(R.string.group_covers_close)
            RoundPill(Icons.Outlined.Blinds, open) { onGroupAction(s.devices, DeviceCommand.OpenCover, open) }
            RoundPill(Icons.Outlined.BlindsClosed, close) { onGroupAction(s.devices, DeviceCommand.CloseCover, close) }
        }
        RoundPill(Icons.Outlined.Edit, stringResource(R.string.action_edit), onClick = onEdit)
    }
}

@Composable
fun PillButton(icon: ImageVector?, label: String, bg: Color, fg: Color, border: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.height(56.dp).clip(shape).background(bg).then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg); Spacer(Modifier.width(8.dp)) }
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun RoundPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(g.glassStrong).border(1.dp, g.edge, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = g.ink) }
}

@Composable
private fun NoRooms(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 160.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.rooms_empty), color = SkyText, fontSize = 32.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(24.dp))
        PillButton(Icons.Outlined.Add, stringResource(R.string.room_add), Accent.Light, Accent.OnLight, onClick = onAdd)
    }
}

/** Räume umbenennen, sortieren, löschen, hinzufügen (ROM-001, ROM-002). */
@Composable
private fun EditRooms(
    state: HomeUiState,
    onDone: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Room) -> Unit,
    onDelete: (Room) -> Unit,
    onMove: (Room, Int) -> Unit,
) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.nav_rooms), color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
        PillButton(Icons.Outlined.Add, stringResource(R.string.room_add), g.glassStrong, g.ink, border = g.edge, onClick = onAdd)
        PillButton(Icons.Outlined.Check, stringResource(R.string.action_done), Accent.Light, Accent.OnLight, onClick = onDone)
    }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), g.glassStrong, g.edge).padding(8.dp)) {
        state.rooms.forEachIndexed { index, summary ->
            val room = summary.room
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = g.track)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(RaumIcons.room(room.icon), null, Modifier.size(28.dp), tint = g.soft)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(room.name, color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(pluralStringResource(R.plurals.devices_count, summary.devices.size, summary.devices.size), color = g.soft, fontSize = 14.sp)
                }
                IconButton(onClick = { onMove(room, -1) }, enabled = index > 0, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.action_move_up), tint = if (index > 0) g.ink else g.track)
                }
                IconButton(onClick = { onMove(room, 1) }, enabled = index < state.rooms.lastIndex, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.action_move_down), tint = if (index < state.rooms.lastIndex) g.ink else g.track)
                }
                IconButton(onClick = { onEdit(room) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit), tint = g.ink)
                }
                IconButton(onClick = { onDelete(room) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), tint = g.warn)
                }
            }
        }
    }
    if (state.unassigned.isNotEmpty()) {
        Text(
            pluralStringResource(R.plurals.devices_without_room, state.unassigned.size, state.unassigned.size),
            color = SkyTextSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomEditDialog(room: Room?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(room?.name ?: "") }
    var icon by remember { mutableStateOf(room?.icon ?: "sofa") }
    RaumAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (room == null) R.string.room_new else R.string.room_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) },
                    label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaumIcons.rooms.forEach { (key, vector) ->
                        FilterChip(
                            selected = icon == key,
                            onClick = { icon = key },
                            label = { Icon(vector, contentDescription = key, Modifier.size(24.dp)) },
                            modifier = Modifier.size(width = 64.dp, height = 48.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, icon) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
