package app.raum.ui.appliance

import app.raum.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.raum.security.AdminPinStore
import app.raum.security.AdminPinStore.VerifyResult
import app.raum.ui.components.RaumDialog
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** Großes Ziffernfeld (kein Tastaturfenster, keine Anzeige der Ziffern). */
@Composable
fun PinPad(pin: String, onChange: (String) -> Unit, maxLength: Int = AdminPinStore.MAX_LENGTH) {
    val digitsEntered = pluralStringResource(R.plurals.pin_digits_entered, pin.length, pin.length)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(24.dp).semantics { contentDescription = digitsEntered }) {
            repeat(maxOf(pin.length, AdminPinStore.MIN_LENGTH)) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(
                        if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(Modifier.size(80.dp))
                        "⌫" -> IconButton(onClick = { onChange(pin.dropLast(1)) }, modifier = Modifier.size(80.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.action_delete))
                        }
                        else -> FilledTonalButton(
                            onClick = { if (pin.length < maxLength) onChange(pin + key) },
                            shape = CircleShape,
                            modifier = Modifier.size(80.dp),
                        ) { Text(key, fontSize = 28.sp) }
                    }
                }
            }
        }
    }
}

/** Übersetzte Meldung zum Prüfergebnis (null = in Ordnung). */
@Composable
fun describe(result: VerifyResult, now: Instant = Instant.now()): String? = when (result) {
    VerifyResult.Ok -> null
    VerifyResult.NotSet -> stringResource(R.string.pin_not_set)
    is VerifyResult.Wrong -> pluralStringResource(R.plurals.pin_wrong, result.attemptsBeforeLockout, result.attemptsBeforeLockout)
    is VerifyResult.LockedOut -> {
        val s = Duration.between(now, result.until).seconds.coerceAtLeast(1)
        val wait = if (s >= 60) stringResource(R.string.duration_minutes, ((s + 59) / 60).toInt()) else stringResource(R.string.duration_seconds, s.toInt())
        stringResource(R.string.pin_locked_out, wait)
    }
}

/** Meldung im PIN-Festlegen-Dialog: entweder Prüfergebnis oder feste Textressource. */
private sealed interface SetupMessage {
    data class Result(val result: VerifyResult) : SetupMessage
    data class Text(val res: Int) : SetupMessage
}

/** PIN abfragen (Wartungsmodus, kritische Aktionen – Spez. 11.3). */
@Composable
fun PinEntryDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> VerifyResult,
    onSuccess: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<VerifyResult?>(null) }
    // Sperr-Countdown aktualisieren
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(error) { while (error is VerifyResult.LockedOut) { delay(1000); now = Instant.now() } }

    RaumDialog(
        title = title,
        onDismiss = onDismiss,
        width = 460.dp,
        confirmButton = {
            TextButton(onClick = {
                val r = onSubmit(pin)
                pin = ""
                if (r == VerifyResult.Ok) onSuccess() else error = r
            }, enabled = pin.length >= AdminPinStore.MIN_LENGTH) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier) {
            PinPad(pin, { pin = it; })
            error?.let { describe(it, now) }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** PIN festlegen oder ändern: ggf. aktuelle PIN, dann neue PIN zweimal. */
@Composable
fun PinSetupDialog(
    requireCurrent: Boolean,
    onDismiss: () -> Unit,
    validate: (String) -> Boolean,
    onSave: (newPin: String, current: String?) -> VerifyResult,
    onDone: () -> Unit,
) {
    var step by remember { mutableStateOf(if (requireCurrent) 0 else 1) }
    var current by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<SetupMessage?>(null) }

    val title = stringResource(when (step) { 0 -> R.string.pin_current; 1 -> R.string.pin_new; else -> R.string.pin_repeat })
    RaumDialog(
        title = title,
        onDismiss = onDismiss,
        width = 460.dp,
        confirmButton = {
            TextButton(enabled = pin.length >= AdminPinStore.MIN_LENGTH, onClick = {
                when (step) {
                    0 -> { current = pin; step = 1; message = null }
                    1 -> if (validate(pin)) { first = pin; step = 2; message = null } else message = SetupMessage.Text(R.string.pin_format)
                    else -> if (pin != first) { message = SetupMessage.Text(R.string.pin_mismatch); step = 1 } else {
                        val r = onSave(pin, current)
                        if (r == VerifyResult.Ok) onDone() else { message = SetupMessage.Result(r); step = if (requireCurrent) 0 else 1 }
                    }
                }
                pin = ""
            }) { Text(stringResource(if (step == 2) R.string.action_save else R.string.action_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PinPad(pin, { pin = it })
            val text = when (val m = message) {
                is SetupMessage.Result -> describe(m.result)
                is SetupMessage.Text -> stringResource(m.res)
                null -> null
            }
            text?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            if (step == 1) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.pin_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
