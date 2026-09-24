package app.raum

import app.raum.matter.bridge.BridgeSync
import app.raum.data.weather.WeatherService
import app.raum.i18n.Strings
import androidx.annotation.StringRes

import app.raum.automation.engine.AutomationEngine
import app.raum.data.preferences.SettingsStore
import app.raum.diagnostics.DeviceEventRecorder
import app.raum.data.database.RoomHomeRepository
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.mock.MockMatterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import app.raum.thread.ThreadService
import app.raum.platform.display.DisplayActuator
import app.raum.platform.display.DisplayController
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.service.CrashRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Start des Cores unabhängig von der Oberfläche (SYS-003).
 * Datenbank initialisieren, Automations-Engine starten. M5 verlagert das in einen Foreground Service.
 */
enum class StartupStep(@StringRes val labelRes: Int) {
    DATABASE(R.string.startup_database),
    CONTROLLER(R.string.startup_controller),
    AUTOMATIONS(R.string.startup_automations),
    READY(R.string.startup_ready),
}

class CoreStartup(
    private val repository: RoomHomeRepository,
    private val controller: MatterController,
    private val log: EventLog,
    private val scope: CoroutineScope,
    private val automationEngine: AutomationEngine,
    private val settings: SettingsStore,
    private val deviceEvents: DeviceEventRecorder,
    private val crashRecorder: CrashRecorder,
    private val kiosk: KioskManager,
    private val display: DisplayController,
    private val displayActuator: DisplayActuator,
    private val strings: Strings,
    private val weather: WeatherService,
    private val bridgeSync: BridgeSync,
    private val thread: ThreadService,
) {
    private val _step = MutableStateFlow(StartupStep.DATABASE)
    /** Fortschritt für den Startbildschirm (SYS-008). */
    val step: StateFlow<StartupStep> = _step.asStateFlow()

    private val started = AtomicBoolean(false)

    /** Idempotent: wird von Application und CoreService aufgerufen. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            crashRecorder.takeLastCrash()?.let {
                log.error(LogCategory.SYSTEM, strings.get(R.string.log_restart_after_crash, it))
            }
            kiosk.applyPolicies()
            val mock = controller as? MockMatterController
            // Ein Zuhause legt erst die Einrichtung an (ONB-001). Bestehende Installationen (vor der
            // Einrichtung angelegt) gelten als eingerichtet.
            // Nur ohne gespeicherten Einrichtungsschritt – sonst würde ein Neustart direkt nach einer
            // Wiederherstellung die restlichen Schritte (PIN, Prüfung, Fabric) überspringen.
            if (!settings.onboardingCompleted.value && settings.onboardingStep == null && repository.hasHome()) {
                settings.setOnboardingCompleted(true)
            }
            // Installationen von vor der Einrichtung haben noch keine Fabric
            if (settings.onboardingCompleted.value && controller.fabric.value == null) controller.ensureFabric()
            _step.value = StartupStep.CONTROLLER

            // Der Mock hält Geräte nur im Speicher – bekannte Nodes wiederherstellen.
            controller.resume(repository.knownNodeIds())
            log.info(LogCategory.SYSTEM, strings.get(if (mock != null) R.string.log_started_mock else R.string.log_started))
            _step.value = StartupStep.AUTOMATIONS
            deviceEvents.start()
            weather.start(scope)
            bridgeSync.start(scope)
            // Border Router suchen (Statushinweise) – nach den Abos, damit Namensauflösungen nicht konkurrieren
            scope.launch { delay(20_000); thread.scanBriefly() }
            automationEngine.start()
            display.start()
            displayActuator.start()
            _step.value = StartupStep.READY
        }
    }
}
