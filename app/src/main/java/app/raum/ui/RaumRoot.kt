package app.raum.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import app.raum.R
import app.raum.ui.components.formatDegrees
import app.raum.ui.overview.LocalSkyScene
import app.raum.ui.overview.SkyBackdrop
import app.raum.ui.theme.SkyContentTheme
import app.raum.ui.overview.climateOf
import app.raum.ui.overview.rememberSkyScene
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

    val ready = onboarded && step == StartupStep.READY
    val home: HomeViewModel? = if (ready) koinViewModel() else null
    val state = home?.state?.collectAsStateWithLifecycle()?.value
    // Himmel einmal für Start, Einrichtung, App und Ruhebildschirm
    val sky = rememberSkyScene(state ?: HomeUiState())

    CompositionLocalProvider(LocalSkyScene provides sky) {
        // Surface setzt LocalContentColor passend zum Hell-/Dunkel-Schema
        // imePadding: Edge-to-Edge verkleinert das Fenster nicht – Eingabefelder sollen über der Tastatur bleiben
        Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.background) {
            // Die App zeichnet ihren Himmel selbst (mit Wetteranimation je Bildschirm)
            if (!ready) SkyBackdrop(sky, animate = true)
            SkyContentTheme {
                when {
                    step != StartupStep.READY -> StartupOverlay(step)
                    !onboarded -> OnboardingScreen()
                    else -> RaumApp()
                }
            }
        }

        val displayMode by display.mode.collectAsStateWithLifecycle()
        val status = if (state != null) {
            val indoor = climateOf(state, sky.outdoorSensorId).celsius
            listOfNotNull(
                sky.outdoorCelsius?.let { "${stringResource(R.string.hero_outdoor)} ${formatDegrees(it)}" },
                indoor?.let { "${stringResource(R.string.hero_indoor)} ${formatDegrees(it)}" },
                lightsStatus(state.lightsOn),
            )
        } else emptyList()
        ScreensaverOverlay(visible = displayMode != DisplayMode.ACTIVE, status = status, sky = { SkyBackdrop(sky, animate = false) })
    }
}
