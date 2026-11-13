package app.raum.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCategory
import app.raum.ui.overview.SkyChip
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
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
            .padding(start = 8.dp, end = 24.dp, top = 30.dp, bottom = 32.dp),
    ) {
        // Kopf wie die anderen Bereiche: Titel mit Tag; „Neue Szene“ steht als gestrichelte Karte am Ende der Liste
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.nav_scenes), style = SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
            if (state.scenes.isNotEmpty()) {
                SkyChip(Icons.Outlined.WbTwilight, SkyText, pluralStringResource(R.plurals.scenes_count, state.scenes.size, state.scenes.size), height = 40)
            }
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
        val byId = state.devices.associateBy { it.id }
        FixedGrid(state.scenes + listOf<Scene?>(null), columns = 4) { scene, mod ->
            if (scene == null) {
                NewSceneCard(mod) { onEditScene(null) }
                return@FixedGrid
            }
            SceneMoodCard(
                scene,
                running = state.runningSceneId == scene.id,
                done = finished == scene.id,
                enabled = state.runningSceneId == null && scene.actions.isNotEmpty(),
                onRun = { onRunScene(scene) },
                modifier = mod,
                tall = true,
                medium = true,
                subtitle = sceneMix(scene, byId),
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

/** „3 Lichter · 1 Store“ – was die Szene schaltet, nach Gerätetyp. */
@Composable
fun sceneMix(scene: Scene, byId: Map<java.util.UUID, Device>): String {
    val devices = scene.actions.map { it.deviceId }.distinct().mapNotNull { byId[it] }
    if (devices.isEmpty()) return stringResource(R.string.no_devices)
    val counts = devices.groupingBy { it.category }.eachCount()
    val parts = listOfNotNull(
        counts[DeviceCategory.LIGHT]?.let { pluralStringResource(R.plurals.scene_mix_lights, it, it) },
        counts[DeviceCategory.COVER]?.let { pluralStringResource(R.plurals.scene_mix_covers, it, it) },
        counts[DeviceCategory.CLIMATE]?.let { pluralStringResource(R.plurals.scene_mix_climate, it, it) },
        counts[DeviceCategory.SWITCH]?.let { pluralStringResource(R.plurals.scene_mix_switches, it, it) },
    )
    val rest = devices.size - listOf(DeviceCategory.LIGHT, DeviceCategory.COVER, DeviceCategory.CLIMATE, DeviceCategory.SWITCH).sumOf { counts[it] ?: 0 }
    return (parts + listOfNotNull(if (rest > 0) pluralStringResource(R.plurals.devices_count, rest, rest) else null)).joinToString(" · ")
}

/** Gestrichelte Karte „Neue Szene“ am Ende der Liste – wie „Hinzufügen“ bei den Geräten. */
@Composable
private fun NewSceneCard(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier.height(290.dp).clip(shape).background(Color.White.copy(alpha = 0.08f))
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.55f), cornerRadius = CornerRadius(32.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                )
            }
            .clickable(onClick = onClick).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, Modifier.size(34.dp), tint = Color.White)
        }
        Text(stringResource(R.string.scene_new), style = SkyTextStyle, color = SkyText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.scene_new_hint), style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
