package app.raum.ui.rooms

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
                RoomStrip(state.rooms, selected.room.id) { selectedId = it.toString() }
                RoomHeader(selected, onGroupAction, onEdit = { editing = true })
                if (selected.devices.isEmpty()) {
                    Text(stringResource(R.string.room_devices_empty_text), color = SkyTextSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp))
                } else {
                    FixedGrid(selected.devices, columns = 4) { d, mod -> GlassDeviceTile(d, cardActions, mod.height(190.dp)) }
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

/** Horizontale Raumleiste: bis sechs Räume füllen die Breite, mehr lassen sich wischen. */
@Composable
private fun RoomStrip(rooms: List<RoomSummary>, selected: UUID, onPick: (UUID) -> Unit) {
    val g = LocalGlass.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val many = rooms.size > 6
        val cardW: Dp = if (many) 162.dp else (maxWidth - 50.dp) / 6
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(rooms, key = { it.room.id }) { r ->
                val on = r.room.id == selected
                val lit = r.lightsOn > 0
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier.width(cardW).height(104.dp)
                        .then(if (on) Modifier.shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.14f)) else Modifier)
                        .clip(shape).background(if (on) g.glassSolid else g.glass)
                        .border(1.dp, if (on) Color.White.copy(alpha = 0.9f) else g.edge, shape)
                        .clickable { onPick(r.room.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(RaumIcons.room(r.room.icon), null, Modifier.size(26.dp), tint = if (lit) (if (g.dark) Accent.Light else Accent.LightDeep) else g.soft)
                        Spacer(Modifier.weight(1f))
                        r.temperatureCelsius?.let { Text(formatDegrees(it), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.Light) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(r.room.name, color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (lit) Accent.Light else g.track))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (r.lightsTotal == 0) pluralStringResource(R.plurals.devices_count, r.devices.size, r.devices.size)
                            else if (lit) stringResource(R.string.lights_short_of, r.lightsOn, r.lightsTotal) else stringResource(R.string.lights_off),
                            color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (r.offlineCount > 0) Icon(Icons.Outlined.CloudOff, stringResource(R.string.offline_count, r.offlineCount), Modifier.size(16.dp), tint = g.warn)
                    }
                }
            }
        }
        if (list.canScrollBackward) {
            StripArrow(Icons.Outlined.ChevronLeft, Modifier.align(Alignment.CenterStart).offset(x = (-20).dp)) {
                scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
            }
        }
        if (list.canScrollForward) {
            StripArrow(Icons.Outlined.ChevronRight, Modifier.align(Alignment.CenterEnd).offset(x = 8.dp)) {
                scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex + 3).coerceAtMost(rooms.lastIndex)) }
            }
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

/** Name, Zustand als Chips und Gruppenaktionen des gewählten Raums. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomHeader(s: RoomSummary, onGroupAction: (List<Device>, DeviceCommand, String) -> Unit, onEdit: () -> Unit) {
    val g = LocalGlass.current
    val lights = s.devices.filter { it.capabilities.find<LightCapability>() != null }
    val hasCovers = s.devices.any { it.capabilities.find<CoverCapability>() != null }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 28.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(s.room.name, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, lineHeight = 58.sp, letterSpacing = (-1.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.temperatureCelsius?.let { SkyChip(Icons.Outlined.Thermostat, SkyText, formatDegrees(it), height = 36) }
                s.humidityPercent?.let { SkyChip(Icons.Outlined.WaterDrop, SkyText, formatPercent(it), height = 36) }
                if (s.lightsTotal > 0) SkyChip(Icons.Outlined.Lightbulb, Accent.Light, stringResource(R.string.lights_n_of_m_on, s.lightsOn, s.lightsTotal), height = 36)
                if (s.occupied == true) SkyChip(Icons.AutoMirrored.Outlined.DirectionsWalk, SkyText, stringResource(R.string.room_occupied_chip), height = 36)
                if (s.offlineCount > 0) SkyChip(Icons.Outlined.CloudOff, Accent.Warn, stringResource(R.string.offline_count, s.offlineCount), warning = true, height = 36)
            }
        }
        if (lights.isNotEmpty()) {
            val on = stringResource(R.string.group_lights_on)
            val off = stringResource(R.string.group_lights_off)
            PillButton(Icons.Outlined.Lightbulb, stringResource(R.string.group_all_on), Accent.Light, Accent.OnLight) {
                onGroupAction(lights, DeviceCommand.SetOn(true), on)
            }
            PillButton(Icons.Outlined.LightbulbCircle, stringResource(R.string.group_all_off), g.glassStrong, g.ink, border = g.edge) {
                onGroupAction(lights, DeviceCommand.SetOn(false), off)
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
