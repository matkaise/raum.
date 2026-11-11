package app.raum.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.R
import app.raum.grohe.GroheAuth
import app.raum.grohe.GroheMode
import app.raum.grohe.GroheService
import app.raum.grohe.labelRes
import app.raum.ui.components.KeepSystemBarsHidden
import app.raum.ui.household.hm
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun groheSummary(service: GroheService = koinInject()): String {
    val st by service.state.collectAsStateWithLifecycle()
    return when {
        st.mode == GroheMode.OFF -> stringResource(R.string.hc_mode_off)
        st.mode == GroheMode.DEMO -> stringResource(R.string.hc_mode_demo)
        !st.signedIn -> stringResource(R.string.hc_not_signed_in)
        st.needsReauth -> stringResource(R.string.hc_link_reauth)
        else -> st.devices.joinToString(", ") { it.name }.ifBlank { stringResource(R.string.grohe_signed_in) }
    }
}

@Composable
fun GroheSection(service: GroheService = koinInject()) {
    val st by service.state.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf(false) }

    Group {
        Text(stringResource(R.string.grohe_settings_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            GroheMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = st.mode == m, onClick = { service.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, GroheMode.entries.size)) {
                    Text(stringResource(m.labelRes), maxLines = 1)
                }
            }
        }
        if (st.mode == GroheMode.DEMO) Text(stringResource(R.string.grohe_demo_explain), style = MaterialTheme.typography.bodyMedium)
    }
    if (st.mode != GroheMode.CLOUD) return

    Group(stringResource(R.string.hc_account_title)) {
        if (st.signedIn && !st.needsReauth) {
            Text(stringResource(R.string.grohe_signed_in), style = MaterialTheme.typography.titleMedium)
            Info(stringResource(R.string.hc_appliances), st.devices.joinToString(", ") { it.name }.ifBlank { "–" })
            st.updatedAt?.let { Info(stringResource(R.string.grohe_updated), hm(it)) }
            st.error?.let { Text(stringResource(R.string.grohe_error, it.detail ?: it.kind.name), color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = service::signOut, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Outlined.Logout, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.hc_sign_out))
            }
        } else {
            Text(stringResource(if (st.needsReauth) R.string.grohe_reauth_explain else R.string.grohe_signin_explain),
                style = MaterialTheme.typography.bodyMedium, color = if (st.needsReauth) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { login = true }, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Outlined.Login, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.grohe_sign_in))
            }
        }
    }
    if (login) GroheLoginDialog(service) { login = false }
}

/**
 * Grohes eigene Login-Seite im Panel. Das Passwort tippt der Nutzer dort ein – raum. liest es nicht mit. Abgefangen
 * wird nur die Weiterleitung „ondus://…“ am Ende; mit ihr und den Sitzungs-Cookies holt raum. die Tokens.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GroheLoginDialog(service: GroheService, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.grohe_signin_failed)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        KeepSystemBarsHidden()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(900.dp).height(700.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.grohe_sign_in), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            // Frische Sitzung: alte Grohe-Cookies verwerfen
                            CookieManager.getInstance().removeAllCookies(null)
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val url = request.url.toString()
                                        if (!url.startsWith("ondus://")) return false
                                        val cookies = CookieManager.getInstance().getCookie("https://idp2-apigw.cloud.grohe.com")
                                        busy = true
                                        scope.launch {
                                            service.completeLogin(url, cookies)
                                                .onSuccess { onClose() }
                                                .onFailure { error = failed + (it.message?.let { m -> " – $m" } ?: ""); busy = false }
                                        }
                                        return true
                                    }
                                }
                                loadUrl(GroheAuth.LOGIN_URL)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
