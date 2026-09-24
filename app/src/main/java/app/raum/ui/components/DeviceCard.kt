package app.raum.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCategory
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find
import androidx.compose.ui.res.stringResource
import app.raum.R

val DeviceCardHeight = 200.dp

/**
 * Gerätekarte, generiert aus den Fähigkeiten des Geräts (Spez. 9.6).
 * Tippen = Primäraktion (Licht/Stecker schalten) bzw. Details; langes Drücken = Details.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceCard(
    device: Device,
    onToggle: (Device) -> Unit,
    onCommand: (Device, DeviceCommand) -> Unit,
    onOpenDetails: (Device) -> Unit,
    modifier: Modifier = Modifier,
    roomName: String? = null,
) {
    val caps = device.capabilities
    val light = caps.find<LightCapability>()
    val switch = caps.find<SwitchCapability>()
    val togglable = light != null || switch != null
    val isOn = light?.isOn ?: switch?.isOn ?: false
    val highlighted = device.isOnline && (isOn || device.isActive)

    val labelOff = stringResource(R.string.a11y_turn_off)
    val labelOn = stringResource(R.string.a11y_turn_on)
    val labelDetails = stringResource(R.string.a11y_open_details)
    val container by animateColorAsState(
        when {
            !device.isOnline -> MaterialTheme.colorScheme.surfaceVariant
            highlighted && device.category == DeviceCategory.LIGHT -> MaterialTheme.colorScheme.primaryContainer
            highlighted -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "cardColor",
    )

    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = if (highlighted) 2.dp else 0.dp,
        modifier = modifier
            .height(DeviceCardHeight)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = { if (togglable && device.isOnline) onToggle(device) else onOpenDetails(device) },
                onLongClick = { onOpenDetails(device) },
                onClickLabel = if (togglable) (if (isOn) labelOff else labelOn) else labelDetails,
                onLongClickLabel = labelDetails,
            ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DeviceIconBadge(
                    icon = RaumIcons.device(device),
                    active = highlighted,
                    tint = light?.rgbColor?.takeIf { isOn }?.let { Color(it.argb) },
                )
                Spacer(Modifier.weight(1f))
                if (!device.isOnline) OfflineBadge()
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.alpha(if (device.isOnline) 1f else 0.6f)) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (roomName != null) {
                    Text(roomName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                CardBody(device, onCommand, highlighted)
            }
        }
    }
}

@Composable
private fun CardBody(device: Device, onCommand: (Device, DeviceCommand) -> Unit, highlighted: Boolean) {
    // Auf farbigen (aktiven) Karten heben sich Buttons mit Oberflächenfarbe ab.
    val buttonColor = if (highlighted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondaryContainer
    val caps = device.capabilities
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val enabled = device.isOnline
    when {
        caps.find<LightCapability>() != null -> {
            val l = caps.find<LightCapability>()!!
            val text = when {
                !l.isOn -> stringResource(R.string.state_off)
                l.brightnessPercent != null -> "${l.brightnessPercent} %"
                else -> stringResource(R.string.state_on)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, color = muted)
            if (l.isOn && l.brightnessPercent != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { l.brightnessPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                    drawStopIndicator = {},
                )
            }
        }
        caps.find<SwitchCapability>() != null -> {
            val s = caps.find<SwitchCapability>()!!
            val power = s.powerWatts?.takeIf { s.isOn }?.let { " · ${formatWatts(it)}" }.orEmpty()
            Text(stringResource(if (s.isOn) R.string.state_on else R.string.state_off) + power, style = MaterialTheme.typography.bodyLarge, color = muted)
        }
        caps.find<ThermostatCapability>() != null -> {
            val t = caps.find<ThermostatCapability>()!!
            val step = Units.stepCelsius(LocalTemperatureUnit.current)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.currentCelsius?.let { formatTemperature(it) } ?: "–", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (t.mode == ThermostatMode.OFF) stringResource(R.string.state_off)
                        else stringResource(R.string.thermostat_target_short, formatTemperature(t.targetCelsius)),
                        style = MaterialTheme.typography.bodyMedium, color = muted,
                    )
                }
                SmallRoundButton(buttonColor, Icons.Outlined.Remove, stringResource(R.string.thermostat_decrease), enabled) {
                    onCommand(device, DeviceCommand.SetTargetTemperature(t.targetCelsius - step))
                }
                Spacer(Modifier.size(8.dp))
                SmallRoundButton(buttonColor, Icons.Outlined.Add, stringResource(R.string.thermostat_increase), enabled) {
                    onCommand(device, DeviceCommand.SetTargetTemperature(t.targetCelsius + step))
                }
            }
        }
        caps.find<CoverCapability>() != null -> {
            val c = caps.find<CoverCapability>()!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    coverShortLabel(c),
                    style = MaterialTheme.typography.bodyLarge, color = muted,
                    modifier = Modifier.weight(1f), maxLines = 1,
                )
                SmallRoundButton(buttonColor, Icons.Outlined.KeyboardArrowUp, stringResource(R.string.cover_open), enabled) { onCommand(device, DeviceCommand.OpenCover) }
                Spacer(Modifier.size(6.dp))
                SmallRoundButton(buttonColor, Icons.Outlined.Stop, stringResource(R.string.cover_stop), enabled) { onCommand(device, DeviceCommand.StopCover) }
                Spacer(Modifier.size(6.dp))
                SmallRoundButton(buttonColor, Icons.Outlined.KeyboardArrowDown, stringResource(R.string.cover_close), enabled) { onCommand(device, DeviceCommand.CloseCover) }
            }
        }
        else -> Text(sensorLabel(device), style = MaterialTheme.typography.bodyLarge, color = muted, maxLines = 1)
    }
}

/** Kompakte Form für Karten, auf denen neben dem Text drei Buttons Platz brauchen. */
@Composable
fun coverShortLabel(c: CoverCapability): String = when (c.movement) {
    CoverMovement.OPENING -> "▲ ${c.openPercent} %"
    CoverMovement.CLOSING -> "▼ ${c.openPercent} %"
    CoverMovement.STOPPED -> when (c.openPercent) {
        100 -> stringResource(R.string.cover_state_open)
        0 -> stringResource(R.string.cover_state_closed_short)
        else -> "${c.openPercent} %"
    }
}

@Composable
fun coverLabel(c: CoverCapability): String = when (c.movement) {
    CoverMovement.OPENING -> stringResource(R.string.cover_opening, c.openPercent)
    CoverMovement.CLOSING -> stringResource(R.string.cover_closing, c.openPercent)
    CoverMovement.STOPPED -> when (c.openPercent) {
        100 -> stringResource(R.string.cover_state_open)
        0 -> stringResource(R.string.cover_state_closed)
        else -> stringResource(R.string.cover_percent_open, c.openPercent)
    }
}

@Composable
fun sensorLabel(device: Device): String {
    val caps = device.capabilities
    val parts = buildList {
        caps.find<ContactSensorCapability>()?.let { add(stringResource(if (it.isOpen) R.string.contact_open else R.string.contact_closed)) }
        caps.find<OccupancyCapability>()?.let { add(stringResource(if (it.isOccupied) R.string.occupancy_occupied else R.string.occupancy_clear)) }
        caps.find<TemperatureSensorCapability>()?.let { add(formatTemperature(it.celsius)) }
        caps.find<HumiditySensorCapability>()?.let { add(formatPercent(it.percent)) }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.sensor_no_values) }
}

@Composable
fun DeviceIconBadge(icon: ImageVector, active: Boolean, tint: Color? = null, size: Int = 48) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = tint ?: if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
fun OfflineBadge() {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(stringResource(R.string.state_offline), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun SmallRoundButton(container: Color, icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    // 48 dp Mindest-Touch-Ziel (Spez. 9.2)
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = container),
    ) {
        Icon(icon, contentDescription = description)
    }
}
