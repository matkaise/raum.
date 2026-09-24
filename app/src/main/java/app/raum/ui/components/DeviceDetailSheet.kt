package app.raum.ui.components

import app.raum.R
import androidx.annotation.StringRes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.RgbColor
import app.raum.domain.models.Room
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.UnknownCapability
import app.raum.domain.models.find
import java.util.UUID

/** Farbauswahl für Leuchten (Detailansicht und Szeneneditor). */
val ColorPresets = listOf(
    RgbColor(255, 170, 90), RgbColor(255, 120, 60), RgbColor(255, 80, 80), RgbColor(255, 90, 170),
    RgbColor(170, 90, 255), RgbColor(120, 80, 255), RgbColor(70, 140, 255), RgbColor(60, 200, 170),
    RgbColor(120, 220, 90), RgbColor(255, 230, 120),
)

data class DeviceDetailActions(
    val onCommand: (Device, DeviceCommand) -> Unit,
    val onFavorite: (Device, Boolean) -> Unit,
    val onRename: (Device, String) -> Unit,
    val onAssignRoom: (Device, UUID?) -> Unit,
    val onRemove: (Device) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    device: Device,
    rooms: List<Room>,
    actions: DeviceDetailActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, sheetMaxWidth = 880.dp) {
        KeepSystemBarsHidden()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Kopf
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceIconBadge(RaumIcons.device(device), active = device.isActive, size = 64)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        (rooms.firstOrNull { it.id == device.roomId }?.name ?: stringResource(R.string.no_room)) +
                            " · " + if (device.isOnline) stringResource(R.string.state_online)
                        else stringResource(R.string.device_offline_since, formatAgo(device.lastSeenAt)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (device.isOnline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { actions.onFavorite(device, !device.favorite) }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (device.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = stringResource(if (device.favorite) R.string.favorite_remove else R.string.favorite_add),
                        tint = if (device.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Controls(device) { actions.onCommand(device, it) }

            HorizontalDivider()
            DeviceSettings(device, rooms, actions)

            HorizontalDivider()
            DeviceInfo(device)

            OutlinedButton(
                onClick = { confirmRemove = true },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Outlined.DeleteOutline, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.device_remove))
            }
        }
    }

    if (confirmRemove) {
        RaumAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.device_remove_title, device.displayName)) },
            text = {
                Text(stringResource(R.string.device_remove_text))
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; actions.onRemove(device); onDismiss() }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Controls(device: Device, send: (DeviceCommand) -> Unit) {
    val enabled = device.isOnline
    val caps = device.capabilities

    caps.find<LightCapability>()?.let { light ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledSwitch(stringResource(R.string.device_light), light.isOn, enabled) { send(DeviceCommand.SetOn(it)) }
            light.brightnessPercent?.let { brightness ->
                CommitSlider(
                    label = stringResource(R.string.brightness), value = brightness.toFloat(), range = 1f..100f, enabled = enabled,
                    format = { "${it.toInt()} %" },
                ) { send(DeviceCommand.SetBrightness(it.toInt())) }
            }
            if (light.colorTemperatureKelvin != null && light.colorTemperatureRange != null) {
                val r = light.colorTemperatureRange
                CommitSlider(
                    label = stringResource(R.string.color_temperature), value = light.colorTemperatureKelvin.toFloat(),
                    range = r.first.toFloat()..r.last.toFloat(), enabled = enabled,
                    format = { "${(it / 50).toInt() * 50} K" },
                ) { send(DeviceCommand.SetColorTemperature((it / 50).toInt() * 50)) }
            }
            if (light.rgbColor != null) {
                Text(stringResource(R.string.color), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorPresets.forEach { c ->
                        val selected = c == light.rgbColor
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(c.argb))
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                )
                                .clickable(enabled = enabled) { send(DeviceCommand.SetColor(c)) },
                        )
                    }
                }
            }
        }
    }

    caps.find<SwitchCapability>()?.let { s ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledSwitch(stringResource(R.string.device_power), s.isOn, enabled) { send(DeviceCommand.SetOn(it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                s.powerWatts?.let { ValueBlock(stringResource(R.string.power_now), formatWatts(it)) }
                s.energyKwh?.let { ValueBlock(stringResource(R.string.energy_total), formatKwh(it)) }
            }
        }
    }

    caps.find<ThermostatCapability>()?.let { t ->
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val step = Units.stepCelsius(LocalTemperatureUnit.current)
                ValueBlock(stringResource(R.string.thermostat_current), t.currentCelsius?.let { formatTemperature(it) } ?: "–", Modifier.weight(1f))
                ValueBlock(stringResource(R.string.thermostat_target), formatTemperature(t.targetCelsius), Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { send(DeviceCommand.SetTargetTemperature(t.targetCelsius - step)) },
                    enabled = enabled && t.targetCelsius > t.minTargetCelsius, modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Outlined.Remove, stringResource(R.string.thermostat_decrease)) }
                Spacer(Modifier.width(16.dp))
                FilledTonalIconButton(
                    onClick = { send(DeviceCommand.SetTargetTemperature(t.targetCelsius + step)) },
                    enabled = enabled && t.targetCelsius < t.maxTargetCelsius, modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Outlined.Add, stringResource(R.string.thermostat_increase)) }
            }
            if (t.supportedModes.size > 1) {
                val modes = ThermostatMode.entries.filter { it in t.supportedModes }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = t.mode == mode,
                            onClick = { send(DeviceCommand.SetThermostatMode(mode)) },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(stringResource(mode.labelRes)) }
                    }
                }
            }
        }
    }

    caps.find<CoverCapability>()?.let { c ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(coverLabel(c), style = MaterialTheme.typography.titleLarge)
            CommitSlider(stringResource(R.string.cover_position_open), c.openPercent.toFloat(), 0f..100f, enabled, { "${it.toInt()} %" }) {
                send(DeviceCommand.SetCoverPosition(it.toInt()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { send(DeviceCommand.OpenCover) }, enabled = enabled) {
                    Icon(Icons.Outlined.KeyboardArrowUp, null); Text(stringResource(R.string.cover_open))
                }
                FilledTonalButton(onClick = { send(DeviceCommand.StopCover) }, enabled = enabled) {
                    Icon(Icons.Outlined.Stop, null); Text(stringResource(R.string.cover_stop))
                }
                FilledTonalButton(onClick = { send(DeviceCommand.CloseCover) }, enabled = enabled) {
                    Icon(Icons.Outlined.KeyboardArrowDown, null); Text(stringResource(R.string.cover_close))
                }
            }
        }
    }

    if (device.category == app.raum.domain.models.DeviceCategory.SENSOR) {
        ValueBlock(stringResource(R.string.sensor_values), sensorLabel(device))
    }

    if (caps.find<UnknownCapability>() != null) {
        Text(
            stringResource(R.string.device_unsupported),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!enabled) {
        Text(
            stringResource(R.string.device_unreachable_hint),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
    }
}

@get:StringRes
val ThermostatMode.labelRes: Int
    get() = when (this) {
        ThermostatMode.OFF -> R.string.thermostat_off
        ThermostatMode.HEAT -> R.string.thermostat_heat
        ThermostatMode.COOL -> R.string.thermostat_cool
        ThermostatMode.AUTO -> R.string.thermostat_auto
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSettings(device: Device, rooms: List<Room>, actions: DeviceDetailActions) {
    var name by remember(device.id) { mutableStateOf(device.displayName) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.device_settings), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = { actions.onRename(device, name) },
                enabled = name.isNotBlank() && name.trim() != device.displayName,
            ) { Text(stringResource(R.string.rename)) }
        }
        Text(stringResource(R.string.room), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rooms.forEach { room ->
                FilterChip(
                    selected = device.roomId == room.id,
                    onClick = { actions.onAssignRoom(device, room.id) },
                    label = { Text(room.name) },
                    leadingIcon = { Icon(RaumIcons.room(room.icon), null, Modifier.size(18.dp)) },
                    modifier = Modifier.height(48.dp),
                )
            }
            FilterChip(
                selected = device.roomId == null,
                onClick = { actions.onAssignRoom(device, null) },
                label = { Text(stringResource(R.string.no_room)) },
                modifier = Modifier.height(48.dp),
            )
        }
    }
}

@Composable
private fun DeviceInfo(device: Device) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.device_info), style = MaterialTheme.typography.titleMedium)
        InfoLine(stringResource(R.string.device_vendor), device.vendorName ?: "–")
        InfoLine(stringResource(R.string.device_product), device.productName ?: "–")
        InfoLine(stringResource(R.string.device_node_id), "0x%016X".format(device.matterNodeId.toLong()), mono = true)
        device.network?.let { n -> InfoLine(stringResource(R.string.device_connection), connectionText(n)) }
        device.capabilities.find<BatteryCapability>()?.let { InfoLine(stringResource(R.string.battery), "${it.percent} %") }
        InfoLine(stringResource(R.string.last_seen), formatAgo(device.lastSeenAt))
    }
}

/** „Thread · Router · Netz „Zuhause“ · Kanal 15“ bzw. „WLAN“ */
@Composable
private fun connectionText(n: app.raum.domain.models.DeviceNetwork): String = listOfNotNull(
    stringResource(
        when (n.transport) {
            app.raum.domain.models.NetworkTransport.THREAD -> R.string.transport_thread
            app.raum.domain.models.NetworkTransport.WIFI -> R.string.transport_wifi
            app.raum.domain.models.NetworkTransport.ETHERNET -> R.string.transport_ethernet
        },
    ),
    n.threadRole?.let { stringResource(it.labelRes) },
    n.threadNetworkName?.let { stringResource(R.string.thread_router_network, it) },
    n.threadChannel?.let { stringResource(R.string.thread_channel_short, it) },
).joinToString(" · ")

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(200.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it })
    }
}

@Composable
fun ValueBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Slider, der während des Ziehens nur lokal aktualisiert und erst beim Loslassen sendet –
 * verhindert eine Flut von Matter-Befehlen.
 */
@Composable
fun CommitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!dragging) local = value }
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = local.coerceIn(range),
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = { dragging = false; onCommit(local) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.height(48.dp),
        )
    }
}
