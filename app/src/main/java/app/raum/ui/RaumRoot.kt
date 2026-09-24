package app.raum.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.raum.CoreStartup
import app.raum.StartupStep
import app.raum.data.preferences.SettingsStore
import app.raum.platform.display.DisplayController
import app.raum.platform.display.DisplayMode
import app.raum.ui.appliance.ScreensaverOverlay
import app.raum.ui.appliance.StartupOverlay
import app.raum.ui.onboarding.OnboardingScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Wurzel der Oberfläche: Startbildschirm (SYS-008) → Einrichtung (ONB) → App.
 * Der Ruhebildschirm liegt über allem, auch über der Einrichtung.
 */
@Composable
fun RaumRoot(
    startup: CoreStartup = koinInject(),
    settings: SettingsStore = koinInject(),
    display: DisplayController = koinInject(),
) {
    val step by startup.step.collectAsStateWithLifecycle()
    val onboarded by settings.onboardingCompleted.collectAsStateWithLifecycle()

    // Surface setzt LocalContentColor passend zum Hell-/Dunkel-Schema
    // imePadding: Edge-to-Edge verkleinert das Fenster nicht – Eingabefelder sollen über der Tastatur bleiben
    Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.background) {
        when {
            step != StartupStep.READY -> StartupOverlay(step)
            !onboarded -> OnboardingScreen()
            else -> RaumApp()
        }
    }

    val displayMode by display.mode.collectAsStateWithLifecycle()
    val lights = if (onboarded && step == StartupStep.READY) {
        val home: HomeViewModel = koinViewModel()
        val state by home.state.collectAsStateWithLifecycle()
        lightsStatus(state.lightsOn)
    } else ""
    ScreensaverOverlay(visible = displayMode != DisplayMode.ACTIVE, statusLine = lights)
}
