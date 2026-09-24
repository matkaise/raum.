package app.raum.ui.automations

import app.raum.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.diagnostics.LogLevel
import app.raum.domain.models.Automation
import app.raum.ui.components.DeviceIconBadge
import app.raum.ui.components.EmptyState
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.ScreenTitle
import app.raum.ui.components.formatAgo
import org.koin.compose.viewmodel.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Automationsliste (AUT-003, AUT-007, AUT-008). */
@Composable
fun AutomationsScreen(
    onEdit: (Automation?) -> Unit,
    viewModel: AutomationsViewModel = koinViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Automation?>(null) }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 40.dp)) {
        ScreenTitle(
            stringResource(R.string.nav_automations),
            stringResource(R.string.automations_subtitle, rows.count { it.automation.enabled }, rows.size),
        ) {
            FilledTonalButton(onClick = { onEdit(null) }) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.automation_new))
            }
        }
        if (rows.isEmpty()) {
            EmptyState(Icons.Outlined.AutoMode, stringResource(R.string.automations_empty), stringResource(R.string.automations_empty_text)) {
                FilledTonalButton(onClick = { onEdit(null) }) { Text(stringResource(R.string.automation_create_first)) }
            }
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(rows, key = { it.automation.id }) { row ->
                AutomationCard(
                    row = row,
                    onToggle = { viewModel.setEnabled(row.automation, it) },
                    onRun = { viewModel.runNow(row.automation) },
                    onEdit = { onEdit(row.automation) },
                    onResume = { viewModel.resume(row.automation) },
                    onDuplicate = { viewModel.duplicate(row.automation) },
                    onDelete = { deleting = row.automation },
                )
            }
        }
    }

    deleting?.let { a ->
        RaumAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_named_title, a.name)) },
            text = { Text(stringResource(R.string.automation_delete_text)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(a); deleting = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AutomationCard(
    row: AutomationRow,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onResume: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val a = row.automation
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onEdit, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceIconBadge(Icons.Outlined.AutoMode, active = a.enabled && row.status.running)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    row.sentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (a.isComplete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                StatusLine(row, onResume)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedIconButton(onClick = onRun, enabled = a.actions.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                Icon(Icons.Outlined.PlayArrow, stringResource(R.string.automation_test_run))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = a.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatusLine(row: AutomationRow, onResume: () -> Unit) {
    val paused = row.status.pausedUntil
    when {
        paused != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.automation_paused_until, HH_MM.format(paused)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
        }
        row.status.running -> Text(stringResource(R.string.running_now), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        row.lastEntry != null -> {
            val e = row.lastEntry
            // Protokolltext ohne vorangestellten Automationsnamen (falls in der aktuellen Sprache geschrieben)
            val msg = e.message.removePrefix(stringResource(R.string.auto_log_entry, row.automation.name, ""))
            Text(
                stringResource(R.string.automation_last_run, formatAgo(e.timestamp), msg),
                color = when (e.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                    LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}
