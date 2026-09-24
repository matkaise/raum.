package app.raum.ui.settings

import app.raum.data.preferences.MatterMode
import app.raum.data.weather.WeatherCondition
import app.raum.data.weather.WeatherState
import app.raum.data.weather.WeatherService
import app.raum.data.preferences.TemperatureUnit
import app.raum.i18n.AppLanguage
import app.raum.R
import androidx.annotation.StringRes

import app.raum.data.database.RoomHomeRepository
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.network.ConnectResult
import app.raum.platform.network.NetworkController
import app.raum.platform.network.WifiNetwork
import app.raum.diagnostics.EventLog
import app.raum.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.automation.triggers.GeoLocation
import app.raum.data.database.LogFilter
import app.raum.data.database.PersistentEventLog
import app.raum.data.preferences.SettingsStore
import app.raum.data.preferences.ThemeMode
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogEntry
import app.raum.domain.models.Device
import app.raum.domain.usecases.DeviceService
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.mock.MockMatterController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class LogRange(@StringRes val labelRes: Int, val duration: Duration?) {
    HOUR(R.string.range_hour, Duration.ofHours(1)),
    DAY(R.string.range_day, Duration.ofDays(1)),
    WEEK(R.string.range_week, Duration.ofDays(7)),
    ALL(R.string.range_all, null),
}

data class LogFilterState(
    val category: LogCategory? = null,
    val deviceId: UUID? = null,
    val range: LogRange = LogRange.DAY,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val eventLog: PersistentEventLog,
    deviceService: DeviceService,
    controller: MatterController,
    private val repository: RoomHomeRepository,
    private val kiosk: KioskManager,
    val network: NetworkController,
    private val log: EventLog,
    private val strings: Strings,
    private val weather: WeatherService,
) : ViewModel() {
    // --- Wetter ---
    val weatherEnabled: StateFlow<Boolean> = settings.weatherEnabled
    val weatherState: StateFlow<WeatherState> = weather.state
    val outdoorSensorId: StateFlow<UUID?> = settings.outdoorSensorId
    val weatherPreview: StateFlow<WeatherCondition?> = weather.preview
    fun setWeatherEnabled(enabled: Boolean) = weather.setEnabled(enabled)
    fun setOutdoorSensor(id: UUID?) = settings.setOutdoorSensor(id)
    fun setWeatherPreview(c: WeatherCondition?) { weather.preview.value = c }
    suspend fun refreshWeather() = weather.refresh()

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    val location: StateFlow<GeoLocation?> = settings.location
    val locationName: StateFlow<String?> = settings.locationName

    /** Neuester Protokolleintrag – Kurzstatus in der Kategorienliste. */
    val lastLogEntry: StateFlow<LogEntry?> = eventLog.observe(LogFilter(limit = 1))
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _timeZone = MutableStateFlow(ZoneId.systemDefault().id)
    val timeZone: StateFlow<String> = _timeZone
    val canSetTimeZone: Boolean get() = kiosk.isDeviceOwner
    fun setTimeZone(id: String) { if (kiosk.setTimeZone(id)) _timeZone.value = id }

    fun renameHome(name: String) {
        val n = name.trim().ifBlank { return }
        viewModelScope.launch { repository.renameHome(n) }
    }

    /** Verbindet und wartet bis zu 20 s darauf, dass das Netz tatsächlich aktiv ist. */
    suspend fun connectWifi(target: WifiNetwork, password: String?): Boolean {
        val r = withContext(Dispatchers.IO) { network.connect(target, password) }
        if (r != ConnectResult.Ok) return false
        val ok = withTimeoutOrNull(20_000) {
            while (true) {
                network.refreshStatus()
                val st = network.status.value
                if (st.wifiConnected && (st.ssid == null || st.ssid == target.ssid)) break
                delay(500)
            }
            true
        } ?: false
        if (ok) log.info(LogCategory.SYSTEM, strings.get(R.string.log_wifi_connected, target.ssid))
        return ok
    }

    fun forgetWifi(ssid: String) {
        if (network.forget(ssid)) log.info(LogCategory.SYSTEM, strings.get(R.string.log_wifi_forgotten, ssid))
    }
    val devices: StateFlow<List<Device>> = deviceService.devices
    val isMockController: Boolean = controller is MockMatterController
    val matterMode: MatterMode = if (isMockController) MatterMode.SIMULATION else MatterMode.MATTER
    fun setMatterMode(mode: MatterMode) = settings.setMatterMode(mode)
    val fabric = controller.fabric
    val credentialStorage = controller.credentialStorage

    private val _filter = MutableStateFlow(LogFilterState())
    val filter: StateFlow<LogFilterState> = _filter

    @OptIn(ExperimentalCoroutinesApi::class)
    val logEntries: StateFlow<List<LogEntry>> = _filter.flatMapLatest { f ->
        eventLog.observe(
            LogFilter(
                category = f.category,
                deviceId = f.deviceId,
                since = f.range.duration?.let { Instant.now().minus(it) } ?: Instant.EPOCH,
                limit = 500,
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    val language: StateFlow<AppLanguage> = settings.language
    val temperatureUnit: StateFlow<TemperatureUnit> = settings.temperatureUnit
    fun setLanguage(language: AppLanguage) = settings.setLanguage(language)
    fun setTemperatureUnit(unit: TemperatureUnit) = settings.setTemperatureUnit(unit)
    fun setLocation(location: GeoLocation?, name: String?) = settings.setLocation(location, name)

    fun setCategory(c: LogCategory?) = _filter.update { it.copy(category = c) }
    fun setDevice(id: UUID?) = _filter.update { it.copy(deviceId = id) }
    fun setRange(r: LogRange) = _filter.update { it.copy(range = r) }
}
