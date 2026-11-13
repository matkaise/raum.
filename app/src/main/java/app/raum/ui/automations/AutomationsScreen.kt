package app.raum.ui.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.diagnostics.LogLevel
import app.raum.domain.models.Automation
import app.raum.ui.components.FixedGrid
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.formatAgo
import app.raum.ui.overview.SkyChip
import app.raum.ui.rooms.PillButton
import app.raum.ui.theme.Accent
import app.raum.ui.theme.LocalGlass
import app.raum.ui.theme.SkyText
import app.raum.ui.theme.SkyTextSoft
import app.raum.ui.theme.SkyTextStyle
import app.raum.ui.theme.glass
import org.koin.compose.viewmodel.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val ErrorRed = Color(0xFFB3261E)

/** Abläufe als Karten: oben Name und Schalter, in der Mitte die Kette Wenn → Nur wenn → Dann, unten der Zustand (AUT-003, AUT-007, AUT-008). */
@Composable
fun AutomationsScreen(
    onEdit: (Automation?) -> Unit,
    viewModel: AutomationsViewModel = koinViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Automation?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 24.dp, top = 30.dp, bottom = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.nav_automations_short), style = SkyTextStyle, color = SkyText, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp, lineHeight = 58.sp)
            if (rows.isNotEmpty()) {
                SkyChip(Icons.Outlined.CheckCircle, Color(0xFFA8E6B8), stringResource(R.string.automations_subtitle, rows.count { it.automation.enabled }, rows.size), height = 40)
                val failing = rows.count { it.hasProblem }
                if (failing > 0) SkyChip(Icons.Outlined.WarningAmber, Color(0xFFFFBE6E), pluralStringResource(R.plurals.automations_failing, failing, failing), warning = true, height = 40)
            }
        }
        Spacer(Modifier.height(28.dp))
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.automations_empty), style = SkyTextStyle, color = SkyText, fontSize = 32.sp, fontWeight = FontWeight.Light)
                Text(stringResource(R.string.automations_empty_text), style = SkyTextStyle, color = SkyTextSoft, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
                PillButton(Icons.Outlined.Add, stringResource(R.string.automation_create_first), Accent.Light, Accent.OnLight) { onEdit(null) }
            }
            return@Column
        }
        FixedGrid(rows + listOf<AutomationRow?>(null), columns = 2, spacing = 14.dp) { row, mod ->
            if (row == null) {
                NewFlowCard(mod) { onEdit(null) }
                return@FixedGrid
            }
            FlowCard(
                row = row,
                modifier = mod,
                onToggle = { viewModel.setEnabled(row.automation, it) },
                onRun = { viewModel.runNow(row.automation) },
                onEdit = { onEdit(row.automation) },
                onResume = { viewModel.resume(row.automation) },
                onDuplicate = { viewModel.duplicate(row.automation) },
                onDelete = { deleting = row.automation },
            )
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

/** Fehler im letzten Lauf oder pausiert – zählt für den Warn-Tag. */
private val AutomationRow.hasProblem: Boolean
    get() = status.pausedUntil != null || lastEntry?.level == LogLevel.ERROR

@Composable
private fun FlowCard(
    row: AutomationRow,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onResume: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val g = LocalGlass.current
    val a = row.automation
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.height(188.dp).alpha(if (a.enabled) 1f else 0.62f).glass(shape, g.glass, g.edge)
            .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onEdit)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val lead = row.triggers.firstOrNull()?.kind?.style()
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(lead?.bg ?: StepKind.DEVICE.style().bg),
                contentAlignment = Alignment.Center,
            ) { Icon(lead?.icon ?: FallbackFlowIcon, null, Modifier.size(24.dp), tint = lead?.fg ?: g.ink) }
            Text(a.name, color = g.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            FlowSwitch(a.enabled, stringResource(R.string.active), onToggle)
        }
        if (a.isComplete) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val parts = listOfNotNull(
                    row.triggers.firstOrNull()?.let { it to row.triggers.size - 1 },
                    row.conditions.firstOrNull()?.let { it to row.conditions.size - 1 },
                    row.actions.firstOrNull()?.let { it to row.actions.size - 1 },
                )
                parts.forEachIndexed { i, (label, more) ->
                    if (i > 0) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = g.ink.copy(alpha = 0.4f))
                    StepChip(label, more, Modifier.weight(1f, fill = false))
                }
            }
        } else {
            Text(row.sentence, color = ErrorRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusLine(row, onResume, Modifier.weight(1f))
            TinyButton(Icons.Outlined.PlayArrow, stringResource(R.string.automation_test_run), enabled = a.actions.isNotEmpty(), onClick = onRun)
            Box {
                TinyButton(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) { menu = true }
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
        }
    }
}

/** Ein Glied der Kette als farbige Pille; „+n“ wenn mehr Schritte folgen. */
@Composable
private fun StepChip(label: StepLabel, more: Int, modifier: Modifier) {
    val st = label.kind.style()
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(st.bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(st.icon, null, Modifier.size(16.dp), tint = st.fg)
        Spacer(Modifier.width(7.dp))
        Text(label.title, color = st.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (more > 0) Text(" · +$more", color = st.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StatusLine(row: AutomationRow, onResume: () -> Unit, modifier: Modifier) {
    val g = LocalGlass.current
    val paused = row.status.pausedUntil
    val e = row.lastEntry
    when {
        paused != null -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PauseCircle, null, Modifier.size(16.dp), tint = ErrorRed)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.automation_paused_short, HH_MM.format(paused)), color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                stringResource(R.string.action_resume), color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onResume).padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        row.status.running -> Text(stringResource(R.string.running_now), color = Color(0xFF2F6B45), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
        !row.automation.enabled -> Text(stringResource(R.string.automation_switched_off), color = g.soft, fontSize = 13.sp, modifier = modifier)
        e != null && e.level != LogLevel.INFO -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            val color = if (e.level == LogLevel.ERROR) ErrorRed else Color(0xFF9A6424)
            Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            // Protokolltext ohne vorangestellten Ablaufnamen (falls in der aktuellen Sprache geschrieben)
            val msg = e.message.removePrefix(stringResource(R.string.auto_log_entry, row.automation.name, ""))
            Text("${formatAgo(e.timestamp)} · $msg", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        e != null -> Text(stringResource(R.string.automation_last_triggered, formatAgo(e.timestamp)), color = g.soft, fontSize = 13.sp, maxLines = 1, modifier = modifier)
        else -> Text(stringResource(R.string.automation_never_triggered), color = g.soft, fontSize = 13.sp, maxLines = 1, modifier = modifier)
    }
}

@Composable
private fun TinyButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(40.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(g.chip)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = g.ink) }
}

/** Schalter im Stil der Karten: dunkle Bahn mit grünem Knopf, aus hell. */
@Composable
internal fun FlowSwitch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) g.ink else g.ink.copy(alpha = 0.15f))
            .clickable(onClickLabel = label, role = Role.Switch) { onChange(!on) }
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 4.dp).size(24.dp)
                .clip(CircleShape).background(if (on) Color(0xFFA8E6B8) else Color.White),
        )
    }
}

/** Gestrichelte Karte „Neuer Ablauf“ am Ende – wie bei Szenen und Geräten. */
@Composable
private fun NewFlowCard(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(188.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.08f))
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.55f), cornerRadius = CornerRadius(28.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                )
            }
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, Modifier.size(30.dp), tint = Color.White)
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(stringResource(R.string.automation_new), style = SkyTextStyle, color = SkyText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.automation_new_hint), style = SkyTextStyle, color = SkyTextSoft, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
