package app.raum.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import app.raum.ui.components.RaumAlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.raum.R
import app.raum.domain.models.Room
import app.raum.ui.HomeUiState
import app.raum.ui.components.DeviceIconBadge
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.ScreenTitle
import java.util.UUID

/** Raumverwaltung (ROM-001, ROM-002). */
@Composable
fun RoomsScreen(
    state: HomeUiState,
    onOpenRoom: (UUID) -> Unit,
    onAddRoom: (String, String) -> Unit,
    onUpdateRoom: (Room) -> Unit,
    onDeleteRoom: (Room) -> Unit,
    onMoveRoom: (Room, Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var dialogRoom by remember { mutableStateOf<Room?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Room?>(null) }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 40.dp)) {
        ScreenTitle(
            stringResource(R.string.nav_rooms),
            pluralStringResource(R.plurals.rooms_count, state.rooms.size, state.rooms.size) + " · " +
                pluralStringResource(R.plurals.devices_count, state.devices.size, state.devices.size),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editing = !editing }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (editing) R.string.action_done else R.string.action_edit))
                }
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.room_add))
                }
            }
        }

        if (editing) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(state.rooms, key = { _, s -> s.room.id }) { index, summary ->
                    val room = summary.room
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeviceIconBadge(RaumIcons.room(room.icon), active = false)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(room.name, style = MaterialTheme.typography.titleMedium)
                                Text(pluralStringResource(R.plurals.devices_count, summary.devices.size, summary.devices.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onMoveRoom(room, -1) }, enabled = index > 0, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.action_move_up))
                            }
                            IconButton(onClick = { onMoveRoom(room, 1) }, enabled = index < state.rooms.lastIndex, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.action_move_down))
                            }
                            IconButton(onClick = { dialogRoom = room }, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit))
                            }
                            IconButton(onClick = { deleting = room }, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(state.rooms, key = { it.room.id }) { summary ->
                    RoomTile(summary, onClick = { onOpenRoom(summary.room.id) })
                }
                if (state.unassigned.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            pluralStringResource(R.plurals.devices_without_room, state.unassigned.size, state.unassigned.size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
