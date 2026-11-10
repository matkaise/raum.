package app.raum.ui.scenes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.R
import app.raum.domain.models.Scene
import app.raum.ui.HomeUiState
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.SceneMoodCard
import app.raum.ui.components.rememberFinishedScene
import app.raum.ui.rooms.PillButton
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText

/** Szenen als große Stimmungskarten: ausführen (SCN-003), anlegen, bearbeiten, duplizieren, löschen (SCN-001, SCN-005). */
@Composable
fun ScenesScreen(
    state: HomeUiState,
    onRunScene: (Scene) -> Unit,
    onEditScene: (Scene?) -> Unit,
    onDuplicateScene: (Scene) -> Unit,
    onDeleteScene: (Scene) -> Unit,
) {
    val g = LocalGlass.current
    var deleting by remember { mutableStateOf<Scene?>(null) }
    var menuFor by remember { mutableStateOf<Scene?>(null) }
    val finished = rememberFinishedScene(state.runningSceneId)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 24.dp, top = 36.dp, bottom = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_scenes), color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, modifier = Modifier.weight(1f))
            PillButton(Icons.Outlined.Add, stringResource(R.string.scene_new), g.glassStrong, g.ink, border = g.edge) { onEditScene(null) }
        }
        Spacer(Modifier.height(28.dp))
        if (state.scenes.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.scenes_empty), color = SkyText, fontSize = 32.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(24.dp))
                PillButton(Icons.Outlined.Add, stringResource(R.string.scene_create_first), Accent.Light, Accent.OnLight) { onEditScene(null) }
            }
            return@Column
        }
        FixedGrid(state.scenes, columns = 5) { scene, mod ->
            val devices = scene.actions.map { it.deviceId }.distinct().size
            SceneMoodCard(
                scene,
                running = state.runningSceneId == scene.id,
                done = finished == scene.id,
                enabled = state.runningSceneId == null && scene.actions.isNotEmpty(),
                onRun = { onRunScene(scene) },
                modifier = mod,
                tall = true,
                subtitle = if (devices == 0) stringResource(R.string.no_devices) else pluralStringResource(R.plurals.devices_count, devices, devices),
                onMore = { menuFor = scene },
            ) {
                DropdownMenu(expanded = menuFor == scene, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menuFor = null; onEditScene(scene) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_duplicate)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menuFor = null; onDuplicateScene(scene) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuFor = null; deleting = scene },
                    )
                }
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
