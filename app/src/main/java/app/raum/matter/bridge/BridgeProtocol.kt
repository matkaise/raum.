package app.raum.matter.bridge

import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.Device
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.find
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Gerätearten der Bridge – muss zu raum::Kind in native/raum-bridge/src/BridgedDevice.h passen. */
enum class BridgeKind(val code: Int) {
    ON_OFF_LIGHT(0), DIMMABLE_LIGHT(1), ON_OFF_PLUG(2), CONTACT_SENSOR(3), OCCUPANCY_SENSOR(4),
    TEMPERATURE_SENSOR(5), HUMIDITY_SENSOR(6), TEMP_HUMIDITY_SENSOR(7), THERMOSTAT(8), COVER(9),
    /** Extended Color Light: Farbe und (falls vorhanden) Farbtemperatur */
    COLOR_LIGHT(10),
    /** Color Temperature Light: nur Weißton */
    COLOR_TEMP_LIGHT(11),
}

/** Ein freigegebenes Gerät, wie es der Bridge-Prozess braucht (Stammdaten + aktueller Zustand). */
@Serializable
data class BridgeEntry(
    val endpoint: Int,
    val kind: Int,
    /** BridgedDeviceBasicInformation › UniqueID (≤ 32 Zeichen, bleibt über Umbenennungen gleich) */
    val uniqueId: String,
    val name: String,
    val vendor: String,
    val product: String,
    val reachable: Boolean,
    val on: Boolean = false,
    /** 1–254, -1 = ohne */
    val level: Int = -1,
    /** BooleanState: true = Kontakt geschlossen */
    val contact: Boolean = false,
    val occupied: Boolean = false,
    /** 0,01 °C; Int.MIN_VALUE = ohne */
    val tempC100: Int = Int.MIN_VALUE,
    /** 0,01 %; -1 = ohne */
    val humidity100: Int = -1,
    /** Thermostat: Bit 0 Heizen, Bit 1 Kühlen, Bit 2 Auto; Grenzen und Sollwert in 0,01 °C */
    val flags: Int = 0,
    val minC100: Int = 500,
    val maxC100: Int = 3000,
    val setpointC100: Int = Int.MIN_VALUE,
    /** Matter SystemModeEnum; -1 = ohne */
    val systemMode: Int = -1,
    /** Storen, Matter-Sicht: 0 = offen, 10000 = zu; -1 = ohne */
    val coverClosed100ths: Int = -1,
    /** 0 steht, 1 öffnet, 2 schließt */
    val coverMovement: Int = 0,
    /** Leuchte: 0 Farbe, 2 Farbtemperatur, -1 ohne; Farbton/Sättigung 0–254, xy 0–65279, Mired */
    val colorMode: Int = -1,
    val hue: Int = -1,
    val saturation: Int = -1,
    val colorX: Int = -1,
    val colorY: Int = -1,
    val mireds: Int = -1,
)

/** Übersetzung raum. ↔ Bridge (rein funktional, getestet). */
object BridgeMapping {

    /** null = Gerät lässt sich nicht über die Bridge teilen (keine bekannten Fähigkeiten). */
    fun kind(d: Device): BridgeKind? {
        val c = d.capabilities
        c.find<LightCapability>()?.let {
            return when {
                it.supportsColor -> BridgeKind.COLOR_LIGHT
                it.supportsColorTemperature -> BridgeKind.COLOR_TEMP_LIGHT
                it.isDimmable -> BridgeKind.DIMMABLE_LIGHT
                else -> BridgeKind.ON_OFF_LIGHT
            }
        }
        if (c.find<ThermostatCapability>() != null) return BridgeKind.THERMOSTAT
        if (c.find<CoverCapability>() != null) return BridgeKind.COVER
        if (c.find<SwitchCapability>() != null) return BridgeKind.ON_OFF_PLUG
        if (c.find<ContactSensorCapability>() != null) return BridgeKind.CONTACT_SENSOR
        if (c.find<OccupancyCapability>() != null) return BridgeKind.OCCUPANCY_SENSOR
        val temp = c.find<TemperatureSensorCapability>() != null
        val hum = c.find<HumiditySensorCapability>() != null
        return when {
            temp && hum -> BridgeKind.TEMP_HUMIDITY_SENSOR
            temp -> BridgeKind.TEMPERATURE_SENSOR
            hum -> BridgeKind.HUMIDITY_SENSOR
            else -> null
        }
    }

    fun entry(d: Device, endpoint: Int, kind: BridgeKind): BridgeEntry {
        val c = d.capabilities
        val light = c.find<LightCapability>()
        val switch = c.find<SwitchCapability>()
        val thermostat = c.find<ThermostatCapability>()
        val cover = c.find<CoverCapability>()
        return BridgeEntry(
            endpoint = endpoint,
            kind = kind.code,
            uniqueId = d.id.toString().replace("-", ""),
            name = utf8Prefix(d.displayName, 32),
            vendor = utf8Prefix(d.vendorName.orEmpty(), 32),
            product = utf8Prefix(d.productName.orEmpty(), 32),
            reachable = d.isOnline,
            on = light?.isOn ?: switch?.isOn ?: false,
            level = light?.brightnessPercent?.let(::percentToLevel) ?: -1,
            contact = c.find<ContactSensorCapability>()?.let { !it.isOpen } ?: false,
            occupied = c.find<OccupancyCapability>()?.isOccupied ?: false,
            tempC100 = thermostat?.let { t -> t.currentCelsius?.let { (it * 100).roundToInt() } ?: Int.MIN_VALUE }
                ?: c.find<TemperatureSensorCapability>()?.let { (it.celsius * 100).roundToInt() } ?: Int.MIN_VALUE,
            humidity100 = c.find<HumiditySensorCapability>()?.let { (it.percent * 100).roundToInt().coerceIn(0, 10000) } ?: -1,
            flags = thermostat?.let(::thermostatFlags) ?: light?.let(::lightFlags) ?: 0,
            // Leuchte: Farbtemperatur-Bereich in Mired (warm = viele Mired)
            minC100 = thermostat?.let { (it.minTargetCelsius * 100).roundToInt() }
                ?: light?.let { ColorMath.kelvinToMireds(lightRange(it).last) } ?: 500,
            maxC100 = thermostat?.let { (it.maxTargetCelsius * 100).roundToInt() }
                ?: light?.let { ColorMath.kelvinToMireds(lightRange(it).first) } ?: 3000,
            setpointC100 = thermostat?.let { (it.targetCelsius * 100).roundToInt() } ?: Int.MIN_VALUE,
            systemMode = thermostat?.let { toMatterMode(it.mode) } ?: -1,
            coverClosed100ths = cover?.let { (100 - it.openPercent.coerceIn(0, 100)) * 100 } ?: -1,
            coverMovement = when (cover?.movement) {
                CoverMovement.OPENING -> 1
                CoverMovement.CLOSING -> 2
                else -> 0
            },
            colorMode = when {
                light == null -> -1
                light.colorMode == LightColorMode.TEMPERATURE -> 2
                light.colorMode == LightColorMode.COLOR || light.supportsColor -> 0
                light.supportsColorTemperature -> 2
                else -> -1
            },
            hue = light?.rgbColor?.let { app.raum.matter.chip.ClusterMapper.rgbToHueSat(it).first.toInt() } ?: -1,
            saturation = light?.rgbColor?.let { app.raum.matter.chip.ClusterMapper.rgbToHueSat(it).second.toInt() } ?: -1,
            colorX = light?.rgbColor?.let { ColorMath.rgbToXy(it).first } ?: -1,
            colorY = light?.rgbColor?.let { ColorMath.rgbToXy(it).second } ?: -1,
            mireds = light?.colorTemperatureKelvin?.let(ColorMath::kelvinToMireds) ?: -1,
        )
    }

    private fun lightFlags(l: LightCapability): Int =
        (if (l.supportsColor) 0x8 else 0) or (if (l.supportsColorTemperature) 0x10 else 0)

    /** Farbtemperatur-Bereich des Geräts in Kelvin (Matter-Standard 2000–6500 K, falls unbekannt) */
    private fun lightRange(l: LightCapability): IntRange = l.colorTemperatureRange ?: 2000..6500

    /** Farbe aus Farbton/Sättigung (0–254) */
    fun hueSatToRgb(hue: Int, saturation: Int) =
        app.raum.matter.chip.ClusterMapper.hsvToRgb(hue.coerceIn(0, 254) * 360.0 / 254, saturation.coerceIn(0, 254) / 254.0)

    private fun thermostatFlags(t: ThermostatCapability): Int {
        var flags = 0
        if (ThermostatMode.HEAT in t.supportedModes) flags = flags or 0x1
        if (ThermostatMode.COOL in t.supportedModes) flags = flags or 0x2
        if (ThermostatMode.AUTO in t.supportedModes) flags = flags or 0x4
        return if (flags and 0x3 == 0) flags or 0x1 else flags // ohne Angabe: Heizen
    }

    /** raum. ↔ Matter SystemModeEnum (Aus 0, Auto 1, Kühlen 3, Heizen 4) */
    fun toMatterMode(mode: ThermostatMode): Int = when (mode) {
        ThermostatMode.OFF -> 0
        ThermostatMode.AUTO -> 1
        ThermostatMode.COOL -> 3
        ThermostatMode.HEAT -> 4
    }

    fun fromMatterMode(mode: Int): ThermostatMode? = when (mode) {
        0 -> ThermostatMode.OFF
        1 -> ThermostatMode.AUTO
        3 -> ThermostatMode.COOL
        4 -> ThermostatMode.HEAT
        else -> null
    }

    /** Matter-Level (1–254) ↔ Prozent */
    fun percentToLevel(percent: Int): Int = (percent.coerceIn(1, 100) * 254 / 100.0).roundToInt().coerceIn(1, 254)
    fun levelToPercent(level: Int): Int = (level.coerceIn(1, 254) * 100 / 254.0).roundToInt().coerceIn(1, 100)

    /** Höchstens [maxBytes] UTF-8-Bytes, ohne ein Zeichen zu zerschneiden (Matter-Strings sind längenbegrenzt). */
    fun utf8Prefix(s: String, maxBytes: Int): String {
        if (s.encodeToByteArray().size <= maxBytes) return s
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).encodeToByteArray().size
            if (bytes + n > maxBytes) break
            out.appendCodePoint(cp); bytes += n; i += Character.charCount(cp)
        }
        return out.toString()
    }
}

/** Nachrichten zwischen raum. (Hauptprozess) und dem Bridge-Prozess (Messenger). */
internal object BridgeMessages {
    // raum. → Bridge
    const val REGISTER = 1
    const val SET_DEVICES = 2   // data: json = List<BridgeEntry>
    const val OPEN_WINDOW = 3   // arg1 = Sekunden
    const val CLOSE_WINDOW = 4
    const val REMOVE_FABRIC = 5 // arg1 = Fabric-Index
    const val RESET = 6
    // Bridge → raum.
    const val STATE = 10        // data: running, fabrics (json), windowOpen
    const val WINDOW = 11       // data: passcode, discriminator, seconds; arg1 = 1 ok / 0 Fehler
    const val COMMAND = 12      // arg1 = Endpunkt, data: type (onoff|level), value

    const val KEY_JSON = "json"
    const val KEY_RUNNING = "running"
    const val KEY_FABRICS = "fabrics"
    const val KEY_WINDOW_OPEN = "windowOpen"
    const val KEY_ATTESTATION = "attestation"
    const val KEY_PASSCODE = "passcode"
    const val KEY_DISCRIMINATOR = "discriminator"
    const val KEY_SECONDS = "seconds"
    const val KEY_TYPE = "type"
    const val KEY_VALUE = "value"

    const val TYPE_ONOFF = "onoff"
    const val TYPE_LEVEL = "level"
    /** Thermostat/Storen: Art in KEY_CMD */
    const val TYPE_OTHER = "other"
    const val KEY_CMD = "cmd"

    // Befehlsarten – müssen zu raum::CommandType (native/raum-bridge/src/RaumClusters.h) passen
    const val CMD_SETPOINT = 10     // Wert: 0,01 °C
    const val CMD_SYSTEM_MODE = 11  // Wert: Matter SystemModeEnum
    const val CMD_COVER_OPEN = 20
    const val CMD_COVER_CLOSE = 21
    const val CMD_COVER_STOP = 22
    const val CMD_COVER_GOTO = 23   // Wert: Prozent offen
    const val CMD_COLOR_HS = 30     // Wert: Farbton << 8 | Sättigung
    const val CMD_COLOR_XY = 31     // Wert: X << 16 | Y (vorzeichenlos)
    const val CMD_COLOR_TEMP = 32   // Wert: Mired
}

/** Eine verbundene App laut Fabric-Tabelle der Bridge. */
@Serializable
data class BridgeFabric(val index: Int, val vendorId: Int, val label: String = "")
