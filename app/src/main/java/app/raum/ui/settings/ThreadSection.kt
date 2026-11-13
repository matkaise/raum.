package app.raum.ui.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.raum.ui.theme.LocalGlass
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.platform.bluetooth.BluetoothState
import app.raum.thread.BorderRouter
import app.raum.thread.DatasetSource
import app.raum.thread.ThreadDataset
import app.raum.thread.ThreadHealth
import app.raum.thread.ThreadInterfaceState
import app.raum.thread.ThreadNetworkSummary
import app.raum.thread.ThreadStatus
import app.raum.ui.appliance.PinEntryDialog
import app.raum.ui.components.RaumDialog
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Kurzfassung für die Kategorienliste. */
@Composable
internal fun threadSummary(vm: ThreadViewModel = koinViewModel()): String = threadStatus(vm).first

/** Kurzfassung und ob etwas nicht stimmt (Border Router fehlt oder ist inaktiv). */
@Composable
internal fun threadStatus(vm: ThreadViewModel = koinViewModel()): Pair<String, Boolean> {
    val status by vm.status.collectAsStateWithLifecycle()
    val text = status.network?.let { stringResource(R.string.thread_summary_network, it.summary.networkName) }
        ?: stringResource(R.string.thread_summary_none)
    return text to (status.health == ThreadHealth.NO_BORDER_ROUTER || status.health == ThreadHealth.BORDER_ROUTER_INACTIVE)
}

/**
 * Matter und Thread (Spez. 8.3, COM-002 – COM-004): oben drei Statuskacheln (Thread-Netz, Border Router, Bluetooth),
 * darunter die Prüfliste und das WLAN für neue Geräte; Netzdetails, Routerliste und Controller unter „Erweitert“.
 */
@Composable
fun ThreadSection(vm: ThreadViewModel = koinViewModel(), advanced: @Composable () -> Unit = {}) {
    val status by vm.status.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val adopting by vm.adopting.collectAsStateWithLifecycle()

    var unlocked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var importing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    // Zugangsdaten sind sensibel – mit PIN geschützt, wenn eine festgelegt ist
    fun guarded(action: () -> Unit) { if (vm.pinRequired && !unlocked) pending = action else action() }

    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    StatusTiles(vm, status, scanning, adopting, onImport = { guarded { importing = true } }, onAdopt = { r -> guarded { vm.adopt(r) } })
    DiagnosisGroup(status)
    WifiGroup(vm, ::guarded)
    // Apple/Google/Amazon: Zugangsdaten gibt es dort nicht – Weg über Teilen nur auf Nachfrage erklären
    if (status.network == null && status.routers.any { it.closedEcosystem } && status.routers.none { it.restAvailable }) {
        val g = LocalGlass.current
        Group(info = stringResource(R.string.thread_closed_ecosystem) + " " + stringResource(R.string.thread_closed_ecosystem_detail)) {
            Text(stringResource(R.string.matter_ecosystems_title), color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.matter_ecosystems_sub), color = g.soft, fontSize = 13.sp)
        }
    }
    Advanced {
        if (status.network != null) NetworkGroup(status, onImport = { guarded { importing = true } }, onRemove = { guarded { confirmRemove = true } })
        RoutersGroup(status, scanning, adopting, onRescan = vm::rescan, onAdopt = { r -> guarded { vm.adopt(r) } })
        advanced()
    }

    pending?.let { action ->
        PinEntryDialog(
            title = stringResource(R.string.thread_pin_title),
            onDismiss = { pending = null },
            onSubmit = vm::verifyPin,
            onSuccess = { unlocked = true; pending = null; action() },
        )
    }
    if (importing) ImportDialog(current = status.network?.summary, preview = vm::preview, onDismiss = { importing = false }) { text ->
        if (vm.import(text)) importing = false
    }
    if (confirmRemove) {
        RaumDialog(
            title = stringResource(R.string.thread_remove_title),
            onDismiss = { confirmRemove = false },
            width = 560.dp,
            confirmButton = { Button(onClick = { vm.clearThread(); confirmRemove = false }) { Text(stringResource(R.string.thread_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { Text(stringResource(R.string.thread_remove_text)) }
    }
}

/**
 * Einrichtung (ONB-007, ONB-008): Thread optional – erkannte Border Router vorschlagen, Dataset übernehmen
 * oder eingeben. Ohne PIN-Abfrage, die Einrichtung läuft ohnehin im Wartungsmodus.
 */
@Composable
fun ThreadSetupCard(vm: ThreadViewModel = koinViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val adopting by vm.adopting.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }
    Group(stringResource(R.string.onb_thread_title)) {
        val net = status.network
        when {
            net != null -> StatusRow(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.secondary,
                stringResource(R.string.onb_thread_ready, net.summary.networkName))
            status.routers.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                Text(stringResource(if (scanning) R.string.thread_routers_searching else R.string.onb_thread_none),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                status.routers.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Router, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.onb_thread_found, r.networkName ?: "–", listOfNotNull(r.vendor, r.displayName).distinct().joinToString(" · ")),
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        )
                        if (r.restAvailable) {
                            if (adopting == r.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            else FilledTonalButton(onClick = { vm.adopt(r) }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.thread_adopt)) }
                        }
                    }
                }
                if (status.routers.none { it.restAvailable }) {
                    Text(stringResource(R.string.onb_thread_closed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (net == null) {
            TextButton(onClick = { importing = true }) { Text(stringResource(R.string.thread_import)) }
        }
        Text(stringResource(R.string.onb_thread_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (importing) ImportDialog(current = status.network?.summary, preview = vm::preview, onDismiss = { importing = false }) { text ->
        if (vm.import(text)) importing = false
    }
}

// --- Thread-Netz ---------------------------------------------------------------------------

@Composable
private fun NetworkGroup(status: ThreadStatus, onImport: () -> Unit, onRemove: () -> Unit) {
    Group(stringResource(R.string.thread_network)) {
        val net = status.network
        if (net == null) {
            Text(stringResource(R.string.thread_none_text), style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = onImport, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.thread_import)) }
            return@Group
        }
        HealthLine(status)
        Info(stringResource(R.string.thread_name), net.summary.networkName)
        Info(stringResource(R.string.thread_channel), net.summary.channel.toString())
        Info(stringResource(R.string.thread_pan_id), "0x%04X".format(net.summary.panId))
        Info(stringResource(R.string.thread_ext_pan_id), net.summary.extPanId.uppercase())
        Info(
            stringResource(R.string.thread_source),
            when (net.source) {
                DatasetSource.BORDER_ROUTER -> stringResource(R.string.thread_source_router, net.sourceName ?: "–", formatDate(net.importedAt))
                DatasetSource.MANUAL -> stringResource(R.string.thread_source_manual, formatDate(net.importedAt))
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.thread_replace)) }
            OutlinedButton(onClick = onRemove, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.thread_remove)) }
        }
    }
}

@Composable
private fun HealthLine(status: ThreadStatus) {
    val (icon, color, text) = when (status.health) {
        ThreadHealth.OK -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.secondary,
            stringResource(R.string.thread_health_ok, status.matching.joinToString { it.displayName }))
        ThreadHealth.BORDER_ROUTER_INACTIVE -> Triple(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.tertiary,
            stringResource(R.string.thread_health_inactive))
        ThreadHealth.NO_BORDER_ROUTER -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error,
            stringResource(R.string.thread_health_no_router))
        ThreadHealth.NOT_CONFIGURED -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.thread_summary_none))
    }
    StatusRow(icon, color, text)
}

@Composable
private fun StatusRow(icon: ImageVector, color: Color, text: String, detail: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, color = LocalGlass.current.ink, fontSize = 15.sp)
            detail?.let { Text(it, color = LocalGlass.current.soft, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun formatDate(at: Instant): String =
    if (at == Instant.EPOCH) "–"
    else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(at)

// --- Border Router -------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutersGroup(
    status: ThreadStatus,
    scanning: Boolean,
    adopting: String?,
    onRescan: () -> Unit,
    onAdopt: (BorderRouter) -> Unit,
) {
    Group(stringResource(R.string.thread_routers)) {
        val rescan: @Composable () -> Unit = {
            if (scanning && status.routers.isEmpty()) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            else IconButton(onClick = onRescan, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, stringResource(R.string.net_search_again)) }
        }
        if (status.routers.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (scanning) R.string.thread_routers_searching else R.string.thread_routers_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                rescan()
            }
            return@Group
        }
        status.routers.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val ours = r in status.matching
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Router, null, tint = if (r.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            r.vendor,
                            r.networkName?.let { stringResource(R.string.thread_router_network, it) },
                            stringResource(
                                when (r.state) {
                                    ThreadInterfaceState.ACTIVE -> R.string.thread_router_active
                                    ThreadInterfaceState.INACTIVE, ThreadInterfaceState.NOT_INITIALIZED -> R.string.thread_router_inactive
                                    ThreadInterfaceState.UNKNOWN -> R.string.thread_router_unknown
                                },
                            ),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    if (i == 0) rescan()
                    if (ours) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.thread_router_ours)) })
                    if (r.restAvailable && !ours) {
                        if (adopting == r.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        else FilledTonalButton(onClick = { onAdopt(r) }, enabled = adopting == null, modifier = Modifier.height(48.dp)) {
                            Text(stringResource(R.string.thread_adopt))
                        }
                    }
                }
            }
        }
    }
}

// --- Diagnose ------------------------------------------------------------------------------

private data class Check(val icon: ImageVector, val color: Color, val text: String, val detail: String? = null)

private val Ok = Color(0xFF2F6B45)
private val Warn = Color(0xFF9A6424)
private val Bad = Color(0xFFB3261E)

@Composable
private fun DiagnosisGroup(status: ThreadStatus) {
    val neutral = LocalGlass.current.soft
    val checks = buildList {
        // Border Router
        val activeRouters = status.routers.count { it.active || it.state == ThreadInterfaceState.UNKNOWN }
        if (status.routers.isEmpty()) {
            add(Check(Icons.Outlined.ErrorOutline, if (status.threadDevices > 0) Bad else neutral,
                stringResource(R.string.thread_diag_no_router), stringResource(R.string.thread_diag_no_router_detail)))
        } else {
            add(Check(Icons.Outlined.CheckCircle, if (activeRouters > 0) Ok else Warn, stringResource(R.string.thread_diag_routers, status.routers.size, activeRouters)))
        }
        // Thread-Geräte
        if (status.threadDevices > 0) {
            val all = status.threadDevicesOnline == status.threadDevices
            add(Check(
                if (all) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                if (all) Ok else if (status.threadDevicesOnline == 0) Bad else Warn,
                stringResource(R.string.thread_diag_devices, status.threadDevicesOnline, status.threadDevices),
                if (status.borderRouterUnreachable) stringResource(R.string.thread_diag_devices_unreachable) else null,
            ))
            // Geräte hängen in einem anderen Netz als dem hinterlegten (z. B. Apple-Netz, Dataset von anderswo)
            val net = status.network?.summary?.networkName
            val foreign = status.deviceNetworks.filter { it != net }
            if (foreign.isNotEmpty()) {
                add(Check(Icons.Outlined.Info, neutral, stringResource(R.string.thread_diag_device_networks, foreign.joinToString { "„$it“" }),
                    if (net != null) stringResource(R.string.thread_diag_device_networks_other, net) else null))
            }
        } else {
            add(Check(Icons.Outlined.Info, neutral, stringResource(R.string.thread_diag_no_devices)))
        }
        // IPv6 – ohne gibt es keinen Weg zu Thread-Geräten
        status.ipv6?.let { v6 ->
            if (v6.hasRoutableAddress) add(Check(Icons.Outlined.CheckCircle, Ok, stringResource(R.string.thread_diag_ipv6_ok)))
            else add(Check(Icons.Outlined.WarningAmber, Warn, stringResource(R.string.thread_diag_ipv6_missing), stringResource(R.string.thread_diag_ipv6_detail)))
        }
    }
    Group {
        checks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { c -> Row(Modifier.weight(1f)) { StatusRow(c.icon, c.color, c.text, c.detail) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// --- Statuskacheln ---------------------------------------------------------------------------

@Composable
private fun StatusTiles(
    vm: ThreadViewModel,
    status: ThreadStatus,
    scanning: Boolean,
    adopting: String?,
    onImport: () -> Unit,
    onAdopt: (BorderRouter) -> Unit,
) {
    val bt by vm.bluetooth.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshBluetooth() }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshBluetooth() }

    Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Thread-Netz
        val net = status.network
        StatusTile(
            Icons.Outlined.Hub, if (net == null) Color(0xFFFDE2DF) else Color(0xFFE9FBEF), if (net == null) Bad else Ok,
            stringResource(R.string.thread_tile_network),
            net?.let { "„${it.summary.networkName}“ · " + stringResource(R.string.thread_channel) + " " + it.summary.channel } ?: stringResource(R.string.thread_not_set),
            Modifier.weight(1f), problem = net == null,
            action = if (net == null) stringResource(R.string.thread_import) to onImport else null,
        )
        // Border Router: unserer bzw. der erste aktive
        val router = status.matching.firstOrNull() ?: status.routers.firstOrNull { it.active } ?: status.routers.firstOrNull()
        val adoptable = status.routers.firstOrNull { it.restAvailable && it !in status.matching }
        StatusTile(
            Icons.Outlined.Router, if (router?.active == true) Color(0xFFE9FBEF) else Color(0xFFFFF1C4), if (router?.active == true) Ok else Warn,
            stringResource(R.string.thread_tile_routers),
            when {
                router != null -> listOfNotNull(
                    router.displayName, router.vendor,
                    stringResource(if (router.active) R.string.thread_router_active else R.string.thread_router_inactive),
                ).joinToString(" · ")
                scanning -> stringResource(R.string.thread_routers_searching)
                else -> stringResource(R.string.thread_routers_none)
            },
            Modifier.weight(1f),
            action = when {
                adoptable != null && adopting == null && net == null -> stringResource(R.string.thread_adopt) to { onAdopt(adoptable) }
                else -> stringResource(R.string.thread_search_again_short) to vm::rescan
            },
        )
        // Bluetooth (Koppeln neuer Geräte)
        val (btText, btProblem) = when (bt) {
            BluetoothState.READY -> R.string.bt_ready to false
            BluetoothState.OFF -> R.string.bt_off to true
            BluetoothState.NO_PERMISSION -> R.string.bt_no_permission to true
            BluetoothState.NO_LOCATION_SERVICE -> R.string.bt_no_location to true
            BluetoothState.UNAVAILABLE -> R.string.bt_unavailable to true
        }
        StatusTile(
            Icons.Outlined.Bluetooth, if (btProblem) Color(0xFFFFF1C4) else Color(0xFFE3F4FC), if (btProblem) Warn else Color(0xFF1D6F99),
            stringResource(R.string.thread_tile_bluetooth), stringResource(btText), Modifier.weight(1f),
            action = when (bt) {
                BluetoothState.OFF, BluetoothState.NO_LOCATION_SERVICE -> stringResource(R.string.bt_enable) to {
                    if (!vm.enableBluetooth() && bt == BluetoothState.OFF) enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                BluetoothState.NO_PERMISSION -> stringResource(R.string.bt_allow) to { permissionLauncher.launch(vm.bluetoothPermissions) }
                else -> null
            },
        )
    }
}

// --- Neue Geräte ---------------------------------------------------------------------------

@Composable
private fun WifiGroup(vm: ThreadViewModel, guarded: (() -> Unit) -> Unit) {
    val g = LocalGlass.current
    val ssid by vm.wifiSsid.collectAsStateWithLifecycle()
    var editWifi by remember { mutableStateOf(false) }
    var confirmWifiRemove by remember { mutableStateOf(false) }

    // WLAN für Matter-over-Wi-Fi-Geräte (COM-003)
    Group {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconDisk(Icons.Outlined.Wifi, Color(0xFFE3F4FC), Color(0xFF1D6F99), 44)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.commissioning_wifi), color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    ssid?.let { stringResource(R.string.commissioning_wifi_set, it) } ?: stringResource(R.string.commissioning_wifi_none),
                    color = g.soft, fontSize = 13.sp,
                )
            }
            ActionPill(stringResource(if (ssid == null) R.string.commissioning_wifi_define else R.string.commissioning_wifi_change)) { guarded { editWifi = true } }
            if (ssid != null) ActionPill(stringResource(R.string.thread_remove)) { guarded { confirmWifiRemove = true } }
        }
    }

    if (editWifi) WifiDialog(initialSsid = ssid ?: vm.currentSsid.orEmpty(), onDismiss = { editWifi = false }) { s, p -> vm.setWifi(s, p); editWifi = false }
    if (confirmWifiRemove) {
        RaumDialog(
            title = stringResource(R.string.commissioning_wifi_remove_title),
            onDismiss = { confirmWifiRemove = false },
            width = 560.dp,
            confirmButton = { Button(onClick = { vm.clearWifi(); confirmWifiRemove = false }) { Text(stringResource(R.string.thread_remove)) } },
            dismissButton = { TextButton(onClick = { confirmWifiRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { Text(stringResource(R.string.commissioning_wifi_remove_text)) }
    }
}

// --- Dialoge -------------------------------------------------------------------------------

@Composable
private fun ImportDialog(
    current: ThreadNetworkSummary?,
    preview: (String) -> ThreadDataset.ParseResult,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { if (text.isBlank()) null else preview(text) }
    // Die Bildschirmtastatur verdeckt sonst die Knöpfe des Dialogs
    val keyboard = LocalSoftwareKeyboardController.current
    RaumDialog(
        title = stringResource(R.string.thread_import_title),
        onDismiss = onDismiss,
        confirmButton = { Button(onClick = { onSave(text) }, enabled = parsed is ThreadDataset.ParseResult.Ok) { Text(stringResource(R.string.thread_import_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.thread_import_text), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.thread_import_where), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1200) },
                label = { Text(stringResource(R.string.thread_import_label)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                isError = parsed is ThreadDataset.ParseResult.Invalid,
                supportingText = {
                    when (parsed) {
                        is ThreadDataset.ParseResult.Invalid -> Text(stringResource(datasetError(parsed.error)))
                        is ThreadDataset.ParseResult.Ok -> Text(
                            stringResource(R.string.thread_import_preview, parsed.dataset.networkName, parsed.dataset.channel, "0x%04X".format(parsed.dataset.panId)),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        null -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
            val new = (parsed as? ThreadDataset.ParseResult.Ok)?.dataset
            if (current != null && new != null && !new.extPanId.equals(current.extPanId, ignoreCase = true)) {
                StatusRow(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.tertiary, stringResource(R.string.thread_import_replaces, current.networkName))
            }
        }
    }
}

private fun datasetError(e: ThreadDataset.Error): Int = when (e) {
    ThreadDataset.Error.EMPTY -> R.string.thread_error_empty
    ThreadDataset.Error.NOT_HEX -> R.string.thread_error_not_hex
    ThreadDataset.Error.MALFORMED -> R.string.thread_error_malformed
    ThreadDataset.Error.TOO_LONG -> R.string.thread_error_too_long
    ThreadDataset.Error.MISSING_FIELDS -> R.string.thread_error_missing
}

@Composable
private fun WifiDialog(initialSsid: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var ssid by remember { mutableStateOf(initialSsid) }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    // WPA: 8–63 Zeichen; offenes Netz ohne Passwort erlaubt
    val valid = ssid.isNotBlank() && ssid.length <= 32 && (password.isEmpty() || password.length in 8..63)
    RaumDialog(
        title = stringResource(R.string.commissioning_wifi),
        onDismiss = onDismiss,
        width = 600.dp,
        confirmButton = { Button(onClick = { onSave(ssid, password) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.commissioning_wifi_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(ssid, { ssid = it.take(32) }, label = { Text(stringResource(R.string.commissioning_wifi_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it.take(63) },
                label = { Text(stringResource(R.string.net_password)) },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(R.string.net_show_password))
                    }
                },
                supportingText = { if (password.isNotEmpty() && password.length < 8) Text(stringResource(R.string.commissioning_wifi_short)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
