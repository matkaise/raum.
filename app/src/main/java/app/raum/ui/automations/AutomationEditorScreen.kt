package app.raum.ui.automations

import app.raum.R
import androidx.compose.ui.res.stringResource

import app.raum.ui.components.currentLocale
import app.raum.ui.components.LocalTemperatureUnit
import org.koin.compose.koinInject
import app.raum.i18n.Strings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.automation.AutomationDescriber
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Condition
import app.raum.domain.models.Trigger
import app.raum.ui.components.EmptyState
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.theme.SectionLabelStyle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.UUID

private sealed interface Editing {
    data class TriggerAt(val index: Int?, val item: Trigger?) : Editing
    data class ConditionAt(val index: Int?, val item: Condition?) : Editing
    data class ActionAt(val index: Int?, val item: AutomationAction?) : Editing
}

@Composable
fun AutomationEditorScreen(
    automationId: UUID?,
    onClose: () -> Unit,
    viewModel: AutomationEditorViewModel = koinViewModel { parametersOf(automationId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    val back = { if (state.dirty) { confirmDiscard = true } else { onClose() } }

    if (state.loading) return
    if (state.notFound) {
        EmptyState(Icons.Outlined.AutoMode, stringResource(R.string.automation_not_found), stringResource(R.string.possibly_deleted)) {
            FilledTonalButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
        }
        return
    }

    val describer = AutomationDescriber(
        devices.associateBy { it.id }, scenes.associate { it.id to it.name },
        koinInject<Strings>(), LocalTemperatureUnit.current, currentLocale(),
    )
    val ctx = EditorContext(devices, { id -> rooms.firstOrNull { it.id == id }?.name }, scenes, viewModel.hasLocation)

    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 32.dp)) {
        Column(Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.isNew) R.string.automation_new else R.string.automation_edit), style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = state.name, onValueChange = viewModel::setName, label = { Text(stringResource(R.string.name)) }, singleLine = true,
                isError = state.showErrors && state.name.isBlank(), modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.active), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
            }
            // AUT-009: verständliche Zusammenfassung
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(stringResource(R.string.summary).uppercase(), style = SectionLabelStyle, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(8.dp))
                    Text(describer.sentence(state.draft), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (state.showErrors) state.errors.forEach {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.action_save)) }
                OutlinedButton(onClick = viewModel::tryOut, enabled = state.actions.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.automation_test_actions))
                }
                if (!state.isNew) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.automation_delete)) }
                }
            }
        }

        Spacer(Modifier.width(40.dp))

        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Section(stringResource(R.string.section_when), stringResource(R.string.section_when_hint), onAdd = { editing = Editing.TriggerAt(null, null) }) {
                state.triggers.forEachIndexed { i, t ->
                    ItemCard(describer.trigger(t).replaceFirstChar { it.uppercase() }, onEdit = { editing = Editing.TriggerAt(i, t) }, onRemove = { viewModel.removeTrigger(i) })
                }
            }
            Section(stringResource(R.string.section_only_if), stringResource(R.string.section_only_if_hint), onAdd = { editing = Editing.ConditionAt(null, null) }) {
                state.conditions.forEachIndexed { i, c ->
                    ItemCard(describer.condition(c).replaceFirstChar { it.uppercase() }, onEdit = { editing = Editing.ConditionAt(i, c) }, onRemove = { viewModel.removeCondition(i) })
                }
            }
            Section(stringResource(R.string.section_then), stringResource(R.string.section_then_hint), onAdd = { editing = Editing.ActionAt(null, null) }) {
                state.actions.forEachIndexed { i, a ->
                    ItemCard(
                        "${i + 1}. " + describer.action(a).replaceFirstChar { it.uppercase() },
                        onEdit = { editing = Editing.ActionAt(i, a) },
                        onRemove = { viewModel.removeAction(i) },
                        onUp = if (i > 0) ({ viewModel.moveAction(i, -1) }) else null,
                        onDown = if (i < state.actions.lastIndex) ({ viewModel.moveAction(i, 1) }) else null,
                    )
                }
            }
        }
    }

    when (val e = editing) {
        is Editing.TriggerAt -> TriggerDialog(e.item, ctx, onDismiss = { editing = null }) { viewModel.putTrigger(e.index, it); editing = null }
        is Editing.ConditionAt -> ConditionDialog(e.item, ctx, onDismiss = { editing = null }) { viewModel.putCondition(e.index, it); editing = null }
        is Editing.ActionAt -> ActionDialog(e.item, ctx, onDismiss = { editing = null }) { viewModel.putAction(e.index, it); editing = null }
        null -> Unit
    }
    if (confirmDiscard) {
        RaumAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.automation_unsaved_text)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.action_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_keep_editing)) } },
        )
    }
    if (confirmDelete) {
        RaumAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named_title, state.name)) },
            text = { Text(stringResource(R.string.automation_delete_text_log)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String, subtitle: String, onAdd: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = SectionLabelStyle, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_add)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ItemCard(text: String, onEdit: () -> Unit, onRemove: () -> Unit, onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null) {
    Surface(onClick = onEdit, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onUp != null || onDown != null) {
                IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.action_move_up)) }
                IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.action_move_down)) }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Close, stringResource(R.string.action_remove)) }
        }
    }
}
