package app.raum

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.raum.data.preferences.SettingsStore
import app.raum.platform.display.DisplayController
import app.raum.platform.display.DisplayMode
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.service.CoreService
import app.raum.security.MaintenanceSession
import app.raum.ui.RaumRoot
import app.raum.ui.theme.RaumTheme
import app.raum.ui.components.LocalTemperatureUnit
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import app.raum.i18n.LocaleController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import org.koin.java.KoinJavaComponent
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val settings: SettingsStore by inject()
    private val display: DisplayController by inject()
    private val kiosk: KioskManager by inject()
    private val maintenance: MaintenanceSession by inject()

    /** Berührung, die das Display geweckt hat, wird komplett verschluckt (bis zum Loslassen). */
    private var swallowGesture = false

    /** Sprache der App (ONB-003) – vor allem anderen, damit alle Texte in der gewählten Sprache erscheinen. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoinJavaComponent.get<LocaleController>(LocaleController::class.java).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoreService.start(this)
        enableEdgeToEdge()
        hideSystemBars()
        // Das Panel regelt Ruhezustand selbst (DisplayController) – kein System-Timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    display.brightness.collect { b ->
                        window.attributes = window.attributes.apply { screenBrightness = b.coerceIn(0.01f, 1f) }
                    }
                }
                launch {
                    combine(settings.kioskEnabled, maintenance.kioskPaused) { enabled, paused -> enabled && !paused }
                        .collect { lock -> if (lock) kiosk.enterLockTask(this@MainActivity) else kiosk.exitLockTask(this@MainActivity) }
                }
                launch {
                    while (true) { maintenance.checkTimeout(); delay(10_000) }
                }
                launch {
                    // Sprachwechsel: Oberfläche neu aufbauen (Kern und Automationen laufen im Dienst weiter)
                    settings.language.drop(1).collect { recreate() }
                }
            }
        }

        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val unit by settings.temperatureUnit.collectAsStateWithLifecycle()
            RaumTheme(themeMode) {
                CompositionLocalProvider(LocalTemperatureUnit provides unit) { RaumRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        display.wake()
        if (settings.kioskEnabled.value && !maintenance.kioskPaused.value) kiosk.enterLockTask(this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowGesture = display.mode.value != DisplayMode.ACTIVE
            display.wake()
            maintenance.touch()
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        display.userActivity()
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** SYS-005: Systemleisten ausblenden (zusätzlich zum Lock Task als Device Owner). */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
