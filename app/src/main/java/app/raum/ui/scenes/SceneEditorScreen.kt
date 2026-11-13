package app.raum.ui.scenes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import app.raum.ui.components.Dial
import app.raum.ui.components.HueSat
import app.raum.ui.components.WhitePresets
import app.raum.ui.components.arcGradient
import app.raum.ui.components.fractionOf
import app.raum.ui.components.kelvinAt
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.Device
import app.raum.domain.models.LightCapability
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceTarget
import app.raum.domain.usecases.SceneTargets
import app.raum.ui.components.ColorQuickPicks
import app.raum.ui.components.EmptyState
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.LampArt
import app.raum.ui.components.LocalTemperatureUnit
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.SceneMoodCard
import app.raum.ui.components.SocketArt
import app.raum.ui.components.Units
import app.raum.ui.components.WindowArt
import app.raum.ui.components.formatDegrees
import app.raum.ui.components.hueSat
import app.raum.ui.components.kelvinColor
import app.raum.ui.components.labelRes
import app.raum.ui.components.moodSky
import app.raum.ui.overview.SkyChip
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.UUID
import kotlin.math.roundToInt

private val Ink = Color(0xFF10131F)
private val Amber = Color(0xFFFFC24F)

/**
 * Szeneneditor (Entwurf „raum. Übersicht Redesign“, Reihe „Szenen“): oben Name, Zusammenfassung, Ausprobieren und
 * Speichern; links Vorschau der Karte, Name und Stimmung; rechts je Gerät eine Karte mit dem Bild der Kachel und
 * knapper Bedienung des Zielzustands. „Geräte hinzufügen“ öffnet ein Fenster mit Bildkacheln nach Raum.
 */
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
    var toneFor by remember { mutableStateOf<UUID?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    BackHandler(enabled = state.dirty && !picking) { confirmDiscard = true }
    val back = { if (state.dirty) { confirmDiscard = true } else { onClose() } }

    if (state.loading) return
    if (state.notFound) {
        EmptyState(Icons.Outlined.Restore, stringResource(R.string.scene_not_found), stringResource(R.string.possibly_deleted)) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
        }
        return
    }

    val byId = devices.associateBy { it.id }
    val title = state.name.ifBlank { stringResource(if (state.isNew) R.string.scene_new else R.string.scene_edit) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) {
            // Kopf: zurück, Name, Zusammenfassung; rechts Ausprobieren und Speichern – immer sichtbar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.action_back), onClick = back),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = SkyText) }
                Text(title, style = SkyTextStyle, color = SkyText, fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                SkyChip(
                    null, SkyText,
                    pluralStringResource(R.plurals.devices_count, state.entries.size, state.entries.size) + " · " +
                        pluralStringResource(R.plurals.actions_count, state.actionCount, state.actionCount),
                    height = 40,
                )
                Spacer(Modifier.weight(1f))
                val canTry = state.entries.isNotEmpty() && !state.busy
                Row(
                    Modifier.height(56.dp).alpha(if (state.entries.isNotEmpty()) 1f else 0.5f).clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.2f)).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                        .clickable(enabled = canTry, onClick = viewModel::tryOut).padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = SkyText, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, null, tint = SkyText)
                    Text(stringResource(R.string.scene_try), style = SkyTextStyle, color = SkyText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Ink).clickable(onClick = viewModel::save).padding(horizontal = 30.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.action_save), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }

            Row(Modifier.weight(1f).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                LeftColumn(state, sceneId, viewModel, Modifier.width(360.dp).fillMaxHeight()) { confirmDelete = true }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    SkyLabel(stringResource(R.string.scene_devices))
                    if (state.showErrors) state.entriesError?.let {
                        Text(stringResource(it), color = Color(0xFFFFD2CC), fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                    }
                    LazyVerticalGrid(
                        GridCells.Fixed(2), Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.entries, key = { it.deviceId }) { entry ->
                            EntryCard(
                                device = byId[entry.deviceId],
                                roomName = rooms.firstOrNull { it.id == byId[entry.deviceId]?.roomId }?.name,
                                target = entry.target,
                                onChange = { viewModel.updateTarget(entry.deviceId, it) },
                                onTone = { toneFor = entry.deviceId },
                                onRemove = { viewModel.removeEntry(entry.deviceId) },
                            )
                        }
                        item(span = { GridItemSpan(2) }) {
                            DashedButton(stringResource(R.string.devices_add), if (state.entries.isEmpty()) stringResource(R.string.scene_devices_empty_text) else null) { picking = true }
                        }
                    }
                }
            }
        }

        if (picking) {
            DevicePicker(
                devices = devices.filter { SceneTargets.isSceneCapable(it) },
                existing = state.entries.map { it.deviceId }.toSet(),
                rooms = rooms,
                sceneName = title,
                onDismiss = { picking = false },
                onConfirm = { viewModel.addDevices(it); picking = false },
            )
        }
        toneFor?.let { id ->
            val target = state.entries.firstOrNull { it.deviceId == id }?.target as? DeviceTarget.Light
            if (target != null) ToneSheet(byId[id], target, onChange = { viewModel.updateTarget(id, it) }, onDismiss = { toneFor = null })
        }
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

@Composable
private fun SkyLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, modifier = modifier.padding(start = 4.dp, bottom = 10.dp))
}

/** Links: Vorschau der Karte, Name, Stimmung; unten Zustände übernehmen und Löschen. */
@Composable
private fun LeftColumn(state: SceneEditorState, sceneId: UUID?, vm: SceneEditorViewModel, modifier: Modifier, onDelete: () -> Unit) {
    val previewId = remember { sceneId ?: UUID.randomUUID() }
    Column(modifier.verticalScroll(rememberScrollState())) {
        SkyLabel(stringResource(R.string.scene_preview))
        SceneMoodCard(
            Scene(previewId, state.name.ifBlank { stringResource(R.string.scene_new) }, state.icon, emptyList()),
            running = false, done = false, enabled = false, onRun = {}, modifier = Modifier.fillMaxWidth(),
        )

        SkyLabel(stringResource(R.string.name), Modifier.padding(top = 20.dp))
        val error = state.showErrors && state.nameError != null
        BasicTextField(
            value = state.name, onValueChange = vm::setName, singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 18.sp), cursorBrush = SolidColor(Ink),
            modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White)
                .border(2.dp, if (error) Color(0xFFB3261E) else Ink, RoundedCornerShape(28.dp)).padding(horizontal = 22.dp),
            decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } },
        )
        if (error) Text(stringResource(state.nameError!!), color = Color(0xFFFFD2CC), fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))

        SkyLabel(stringResource(R.string.scene_mood), Modifier.padding(top = 20.dp))
        // Symbol und Farbe in einem: jede Stimmung bringt ihren Kartenverlauf mit
        FixedGrid(RaumIcons.scenes.entries.toList(), columns = 6, spacing = 6.dp) { (key, vector), mod ->
            val on = state.icon == key
            Box(mod, contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(54.dp).then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).padding(5.dp)
                        .clip(CircleShape).background(Brush.linearGradient(moodSky(key))).clickable(onClickLabel = key) { vm.setIcon(key) },
                    contentAlignment = Alignment.Center,
                ) { Icon(vector, key, Modifier.size(22.dp), tint = Color.White) }
            }
        }

        Spacer(Modifier.weight(1f).height(20.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).alpha(if (state.entries.isNotEmpty()) 1f else 0.5f).clip(RoundedCornerShape(26.dp))
                .background(Color.White.copy(alpha = 0.16f)).border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(26.dp))
                .clickable(enabled = state.entries.isNotEmpty(), onClick = vm::captureCurrentStates),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Restore, null, tint = SkyText)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.capture_states), style = SkyTextStyle, color = SkyText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!state.isNew) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onDelete),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFFFD2CC))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scene_delete), color = Color(0xFFFFD2CC), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Gestrichelte Fläche „Hinzufügen“ (wie bei Geräten und Szenen). */
@Composable
private fun DashedButton(label: String, hint: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(if (hint != null) 120.dp else 84.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.08f))
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.55f), cornerRadius = CornerRadius(28.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                )
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, null, Modifier.size(24.dp), tint = SkyText)
            Spacer(Modifier.width(10.dp))
            Text(label, style = SkyTextStyle, color = SkyText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        hint?.let { Text(it, style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

// --- Gerätekarten -------------------------------------------------------------------------------

/** Bild des Zielzustands im Kreis – dieselben Bilder wie auf den Kacheln. */
@Composable
private fun EntryArt(device: Device?, target: DeviceTarget, size: Dp = 52.dp) {
    val bg = when (target) {
        is DeviceTarget.Light -> if (target.on) Color(0xFFFFF3DC) else Color.White
        is DeviceTarget.Thermostat -> Color(0xFFFFE0CC)
        else -> Color.White
    }
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when (target) {
            is DeviceTarget.Light -> LampArt(target.on, (target.brightnessPercent ?: 100) / 100f, lightColorOf(target), true, Modifier.size(size * 0.8f, size * 0.66f))
            is DeviceTarget.Cover -> WindowArt(target.openPercent, CoverMovement.STOPPED, true, Modifier.size(size * 0.74f, size * 0.6f))
            is DeviceTarget.Switch -> SocketArt(target.on, Modifier.size(size * 0.7f))
            is DeviceTarget.Thermostat -> Icon(Icons.Outlined.Thermostat, null, Modifier.size(size * 0.5f), tint = Color(0xFFB4561F))
        }
    }
}

private fun lightColorOf(t: DeviceTarget.Light): Color? = t.color?.let { Color(it.argb) } ?: t.colorTemperatureKelvin?.let { kelvinColor(it) }

@Composable
private fun EntryCard(
    device: Device?,
    roomName: String?,
    target: DeviceTarget,
    onChange: (DeviceTarget) -> Unit,
    onTone: () -> Unit,
    onRemove: () -> Unit,
) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), g.glass, g.edge).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryArt(device, target)
            Column(Modifier.weight(1f)) {
                Text(device?.displayName ?: stringResource(R.string.device_unknown), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                roomName?.let { Text(it, color = g.soft, fontSize = 13.sp, maxLines = 1) }
            }
            if (device != null && !device.isOnline) {
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFB3261E).copy(alpha = 0.1f)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(14.dp), tint = Color(0xFFB3261E))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.state_offline), color = Color(0xFFB3261E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(g.chip).clickable(onClickLabel = stringResource(R.string.scene_remove_device), onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, stringResource(R.string.scene_remove_device), Modifier.size(18.dp), tint = g.ink) }
        }
        when (target) {
            is DeviceTarget.Light -> {
                val cap = device?.capabilities?.find<LightCapability>()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OnOff(target.on, Modifier.width(150.dp)) { on ->
                        onChange(
                            if (!on) target.copy(on = false)
                            else target.copy(
                                on = true,
                                brightnessPercent = target.brightnessPercent ?: cap?.brightnessPercent,
                                colorTemperatureKelvin = target.colorTemperatureKelvin ?: cap?.colorTemperatureKelvin?.takeIf { target.color == null },
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val canColor = cap?.supportsColor == true || target.color != null
                    val canTemp = cap?.colorTemperatureRange != null || target.colorTemperatureKelvin != null
                    if (target.on && (canColor || canTemp)) ToneChip(target, onTone)
                }
                if (target.on && (cap?.isDimmable == true || target.brightnessPercent != null)) {
                    PercentBar(target.brightnessPercent ?: 100, lightColorOf(target)?.takeIf { target.color != null } ?: Amber) {
                        onChange(target.copy(brightnessPercent = it))
                    }
                }
            }
            is DeviceTarget.Switch -> OnOff(target.on, Modifier.width(150.dp)) { onChange(target.copy(on = it)) }
            is DeviceTarget.Cover -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.cover_pos_open to 100, R.string.cover_pos_half to 50, R.string.cover_pos_closed to 0).forEach { (label, v) ->
                        Choice(stringResource(label), target.openPercent == v, Modifier.weight(1f)) { onChange(target.copy(openPercent = v)) }
                    }
                }
                Stepper("${target.openPercent} %",
                    onDec = { onChange(target.copy(openPercent = (target.openPercent - 10).coerceAtLeast(0))) },
                    onInc = { onChange(target.copy(openPercent = (target.openPercent + 10).coerceAtMost(100))) })
            }
            is DeviceTarget.Thermostat -> {
                val cap = device?.capabilities?.find<ThermostatCapability>()
                val min = cap?.minTargetCelsius ?: 5.0
                val max = cap?.maxTargetCelsius ?: 30.0
                val step = Units.stepCelsius(LocalTemperatureUnit.current)
                val modes = (cap?.supportedModes ?: setOf(ThermostatMode.OFF, ThermostatMode.HEAT)).let { s -> ThermostatMode.entries.filter { it in s } }
                if (modes.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { m -> Choice(stringResource(m.labelRes), target.mode == m, Modifier.weight(1f)) { onChange(target.copy(mode = m)) } }
                    }
                }
                Stepper(formatDegrees(target.targetCelsius),
                    onDec = { onChange(target.copy(targetCelsius = (target.targetCelsius - step).coerceAtLeast(min))) },
                    onInc = { onChange(target.copy(targetCelsius = (target.targetCelsius + step).coerceAtMost(max))) })
            }
        }
    }
}

@Composable
private fun OnOff(on: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Row(modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(g.chip).padding(3.dp)) {
        listOf(false to R.string.state_off, true to R.string.state_on).forEach { (v, label) ->
            val sel = on == v
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp)).background(if (sel) (if (v) Amber else Ink) else Color.Transparent).clickable { onChange(v) },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(label), color = if (sel) (if (v) Ink else Color.White) else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) Ink else g.chip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Color.White else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun Stepper(value: String, onDec: () -> Unit, onInc: () -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(g.chip).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(g.glassSolid).clickable(onClick = onDec), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Remove, null, tint = g.ink) }
        Text(value, color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Box(Modifier.size(42.dp).clip(CircleShape).background(g.glassSolid).clickable(onClick = onInc), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, tint = g.ink) }
    }
}

/** Helligkeit als Fläche wie auf der Kachel – Ziehen ändert den Zielwert sofort (nichts wird gesendet). */
@Composable
private fun PercentBar(percent: Int, fill: Color, onChange: (Int) -> Unit) {
    val g = LocalGlass.current
    val change by rememberUpdatedState(onChange)
    fun at(x: Float, w: Int) = (x / w * 100f).roundToInt().coerceIn(1, 100)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(g.chip)
            .pointerInput(Unit) { detectTapGestures { o -> change(at(o.x, size.width)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume(); change(at(c.position.x, size.width)) } },
    ) {
        Box(Modifier.fillMaxHeight().width((maxWidth * (percent / 100f)).coerceAtLeast(44.dp)).clip(RoundedCornerShape(22.dp)).background(fill))
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.brightness), color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$percent %", color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Lichtfarbe als Chip: Farbfleck und „2400 K“ bzw. „Farbe“ – antippen öffnet die Auswahl. */
@Composable
private fun ToneChip(target: DeviceTarget.Light, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(g.chip).clickable(onClickLabel = stringResource(R.string.scene_light_tone), onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(lightColorOf(target) ?: Amber).border(1.dp, g.edge, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            target.colorTemperatureKelvin?.takeIf { target.color == null }?.let { "$it K" } ?: stringResource(R.string.color),
            color = g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Lichtfarbe einer Szene im Fenster wie das Lichtfenster: Weißton bzw. Farbton am Drehring (Verlauf bzw. Farbkreis,
 * Leuchte in der Mitte, −/+ in der Lücke für feine Schritte), rechts die Schnellwahl.
 */
@Composable
private fun ToneSheet(device: Device?, target: DeviceTarget.Light, onChange: (DeviceTarget) -> Unit, onDismiss: () -> Unit) {
    val cap = device?.capabilities?.find<LightCapability>()
    val canColor = cap?.supportsColor == true || target.color != null
    val canTemp = cap?.colorTemperatureRange != null || target.colorTemperatureKelvin != null
    val range = cap?.colorTemperatureRange ?: 2200..6500
    var colorTab by remember { mutableStateOf(target.color != null || !canTemp) }
    var preview by remember(colorTab) { mutableStateOf<Float?>(null) }
    val noRipple = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF050814).copy(alpha = 0.4f)).clickable(noRipple, null, onClick = onDismiss))
        CompositionLocalProvider(LocalContentColor provides Ink) {
            Column(
                Modifier.align(Alignment.Center).width(980.dp).height(600.dp)
                    .shadow(40.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)).background(Color(0xFFF6F7FA))
                    .clickable(noRipple, null) {}.padding(horizontal = 34.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.scene_light_tone), fontSize = 32.sp)
                        device?.let { Text(it.displayName, color = Ink.copy(alpha = 0.62f), fontSize = 15.sp) }
                    }
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Ink.copy(alpha = 0.07f)).clickable(onClickLabel = stringResource(R.string.action_close), onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close), tint = Ink) }
                }
                Row(Modifier.weight(1f).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(340.dp)) {
                            if (!colorTab) {
                                val k = target.colorTemperatureKelvin ?: range.first
                                Dial(
                                    fraction = fractionOf(range, k), accent = Amber, enabled = true, label = "", value = { "" }, sub = { "" },
                                    track = { c -> arcGradient(c) { t -> kelvinColor(kelvinAt(range, t)) } },
                                    knobColor = { kelvinColor(kelvinAt(range, it)) },
                                    centerContent = { f -> ToneDisk(Color(0xFFFFF2E2)) { LampArt(true, 1f, kelvinColor(kelvinAt(range, f)), true, Modifier.fillMaxSize().padding(top = 10.dp)) } },
                                    modifier = Modifier.fillMaxSize(), ringWidth = 18.dp, onPreview = { preview = it },
                                ) { onChange(target.copy(colorTemperatureKelvin = kelvinAt(range, it), color = null)) }
                                GapPair(
                                    stringResource(R.string.light_warmer), { onChange(target.copy(colorTemperatureKelvin = (k - 100).coerceAtLeast(range.first), color = null)) },
                                    stringResource(R.string.light_cooler), { onChange(target.copy(colorTemperatureKelvin = (k + 100).coerceAtMost(range.last), color = null)) },
                                )
                            } else {
                                val hs = target.color?.hueSat() ?: HueSat(30f, 0.7f)
                                val sat = hs.saturation.coerceAtLeast(0.35f)
                                Dial(
                                    fraction = hs.hue / 360f, accent = Amber, enabled = true, label = "", value = { "" }, sub = { "" },
                                    track = { c -> arcGradient(c) { t -> HueSat(t * 360f, 0.85f).color() } },
                                    knobColor = { HueSat(it * 360f, sat).color() },
                                    centerContent = { f -> ToneDisk(Color(0xFFF4ECFF)) { LampArt(true, 1f, HueSat(f * 360f, sat).color(), true, Modifier.fillMaxSize().padding(top = 10.dp)) } },
                                    modifier = Modifier.fillMaxSize(), ringWidth = 18.dp, onPreview = { preview = it },
                                ) { onChange(target.copy(color = HueSat(it * 360f, sat).toRgb(), colorTemperatureKelvin = null)) }
                                GapPair(
                                    stringResource(R.string.light_hue_prev), { onChange(target.copy(color = HueSat((hs.hue - 5f + 360f) % 360f, sat).toRgb(), colorTemperatureKelvin = null)) },
                                    stringResource(R.string.light_hue_next), { onChange(target.copy(color = HueSat((hs.hue + 5f) % 360f, sat).toRgb(), colorTemperatureKelvin = null)) },
                                )
                            }
                        }
                        val valueText = if (!colorTab) "${preview?.let { kelvinAt(range, it) } ?: target.colorTemperatureKelvin ?: range.first} K" else stringResource(R.string.color)
                        Text(valueText, fontSize = 36.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 10.dp))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (canColor && canTemp) {
                            Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Ink.copy(alpha = 0.07f)).padding(4.dp)) {
                                listOf(false to R.string.light_tab_white, true to R.string.color).forEach { (c, label) ->
                                    Box(
                                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(if (colorTab == c) Ink else Color.Transparent).clickable { colorTab = c },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(stringResource(label), color = if (colorTab == c) Color.White else Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                        Text(stringResource(R.string.quick_select).uppercase(), color = Ink.copy(alpha = 0.62f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, modifier = Modifier.padding(bottom = 12.dp))
                        if (!colorTab) {
                            val k = target.colorTemperatureKelvin
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                WhitePresets.forEach { (label, f) ->
                                    val kelvin = kelvinAt(range, f)
                                    val sel = target.color == null && k != null && kotlin.math.abs(k - kelvin) < 100
                                    Column(
                                        Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(22.dp)).background(kelvinColor(kelvin))
                                            .border(if (sel) 3.dp else 1.dp, if (sel) Ink else Ink.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                                            .clickable { onChange(target.copy(colorTemperatureKelvin = kelvin, color = null)) },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(stringResource(label), color = Color(0xFF2E1D05), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("$kelvin K", color = Color(0xFF2E1D05).copy(alpha = 0.7f), fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            val current = target.color?.hueSat()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                ColorQuickPicks.forEach { pick ->
                                    val sel = current != null && pick.near(current)
                                    Box(
                                        Modifier.size(52.dp).then(if (sel) Modifier.border(3.dp, Ink, CircleShape).padding(5.dp) else Modifier)
                                            .clip(CircleShape).background(pick.color()).clickable { onChange(target.copy(color = pick.toRgb(), colorTemperatureKelvin = null)) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(Ink).clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(R.string.action_done), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToneDisk(bg: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(224.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { content() }
}

/** −/+ in der Lücke unter dem Ring. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GapPair(decLabel: String, onDec: () -> Unit, incLabel: String, onInc: () -> Unit) {
    listOf(Triple(Icons.Outlined.Remove, decLabel, onDec) to (-40).dp, Triple(Icons.Outlined.Add, incLabel, onInc) to 40.dp).forEach { (t, x) ->
        Box(
            Modifier.align(Alignment.BottomCenter).offset(x = x, y = (-2).dp).size(56.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White)
                .clickable(onClickLabel = t.second, onClick = t.third),
            contentAlignment = Alignment.Center,
        ) { Icon(t.first, t.second, Modifier.size(24.dp), tint = Ink) }
    }
}

// --- Geräte hinzufügen ------------------------------------------------------------------------

/** Fenster wie die Detailfenster: Räume als Abschnitte, Geräte als Bildkacheln zum Antippen. */
@Composable
private fun DevicePicker(
    devices: List<Device>,
    existing: Set<UUID>,
    rooms: List<Room>,
    sceneName: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<UUID>) -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<UUID>()) }
    val noRipple = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismiss)
    val groups: List<Pair<Room?, List<Device>>> = rooms.map { r -> r to devices.filter { it.roomId == r.id } } +
        (null to devices.filter { d -> rooms.none { it.id == d.roomId } })
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF050814).copy(alpha = 0.4f)).clickable(noRipple, null, onClick = onDismiss))
        CompositionLocalProvider(LocalContentColor provides Ink) {
            Column(
                Modifier.align(Alignment.Center).width(1100.dp).height(688.dp)
                    .shadow(40.dp, RoundedCornerShape(40.dp)).clip(RoundedCornerShape(40.dp)).background(Color(0xFFF6F7FA))
                    .clickable(noRipple, null) {}.padding(horizontal = 34.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.devices_add), fontSize = 32.sp)
                        Text(stringResource(R.string.scene_add_to, sceneName), color = Ink.copy(alpha = 0.62f), fontSize = 15.sp)
                    }
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Ink.copy(alpha = 0.07f)).clickable(onClickLabel = stringResource(R.string.action_close), onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close), tint = Ink) }
                }
                LazyColumn(Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    groups.filter { it.second.isNotEmpty() }.forEach { (room, list) ->
                        item(key = "h-${room?.id}") {
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                room?.let { Icon(RaumIcons.room(it.icon), null, Modifier.size(18.dp), tint = Ink.copy(alpha = 0.62f)); Spacer(Modifier.width(8.dp)) }
                                Text((room?.name ?: stringResource(R.string.no_room)).uppercase(), color = Ink.copy(alpha = 0.62f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                            }
                        }
                        item(key = "g-${room?.id}") {
                            FixedGrid(list, columns = 6, spacing = 10.dp) { d, mod ->
                                val already = d.id in existing
                                val on = d.id in selected
                                PickTile(d, already, on, mod) { if (!already) selected = if (on) selected - d.id else selected + d.id }
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.scene_add_hint), color = Ink.copy(alpha = 0.62f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(Ink.copy(alpha = 0.07f)).clickable(onClick = onDismiss).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.action_cancel), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        Modifier.height(60.dp).alpha(if (selected.isEmpty()) 0.45f else 1f).clip(RoundedCornerShape(30.dp)).background(Ink)
                            .clickable(enabled = selected.isNotEmpty()) { onConfirm(selected) }.padding(horizontal = 30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (selected.isEmpty()) stringResource(R.string.devices_add) else stringResource(R.string.scene_add_n, selected.size),
                            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** Kleine Bildkachel zur Auswahl: aktueller Zustand als Bild, gewählt dunkel mit Häkchen. */
@Composable
private fun PickTile(d: Device, already: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (selected) Ink else Ink.copy(alpha = 0.06f)
    Box(
        modifier.height(132.dp).alpha(if (already) 0.5f else 1f).clip(RoundedCornerShape(22.dp)).background(bg)
            .clickable(enabled = !already, onClickLabel = d.displayName, onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val c = d.capabilities
            val art = Modifier.size(64.dp, 50.dp)
            when {
                c.find<LightCapability>() != null -> LampArt(c.find<LightCapability>()!!.isOn || selected, 0.8f, null, true, art)
                c.find<CoverCapability>() != null -> WindowArt(c.find<CoverCapability>()!!.openPercent, CoverMovement.STOPPED, d.isOnline, art)
                c.find<SwitchCapability>() != null -> SocketArt(c.find<SwitchCapability>()!!.isOn, Modifier.size(50.dp))
                c.find<ThermostatCapability>() != null -> Icon(Icons.Outlined.Thermostat, null, Modifier.size(40.dp), tint = Color(0xFFF0894A))
                else -> Icon(RaumIcons.device(d), null, Modifier.size(40.dp), tint = if (selected) Color.White else Ink)
            }
            Text(d.displayName, color = if (selected) Color.White else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (already) Text(stringResource(R.string.scene_already_in), color = Ink.copy(alpha = 0.62f), fontSize = 12.sp)
            else if (!d.isOnline) Text(stringResource(R.string.state_offline), color = Color(0xFFB3261E), fontSize = 12.sp)
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp).clip(CircleShape).background(Amber),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = Ink) }
        }
    }
}
