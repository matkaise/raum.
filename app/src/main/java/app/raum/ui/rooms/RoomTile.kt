package app.raum.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.raum.R
import app.raum.ui.RoomSummary
import app.raum.ui.components.DeviceIconBadge
import app.raum.ui.components.RaumIcons
import app.raum.ui.components.formatTemperature
import app.raum.ui.components.formatPercent

@Composable
fun RoomTile(summary: RoomSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lit = summary.lightsOn > 0
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.height(200.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DeviceIconBadge(RaumIcons.room(summary.room.icon), active = lit)
                Spacer(Modifier.weight(1f))
                summary.temperatureCelsius?.let {
                    Text(formatTemperature(it), style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(summary.room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (summary.lightsTotal > 0) {
                    Chip(Icons.Outlined.Lightbulb, if (lit) stringResource(R.string.lights_n_on_short, summary.lightsOn) else stringResource(R.string.lights_off_short), lit)
                }
                summary.humidityPercent?.let { Chip(Icons.Outlined.WaterDrop, formatPercent(it), false) }
                if (summary.offlineCount > 0) {
                    Text(stringResource(R.string.offline_count, summary.offlineCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null, Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
