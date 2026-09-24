package app.raum.ui.appliance

import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LockOpen
import app.raum.R
import androidx.compose.ui.res.stringResource

import android.app.Activity
import androidx.activity.compose.LocalActivity
import android.content.Intent
import android.os.Process
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.ui.components.RaumAlertDialog
import app.raum.ui.components.SectionHeader
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

private const val DPM_COMMAND = "adb shell dpm set-device-owner app.raum.panel/app.raum.platform.kiosk.RaumDeviceAdminReceiver"

private enum class Critical { ADB_OFF, ADB_ON, RELEASE_OWNER }

/** Geschützter Wartungsmodus (SYS-006, Spez. 11.3). Verlässt sich selbst, sobald die Sitzung abläuft. */
@Composable
fun MaintenanceScreen(vm: ApplianceViewModel, onClose: () -> Unit, dataVm: DataMaintenanceViewModel = koinViewModel()) {
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val status by vm.kioskStatus.collectAsStateWithLifecycle()
    val kioskEnabled by vm.kioskEnabled.collectAsStateWithLifecycle()
    val paused by vm.kioskPaused.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    var confirm by remember { mutableStateOf<Critical?>(null) }
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }

    LaunchedEffect(unlocked) { if (!unlocked) onClose() }
    LaunchedEffect(Unit) { while (true) { vm.refreshKioskStatus(); delay(2_000) } }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 40.dp, top = 32.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.maintenance), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.maintenance_autolock), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { vm.lock(); onClose() }) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.lock)) }
        }

        if (!pinSet) {
            Surface(
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LockOpen, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.maintenance_unprotected), Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = { vm.touch(); settingPin = true }, modifier = Modifier.height(56.dp)) {
                        Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.pin_set))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f)) {
                SectionHeader(stringResource(R.string.appliance_status))
                Card {
                    StatusLine(stringResource(R.string.status_device_owner), status.isDeviceOwner)
                    StatusLine(stringResource(R.string.status_lock_task), status.lockTaskActive)
                    StatusLine(stringResource(R.string.status_launcher), status.isDefaultLauncher)
                    StatusLine(stringResource(R.string.status_adb), status.adbEnabled, goodWhen = false)
                    if (!status.isDeviceOwner) {
                        Text(stringResource(R.string.setup_device_owner_hint), style = MaterialTheme.typography.bodyMedium)
                        Text(DPM_COMMAND, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                SectionHeader(stringResource(R.string.kiosk))
                Card {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.kiosk_permanent), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = kioskEnabled, onCheckedChange = { vm.touch(); vm.setKioskEnabled(it) })
                    }
                    if (paused) {
                        Text(stringResource(R.string.kiosk_paused_session), color = MaterialTheme.colorScheme.tertiary)
                        FilledTonalButton(onClick = vm::resumeKiosk) { Text(stringResource(R.string.kiosk_resume)) }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                SectionHeader(stringResource(R.string.actions))
                Card {
                    FilledTonalButton(onClick = {
                        vm.pauseKiosk(activity)
                        activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_android_settings))
                    }
                    Text(stringResource(R.string.android_settings_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        // Prozess beenden – CoreService (START_STICKY) und Launcher starten raum. neu.
                        activity.finishAffinity(); Process.killProcess(Process.myPid())
                    }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Outlined.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.restart_raum))
                    }
                    if (status.isDeviceOwner) {
                        OutlinedButton(onClick = { confirm = if (status.adbEnabled) Critical.ADB_OFF else Critical.ADB_ON },
                            modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(stringResource(if (status.adbEnabled) R.string.adb_disable else R.string.adb_enable))
                        }
                        TextButton(onClick = { confirm = Critical.RELEASE_OWNER }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(stringResource(R.string.release_device_owner), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        DataMaintenanceSection(dataVm)
    }

    // Kritische Aktionen benötigen eine erneute Bestätigung (Spez. 11.3)
    confirm?.let { c ->
        val (title, text) = when (c) {
            Critical.ADB_OFF -> stringResource(R.string.adb_disable_title) to stringResource(R.string.adb_disable_text)
            Critical.ADB_ON -> stringResource(R.string.adb_enable_title) to stringResource(R.string.adb_enable_text)
            Critical.RELEASE_OWNER -> stringResource(R.string.release_device_owner_title) to stringResource(R.string.release_device_owner_text)
        }
        RaumAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    when (c) {
                        Critical.ADB_OFF -> vm.setAdb(false)
                        Critical.ADB_ON -> vm.setAdb(true)
                        Critical.RELEASE_OWNER -> vm.releaseDeviceOwner(activity)
                    }
                    confirm = null
                }) { Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (settingPin) {
        PinSetupDialog(
            requireCurrent = false,
            onDismiss = { settingPin = false },
            validate = vm::pinValid,
            onSave = vm::setPin,
            onDone = { settingPin = false },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun StatusLine(label: String, value: Boolean, goodWhen: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (value) R.string.yes else R.string.no),
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == goodWhen) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
    }
}
