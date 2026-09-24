package app.raum.ui.components

import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.raum.platform.display.DisplayController
import app.raum.security.MaintenanceSession
import org.koin.compose.koinInject

/**
 * Dialoge und Bottom Sheets laufen in eigenen Fenstern und würden die Systemleisten wieder
 * einblenden. Im Panel-Betrieb (SYS-005) bleiben sie auch dort verborgen.
 * Innerhalb des Dialog-/Sheet-Inhalts aufrufen. Meldet außerdem Berührungen als Nutzeraktivität.
 */
@Composable
fun KeepSystemBarsHidden() {
    val view = LocalView.current
    val display = koinInject<DisplayController>()
    val maintenance = koinInject<MaintenanceSession>()
    DisposableEffect(view) {
        val window = view.findDialogWindow()
        val original = window?.callback
        if (window != null && original != null) {
            hideSystemBars(window)
            // Berührungen in Dialogen erreichen die Activity nicht – Aktivität hier melden,
            // sonst schläft das Display mitten in einer Eingabe ein.
            window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) { display.userActivity(); maintenance.touch() }
                    return original.dispatchTouchEvent(event)
                }
            }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}

fun hideSystemBars(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

private fun View.findDialogWindow(): Window? {
    var v: Any? = this
    while (v is View) {
        if (v is DialogWindowProvider) return v.window
        v = v.parent
    }
    return null
}

/** [androidx.compose.material3.AlertDialog] mit verborgenen Systemleisten. */
@Composable
fun RaumAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = {
            KeepSystemBarsHidden()
            title?.invoke()
        },
        text = text,
    )
}
