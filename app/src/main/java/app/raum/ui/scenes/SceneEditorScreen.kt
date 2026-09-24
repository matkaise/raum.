package app.raum.ui.scenes

import app.raum.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.domain.models.Device
import app.raum.domain.models.LightCapability
import app.raum.domain.models.Room
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceTarget
import app.raum.domain.usecases.SceneTargets
import app.raum.ui.components.ColorPresets
import app.raum.ui.components.DeviceIconBadge
import app.raum.ui.components.EmptyState
import app.raum.ui.components.OfflineBadge
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.SectionHeader
import app.raum.ui.components.TargetEditor
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.UUID

@Composable
fun SceneEditorScreen(
    sceneId: UUID?,
    rooms: List<Room>,
    onClose: () -> Unit,
    viewModel: SceneEditorViewModel = koinViewModel { parametersOf(sceneId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    val back = { if (state.dirty) { confirmDiscard = true } else { onClose() } }

    if (state.loading) return
    if (state.notFound) {
        EmptyState(Icons.Outlined.WbTwilight, stringResource(R.string.scene_not_found), stringResource(R.string.possibly_deleted)) {
            FilledTonalButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
        }
        return
    }

    val byId = devices.associateBy { it.id }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 32.dp)) {
        // Linke Spalte: Name, Icon, Aktionen
        Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.isNew) R.string.scene_new else R.string.scene_edit), style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                isError = state.showErrors && state.nameError != null,
                supportingText = { if (state.showErrors) state.nameError?.let { Text(stringResource(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            IconPicker(state.icon, viewModel::setIcon)
            Spacer(Modifier.height(24.dp))
            Text(
                pluralStringResource(R.plurals.devices_count, state.entries.size, state.entries.size) + " · " +
                    pluralStringResource(R.plurals.actions_count, state.actionCount, state.actionCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.showErrors) state.entriesError?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.action_save)) }
                FilledTonalButton(
                    onClick = viewModel::tryOut,
                    enabled = state.entries.isNotEmpty() && !state.busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.try_now))
                }
                OutlinedButton(
                    onClick = viewModel::captureCurrentStates,
                    enabled = state.entries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Outlined.Restore, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.capture_states))
                }
                if (!state.isNew) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scene_delete))
                    }
                }
            }
        }

        Spacer(Modifier.width(40.dp))

        // Rechte Spalte: Geräte und Zielzustände
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SectionHeader(stringResource(R.string.scene_devices), Modifier.padding(top = 20.dp)) {
                FilledTonalButton(onClick = { picking = true }) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.devices_add))
                }
            }
            if (state.entries.isEmpty()) {
                EmptyState(
                    Icons.Outlined.WbTwilight,
                    stringResource(R.string.devices_empty),
                    stringResource(R.string.scene_devices_empty_text),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(state.entries, key = { it.deviceId }) { entry ->
                        EntryCard(
                            device = byId[entry.deviceId],
                            roomName = rooms.firstOrNull { it.id == byId[entry.deviceId]?.roomId }?.name,
                            target = entry.target,
                            onChange = { viewModel.updateTarget(entry.deviceId, it) },
                            onRemove = { viewModel.removeEntry(entry.deviceId) },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        DevicePickerDialog(
            devices = devices.filter { d -> SceneTargets.isSceneCapable(d) && state.entries.none { it.deviceId == d.id } },
            rooms = rooms,
            onDismiss = { picking = false },
            onConfirm = { viewModel.addDevices(it); picking = false },
        )
    }
    if (confirmDiscard) {
        RaumAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.scene_unsaved_text)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.action_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
        )
    }
    if (confirmDelete) {
        RaumAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named_title, state.name)) },
            text = { Text(stringResource(R.string.scene_delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaumIcons.scenes.forEach { (key, vector) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Icon(vector, contentDescription = key, Modifier.size(24.dp)) },
                modifier = Modifier.size(width = 64.dp, height = 48.dp),
            )
        }
    }
}

@Composable
private fun EntryCard(
    device: Device?,
    roomName: String?,
    target: DeviceTarget,
    onChange: (DeviceTarget) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device != null) DeviceIconBadge(RaumIcons.device(device), active = false)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(device?.displayName ?: stringResource(R.string.device_unknown), style = MaterialTheme.typography.titleMedium)
                    roomName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (device != null && !device.isOnline) { OfflineBadge(); Spacer(Modifier.width(8.dp)) }
                IconButton(onClick = onRemove, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Close, stringResource(R.string.scene_remove_device)) }
            }
            Spacer(Modifier.height(12.dp))
            TargetEditor(device, target, onChange)
        }
    }
}

@Composable
private fun DevicePickerDialog(
    devices: List<Device>,
    rooms: List<Room>,
    onDismiss: () -> Unit,
    onConfirm: (Set<UUID>) -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<UUID>()) }
    val noRoom = stringResource(R.string.no_room)
    val groups = rooms.map { r -> r.name to devices.filter { it.roomId == r.id } } +
        (noRoom to devices.filter { d -> rooms.none { it.id == d.roomId } })

    RaumAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_add)) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.scene_all_devices_added))
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp).width(520.dp)) {
                    groups.filter { it.second.isNotEmpty() }.forEach { (room, list) ->
                        item(key = "h-$room") { SectionHeader(room, Modifier.padding(top = 8.dp)) }
                        items(list, key = { it.id }) { d ->
                            val checked = d.id in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .toggleable(value = checked, role = Role.Checkbox) {
                                        selected = if (it) selected + d.id else selected - d.id
                                    },
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Icon(RaumIcons.device(d), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(d.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (!d.isOnline) Text(stringResource(R.string.state_offline), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) {
                Text(if (selected.isEmpty()) stringResource(R.string.action_add) else stringResource(R.string.action_add_count, selected.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
