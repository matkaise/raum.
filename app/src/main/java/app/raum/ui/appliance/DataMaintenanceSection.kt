package app.raum.ui.appliance

import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import app.raum.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import org.koin.compose.koinInject
import app.raum.i18n.Strings
import app.raum.i18n.ErrorTexts
import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.data.backup.BackupCodec
import app.raum.data.backup.RestorePlan
import app.raum.data.reset.ResetMode
import app.raum.platform.update.UpdateCandidate
import app.raum.ui.components.RaumDialog
import app.raum.ui.components.SectionHeader
import java.time.LocalDate

private sealed interface Pending {
    data class BackupPassword(val create: Boolean) : Pending
    data class ResetExplain(val mode: ResetMode) : Pending
    data class ResetPin(val mode: ResetMode) : Pending
    /** Ohne Administrator-PIN: zweite, ausdrückliche Bestätigung statt PIN-Abfrage. */
    data class ResetConfirm(val mode: ResetMode) : Pending
}

/** Sicherung, Wiederherstellung, Export, Update und Zurücksetzen im Wartungsmodus (M7). */
@Composable
fun DataMaintenanceSection(vm: DataMaintenanceViewModel) {
    val activity = LocalActivity.current ?: return
    val busy by vm.busy.collectAsStateWithLifecycle()
    val plan by vm.restorePlan.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val handover by vm.handover.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var password by remember { mutableStateOf("") }
    val today = LocalDate.now().toString()

    // Dateiauswahl über das System: USB-Speicher, interne Ablage oder installierte Netzwerk-Anbieter (BAK-003)
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.createBackup(uri, password)
        password = ""
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri, password)
        password = ""
    }
    val exportHandover = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(vm::exportHandover) }
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(vm::exportDiagnostics) }
    val openUpdate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::inspectUpdate) }

    busy?.let { busyRes ->
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(busyRes), style = MaterialTheme.typography.bodyLarge)
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f)) {
            SectionHeader(stringResource(R.string.backup))
            Card {
                Text(stringResource(R.string.backup_description),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton(Icons.Outlined.Backup, stringResource(R.string.backup_create), busy == null) { pending = Pending.BackupPassword(create = true) }
                ActionButton(Icons.Outlined.Restore, stringResource(R.string.backup_restore), busy == null) { pending = Pending.BackupPassword(create = false) }
                Text(stringResource(R.string.recovery_points, vm.recoveryPoints),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeader(stringResource(R.string.update))
            Card {
                Text(stringResource(R.string.update_description),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton(Icons.Outlined.SystemUpdate, stringResource(R.string.update_choose_file), busy == null) {
                    openUpdate.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            SectionHeader(stringResource(R.string.export))
            Card {
                ActionButton(Icons.Outlined.Description, stringResource(R.string.handover_report), busy == null) {
                    exportHandover.launch("raum-uebergabe-$today.txt")
                }
                ActionButton(Icons.Outlined.Description, stringResource(R.string.diagnostics_package), busy == null) { exportDiagnostics.launch("raum-diagnose-$today.txt") }
            }
            SectionHeader(stringResource(R.string.reset))
            Card {
                ActionButton(Icons.Outlined.RestartAlt, stringResource(R.string.reset_handover), busy == null) {
                    pending = Pending.ResetExplain(ResetMode.HANDOVER)
                }
                OutlinedButton(onClick = { pending = Pending.ResetExplain(ResetMode.FULL) }, enabled = busy == null,
                    modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_full), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    when (val p = pending) {
        is Pending.BackupPassword -> PasswordDialog(
            create = p.create,
            onDismiss = { pending = null },
            onConfirm = { pw ->
                password = pw; pending = null
                if (p.create) createBackup.launch("raum-sicherung-$today.${BackupCodec.FILE_EXTENSION}")
                else openBackup.launch(arrayOf("*/*"))
            },
        )
        is Pending.ResetExplain -> ResetExplainDialog(p.mode, vm.pinSet, onDismiss = { pending = null }) {
            pending = if (vm.pinSet) Pending.ResetPin(p.mode) else Pending.ResetConfirm(p.mode)
        }
        is Pending.ResetPin -> PinEntryDialog(
            title = stringResource(R.string.confirm_with_pin),
            onDismiss = { pending = null },
            onSubmit = vm::verifyPin,
            onSuccess = { pending = null; vm.reset(activity, p.mode) },
        )
        is Pending.ResetConfirm -> AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_no_pin)) },
            confirmButton = {
                Button(
                    onClick = { pending = null; vm.reset(activity, p.mode) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.reset_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }
    plan?.let { RestorePreviewDialog(it, onDismiss = vm::dismissRestore, onConfirm = vm::restore) }
    update?.let { UpdateDialog(it, onDismiss = vm::dismissUpdate, onConfirm = vm::installUpdate) }
    handover?.let {
        HandoverIncompleteDialog(it, onCancel = vm::cancelHandover, onRetry = { vm.retryHandover(activity) },
            onFinish = { vm.finishHandoverIncomplete(activity) })
    }
}

/** Übergabe mit offenen Punkten: nichts ist gelöscht; erneut versuchen oder ausdrücklich unvollständig abschließen. */
@Composable
private fun HandoverIncompleteDialog(
    problems: DataMaintenanceViewModel.HandoverProblems,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    RaumDialog(
        title = stringResource(R.string.handover_incomplete_title),
        onDismiss = onCancel,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.handover_finish_incomplete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.handover_retry)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Text(stringResource(R.string.handover_incomplete_text, problems.items.size, problems.checked), style = MaterialTheme.typography.bodyLarge)
        problems.items.forEach { (device, reason) ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(device, style = MaterialTheme.typography.titleSmall)
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.handover_incomplete_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Zeitpunkt der Sicherung (gespeichert als UTC-ISO) in Ortszeit und Gebietsschema anzeigen. */
internal fun localBackupTime(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrElse { iso.take(16).replace('T', ' ') }

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

@Composable
private fun PasswordDialog(create: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val long = pw.length >= BackupCodec.MIN_PASSWORD_LENGTH
    val ok = long && (!create || pw == repeat)
    RaumDialog(
        title = stringResource(if (create) R.string.backup_password_set else R.string.backup_password_enter),
        onDismiss = onDismiss,
        width = 560.dp,
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = ok) { Text(stringResource(R.string.continue_to_file)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.password_min, BackupCodec.MIN_PASSWORD_LENGTH)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
        if (create) {
            OutlinedTextField(repeat, { repeat = it }, label = { Text(stringResource(R.string.password_repeat)) }, singleLine = true,
                isError = repeat.isNotEmpty() && repeat != pw,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.backup_password_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RestorePreviewDialog(plan: RestorePlan, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = koinInject<Strings>()
    RaumDialog(
        title = stringResource(R.string.restore_title),
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Text(stringResource(R.string.restore_source, plan.home.name, localBackupTime(plan.source.createdAt), plan.source.appVersion),
            style = MaterialTheme.typography.bodyLarge)
        Text(
            listOf(
                pluralStringResource(R.plurals.rooms_count, plan.rooms.size, plan.rooms.size),
                pluralStringResource(R.plurals.devices_count, plan.devices.size, plan.devices.size),
                pluralStringResource(R.plurals.scenes_count, plan.scenes.size, plan.scenes.size),
                pluralStringResource(R.plurals.automations_count, plan.automations.size, plan.automations.size),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.restore_replaces),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.warnings.forEach { w ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(ErrorTexts.restoreWarning(w, strings), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun UpdateDialog(c: UpdateCandidate, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    RaumDialog(
        title = stringResource(R.string.update_install_title),
        onDismiss = onDismiss,
        width = 560.dp,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.install)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Text("raum. ${c.currentVersionName} → ${c.versionName}", style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.update_install_text),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Spez. 11.3: Werksreset und Fabric-Löschung getrennt erklären. */
@Composable
private fun ResetExplainDialog(mode: ResetMode, pinSet: Boolean, onDismiss: () -> Unit, onContinue: () -> Unit) {
    val handover = mode == ResetMode.HANDOVER
    RaumDialog(
        title = stringResource(if (handover) R.string.reset_handover_title else R.string.reset_full),
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(if (pinSet) R.string.continue_with_pin else R.string.action_next), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Text(stringResource(R.string.reset_fabric_heading), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (handover) R.string.reset_fabric_text_handover else R.string.reset_fabric_text))
        Text(stringResource(R.string.reset_data_heading), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (handover) R.string.reset_handover_data else R.string.reset_full_data)
        )
        Text(stringResource(R.string.reset_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
