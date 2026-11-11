package app.raum.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.homeconnect.HcBudget
import app.raum.homeconnect.HcLink
import app.raum.homeconnect.HcMode
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.labelRes
import app.raum.ui.components.QrCode
import app.raum.ui.household.hm
import app.raum.ui.household.rememberNow
import org.koin.compose.koinInject
import java.time.Duration

/** Kurzstatus für die Kategorienliste. */
@Composable
fun homeConnectSummary(service: HomeConnectService = koinInject()): String {
    val st by service.state.collectAsStateWithLifecycle()
    return when {
        st.mode == HcMode.OFF -> stringResource(R.string.hc_mode_off)
        st.mode == HcMode.DEMO -> stringResource(R.string.hc_mode_demo)
        !st.signedIn -> stringResource(R.string.hc_not_signed_in)
        st.link == HcLink.NEEDS_SIGN_IN -> stringResource(R.string.hc_link_reauth)
        else -> pluralStringResource(R.plurals.hc_connected_count, st.appliances.count { it.connected }, st.appliances.count { it.connected })
    }
}

@Composable
fun HomeConnectSection(service: HomeConnectService = koinInject()) {
    val st by service.state.collectAsStateWithLifecycle()
    val clientId by service.clientId.collectAsStateWithLifecycle()

    Group {
        Text(stringResource(R.string.hc_settings_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            HcMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = st.mode == m, onClick = { service.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, HcMode.entries.size)) {
                    Text(stringResource(m.labelRes), maxLines = 1)
                }
            }
        }
        if (st.mode == HcMode.DEMO) Text(stringResource(R.string.hc_demo_explain), style = MaterialTheme.typography.bodyMedium)
    }

    if (st.mode != HcMode.CLOUD) return

    // Zugang (Client-ID der eigenen Home-Connect-Anwendung). Ist eine fest eingebaut, bleibt das ausgeblendet –
    // dann genügt die Anmeldung mit dem normalen Home-Connect-Konto.
    var id by remember(clientId) { mutableStateOf(clientId) }
    var secret by remember { mutableStateOf("") }
    var showClient by remember { mutableStateOf(false) }
    val clientVisible = !service.hasBuiltInClient || clientId.isNotBlank() || showClient
    if (clientVisible) Group(stringResource(R.string.hc_client_title)) {
        Text(stringResource(R.string.hc_client_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(id, { id = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.hc_client_id)) },
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
        OutlinedTextField(secret, { secret = it.trim() }, singleLine = true,
            label = { Text(stringResource(if (service.hasClientSecret) R.string.hc_client_secret_set else R.string.hc_client_secret)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { service.setClient(id, secret.ifBlank { null }); secret = "" },
                enabled = id.isNotBlank() && (id != clientId || secret.isNotBlank()), modifier = Modifier.height(48.dp),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }

    Group(stringResource(R.string.hc_account_title)) {
        val code = st.signIn
        when {
            code != null -> SignInCode(code.userCode, code.verificationUriComplete ?: code.verificationUri, code.verificationUri, code.expiresAt, onCancel = service::cancelSignIn)
            st.signedIn && !st.needsReauth -> {
                Text(stringResource(R.string.hc_signed_in), style = MaterialTheme.typography.titleMedium)
                Text(linkText(st.link, st.linkError?.let { stringResource(it.failure.labelRes) }, st.retryAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = service::signOut, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Outlined.Logout, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hc_sign_out))
                }
            }
            else -> {
                if (st.needsReauth) Text(stringResource(R.string.hc_reauth_explain), color = MaterialTheme.colorScheme.error)
                else Text(stringResource(R.string.hc_signin_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = service::beginSignIn, enabled = service.canSignIn(clientId), modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Outlined.Login, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hc_sign_in))
                }
                if (!service.canSignIn(clientId)) Text(stringResource(R.string.hc_signin_no_client), color = MaterialTheme.colorScheme.error)
            }
        }
        st.signInError?.let { Text(stringResource(R.string.hc_signin_failed, it), color = MaterialTheme.colorScheme.error) }
        if (!clientVisible) TextButton(onClick = { showClient = true }) { Text(stringResource(R.string.hc_use_own_client)) }
    }

    if (st.signedIn) Group(stringResource(R.string.hc_usage_title)) {
        Info(stringResource(R.string.hc_requests_today), stringResource(R.string.hc_requests_value, st.requestsToday, HcBudget.DAILY))
        Info(stringResource(R.string.hc_appliances), st.appliances.joinToString(", ") { it.name }.ifBlank { "–" })
        Text(stringResource(R.string.hc_usage_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun linkText(link: HcLink, error: String?, retryAt: java.time.Instant?): String = when (link) {
    HcLink.LIVE -> stringResource(R.string.hc_link_live)
    HcLink.CONNECTING -> stringResource(R.string.hc_connecting)
    HcLink.RETRYING -> stringResource(R.string.hc_link_retrying, error ?: "–", retryAt?.let(::hm) ?: "–")
    HcLink.NEEDS_SIGN_IN -> stringResource(R.string.hc_link_reauth)
    HcLink.IDLE -> stringResource(R.string.hc_link_idle)
}

/** Anmeldecode mit QR-Code: auf dem Telefon scannen, mit der SingleKey ID anmelden, Code bestätigen. */
@Composable
private fun SignInCode(userCode: String, qrUri: String, uri: String, expiresAt: java.time.Instant, onCancel: () -> Unit) {
    val now = rememberNow()
    val left = Duration.between(now, expiresAt).seconds.coerceAtLeast(0)
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
            QrCode(qrUri, Modifier.size(196.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.hc_code_step1), style = MaterialTheme.typography.bodyLarge)
            Text(uri, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.hc_code_step2), style = MaterialTheme.typography.bodyLarge)
            Text(userCode, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.hc_code_waiting, left / 60, left % 60), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
