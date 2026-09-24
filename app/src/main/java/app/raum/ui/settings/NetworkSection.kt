package app.raum.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Wifi1Bar
import androidx.compose.material.icons.outlined.Wifi2Bar
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.platform.network.WifiNetwork
import app.raum.platform.network.WifiSecurity
import app.raum.ui.components.RaumDialog
import kotlinx.coroutines.launch

/** Netzwerk: Status (LAN/WLAN) und WLAN verbinden – direkt nur als Geräteeigentümer. */
@Composable
fun NetworkSection(vm: SettingsViewModel) {
    val net = vm.network
    val status by net.status.collectAsStateWithLifecycle()
    val networks by net.networks.collectAsStateWithLifecycle()
    val scanning by net.scanning.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var connectTo by remember { mutableStateOf<WifiNetwork?>(null) }

    // Status
    Group {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                status.ethernet -> Icons.Outlined.Cable
                status.wifiConnected -> Icons.Outlined.Wifi
                else -> Icons.Outlined.WifiOff
            }
            val connected = status.ethernet || status.wifiConnected
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(32.dp),
                    tint = if (connected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        status.ethernet -> stringResource(R.string.net_ethernet_connected)
                        status.wifiConnected -> status.ssid?.let { stringResource(R.string.net_wifi_connected, it) } ?: stringResource(R.string.net_wifi_connected_unknown)
                        else -> stringResource(R.string.net_not_connected)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                status.ipAddress?.takeIf { connected }?.let { Text(stringResource(R.string.net_ip, it), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val ssid = status.ssid
            if (status.wifiConnected && ssid != null && net.canManage) {
                OutlinedButton(onClick = { vm.forgetWifi(ssid) }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.net_forget)) }
            }
        }
        if (status.wifiConnected && !status.ethernet) {
            Text(stringResource(R.string.net_lan_recommended), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // WLAN
    Group(stringResource(R.string.net_wifi)) {
        if (net.canManage) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.net_wifi), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = status.wifiEnabled, onCheckedChange = { net.setWifiEnabled(it); if (it) net.scan() })
            }
        } else {
            Text(stringResource(R.string.net_manage_requires_owner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = {
                activity?.startActivity(Intent(Settings.Panel.ACTION_WIFI))
            }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.net_open_android)) }
        }

        if (!status.wifiEnabled) {
            Text(stringResource(R.string.net_wifi_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Group
        }
        if (net.needsLocationService) {
            Text(stringResource(R.string.net_location_service), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            if (net.canManage) OutlinedButton(onClick = { net.enableLocationService() }) { Text(stringResource(R.string.net_location_enable)) }
        } else if (!net.canScan) {
            Text(stringResource(R.string.net_permission_missing), color = MaterialTheme.colorScheme.error)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.net_available), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            else IconButton(onClick = net::scan, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, stringResource(R.string.net_search_again)) }
        }
        if (networks.isEmpty() && !scanning) {
            Text(stringResource(R.string.net_none_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            networks.forEachIndexed { i, n ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val current = status.wifiConnected && status.ssid == n.ssid
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = net.canManage && !current) { connectTo = n }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when { n.level >= 3 -> Icons.Outlined.Wifi; n.level == 2 -> Icons.Outlined.Wifi2Bar; else -> Icons.Outlined.Wifi1Bar },
                        null, tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.ssid, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                current -> stringResource(R.string.net_connected)
                                n.security == WifiSecurity.ENTERPRISE -> stringResource(R.string.net_enterprise_unsupported)
                                n.secured -> stringResource(R.string.net_secured)
                                else -> stringResource(R.string.net_open)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (n.secured) Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    connectTo?.let { target -> ConnectDialog(vm, target, onDismiss = { connectTo = null }) }
}

@Composable
private fun ConnectDialog(vm: SettingsViewModel, target: WifiNetwork, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unsupported = target.security == WifiSecurity.ENTERPRISE || target.security == WifiSecurity.WEP
    val ready = !busy && !unsupported && (!target.secured || password.length >= 8)

    fun connect() {
        if (!ready) return
        busy = true; failed = false
        scope.launch {
            val ok = vm.connectWifi(target, password.takeIf { target.secured })
            busy = false
            if (ok) onDismiss() else failed = true
        }
    }

    RaumDialog(
        title = stringResource(R.string.net_connect_title, target.ssid),
        onDismiss = { if (!busy) onDismiss() },
        width = 560.dp,
        confirmButton = {
            Button(onClick = ::connect, enabled = ready, modifier = Modifier.height(48.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.net_connecting)) }
                else Text(stringResource(R.string.net_connect))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                unsupported -> Text(stringResource(R.string.net_enterprise_unsupported), color = MaterialTheme.colorScheme.error)
                target.secured -> OutlinedTextField(
                    password, { password = it; failed = false },
                    label = { Text(stringResource(R.string.net_password)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(R.string.net_show_password))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Text(stringResource(R.string.net_open_warning), style = MaterialTheme.typography.bodyMedium)
            }
            if (failed) Text(stringResource(R.string.net_connect_failed, target.ssid), color = MaterialTheme.colorScheme.error)
        }
    }
}
