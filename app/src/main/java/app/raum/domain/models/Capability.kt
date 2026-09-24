package app.raum.domain.models

import kotlinx.serialization.Serializable

/**
 * raum. Capability Layer (Spez. 5.4).
 *
 * Die UI arbeitet ausschließlich mit diesen Fähigkeiten – nie mit Matter-Cluster-IDs.
 * Der Matter-Adapter übersetzt Endpoints/Cluster in diese Modelle.
 */
sealed interface Capability

@Serializable
data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    val argb: Long get() = 0xFF000000L or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
}

data class LightCapability(
    val isOn: Boolean,
    /** null = nicht dimmbar */
    val brightnessPercent: Int? = null,
    /** null = keine Farbtemperatur */
    val colorTemperatureKelvin: Int? = null,
    val colorTemperatureRange: IntRange? = null,
    /** null = keine Farbe */
    val rgbColor: RgbColor? = null,
    /** Welcher Farbwert gerade wirkt (Matter ColorControl.ColorMode); null = Gerät ohne Farbe. */
    val colorMode: LightColorMode? = null,
) : Capability {
    val isDimmable: Boolean get() = brightnessPercent != null
    val supportsColorTemperature: Boolean get() = colorTemperatureKelvin != null
    val supportsColor: Boolean get() = rgbColor != null
}

/** Zwischenstecker / Relais (On/Off Plug-in Unit), optional mit Leistungsmessung. */
data class SwitchCapability(
    val isOn: Boolean,
    val powerWatts: Double? = null,
    val energyKwh: Double? = null,
) : Capability

/** Aktiver Farbmodus einer Leuchte. */
enum class LightColorMode { TEMPERATURE, COLOR }

@Serializable
enum class ThermostatMode { OFF, HEAT, COOL, AUTO }

data class ThermostatCapability(
    val currentCelsius: Double?,
    val targetCelsius: Double,
    val mode: ThermostatMode,
    val supportedModes: Set<ThermostatMode> = setOf(ThermostatMode.OFF, ThermostatMode.HEAT),
    val minTargetCelsius: Double = 5.0,
    val maxTargetCelsius: Double = 30.0,
) : Capability

enum class CoverMovement { STOPPED, OPENING, CLOSING }

/**
 * Rollladen/Storen. Bewusst als "Prozent offen" modelliert
 * (Matter WindowCovering verwendet intern "Prozent geschlossen").
 */
data class CoverCapability(
    val openPercent: Int,
    val movement: CoverMovement = CoverMovement.STOPPED,
) : Capability

data class ContactSensorCapability(val isOpen: Boolean) : Capability

data class OccupancyCapability(val isOccupied: Boolean) : Capability

data class TemperatureSensorCapability(val celsius: Double) : Capability

data class HumiditySensorCapability(val percent: Double) : Capability

data class BatteryCapability(val percent: Int) : Capability

/** Unbekanntes Gerät: nur sicher lesbare Basisinformationen, keine Schreibbefehle (Spez. 6.3). */
data class UnknownCapability(val deviceTypeIds: List<Long>) : Capability

inline fun <reified T : Capability> List<Capability>.find(): T? = filterIsInstance<T>().firstOrNull()
