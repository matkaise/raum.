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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.raum.ui.theme.LocalGlass
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
        !st.signedIn -> stringResource(R.string.hc_not_signed_in)
        st.needsReauth -> stringResource(R.string.hc_link_reauth)
        else -> st.devices.joinToString(", ") { it.name }.ifBlank { stringResource(R.string.grohe_signed_in) }
    }
}

@Composable
fun GroheSection(service: GroheService = koinInject()) {
    val st by service.state.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf(false) }

    val g = LocalGlass.current
    ServiceHero(
        Icons.Outlined.WaterDrop, Color(0xFFE3F4FC), Color(0xFF1D6F99),
        stringResource(R.string.settings_grohe), stringResource(R.string.grohe_hero_sub),
        info = stringResource(R.string.grohe_settings_explain),
    ) {
        Seg(listOf(GroheMode.OFF to stringResource(R.string.state_off), GroheMode.CLOUD to stringResource(R.string.state_on)), st.mode, Modifier.width(240.dp)) { service.setMode(it) }
    }
    if (st.mode != GroheMode.CLOUD) return

    if (st.signedIn && !st.needsReauth) {
        Group(stringResource(R.string.hc_account)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconDisk(Icons.Outlined.AccountCircle, g.ink.copy(alpha = 0.07f), g.ink, 48)
                Column(Modifier.weight(1f)) {
                    Text(st.devices.joinToString(", ") { it.name }.ifBlank { stringResource(R.string.grohe_signed_in) }, color = g.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(stringResource(R.string.grohe_signed_in), st.updatedAt?.let { stringResource(R.string.grohe_updated) + " " + hm(it) }).joinToString(" · "),
                        color = g.soft, fontSize = 13.sp,
                    )
                }
                ActionPill(stringResource(R.string.hc_sign_out), onClick = service::signOut)
            }
            st.error?.let { Text(stringResource(R.string.grohe_error, it.detail ?: it.kind.name), color = Color(0xFFB3261E)) }
        }
    } else {
        Group(stringResource(R.string.hc_account), info = stringResource(R.string.grohe_signin_explain)) {
            if (st.needsReauth) Text(stringResource(R.string.grohe_reauth_explain), color = Color(0xFFB3261E))
            ActionPill(stringResource(R.string.grohe_sign_in), Icons.Outlined.Login, primary = true) { login = true }
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
