package app.raum.ui.automations

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.raum.domain.models.Device
import app.raum.domain.models.Weekdays
import app.raum.ui.components.RaumIcons
import java.util.UUID

private val DAYS = listOf(
    1 to R.string.day_mon, 2 to R.string.day_tue, 3 to R.string.day_wed, 4 to R.string.day_thu,
    5 to R.string.day_fri, 6 to R.string.day_sat, 7 to R.string.day_sun,
)

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Wochentage; leere Menge = täglich. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekdayChips(selected: Weekdays, onChange: (Weekdays) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected.isEmpty(), onClick = { onChange(emptySet()) }, label = { Text(stringResource(R.string.days_daily)) }, modifier = Modifier.height(48.dp))
            FilterChip(selected = selected == (1..5).toSet(), onClick = { onChange((1..5).toSet()) }, label = { Text(stringResource(R.string.auto_days_workdays)) }, modifier = Modifier.height(48.dp))
            FilterChip(selected = selected == setOf(6, 7), onClick = { onChange(setOf(6, 7)) }, label = { Text(stringResource(R.string.days_weekend)) }, modifier = Modifier.height(48.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DAYS.forEach { (day, label) ->
                val on = selected.isEmpty() || day in selected
                FilterChip(
                    selected = on,
                    onClick = {
                        val base = selected.ifEmpty { (1..7).toSet() }
                        val next = if (day in base) base - day else base + day
                        // Alle 7 = täglich; keiner ausgewählt ist nicht sinnvoll → täglich
                        onChange(if (next.size == 7 || next.isEmpty()) emptySet() else next)
                    },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.size(width = 64.dp, height = 48.dp),
                )
            }
        }
    }
}

/**
 * Uhrzeit ohne Tastatur: große Tasten für Stunden und 5-Minuten-Schritte,
 * dazu Feinjustierung um ±1 Minute.
 */
@Composable
fun TimeField(minuteOfDay: Int, onChange: (Int) -> Unit) {
    fun norm(m: Int) = ((m % 1440) + 1440) % 1440
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(onMinus = { onChange(norm(minuteOfDay - 60)) }, onPlus = { onChange(norm(minuteOfDay + 60)) }) {
                Text("%02d".format(minuteOfDay / 60), style = MaterialTheme.typography.displaySmall)
            }
            Text(":", style = MaterialTheme.typography.displaySmall)
            Stepper(onMinus = { onChange(norm(minuteOfDay - 5)) }, onPlus = { onChange(norm(minuteOfDay + 5)) }) {
                Text("%02d".format(minuteOfDay % 60), style = MaterialTheme.typography.displaySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { onChange(norm(minuteOfDay - 1)) }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.minus_one_minute)) }
            TextButton(onClick = { onChange(norm(minuteOfDay + 1)) }, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.plus_one_minute)) }
        }
    }
}

@Composable
fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean = true, plusEnabled: Boolean = true, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Remove, stringResource(R.string.less)) }
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        FilledTonalIconButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Add, stringResource(R.string.more)) }
    }
}

/** Einfachauswahl eines Geräts; [devices] ist bereits nach Eignung gefiltert. */
@Composable
fun DeviceRadioList(devices: List<Device>, roomName: (UUID?) -> String?, selected: UUID?, onSelect: (Device) -> Unit) {
    if (devices.isEmpty()) {
        Text(stringResource(R.string.no_matching_device), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            devices.forEach { d ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .selectable(selected = d.id == selected, role = Role.RadioButton) { onSelect(d) }
                        .padding(horizontal = 12.dp),
                ) {
                    RadioButton(selected = d.id == selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Icon(RaumIcons.device(d), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(d.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    roomName(d.roomId)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!d.isOnline) Text("  " + stringResource(R.string.state_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Auswahl aus wenigen Optionen als Chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }, modifier = Modifier.height(48.dp))
        }
    }
}
