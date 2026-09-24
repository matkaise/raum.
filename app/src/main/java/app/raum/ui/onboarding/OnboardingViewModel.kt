package app.raum.ui.onboarding

import app.raum.data.weather.WeatherService
import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.R
import app.raum.automation.triggers.GeoLocation
import app.raum.data.backup.BackupService
import app.raum.data.backup.RestorePlan
import app.raum.data.database.InitialHomeData
import app.raum.data.database.RoomHomeRepository
import app.raum.data.preferences.SettingsStore
import app.raum.data.preferences.TemperatureUnit
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.i18n.AppLanguage
import app.raum.i18n.ErrorTexts
import app.raum.i18n.Strings
import app.raum.matter.controller.FabricInfo
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.mock.LocalizedDemo
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import app.raum.platform.CheckItem
import app.raum.platform.SystemCheck
import app.raum.platform.kiosk.KioskManager
import app.raum.security.AdminPinStore
import app.raum.ui.UiRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val path: OnboardingPath = OnboardingPath.NEW,
    val homeName: String = "",
    val timeZone: String = ZoneId.systemDefault().id,
    val pinSet: Boolean = false,
    val checks: List<CheckItem> = emptyList(),
    val fabric: FabricInfo? = null,
    val restorePlan: RestorePlan? = null,
    val restored: Boolean = false,
    /** Zuhause existiert bereits (nach „Übergabe vorbereiten“). */
    val existingHome: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Geführte Erstinbetriebnahme (Spez. 7.2, ONB-001 – ONB-006). */
class OnboardingViewModel(
    private val app: Application,
    private val settings: SettingsStore,
    private val repository: RoomHomeRepository,
    private val controller: MatterController,
    private val pins: AdminPinStore,
    private val kiosk: KioskManager,
    private val systemCheck: SystemCheck,
    private val backup: BackupService,
    private val requests: UiRequests,
    private val log: EventLog,
    private val strings: Strings,
    private val weather: WeatherService,
) : ViewModel() {
    val weatherEnabled: StateFlow<Boolean> = settings.weatherEnabled
    fun setWeatherEnabled(enabled: Boolean) = weather.setEnabled(enabled)


    val isMock: Boolean = controller is MockMatterController
    val canSetTimeZone: Boolean get() = kiosk.isDeviceOwner
    val language: StateFlow<AppLanguage> = settings.language
    val temperatureUnit: StateFlow<TemperatureUnit> = settings.temperatureUnit
    val location: StateFlow<GeoLocation?> = settings.location

    private val _state = MutableStateFlow(
        OnboardingState(
            // Nach einem Neustart dort weitermachen, wo die Einrichtung unterbrochen wurde
            step = settings.onboardingStep?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() } ?: OnboardingStep.WELCOME,
            path = if (settings.onboardingStep == OnboardingStep.RESTORE.name) OnboardingPath.RESTORE else OnboardingPath.NEW,
            // Leer = Standardname der beim Abschluss gewählten Sprache (Platzhalter im Feld)
            homeName = settings.onboardingHomeName ?: "",
            pinSet = pins.isSet,
            fabric = controller.fabric.value,
        )
    )
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when {
                // Wiederherstellung bereits erfolgt (Neustart zwischen Wiederherstellung und Abschluss)
                settings.onboardingRestored -> _state.update { it.copy(restored = true, path = OnboardingPath.RESTORE) }
                // Übergabe: Zuhause und Räume sind noch da – Name vorschlagen, keine Beispielwohnung
                else -> repository.homeName()?.let { existing ->
                    _state.update { it.copy(existingHome = true, homeName = settings.onboardingHomeName ?: existing) }
                }
            }
        }
        if (state.value.step == OnboardingStep.CHECK) runChecks()
    }

    private fun goTo(step: OnboardingStep) {
        settings.onboardingStep = step.name
        _state.update { it.copy(step = step, error = null) }
        when (step) {
            OnboardingStep.CHECK -> runChecks()
            OnboardingStep.FABRIC -> createFabric()
            else -> Unit
        }
    }

    fun next() = goTo(OnboardingFlow.next(state.value.step, state.value.path))
    fun back() = goTo(OnboardingFlow.previous(state.value.step, state.value.path))

    // --- Willkommen -------------------------------------------------------------

    fun setLanguage(language: AppLanguage) {
        // Sprache sofort anwenden (Activity wird neu aufgebaut, Fortschritt bleibt erhalten)
        settings.onboardingStep = state.value.step.name
        settings.setLanguage(language)
    }

    fun choose(path: OnboardingPath) {
        _state.update { it.copy(path = path) }
        goTo(if (path == OnboardingPath.NEW) OnboardingStep.HOME else OnboardingStep.RESTORE)
    }

    // --- Zuhause und Region ---------------------------------------------------------

    fun setHomeName(name: String) {
        val v = name.take(40)
        settings.onboardingHomeName = v
        _state.update { it.copy(homeName = v) }
    }

    fun setTimeZone(zoneId: String) {
        if (kiosk.setTimeZone(zoneId)) _state.update { it.copy(timeZone = zoneId) }
    }

    fun setTemperatureUnit(unit: TemperatureUnit) = settings.setTemperatureUnit(unit)
    val locationName: StateFlow<String?> = settings.locationName
    fun setLocation(location: GeoLocation?, name: String?) = settings.setLocation(location, name)

    // --- PIN (optional) ------------------------------------------------------------

    fun pinValid(pin: String) = pins.isValidFormat(pin)

    fun setPin(pin: String): Boolean {
        if (!pins.isValidFormat(pin) || pins.isSet) return false
        val ok = pins.setPin(pin) == AdminPinStore.VerifyResult.Ok
        if (ok) {
            log.info(LogCategory.SYSTEM, strings.get(R.string.log_pin_set))
            _state.update { it.copy(pinSet = true) }
            next()
        }
        return ok
    }

    fun skipPin() {
        log.warning(LogCategory.SYSTEM, strings.get(R.string.log_pin_skipped))
        next()
    }

    // --- Prüfung und Fabric ------------------------------------------------------------

    fun runChecks() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.Default) { systemCheck.run() }
            _state.update { it.copy(checks = items) }
        }
    }

    private fun createFabric() {
        if (state.value.fabric != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val info = controller.ensureFabric()
                log.info(LogCategory.SYSTEM, strings.get(R.string.log_fabric_created, "%016X".format(info.fabricId.toLong())))
                _state.update { it.copy(fabric = info) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun retryFabric() = createFabric()

    // --- Wiederherstellen aus Sicherung ---------------------------------------------------

    fun inspectBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val bytes = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: error("unreadable")
                _state.update { it.copy(restorePlan = backup.inspect(bytes, password)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = ErrorTexts.maintenance(e, strings) ?: strings.get(R.string.error_unexpected, e.message ?: "")) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissPlan() = _state.update { it.copy(restorePlan = null) }

    fun restore() {
        val plan = state.value.restorePlan ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, restorePlan = null) }
            try {
                // Fortschritt zuerst sichern: nach der Wiederherstellung existiert ein Zuhause
                settings.onboardingStep = OnboardingStep.RESTORE.name
                backup.restore(plan)
                settings.onboardingRestored = true
                controller.resume(repository.knownNodeIds())
                _state.update { it.copy(restored = true, homeName = plan.home.name) }
                goTo(OnboardingStep.REGION)
            } catch (e: Exception) {
                _state.update { it.copy(error = strings.get(R.string.error_unexpected, e.message ?: "")) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // --- Abschluss ---------------------------------------------------------------

    /**
     * @param demo Beispielwohnung anlegen (nur Mock-Controller)
     * @param addDevice danach direkt „Gerät hinzufügen“ öffnen
     */
    fun finish(demo: Boolean, addDevice: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (!state.value.restored) {
                val name = state.value.homeName.trim().ifBlank { strings.get(R.string.home_default_name) }
                val initial = if (demo && isMock) LocalizedDemo.initialData(strings) else InitialHomeData()
                if (state.value.existingHome) repository.renameHome(name)
                else repository.initializeIfEmpty(name, initial)
                if (demo && isMock && !state.value.existingHome) (controller as MockMatterController).adoptNodes(MockHomeSeed.devices.map { it.nodeId })
            }
            log.info(LogCategory.SYSTEM, strings.get(R.string.log_onboarding_done))
            if (addDevice) requests.openCommissioning.value = true
            settings.setOnboardingCompleted(true)
        }
    }
}
