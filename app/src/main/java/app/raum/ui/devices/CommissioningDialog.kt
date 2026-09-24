package app.raum.ui.devices

import app.raum.matter.controller.CommissioningFailure
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import app.raum.ui.components.RaumAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.domain.models.Room
import app.raum.matter.controller.CommissioningStep
import app.raum.i18n.ErrorTexts
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CommissioningDialog(
    rooms: List<Room>,
    onClose: () -> Unit,
    viewModel: CommissioningViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val close = { viewModel.cancel(); onClose() }

    when (val s = state) {
        is CommissioningUiState.EnterCode -> RaumAlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.commissioning_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.commissioning_intro),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedTextField(
                        value = s.code,
                        onValueChange = viewModel::updateCode,
                        label = { Text(stringResource(R.string.setup_code)) },
                        placeholder = { Text("1234-567-8901") },
                        singleLine = true,
                        isError = s.error != null,
                        supportingText = { s.error?.let { Text(stringResource(ErrorTexts.setupCode(it))) } },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, letterSpacing = 2.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.start() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::start, enabled = s.code.isNotBlank()) { Text(stringResource(R.string.connect)) } },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
        )

        is CommissioningUiState.Running -> RaumAlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.commissioning_running)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StepList(s.step)
                    // Schlafende Thread-Sensoren antworten nur alle paar Sekunden
                    Text(
                        stringResource(R.string.commissioning_slow_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
        )

        is CommissioningUiState.NameDevice -> NamingDialog(s, rooms, viewModel, onDone = { viewModel.finish(); onClose() })

        is CommissioningUiState.Failed -> RaumAlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.commissioning_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(s.invalidCode?.let { ErrorTexts.setupCode(it) } ?: ErrorTexts.commissioning(s.reason)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.commissioning_tips),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // COM-006: konkrete Wiederholungs- und Abbruchoptionen
            confirmButton = {
                if (s.reason == CommissioningFailure.ATTESTATION) {
                    // Nur auf ausdrücklichen Wunsch: Echtheit des Geräts nicht bestätigt
                    TextButton(onClick = viewModel::startUncertified) { Text(stringResource(R.string.commissioning_add_anyway), color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { viewModel.start() }) { Text(stringResource(R.string.retry)) }
                }
            },
            dismissButton = {
                Row {
                    // Fehlende Zugangsdaten/Bluetooth: direkt dorthin, wo man es einrichtet
                    if (s.reason in SETUP_REASONS) {
                        TextButton(onClick = { viewModel.openNetworkSettings(); onClose() }) { Text(stringResource(R.string.commissioning_open_setup)) }
                    } else {
                        TextButton(onClick = { viewModel.updateCode(s.code) }) { Text(stringResource(R.string.change_code)) }
                    }
                    TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )

        is CommissioningUiState.AlreadyPresent -> RaumAlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.commissioning_duplicate)) },
            text = { Text(stringResource(R.string.commissioning_duplicate_text, s.deviceName)) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

private val SETUP_REASONS = setOf(
    CommissioningFailure.NO_THREAD_NETWORK, CommissioningFailure.NO_WIFI_CREDENTIALS, CommissioningFailure.BLUETOOTH_UNAVAILABLE,
)

@Composable
private fun StepList(current: CommissioningStep) {
    Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CommissioningStep.entries.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    step.ordinal < current.ordinal -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                    step == current -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(step.labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (step.ordinal <= current.ordinal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamingDialog(
    s: CommissioningUiState.NameDevice,
    rooms: List<Room>,
    viewModel: CommissioningViewModel,
    onDone: () -> Unit,
) {
    RaumAlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(stringResource(R.string.commissioning_done)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(listOfNotNull(s.vendorName, s.productName).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = s.name,
                    onValueChange = { viewModel.updateNaming(name = it) },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.room), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rooms.forEach { room ->
                        FilterChip(
                            selected = s.roomId == room.id,
                            onClick = { viewModel.updateNaming(roomId = room.id) },
                            label = { Text(room.name) },
                            modifier = Modifier.height(48.dp),
                        )
                    }
                }
                // Ganze Zeile als Touch-Ziel, nicht nur das Kästchen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .toggleable(value = s.favorite, role = Role.Checkbox) { viewModel.updateNaming(favorite = it) },
                ) {
                    Checkbox(checked = s.favorite, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.show_as_favorite))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone, enabled = s.name.isNotBlank()) { Text(stringResource(R.string.action_done)) } },
    )
}
