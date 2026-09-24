package app.raum.di

import app.raum.data.preferences.MatterMode
import app.raum.matter.chip.ChipMatterController
import app.raum.matter.bridge.BridgeSync
import app.raum.matter.bridge.MockMatterBridge
import app.raum.matter.bridge.ChipMatterBridge
import app.raum.matter.bridge.MatterBridge
import app.raum.ui.settings.ShareViewModel
import app.raum.BuildConfig
import app.raum.data.weather.UrlConnectionHttp
import app.raum.data.weather.WeatherService
import app.raum.data.preferences.SecurePrefsStore
import app.raum.data.preferences.SettingsStore
import app.raum.i18n.AndroidStrings
import app.raum.i18n.LocaleController
import app.raum.i18n.Strings
import app.raum.platform.display.DisplayActuator
import app.raum.platform.display.DisplayController
import app.raum.platform.display.ScreenPower
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.sensors.AmbientSensors
import app.raum.platform.service.CrashRecorder
import app.raum.security.AdminPinStore
import app.raum.security.MaintenanceSession
import java.io.File
import app.raum.CoreStartup
import app.raum.data.database.RaumDatabase
import app.raum.data.database.RoomHomeRepository
import app.raum.diagnostics.EventLog
import app.raum.automation.engine.AutomationEngine
import app.raum.automation.DynamicZoneClock
import app.raum.diagnostics.DeviceEventRecorder
import app.raum.data.database.PersistentEventLog
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.mock.MockMatterController
import app.raum.ui.HomeViewModel
import app.raum.ui.UiRequests
import app.raum.platform.network.NetworkController
import app.raum.data.geo.CityIndex
import app.raum.ui.onboarding.OnboardingViewModel
import app.raum.platform.SystemCheck
import app.raum.ui.devices.CommissioningViewModel
import app.raum.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import app.raum.ui.scenes.SceneEditorViewModel
import app.raum.ui.automations.AutomationEditorViewModel
import app.raum.ui.automations.AutomationsViewModel
import app.raum.ui.appliance.ApplianceViewModel
import app.raum.ui.appliance.DataMaintenanceViewModel
import app.raum.data.backup.BackupService
import app.raum.data.reset.ResetService
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.platform.update.UpdateInstaller
import java.util.UUID
import org.koin.dsl.module
import app.raum.platform.bluetooth.BluetoothAccess
import app.raum.security.KeystoreSecretStore
import app.raum.thread.BorderRouterDiscovery
import app.raum.thread.NetworkCredentialStore
import app.raum.thread.NsdBorderRouterDiscovery
import app.raum.thread.ThreadService
import app.raum.ui.settings.ThreadViewModel

val appModule = module {
    /** Anwendungsweiter Scope für den Core; überlebt Activity-Neustarts (M5: Foreground Service). */
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single { PersistentEventLog(get<RaumDatabase>().eventLogDao(), get()) }
    single { EventLog(sink = get<PersistentEventLog>()) }
    single { UiMessageBus() }
    single { SettingsStore(androidContext()) }
    single { LocaleController(get<SettingsStore>().language) }
    single<Strings> { AndroidStrings(androidContext(), get()) }

    // Echter Controller (Matter-SDK) oder Simulation – Einstellungen → Matter und Thread, gilt ab Neustart.
    // Beide starten leer; bekannte Geräte stellt CoreStartup aus der Datenbank wieder her (resume).
    single<MatterController> {
        when (get<SettingsStore>().matterMode) {
            MatterMode.MATTER -> ChipMatterController(androidContext(), get(), SecurePrefsStore(androidContext()), get(), get())
            MatterMode.SIMULATION -> MockMatterController(scope = get(), seed = emptyList(), fabricStore = SecurePrefsStore(androidContext()), credentials = get())
        }
    }

    single { RaumDatabase.create(androidContext()) }
    single { RoomHomeRepository(get(), get()) }
    single<HomeRepository> { get<RoomHomeRepository>() }
    single {
        val settings = get<SettingsStore>()
        AutomationEngine(
            repository = get(),
            deviceService = get(),
            sceneRunner = get(),
            messages = get(),
            log = get(),
            location = { settings.location.value },
            scope = get(),
            strings = get(),
            temperatureUnit = { settings.temperatureUnit.value },
            clock = DynamicZoneClock,
        )
    }
    single { DeviceEventRecorder(get(), get(), get(), get()) }

    // --- Appliance-Betrieb (M5) ---
    single { CrashRecorder(File(androidContext().filesDir, "last_crash.txt")) }
    single { KioskManager(androidContext()) }
    single { AmbientSensors(androidContext()) }
    single { ScreenPower(androidContext(), get()) }
    single {
        val sensors = get<AmbientSensors>()
        val screen = get<ScreenPower>()
        DisplayController(
            settings = get<SettingsStore>().display,
            lux = sensors.lux,
            proximityNear = sensors.near,
            scope = get(),
            canTurnOff = { screen.canTurnOff },
        )
    }
    single { DisplayActuator(get(), get(), get(), get()) }
    single { AdminPinStore(SecurePrefsStore(androidContext())) }
    single { MaintenanceSession() }

    // --- Stabilisierung (M7) ---
    single { BackupService(get(), get(), get(), get(), get()) }
    single { ResetService(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { UpdateInstaller(androidContext(), get(), get()) }

    // --- Erstinbetriebnahme ---
    single { SystemCheck(androidContext(), get(), get()) }
    single { UiRequests() }
    single { NetworkController(androidContext(), get()) }
    // --- Thread und neue Geräte (M6) ---
    single { NetworkCredentialStore(KeystoreSecretStore(androidContext()), SecurePrefsStore(androidContext())) }
    single { BluetoothAccess(androidContext(), get()) }
    single<BorderRouterDiscovery> { NsdBorderRouterDiscovery(androidContext(), get()) }
    single { ThreadService(androidContext(), get(), get(), get(), get(), get(), get()) }
    // raum. als Matter-Bridge – bis M2 simuliert (Kopplungen dauerhaft gespeichert)
    single<MatterBridge> {
        when (get<SettingsStore>().matterMode) {
            MatterMode.MATTER -> ChipMatterBridge(androidContext(), SecurePrefsStore(androidContext()))
            // Test: echte Bridge mit den simulierten Geräten (nur Debug, nur mit Markierungsdatei) – so lassen sich
            // Thermostat- und Storenbefehle über die Bridge prüfen, ohne echte Geräte zu bewegen
            MatterMode.SIMULATION ->
                if (BuildConfig.DEBUG && File(androidContext().filesDir, "bridge-real-in-simulation").exists()) {
                    ChipMatterBridge(androidContext(), SecurePrefsStore(androidContext()))
                } else {
                    MockMatterBridge(SecurePrefsStore(androidContext()))
                }
        }
    }
    single { BridgeSync(get(), get(), get<SettingsStore>(), get(), get()) }
    // Wetter (MET Norway, freiwillig). Nutzungsbedingungen: eindeutiger User-Agent mit Kontakt.
    single {
        val contact = BuildConfig.WEATHER_CONTACT.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        WeatherService(
            settings = get<SettingsStore>(), cacheFile = File(androidContext().filesDir, "weather.json"), http = UrlConnectionHttp(),
            userAgent = "raum-panel/${BuildConfig.VERSION_NAME}$contact", log = get(), strings = get(),
        )
    }
    single { CityIndex { androidContext().assets.open(CityIndex.ASSET) } }

    single { CoreStartup(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single { DeviceService(get(), get(), get(), get(), get(), get()) }
    single { SceneRunner(get(), get(), get(), get()) }

    viewModelOf(::HomeViewModel)
    viewModelOf(::CommissioningViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::AutomationsViewModel)
    viewModelOf(::ApplianceViewModel)
    viewModelOf(::DataMaintenanceViewModel)
    viewModelOf(::OnboardingViewModel)
    viewModelOf(::ShareViewModel)
    viewModelOf(::ThreadViewModel)
    viewModel { params -> AutomationEditorViewModel(params.getOrNull<UUID>(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { params -> SceneEditorViewModel(params.getOrNull<UUID>(), get(), get(), get(), get(), get(), get()) }
}
