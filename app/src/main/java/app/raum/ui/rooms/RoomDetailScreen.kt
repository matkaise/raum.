package app.raum.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.BlindsClosed
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.raum.R
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.find
import app.raum.ui.RoomSummary
import app.raum.ui.components.DeviceCard
import app.raum.ui.components.DeviceCardActions
import app.raum.ui.components.DeviceIconBadge
import app.raum.ui.components.EmptyState
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.coverLabel
import app.raum.ui.components.formatTemperature
import app.raum.ui.components.formatPercent

/** Raumansicht (Spez. 9.5) mit Gruppenaktionen (ROM-004). */
@Composable
fun RoomDetailScreen(
    summary: RoomSummary?,
    cardActions: DeviceCardActions,
    onGroupAction: (List<Device>, DeviceCommand, String) -> Unit,
    onBack: () -> Unit,
) {
    if (summary == null) {
        EmptyState(Icons.Outlined.MeetingRoom, stringResource(R.string.room_not_found), stringResource(R.string.room_not_found_text)) {
            FilledTonalButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        return
    }
    val devices = summary.devices
    val hasLights = devices.any { it.capabilities.find<LightCapability>() != null }
    val hasCovers = devices.any { it.capabilities.find<CoverCapability>() != null }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(8.dp))
            DeviceIconBadge(RaumIcons.room(summary.room.icon), active = summary.lightsOn > 0, size = 56)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(summary.room.name, style = MaterialTheme.typography.displaySmall)
                Text(roomStatus(summary), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (hasLights || hasCovers) {
            Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasLights) {
                    val allOff = stringResource(R.string.group_lights_off)
                    GroupButton(Icons.Outlined.PowerSettingsNew, allOff) {
                        onGroupAction(devices, DeviceCommand.SetOn(false), allOff)
                    }
                    val allOn = stringResource(R.string.group_lights_on)
                    GroupButton(Icons.Outlined.Lightbulb, allOn) {
                        onGroupAction(devices.filter { it.capabilities.find<LightCapability>() != null }, DeviceCommand.SetOn(true), allOn)
                    }
                }
                if (hasCovers) {
                    val open = stringResource(R.string.group_covers_open)
                    GroupButton(Icons.Outlined.Blinds, open) {
                        onGroupAction(devices, DeviceCommand.OpenCover, open)
                    }
                    val close = stringResource(R.string.group_covers_close)
                    GroupButton(Icons.Outlined.BlindsClosed, close) {
                        onGroupAction(devices, DeviceCommand.CloseCover, close)
                    }
                }
            }
        } else {
            Spacer(Modifier.size(24.dp))
        }

        if (devices.isEmpty()) {
            EmptyState(Icons.Outlined.MeetingRoom, stringResource(R.string.devices_empty), stringResource(R.string.room_devices_empty_text))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        onToggle = cardActions.onToggle,
                        onCommand = cardActions.onCommand,
                        onOpenDetails = cardActions.onOpenDetails,
                    )
                }
            }
        }
    }
}

@Composable
private fun roomStatus(s: RoomSummary): String = buildList {
    s.temperatureCelsius?.let { add(formatTemperature(it)) }
    s.humidityPercent?.let { add(stringResource(R.string.humidity_value, formatPercent(it))) }
    if (s.lightsTotal > 0) add(
        if (s.lightsOn > 0) stringResource(R.string.lights_n_of_m_on, s.lightsOn, s.lightsTotal) else stringResource(R.string.lights_off)
    )
    s.coversOpenAverage?.let { add(stringResource(R.string.room_covers_state, coverLabel(CoverCapability(it)))) }
    s.thermostatTarget?.let { add(stringResource(R.string.room_heating, formatTemperature(it))) }
    s.occupied?.let { add(stringResource(if (it) R.string.room_occupied else R.string.room_empty)) }
    if (s.openContacts > 0) add(stringResource(R.string.room_open_contacts, s.openContacts))
}.joinToString(" · ").ifEmpty { pluralStringResource(R.plurals.devices_count, s.devices.size, s.devices.size) }

@Composable
private fun GroupButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
