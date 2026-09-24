package app.raum.ui.scenes

import app.raum.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.raum.domain.models.Scene
import app.raum.ui.HomeUiState
import app.raum.ui.components.DeviceIconBadge
import app.raum.ui.components.EmptyState
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.ScreenTitle

/** Szenenliste: ausführen (SCN-003), anlegen, bearbeiten, duplizieren, löschen (SCN-001, SCN-005). */
@Composable
fun ScenesScreen(
    state: HomeUiState,
    onRunScene: (Scene) -> Unit,
    onEditScene: (Scene?) -> Unit,
    onDuplicateScene: (Scene) -> Unit,
    onDeleteScene: (Scene) -> Unit,
) {
    var deleting by remember { mutableStateOf<Scene?>(null) }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 40.dp)) {
        ScreenTitle(stringResource(R.string.nav_scenes), pluralStringResource(R.plurals.scenes_count, state.scenes.size, state.scenes.size)) {
            FilledTonalButton(onClick = { onEditScene(null) }) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scene_new))
            }
        }
        if (state.scenes.isEmpty()) {
            EmptyState(Icons.Outlined.WbTwilight, stringResource(R.string.scenes_empty), stringResource(R.string.scenes_empty_text)) {
                FilledTonalButton(onClick = { onEditScene(null) }) { Text(stringResource(R.string.scene_create_first)) }
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(state.scenes, key = { it.id }) { scene ->
                SceneCard(
                    scene = scene,
                    deviceNames = scene.actions.map { it.deviceId }.distinct()
                        .mapNotNull { id -> state.devices.firstOrNull { it.id == id }?.displayName },
                    running = state.runningSceneId == scene.id,
                    runEnabled = state.runningSceneId == null,
                    onRun = { onRunScene(scene) },
                    onEdit = { onEditScene(scene) },
                    onDuplicate = { onDuplicateScene(scene) },
                    onDelete = { deleting = scene },
                )
            }
        }
    }

    deleting?.let { scene ->
        RaumAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_named_title, scene.name)) },
            text = { Text(stringResource(R.string.scene_delete_text)) },
            confirmButton = {
                TextButton(onClick = { onDeleteScene(scene); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    deviceNames: List<String>,
    running: Boolean,
    runEnabled: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp).height(196.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceIconBadge(RaumIcons.scene(scene.icon), active = running, size = 56)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(scene.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pluralStringResource(R.plurals.devices_count, deviceNames.size, deviceNames.size) + " · " +
                            pluralStringResource(R.plurals.actions_count, scene.actions.size, scene.actions.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                deviceNames.joinToString(", ").ifEmpty { stringResource(R.string.no_devices) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onRun, enabled = runEnabled && scene.actions.isNotEmpty(), modifier = Modifier.weight(1f).height(56.dp)) {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (running) R.string.running else R.string.action_run))
                }
                OutlinedIconButton(onClick = onEdit, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
        }
    }
}
