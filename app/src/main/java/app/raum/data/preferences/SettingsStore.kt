package app.raum.data.preferences

import java.util.UUID
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import app.raum.R
import app.raum.automation.triggers.GeoLocation
import app.raum.i18n.AppLanguage
import app.raum.platform.display.DisplaySettings
import app.raum.platform.display.SleepAction
import app.raum.security.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark)
}

/** Maßeinheit für Temperaturen (ONB-003). Intern wird immer in °C gerechnet und gespeichert. */
enum class TemperatureUnit(@StringRes val labelRes: Int) { CELSIUS(R.string.unit_celsius), FAHRENHEIT(R.string.unit_fahrenheit) }

/** Welcher Matter-Controller läuft – gilt ab dem nächsten Start. */
enum class MatterMode { MATTER, SIMULATION }

/** Einfache, nicht-sensible Anzeigeeinstellungen. Sensible Daten gehören in den Keystore (Spez. 10.3). */
class SettingsStore(context: Context) : app.raum.data.weather.WeatherPrefs, app.raum.matter.bridge.BridgePrefs {
    private val prefs = context.getSharedPreferences("raum_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(
        runCatching { AppLanguage.valueOf(prefs.getString(KEY_LANGUAGE, null) ?: "") }.getOrDefault(AppLanguage.SYSTEM)
    )
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        _language.value = language
    }

    private val _temperatureUnit = MutableStateFlow(
        runCatching { TemperatureUnit.valueOf(prefs.getString(KEY_TEMP_UNIT, null) ?: "") }.getOrDefault(TemperatureUnit.CELSIUS)
    )
    val temperatureUnit: StateFlow<TemperatureUnit> = _temperatureUnit.asStateFlow()

    fun setTemperatureUnit(unit: TemperatureUnit) {
        prefs.edit().putString(KEY_TEMP_UNIT, unit.name).apply()
        _temperatureUnit.value = unit
    }

    // --- Erstinbetriebnahme (ONB) ---------------------------------------------------

    private val _onboardingCompleted = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    /** Synchron gespeichert: danach wird sofort die normale Oberfläche angezeigt. */
    @SuppressLint("ApplySharedPref")
    fun setOnboardingCompleted(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done)
            .remove(KEY_ONBOARDING_STEP).remove(KEY_ONBOARDING_NAME).remove(KEY_ONBOARDING_RESTORED).commit()
        _onboardingCompleted.value = done
    }

    /** Fortschritt, damit ein Neustart mitten in der Einrichtung dort weitermacht. */
    var onboardingStep: String?
        get() = prefs.getString(KEY_ONBOARDING_STEP, null)
        set(v) { prefs.edit().putString(KEY_ONBOARDING_STEP, v).apply() }

    /** Einrichtung erneut verlangen, obwohl ein Zuhause existiert (Übergabe an neue Bewohner, RST-005). */
    @SuppressLint("ApplySharedPref")
    fun restartOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, false).putString(KEY_ONBOARDING_STEP, "WELCOME")
            .remove(KEY_ONBOARDING_NAME).remove(KEY_ONBOARDING_RESTORED).commit()
        _onboardingCompleted.value = false
    }

    /** Sicherung wurde in der laufenden Einrichtung übernommen (übersteht einen Neustart). */
    var onboardingRestored: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_RESTORED, false)
        set(v) { prefs.edit().putBoolean(KEY_ONBOARDING_RESTORED, v).apply() }

    var onboardingHomeName: String?
        get() = prefs.getString(KEY_ONBOARDING_NAME, null)
        set(v) { prefs.edit().putString(KEY_ONBOARDING_NAME, v).apply() }

    /** Manuell hinterlegter Standort für Sonnenauf-/-untergang (Spez. 7.8). */
    private val _location = MutableStateFlow(readLocation())
    override val location: StateFlow<GeoLocation?> = _location.asStateFlow()

    /** Wetter von MET Norway (Internet, freiwillig) – ab Werk aus. */
    private val _weatherEnabled = MutableStateFlow(prefs.getBoolean(KEY_WEATHER, false))
    override val weatherEnabled: StateFlow<Boolean> = _weatherEnabled.asStateFlow()
    override fun setWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEATHER, enabled).apply()
        _weatherEnabled.value = enabled
    }

    /**
     * Betriebsart. Neue Installationen steuern echte Geräte; Installationen aus der Zeit vor M2
     * (Einrichtung schon abgeschlossen) behalten die Simulation mit ihrer Beispielwohnung.
     */
    val matterMode: MatterMode
        get() = prefs.getString(KEY_MATTER_MODE, null)?.let { runCatching { MatterMode.valueOf(it) }.getOrNull() }
            ?: (if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)) MatterMode.SIMULATION else MatterMode.MATTER)
                .also { setMatterMode(it) }

    /** Synchron, denn direkt danach wird neu gestartet. */
    @SuppressLint("ApplySharedPref")
    fun setMatterMode(mode: MatterMode) { prefs.edit().putString(KEY_MATTER_MODE, mode.name).commit() }

    /** raum. als Matter-Bridge für Apple Home, Google Home, Alexa … */
    private val _bridgeEnabled = MutableStateFlow(prefs.getBoolean(KEY_BRIDGE, false))
    override val bridgeEnabled: StateFlow<Boolean> = _bridgeEnabled.asStateFlow()
    fun setBridgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRIDGE, enabled).apply()
        _bridgeEnabled.value = enabled
    }

    private val _bridgeExcluded = MutableStateFlow(
        prefs.getStringSet(KEY_BRIDGE_EXCLUDED, emptySet()).orEmpty().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
    )
    override val bridgeExcluded: StateFlow<Set<UUID>> = _bridgeExcluded.asStateFlow()
    fun setBridgeExcluded(ids: Set<UUID>) {
        prefs.edit().putStringSet(KEY_BRIDGE_EXCLUDED, ids.map(UUID::toString).toSet()).apply()
        _bridgeExcluded.value = ids
    }

    /** Matter-Temperatursensor, der draußen hängt (Geräte-ID) – ersetzt die Vorhersage-Temperatur. */
    private val _outdoorSensorId = MutableStateFlow(prefs.getString(KEY_OUTDOOR_SENSOR, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() })
    val outdoorSensorId: StateFlow<UUID?> = _outdoorSensorId.asStateFlow()
    fun setOutdoorSensor(id: UUID?) {
        prefs.edit().putString(KEY_OUTDOOR_SENSOR, id?.toString()).apply()
        _outdoorSensorId.value = id
    }

    /** Anzeigename des Standorts (aus der Ortssuche), null bei manuellen Koordinaten. */
    private val _locationName = MutableStateFlow(prefs.getString(KEY_LOCATION_NAME, null))
    val locationName: StateFlow<String?> = _locationName.asStateFlow()

    fun setLocation(location: GeoLocation?, name: String? = null) {
        prefs.edit().apply {
            if (location == null) remove(KEY_LAT).remove(KEY_LON).remove(KEY_LOCATION_NAME)
            else putString(KEY_LAT, location.latitude.toString()).putString(KEY_LON, location.longitude.toString())
                .putString(KEY_LOCATION_NAME, name)
        }.apply()
        _location.value = location
        _locationName.value = if (location == null) null else name
    }

    private fun readLocation(): GeoLocation? = runCatching {
        GeoLocation(prefs.getString(KEY_LAT, null)!!.toDouble(), prefs.getString(KEY_LON, null)!!.toDouble())
    }.getOrNull()

    private val _display = MutableStateFlow(readDisplay())
    val display: StateFlow<DisplaySettings> = _display.asStateFlow()

    fun updateDisplay(transform: (DisplaySettings) -> DisplaySettings) {
        val next = transform(_display.value)
        prefs.edit()
            .putInt(KEY_SLEEP_AFTER, next.sleepAfterSeconds ?: -1)
            .putString(KEY_SLEEP_ACTION, next.sleepAction.name)
            .putBoolean(KEY_PROX_WAKE, next.proximityWake)
            .putBoolean(KEY_AUTO_BRIGHTNESS, next.autoBrightness)
            .putFloat(KEY_MANUAL_BRIGHTNESS, next.manualBrightness)
            .putFloat(KEY_MIN_BRIGHTNESS, next.minBrightness)
            .apply()
        _display.value = next
    }

    private fun readDisplay(): DisplaySettings {
        val d = DisplaySettings()
        return d.copy(
            sleepAfterSeconds = prefs.getInt(KEY_SLEEP_AFTER, d.sleepAfterSeconds ?: -1).takeIf { it > 0 },
            sleepAction = runCatching { SleepAction.valueOf(prefs.getString(KEY_SLEEP_ACTION, null)!!) }.getOrDefault(d.sleepAction),
            proximityWake = prefs.getBoolean(KEY_PROX_WAKE, d.proximityWake),
            autoBrightness = prefs.getBoolean(KEY_AUTO_BRIGHTNESS, d.autoBrightness),
            manualBrightness = prefs.getFloat(KEY_MANUAL_BRIGHTNESS, d.manualBrightness),
            minBrightness = prefs.getFloat(KEY_MIN_BRIGHTNESS, d.minBrightness),
        )
    }

    /** Kiosk-Modus aktiv (nur wirksam als Device Owner). */
    private val _kioskEnabled = MutableStateFlow(prefs.getBoolean(KEY_KIOSK, true))
    val kioskEnabled: StateFlow<Boolean> = _kioskEnabled.asStateFlow()

    fun setKioskEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KIOSK, enabled).apply()
        _kioskEnabled.value = enabled
    }

    /** Sicherungsrelevante Einstellungen (BAK-001). */
    fun toBackup(): app.raum.data.backup.SettingsDto {
        val d = display.value
        return app.raum.data.backup.SettingsDto(
            themeMode = themeMode.value.name,
            latitude = location.value?.latitude,
            longitude = location.value?.longitude,
            locationName = locationName.value,
            weatherEnabled = weatherEnabled.value,
            outdoorSensorId = outdoorSensorId.value?.toString(),
            sleepAfterSeconds = d.sleepAfterSeconds ?: -1,
            sleepAction = d.sleepAction.name,
            proximityWake = d.proximityWake,
            autoBrightness = d.autoBrightness,
            manualBrightness = d.manualBrightness,
            minBrightness = d.minBrightness,
            language = language.value.name,
            temperatureUnit = temperatureUnit.value.name,
        )
    }

    fun restoreFrom(s: app.raum.data.backup.SettingsDto) {
        s.themeMode?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }?.let(::setThemeMode)
        s.language?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }?.let(::setLanguage)
        s.temperatureUnit?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }?.let(::setTemperatureUnit)
        if (s.latitude != null && s.longitude != null) runCatching { GeoLocation(s.latitude, s.longitude) }.getOrNull()?.let { setLocation(it, s.locationName) }
        s.weatherEnabled?.let(::setWeatherEnabled)
        s.outdoorSensorId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(::setOutdoorSensor)
        updateDisplay { d ->
            d.copy(
                // -1 steht in der Sicherung für „nie“; fehlt der Wert, bleibt die aktuelle Einstellung
                sleepAfterSeconds = if (s.sleepAfterSeconds == null) d.sleepAfterSeconds else s.sleepAfterSeconds.takeIf { it > 0 },
                sleepAction = s.sleepAction?.let { runCatching { SleepAction.valueOf(it) }.getOrNull() } ?: d.sleepAction,
                proximityWake = s.proximityWake ?: d.proximityWake,
                autoBrightness = s.autoBrightness ?: d.autoBrightness,
                manualBrightness = s.manualBrightness ?: d.manualBrightness,
                minBrightness = s.minBrightness ?: d.minBrightness,
            )
        }
    }

    /** Werksreset: alle Einstellungen verwerfen (synchron – direkt danach folgt der Prozessneustart). */
    @SuppressLint("ApplySharedPref")
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_ONBOARDING_DONE = "onboarding_completed"
        const val KEY_ONBOARDING_STEP = "onboarding_step"
        const val KEY_ONBOARDING_NAME = "onboarding_home_name"
        const val KEY_ONBOARDING_RESTORED = "onboarding_restored"
        const val KEY_LANGUAGE = "language"
        const val KEY_TEMP_UNIT = "temperature_unit"
        const val KEY_LAT = "location_lat"
        const val KEY_LON = "location_lon"
        const val KEY_LOCATION_NAME = "location_name"
        const val KEY_WEATHER = "weather_enabled"
        const val KEY_MATTER_MODE = "matter_mode"
        const val KEY_BRIDGE = "bridge_enabled"
        const val KEY_BRIDGE_EXCLUDED = "bridge_excluded"
        const val KEY_OUTDOOR_SENSOR = "outdoor_sensor"
        const val KEY_SLEEP_AFTER = "display_sleep_after"
        const val KEY_SLEEP_ACTION = "display_sleep_action"
        const val KEY_PROX_WAKE = "display_proximity_wake"
        const val KEY_AUTO_BRIGHTNESS = "display_auto_brightness"
        const val KEY_MANUAL_BRIGHTNESS = "display_manual_brightness"
        const val KEY_MIN_BRIGHTNESS = "display_min_brightness"
        const val KEY_KIOSK = "kiosk_enabled"
    }
}

/** Getrennte Datei für Sicherheitswerte (PIN-Hash, Fehlversuche). Wird von Backups ausgenommen. */
class SecurePrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("raum_security", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    // commit statt apply: Fehlversuche müssen einen sofortigen Neustart überstehen.
    @SuppressLint("ApplySharedPref")
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
}
