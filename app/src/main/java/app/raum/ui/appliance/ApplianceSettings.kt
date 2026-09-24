package app.raum.ui.appliance

import androidx.compose.runtime.LaunchedEffect
import app.raum.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.platform.display.SleepAction
import app.raum.ui.components.SectionHeader

private val SLEEP_OPTIONS = listOf(30, 60, 120, 300, 600, null)

@Composable
private fun sleepLabel(seconds: Int?): String = when {
    seconds == null -> stringResource(R.string.never)
    seconds < 60 -> stringResource(R.string.duration_seconds, seconds)
    else -> stringResource(R.string.duration_minutes, seconds / 60)
}

@Composable
private fun SettingsSurface(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Display: Ruhezustand, Näherungssensor, Helligkeit (Spez. 4.1, 9.7). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayCard(vm: ApplianceViewModel, canTurnOff: Boolean) {
    val s by vm.displaySettings.collectAsStateWithLifecycle()
    val lux by vm.lux.collectAsStateWithLifecycle()
    val near by vm.near.collectAsStateWithLifecycle()

    SectionHeader(stringResource(R.string.display))
    SettingsSurface {
        Text(stringResource(R.string.sleep_after), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SLEEP_OPTIONS.forEach { sec ->
                FilterChip(selected = s.sleepAfterSeconds == sec, onClick = { vm.updateDisplay { it.copy(sleepAfterSeconds = sec) } },
                    label = { Text(sleepLabel(sec)) }, modifier = Modifier.height(48.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepAction.entries.forEach { a ->
                FilterChip(
                    selected = s.sleepAction == a,
                    onClick = { vm.updateDisplay { it.copy(sleepAction = a) } },
                    enabled = a != SleepAction.DISPLAY_OFF || canTurnOff,
                    label = { Text(stringResource(a.labelRes)) }, modifier = Modifier.height(48.dp),
                )
            }
        }
        if (!canTurnOff) Text(stringResource(R.string.display_off_requires_owner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SwitchRow(stringResource(R.string.proximity_wake), s.proximityWake, enabled = vm.hasProximitySensor) { v -> vm.updateDisplay { it.copy(proximityWake = v) } }
        SwitchRow(stringResource(R.string.auto_brightness), s.autoBrightness, enabled = vm.hasLightSensor) { v -> vm.updateDisplay { it.copy(autoBrightness = v) } }
        if (s.autoBrightness && vm.hasLightSensor) {
            Text(stringResource(R.string.min_brightness, (s.minBrightness * 100).toInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(value = s.minBrightness, onValueChange = { v -> vm.updateDisplay { it.copy(minBrightness = v) } }, valueRange = 0.02f..0.6f)
        } else {
            Text(stringResource(R.string.brightness_value, (s.manualBrightness * 100).toInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(value = s.manualBrightness, onValueChange = { v -> vm.updateDisplay { it.copy(manualBrightness = v) } }, valueRange = 0.05f..1f)
        }
        Text(
            listOf(
                if (vm.hasLightSensor) stringResource(R.string.ambient_light_value, lux?.let { "%.0f lx".format(it) } ?: "–") else stringResource(R.string.no_light_sensor),
                if (vm.hasProximitySensor) stringResource(
                    R.string.proximity_value,
                    when (near) { true -> stringResource(R.string.proximity_near); false -> stringResource(R.string.proximity_far); null -> "–" },
                ) else stringResource(R.string.no_proximity_sensor),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = vm::sleepNow) { Icon(Icons.Outlined.NightsStay, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sleep_now)) }
    }
}

/** Sicherheit: Administrator-PIN und Zugang zum Wartungsmodus (SYS-006, Spez. 11.3). */
@Composable
fun SecurityCard(vm: ApplianceViewModel, onOpenMaintenance: () -> Unit) {
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshPin() }

    SettingsSurface {
        Text(
            stringResource(if (pinSet) R.string.pin_is_set else R.string.pin_missing_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = if (pinSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { setup = true }) {
                Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (pinSet) R.string.pin_change else R.string.pin_set))
            }
            FilledTonalButton(onClick = {
                when {
                    unlocked -> onOpenMaintenance()
                    vm.enterWithoutPin() -> onOpenMaintenance()
                    else -> entering = true
                }
            }) {
                Icon(Icons.Outlined.Build, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.maintenance))
            }
        }
    }

    if (setup) {
        PinSetupDialog(
            requireCurrent = pinSet,
            onDismiss = { setup = false },
            validate = vm::pinValid,
            onSave = vm::setPin,
            onDone = { setup = false },
        )
    }
    if (entering) {
        PinEntryDialog(
            title = stringResource(R.string.maintenance_enter_pin),
            onDismiss = { entering = false },
            onSubmit = vm::unlock,
            onSuccess = { entering = false; onOpenMaintenance() },
        )
    }
}
