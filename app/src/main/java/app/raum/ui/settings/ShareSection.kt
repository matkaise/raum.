package app.raum.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.BuildConfig
import app.raum.R
import app.raum.domain.models.Device
import app.raum.matter.commissioning.SetupCodeGenerator
import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.Ecosystems
import app.raum.matter.controller.PairingWindow
import app.raum.ui.appliance.PinEntryDialog
import app.raum.ui.components.QrCode
import app.raum.ui.components.RaumDialog
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Composable
private fun adminName(f: AdminFabric): String =
    Ecosystems.name(f) ?: stringResource(R.string.share_other_app, "0x%04X".format(f.vendorId))

/** Kurzstatus für die Kategorienliste. */
@Composable
fun shareSummary(vm: ShareViewModel = koinViewModel()): String {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val admins by vm.admins.collectAsStateWithLifecycle()
    val bridgeOn by vm.bridgeEnabled.collectAsStateWithLifecycle()
    val bridge by vm.bridgeState.collectAsStateWithLifecycle()
    // Direkt geteilt wird immer der ganze Node – weitere Kanäle zählen nicht extra
    val direct = devices.count { d -> d.isPrimaryChannel && admins[d.matterNodeId].orEmpty().any { !it.own } }
    val parts = buildList {
        if (bridgeOn) add(
            if (bridge.admins.isEmpty()) stringResource(R.string.bridge_summary_on)
            else stringResource(R.string.bridge_summary_apps, bridge.admins.map { adminName(it) }.joinToString(", ")),
        )
        if (direct > 0) add(stringResource(R.string.share_summary_direct, direct))
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.share_none) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSection(roomName: (Device) -> String?, vm: ShareViewModel = koinViewModel()) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val admins by vm.admins.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val bridgeOn by vm.bridgeEnabled.collectAsStateWithLifecycle()
    val bridge by vm.bridgeState.collectAsStateWithLifecycle()
    val excluded by vm.bridgeExcluded.collectAsStateWithLifecycle()
    val bridgeSession by vm.bridgeSession.collectAsStateWithLifecycle()

    var unlocked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var removing by remember { mutableStateOf<Pair<Device?, AdminFabric>?>(null) }
    var selecting by remember { mutableStateOf(false) }

    // Teilen gibt anderen Kontrolle über Geräte – mit PIN geschützt, wenn eine festgelegt ist
    fun guarded(action: () -> Unit) {
        if (vm.pinRequired && !unlocked) pending = action else action()
    }

    // --- Alles auf einmal: Bridge ---------------------------------------------------------
    Group(stringResource(R.string.bridge_title)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bridge_explain), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(24.dp))
            Switch(checked = bridgeOn, onCheckedChange = { on -> if (on) guarded { vm.setBridgeEnabled(true) } else vm.setBridgeEnabled(false) })
        }
        Text(stringResource(R.string.bridge_tradeoff), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Echtheitsnachweis der Bridge (Inbetriebnahme: je Panel eigenes Zertifikat)
        if (vm.canImportAttestation && bridgeOn) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val pick = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri -> if (uri != null) vm.importAttestation { context.contentResolver.openInputStream(uri) } }
            val save = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pkcs10"),
            ) { uri ->
                if (uri != null) vm.createAttestationRequest { pem ->
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                }
            }
            val pending by vm.attestationPending.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPending() }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bridge_attestation), style = MaterialTheme.typography.bodyLarge)
                    val (text, warn) = when (bridge.attestation) {
                        app.raum.matter.bridge.AttestationStatus.CUSTOM -> stringResource(
                            R.string.bridge_attestation_custom,
                            "%04X".format(app.raum.BuildConfig.MATTER_VENDOR_ID), "%04X".format(app.raum.BuildConfig.BRIDGE_PRODUCT_ID),
                        ) to false
                        app.raum.matter.bridge.AttestationStatus.MISSING -> stringResource(
                            R.string.bridge_attestation_missing, "%04X".format(app.raum.BuildConfig.MATTER_VENDOR_ID),
                        ) to true
                        app.raum.matter.bridge.AttestationStatus.TEST -> stringResource(R.string.bridge_attestation_test) to false
                        null -> "–" to false
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium,
                        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    pending?.let { since ->
                        Text(
                            stringResource(R.string.bridge_attestation_pending,
                                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                                    .withZone(java.time.ZoneId.systemDefault()).format(since)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { guarded { save.launch("raum-bridge-${"%04X".format(app.raum.BuildConfig.MATTER_VENDOR_ID)}.csr") } },
                    modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.bridge_attestation_request))
                }
                OutlinedButton(onClick = { guarded { pick.launch(arrayOf("application/zip", "application/octet-stream")) } }, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.bridge_attestation_import))
                }
            }
        }
        if (bridgeOn) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.bridge_connected_apps), style = MaterialTheme.typography.titleSmall)
            if (bridge.admins.isEmpty()) {
                Text(stringResource(R.string.bridge_no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    bridge.admins.forEach { f ->
                        InputChip(
                            selected = true, onClick = { removing = null to f },
                            label = { Text(adminName(f)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.share_remove), Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            val sharedCount = devices.count { it.id !in excluded && vm.bridgeSupports(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { guarded(vm::openBridge) }, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Outlined.AddLink, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.bridge_connect))
                }
                OutlinedButton(onClick = { selecting = true }, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Outlined.Checklist, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bridge_devices, sharedCount, devices.count(vm::bridgeSupports)))
                }
            }
            // Gleiche App direkt und über die Bridge → Gerät erscheint dort doppelt
            val bridgeVendors = bridge.admins.map { it.vendorId }.toSet()
            val doubled = devices.count { d ->
                d.isPrimaryChannel && d.id !in excluded && admins[d.matterNodeId].orEmpty().any { !it.own && it.vendorId in bridgeVendors }
            }
            if (doubled > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(pluralStringResource(R.plurals.bridge_doubled, doubled, doubled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }

    // --- Einzelne Geräte direkt ------------------------------------------------------------
    Group(stringResource(R.string.share_direct_title)) {
        Text(stringResource(R.string.share_direct_explain), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.share_hubs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Multi-Admin gilt für den ganzen Node (alle Kanäle) – daher nur Hauptkanäle
        val nodes = devices.filter { it.isPrimaryChannel }
        val online = nodes.filter { it.isOnline }.sortedBy { it.displayName }
        TextButton(onClick = { guarded { vm.start(online) } }, enabled = online.isNotEmpty()) {
            Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share_all, online.size))
        }
        nodes.sortedBy { it.displayName }.forEach { d ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(roomName(d), if (!d.isOnline) stringResource(R.string.state_offline) else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        admins[d.matterNodeId].orEmpty().forEach { f ->
                            InputChip(
                                selected = !f.own, onClick = { if (!f.own) removing = d to f },
                                label = { Text(adminName(f)) },
                                trailingIcon = if (f.own) null else ({ Icon(Icons.Outlined.Close, stringResource(R.string.share_remove), Modifier.size(18.dp)) }),
                                enabled = !f.own && d.isOnline,
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { guarded { vm.start(listOf(d)) } }, enabled = d.isOnline, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share))
                }
            }
        }
    }

    // --- Dialoge ---------------------------------------------------------------------------
    pending?.let { action ->
        PinEntryDialog(
            title = stringResource(R.string.share_pin_title),
            onDismiss = { pending = null },
            onSubmit = vm::verifyPin,
            onSuccess = { unlocked = true; pending = null; action() },
        )
    }
    removing?.let { (d, f) ->
        RaumDialog(
            title = stringResource(R.string.share_remove_title, adminName(f)),
            onDismiss = { removing = null },
            width = 560.dp,
            confirmButton = {
                Button(onClick = { if (d == null) vm.removeBridgeAdmin(f) else vm.removeAdmin(d, f); removing = null }) { Text(stringResource(R.string.share_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            Text(
                if (d == null) stringResource(R.string.bridge_remove_text, adminName(f))
                else stringResource(R.string.share_remove_text, adminName(f), d.displayName),
            )
        }
    }
    if (selecting) BridgeDeviceDialog(devices, excluded, roomName, vm::bridgeSupports, vm::setExcluded) { selecting = false }
    bridgeSession?.let { BridgeDialog(it, devices.count { d -> d.id !in excluded && vm.bridgeSupports(d) }, vm) }
    session?.let { ShareDialog(it, vm) }
}

/** Auswahl, welche Geräte über die Bridge sichtbar sind – nach Räumen gruppiert. */
@Composable
private fun BridgeDeviceDialog(
    devices: List<Device>, excluded: Set<UUID>, roomName: (Device) -> String?, supported: (Device) -> Boolean,
    onChange: (Set<UUID>) -> Unit, onDismiss: () -> Unit,
) {
    val noRoom = stringResource(R.string.no_room)
    RaumDialog(
        title = stringResource(R.string.bridge_select_title),
        onDismiss = onDismiss,
        width = 720.dp,
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onChange(emptySet()) }) { Text(stringResource(R.string.select_all)) }
                TextButton(onClick = { onChange(devices.map { it.id }.toSet()) }) { Text(stringResource(R.string.select_none)) }
            }
        },
    ) {
        Column {
            Text(stringResource(R.string.bridge_select_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            devices.groupBy { roomName(it) ?: noRoom }.toSortedMap().forEach { (room, list) ->
                Text(room, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                list.sortedBy { it.displayName }.forEach { d ->
                    val on = d.id !in excluded
                    if (!supported(d)) {
                        // Thermostate und Storen kann die Bridge noch nicht zeigen – ehrlich sagen statt still weglassen
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.bridge_unsupported), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        return@forEach
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                            .clickable { onChange(if (on) excluded + d.id else excluded - d.id) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { onChange(if (on) excluded + d.id else excluded - d.id) })
                        Text(d.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeDialog(s: BridgeSession, deviceCount: Int, vm: ShareViewModel) {
    RaumDialog(
        title = stringResource(R.string.bridge_dialog_title),
        onDismiss = vm::closeBridge,
        width = 960.dp,
        confirmButton = { TextButton(onClick = vm::closeBridge) { Text(stringResource(if (s.joined != null) R.string.action_done else R.string.action_close)) } },
    ) {
        when {
            s.joined != null -> Joined(stringResource(R.string.bridge_joined, adminName(s.joined), deviceCount), stringResource(R.string.bridge_joined_text))
            s.opening -> Opening()
            s.error != null -> Text(stringResource(R.string.bridge_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
            s.window != null -> PairingPanel(
                window = s.window,
                onRenew = vm::openBridge,
                simulate = if (BuildConfig.DEBUG && vm.canSimulateBridge) listOf(
                    "Apple Home" to { vm.simulateBridgeJoin(Ecosystems.APPLE) },
                    "Google Home" to { vm.simulateBridgeJoin(Ecosystems.GOOGLE) },
                ) else emptyList(),
                note = stringResource(R.string.bridge_pairing_note, deviceCount),
            )
        }
    }
}

@Composable
private fun ShareDialog(s: ShareSession, vm: ShareViewModel) {
    val title = if (s.queue.size > 1) stringResource(R.string.share_dialog_title_n, s.device.displayName, s.index + 1, s.queue.size)
    else stringResource(R.string.share_dialog_title, s.device.displayName)
    RaumDialog(
        title = title,
        onDismiss = vm::close,
        width = 960.dp,
        confirmButton = {
            if (s.hasNext) Button(onClick = vm::next, modifier = Modifier.height(48.dp)) {
                Text(stringResource(if (s.joined != null) R.string.share_next else R.string.share_skip))
            }
        },
        dismissButton = { TextButton(onClick = vm::close) { Text(stringResource(if (s.joined != null && !s.hasNext) R.string.action_done else R.string.action_close)) } },
    ) {
        when {
            s.joined != null -> Joined(stringResource(R.string.share_joined, s.device.displayName, adminName(s.joined)), stringResource(R.string.share_joined_text))
            s.opening -> Opening()
            s.error != null -> Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (s.error == CommandFailure.OFFLINE) R.string.share_error_offline else R.string.share_error),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge,
                )
                FilledTonalButton(onClick = vm::renew) { Text(stringResource(R.string.retry)) }
            }
            s.window != null -> PairingPanel(
                window = s.window,
                onRenew = vm::renew,
                simulate = if (BuildConfig.DEBUG && vm.canSimulate) listOf(
                    "Apple Home" to { vm.simulateJoin(Ecosystems.APPLE, "") },
                    "Google Home" to { vm.simulateJoin(Ecosystems.GOOGLE, "") },
                ) else emptyList(),
            )
        }
    }
}

@Composable
private fun Joined(title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Opening() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
        CircularProgressIndicator(); Spacer(Modifier.width(16.dp)); Text(stringResource(R.string.share_opening), style = MaterialTheme.typography.titleMedium)
    }
}

/** QR-Code, Zifferncode, Schritte, Restzeit – für einzelne Geräte und die Bridge. */
@Composable
private fun PairingPanel(window: PairingWindow, onRenew: () -> Unit, simulate: List<Pair<String, () -> Unit>>, note: String? = null) {
    val remaining by produceState(Duration.between(Instant.now(), window.expiresAt), window) {
        while (true) { value = Duration.between(Instant.now(), window.expiresAt); delay(1000) }
    }
    val expired = remaining.isNegative || remaining.isZero

    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Box(Modifier.size(300.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp)) {
            if (!expired) QrCode(window.qrPayload, Modifier.size(276.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Step(1, stringResource(R.string.share_step_open))
            Step(2, stringResource(R.string.share_step_scan))
            Text(
                SetupCodeGenerator.formatManual(window.manualCode),
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = 44.dp),
            )
            Step(3, note ?: stringResource(R.string.share_step_done))
            if (expired) {
                Text(stringResource(R.string.share_expired), color = MaterialTheme.colorScheme.error)
                FilledTonalButton(onClick = onRenew) { Text(stringResource(R.string.share_new_code)) }
            } else {
                Text(
                    stringResource(R.string.share_valid_for, "%d:%02d".format(remaining.toMinutes(), remaining.seconds % 60)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (simulate.isNotEmpty() && !expired) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    simulate.forEach { (name, action) -> TextButton(onClick = action) { Text(stringResource(R.string.share_simulate, name)) } }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    }
}
