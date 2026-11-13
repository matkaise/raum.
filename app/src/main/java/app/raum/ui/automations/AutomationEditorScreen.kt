package app.raum.ui.automations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.automation.AutomationDescriber
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Condition
import app.raum.domain.models.Trigger
import app.raum.i18n.Strings
import app.raum.ui.components.EmptyState
import app.raum.ui.components.LocalTemperatureUnit
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.currentLocale
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.UUID

private sealed interface Editing {
    data class TriggerAt(val index: Int?, val item: Trigger?) : Editing
    data class ConditionAt(val index: Int?, val item: Condition?) : Editing
    data class ActionAt(val index: Int?, val item: AutomationAction?) : Editing
}

private val Ink = Color(0xFF10131F)
private val Salmon = Color(0xFFFFD2CC)

/** Ablauf bearbeiten: Kopf mit Name und Schalter, darunter der Satz, darunter die Kette Wenn → Nur wenn → Dann (AUT-001, AUT-002, AUT-009). */
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
    BackHandler(enabled = state.dirty && editing == null) { confirmDiscard = true }
    val back = { if (state.dirty) { confirmDiscard = true } else { onClose() } }

    if (state.loading) return
    if (state.notFound) {
        EmptyState(Icons.Outlined.AutoMode, stringResource(R.string.automation_not_found), stringResource(R.string.possibly_deleted)) {
            FilledTonalButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
        }
        return
    }

    val strings = koinInject<Strings>()
    val byId = devices.associateBy { it.id }
    val roomName = { id: UUID? -> rooms.firstOrNull { it.id == id }?.name }
    val describer = AutomationDescriber(byId, scenes.associate { it.id to it.name }, strings, LocalTemperatureUnit.current, currentLocale())
    val labels = FlowLabels(byId, describer, strings, roomName)
    val ctx = EditorContext(devices, roomName, scenes, viewModel.hasLocation, describer, state.name)
    val g = LocalGlass.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) {
            // Kopf: zurück, Name, Aktiv-Schalter; rechts Testen und Speichern
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.action_back), onClick = back),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = SkyText) }
                val nameError = state.showErrors && state.name.isBlank()
                BasicTextField(
                    value = state.name, onValueChange = viewModel::setName, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light), cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.widthIn(min = 340.dp, max = 460.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.12f))
                        .border(if (nameError) 2.dp else 1.dp, if (nameError) Salmon else Color.White.copy(alpha = 0.35f), RoundedCornerShape(28.dp)).padding(horizontal = 22.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (state.name.isEmpty()) Text(stringResource(if (state.isNew) R.string.automation_new else R.string.name), color = SkyTextSoft, fontSize = 24.sp, fontWeight = FontWeight.Light)
                            inner()
                        }
                    },
                )
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.16f))
                        .clickable { viewModel.setEnabled(!state.enabled) }.padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.active), style = SkyTextStyle, color = SkyText, fontSize = 15.sp)
                    OnSkySwitch(state.enabled)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(56.dp).alpha(if (state.actions.isNotEmpty()) 1f else 0.5f).clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.2f)).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                        .clickable(enabled = state.actions.isNotEmpty(), onClick = viewModel::tryOut).padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = SkyText)
                    Text(stringResource(R.string.automation_test_actions), style = SkyTextStyle, color = SkyText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).clickable(onClick = viewModel::save).padding(horizontal = 30.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.action_save), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }

            // AUT-009: der ganze Ablauf als Satz
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFFFE1A3).copy(alpha = 0.92f))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.summary), Modifier.size(26.dp), tint = Color(0xFF5A3D12))
                Text(describer.sentence(state.draft), color = Color(0xFF2E1D05), fontSize = 17.sp, lineHeight = 23.sp)
            }
            if (state.showErrors && state.errors.isNotEmpty()) {
                Text(state.errors.map { stringResource(it) }.joinToString(" · "), color = Salmon, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 10.dp))
            }

            // Die Kette: drei Spalten mit Pfeilen dazwischen
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp)) {
                ChainColumn(stringResource(R.string.section_when), stringResource(R.string.section_when_hint), Modifier.weight(1f)) {
                    state.triggers.forEachIndexed { i, t ->
                        StepCard(labels.trigger(t), onEdit = { editing = Editing.TriggerAt(i, t) }, onRemove = { viewModel.removeTrigger(i) })
                    }
                    AddButton(stringResource(R.string.automation_add_trigger)) { editing = Editing.TriggerAt(null, null) }
                }
                ChainArrow()
                ChainColumn(stringResource(R.string.section_only_if), stringResource(R.string.section_only_if_hint), Modifier.weight(1f)) {
                    state.conditions.forEachIndexed { i, c ->
                        StepCard(labels.condition(c), onEdit = { editing = Editing.ConditionAt(i, c) }, onRemove = { viewModel.removeCondition(i) })
                    }
                    AddButton(stringResource(R.string.automation_add_condition)) { editing = Editing.ConditionAt(null, null) }
                }
                ChainArrow()
                ChainColumn(stringResource(R.string.section_then), stringResource(R.string.section_then_hint), Modifier.weight(1.25f)) {
                    state.actions.forEachIndexed { i, a ->
                        StepCard(
                            labels.action(a),
                            number = i + 1,
                            onEdit = { editing = Editing.ActionAt(i, a) },
                            onRemove = { viewModel.removeAction(i) },
                            onUp = if (i > 0) ({ viewModel.moveAction(i, -1) }) else null,
                            onDown = if (i < state.actions.lastIndex) ({ viewModel.moveAction(i, 1) }) else null,
                        )
                    }
                    AddButton(stringResource(R.string.automation_add_action)) { editing = Editing.ActionAt(null, null) }
                    if (!state.isNew) {
                        Row(
                            Modifier.align(Alignment.End).padding(top = 12.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
                                .clickable { confirmDelete = true }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp), tint = Salmon)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.automation_delete), color = Salmon, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
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

/** Schalter auf dem Himmel: hellgrüne Bahn mit dunklem Knopf, aus durchscheinend. */
@Composable
private fun OnSkySwitch(on: Boolean) {
    Box(Modifier.size(48.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFFA8E6B8) else Color.White.copy(alpha = 0.25f))) {
        Box(
            Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 4.dp).size(24.dp)
                .clip(CircleShape).background(if (on) Ink else Color.White),
        )
    }
}

@Composable
private fun ChainColumn(title: String, hint: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(title.uppercase(), style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Text(hint, style = SkyTextStyle, color = SkyTextSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        }
        content()
    }
}

@Composable
private fun ChainArrow() {
    Box(Modifier.width(36.dp).padding(top = 92.dp), contentAlignment = Alignment.TopCenter) {
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(26.dp), tint = Color.White.copy(alpha = 0.7f))
    }
}

/** Ein Schritt der Kette: Symbol im Farbkreis, Titel und Art; Aktionen nummeriert mit ↑/↓. */
@Composable
private fun StepCard(
    label: StepLabel,
    number: Int? = null,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val g = LocalGlass.current
    val st = label.kind.style()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).glass(RoundedCornerShape(24.dp), g.glass, g.edge)
            .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onEdit)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        number?.let {
            Box(Modifier.size(24.dp).clip(CircleShape).background(g.ink), contentAlignment = Alignment.Center) {
                Text("$it", color = if (g.dark) Ink else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(st.bg), contentAlignment = Alignment.Center) {
            Icon(st.icon, null, Modifier.size(24.dp), tint = st.fg)
        }
        Column(Modifier.weight(1f)) {
            Text(label.title, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Text(label.subtitle, color = g.soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (onUp != null || onDown != null) {
            SmallRound(Icons.Outlined.ArrowUpward, stringResource(R.string.action_move_up), onUp)
            SmallRound(Icons.Outlined.ArrowDownward, stringResource(R.string.action_move_down), onDown)
        }
        SmallRound(Icons.Outlined.Close, stringResource(R.string.action_remove), onRemove)
    }
}

@Composable
private fun SmallRound(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    val g = LocalGlass.current
    Box(
        Modifier.size(36.dp).alpha(if (onClick != null) 1f else 0.35f).clip(CircleShape).background(g.chip)
            .clickable(enabled = onClick != null, onClickLabel = label) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(16.dp), tint = g.ink) }
}

/** Gestrichelter „+ …“-Knopf am Ende jeder Spalte. */
@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.06f))
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.5f), cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                )
            }
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp), tint = SkyText)
        Spacer(Modifier.width(8.dp))
        Text(label, style = SkyTextStyle, color = SkyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
