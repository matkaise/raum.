package app.raum.ui.settings

import app.raum.R
import androidx.compose.ui.res.stringResource

import app.raum.ui.components.rememberLogTimeFormatter
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.automation.triggers.GeoLocation
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogLevel
import app.raum.ui.components.SectionHeader
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ereignisprotokoll mit Filtern nach Kategorie, Gerät und Zeitraum (LOG-005). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogPanel(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val entries by viewModel.logEntries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var deviceMenu by remember { mutableStateOf(false) }

    val logTime = rememberLogTimeFormatter()
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter.category == null, onClick = { viewModel.setCategory(null) }, label = { Text(stringResource(R.string.filter_all)) }, modifier = Modifier.height(48.dp))
            LogCategory.entries.forEach { c ->
                FilterChip(selected = filter.category == c, onClick = { viewModel.setCategory(c) }, label = { Text(stringResource(c.labelRes)) }, modifier = Modifier.height(48.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogRange.entries.forEach { r ->
                FilterChip(selected = filter.range == r, onClick = { viewModel.setRange(r) }, label = { Text(stringResource(r.labelRes)) }, modifier = Modifier.height(48.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { deviceMenu = true }, modifier = Modifier.height(48.dp)) {
                    Text(devices.firstOrNull { it.id == filter.deviceId }?.displayName ?: stringResource(R.string.all_devices), maxLines = 1)
                    Icon(Icons.Outlined.ArrowDropDown, null)
                }
                DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.all_devices)) }, onClick = { viewModel.setDevice(null); deviceMenu = false })
                    devices.sortedBy { it.displayName }.forEach { d ->
                        DropdownMenuItem(text = { Text(d.displayName) }, onClick = { viewModel.setDevice(d.id); deviceMenu = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                items(entries) { e ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            "${logTime.format(e.timestamp)} · ${stringResource(e.category.labelRes)}" + (e.deviceName?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            e.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (e.level) {
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
