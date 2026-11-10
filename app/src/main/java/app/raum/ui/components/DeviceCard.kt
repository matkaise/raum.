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
