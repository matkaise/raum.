package app.raum.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.raum.ui.household.iconFor
import app.raum.ui.theme.LocalGlass
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
        !st.signedIn -> stringResource(R.string.hc_not_signed_in)
        st.link == HcLink.NEEDS_SIGN_IN -> stringResource(R.string.hc_link_reauth)
        else -> pluralStringResource(R.plurals.hc_connected_count, st.appliances.count { it.connected }, st.appliances.count { it.connected })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeConnectSection(service: HomeConnectService = koinInject()) {
    val g = LocalGlass.current
    val st by service.state.collectAsStateWithLifecycle()
    val clientId by service.clientId.collectAsStateWithLifecycle()

    ServiceHero(
        Icons.Outlined.Kitchen, Color(0xFFFFF3DC), Color(0xFF9A6424),
        stringResource(R.string.hc_hero_title), stringResource(R.string.hc_hero_sub),
        info = stringResource(R.string.hc_settings_explain),
    ) {
        Seg(listOf(HcMode.OFF to stringResource(R.string.state_off), HcMode.CLOUD to stringResource(R.string.state_on)), st.mode, Modifier.width(240.dp)) { service.setMode(it) }
    }

    if (st.mode != HcMode.CLOUD) return

    // Eigene Client-ID: nur nötig, wenn keine fest eingebaut ist – sonst unter „Erweitert“
    var id by remember(clientId) { mutableStateOf(clientId) }
    var secret by remember { mutableStateOf("") }
    val clientGroup: @Composable () -> Unit = {
        Group(stringResource(R.string.hc_client_title), info = stringResource(R.string.hc_client_explain)) {
            OutlinedTextField(id, { id = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.hc_client_id)) },
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
            OutlinedTextField(secret, { secret = it.trim() }, singleLine = true,
                label = { Text(stringResource(if (service.hasClientSecret) R.string.hc_client_secret_set else R.string.hc_client_secret)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth())
            ActionPill(stringResource(R.string.action_save), primary = true, enabled = id.isNotBlank() && (id != clientId || secret.isNotBlank())) {
                service.setClient(id, secret.ifBlank { null }); secret = ""
            }
        }
    }
    val needsClient = !service.hasBuiltInClient && clientId.isBlank()
    if (needsClient) clientGroup()

    val code = st.signIn
    when {
        code != null -> Group(stringResource(R.string.hc_account)) {
            SignInCode(code.userCode, code.verificationUriComplete ?: code.verificationUri, code.verificationUri, code.expiresAt, onCancel = service::cancelSignIn)
        }
        st.signedIn && !st.needsReauth -> {
            if (st.appliances.isNotEmpty()) Group(stringResource(R.string.hc_appliances)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.appliances.forEach { a ->
                        Row(
                            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(g.ink.copy(alpha = 0.06f)).padding(start = 6.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(g.glassSolid), contentAlignment = Alignment.Center) {
                                Icon(iconFor(a.type), null, Modifier.size(18.dp), tint = if (a.connected) g.ink else g.soft)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(a.name, color = if (a.connected) g.ink else g.soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Tageskontingent als Ring
                Group(modifier = Modifier.weight(1f), info = stringResource(R.string.hc_usage_explain)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        UsageRing(st.requestsToday, HcBudget.DAILY)
                        Column {
                            Text(stringResource(R.string.hc_requests_today), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.hc_requests_value, st.requestsToday, HcBudget.DAILY), color = g.soft, fontSize = 14.sp)
                        }
                    }
                }
                Group(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconDisk(Icons.Outlined.AccountCircle, g.ink.copy(alpha = 0.07f), g.ink, 48)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.hc_signed_in), color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(linkText(st.link, st.linkError?.let { stringResource(it.failure.labelRes) }, st.retryAt), color = g.soft, fontSize = 13.sp, maxLines = 2)
                        }
                        ActionPill(stringResource(R.string.hc_sign_out), onClick = service::signOut)
                    }
                }
            }
        }
        else -> Group(stringResource(R.string.hc_account), info = stringResource(R.string.hc_signin_explain)) {
            if (st.needsReauth) Text(stringResource(R.string.hc_reauth_explain), color = Color(0xFFB3261E))
            ActionPill(stringResource(R.string.hc_sign_in), Icons.Outlined.Login, primary = true, enabled = service.canSignIn(clientId), onClick = service::beginSignIn)
            if (!service.canSignIn(clientId)) Text(stringResource(R.string.hc_signin_no_client), color = Color(0xFFB3261E))
        }
    }
    st.signInError?.let { Text(stringResource(R.string.hc_signin_failed, it), color = Color(0xFFFFD2CC), modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) }
    if (!needsClient) Advanced { clientGroup() }
}

/** Anfragen heute als kleiner Ring (grün, ab 80 % gelb). */
@Composable
private fun UsageRing(used: Int, total: Int) {
    val g = LocalGlass.current
    val f = (used.toFloat() / total).coerceIn(0f, 1f)
    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            drawCircle(g.ink.copy(alpha = 0.08f), size.minDimension / 2 - w / 2, style = Stroke(w))
            if (f > 0f) drawArc(if (f < 0.8f) Color(0xFF7FD19A) else Color(0xFFFFBE6E), -90f, 360f * f, false,
                topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("$used", color = g.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
