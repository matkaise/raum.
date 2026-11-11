package app.raum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.sonos.SonosLink
import app.raum.sonos.SonosService
import org.koin.compose.koinInject

@Composable
fun sonosSummary(service: SonosService = koinInject()): String {
    val st by service.state.collectAsStateWithLifecycle()
    return when {
        !st.enabled -> stringResource(R.string.hc_mode_off)
        st.players.isNotEmpty() -> pluralStringResource(R.plurals.sonos_speakers, st.players.size, st.players.size)
        st.link == SonosLink.SEARCHING -> stringResource(R.string.sonos_searching_short)
        else -> stringResource(R.string.sonos_none_found_short)
    }
}

@Composable
fun SonosSection(service: SonosService = koinInject()) {
    val st by service.state.collectAsStateWithLifecycle()

    Group {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sonos_enable), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = st.enabled, onCheckedChange = service::setEnabled)
        }
        Text(stringResource(R.string.sonos_settings_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!st.enabled) return

    Group(stringResource(R.string.sonos_speakers_title)) {
        when {
            st.players.isNotEmpty() -> st.players.forEach { p ->
                val group = st.groupOf(p.uuid)
                val grouped = group != null && group.members.size > 1
                Info(p.name, listOfNotNull(p.host, if (grouped) stringResource(R.string.sonos_grouped_with, group!!.name) else null).joinToString(" · "))
            }
            st.link == SonosLink.SEARCHING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Text(stringResource(R.string.sonos_searching), style = MaterialTheme.typography.bodyLarge)
            }
            else -> Text(stringResource(R.string.sonos_none_found_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st.link == SonosLink.ERROR && st.players.isNotEmpty()) Text(stringResource(R.string.sonos_unreachable), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = service::rescan, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sonos_rescan))
        }
    }

    Group(stringResource(R.string.sonos_manual_title)) {
        Text(stringResource(R.string.sonos_manual_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var manual by remember { mutableStateOf(service.manualHosts) }
        manual.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { service.removeManualHost(h); manual = service.manualHosts }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_remove)) }
            }
        }
        var input by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                input, { input = it; invalid = false }, singleLine = true, isError = invalid,
                placeholder = { Text("192.168.1.39") },
                supportingText = if (invalid) { { Text(stringResource(R.string.sonos_manual_invalid)) } } else null,
                modifier = Modifier.widthIn(max = 360.dp).weight(1f, fill = false),
            )
            FilledTonalButton(
                onClick = { if (service.addManualHost(input)) { input = ""; manual = service.manualHosts } else invalid = true },
                enabled = input.isNotBlank(), modifier = Modifier.height(56.dp),
            ) { Text(stringResource(R.string.sonos_manual_add)) }
        }
    }

    Group(stringResource(R.string.sonos_online_art_title)) {
        val online by service.onlineArt.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sonos_online_art), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = online, onCheckedChange = service::setOnlineArt)
        }
        Text(stringResource(R.string.sonos_online_art_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Group(stringResource(R.string.sonos_ytm_title)) {
        Text(stringResource(R.string.sonos_ytm_explain), style = MaterialTheme.typography.bodyMedium)
    }
}
