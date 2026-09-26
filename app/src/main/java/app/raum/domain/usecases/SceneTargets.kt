package app.raum.domain.usecases

import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.RgbColor
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.find

/**
 * Zielzustand eines Geräts innerhalb einer Szene – das, was der Editor anzeigt.
 * Gespeichert wird weiterhin als Befehlsliste; [SceneTargets] übersetzt in beide Richtungen.
 */
sealed interface DeviceTarget {
    data class Light(
        val on: Boolean,
        val brightnessPercent: Int? = null,
        val colorTemperatureKelvin: Int? = null,
        val color: RgbColor? = null,
    ) : DeviceTarget

    data class Switch(val on: Boolean) : DeviceTarget

    data class Thermostat(val targetCelsius: Double, val mode: ThermostatMode? = null) : DeviceTarget

    data class Cover(val openPercent: Int) : DeviceTarget
}

object SceneTargets {

    /** Kann das Gerät Teil einer Szene sein? Sensoren und unbekannte Geräte nicht. */
    fun isSceneCapable(device: Device): Boolean = defaultFor(device) != null

    /** Befehlsfolge in ausführbarer Reihenfolge (Farbe vor Helligkeit). */
    fun toCommands(target: DeviceTarget): List<DeviceCommand> = when (target) {
        is DeviceTarget.Light ->
            if (!target.on) listOf(DeviceCommand.SetOn(false))
            else buildList {
                add(DeviceCommand.SetOn(true))
                target.colorTemperatureKelvin?.let { add(DeviceCommand.SetColorTemperature(it)) }
                target.color?.let { add(DeviceCommand.SetColor(it)) }
                target.brightnessPercent?.let { add(DeviceCommand.SetBrightness(it)) }
            }
        is DeviceTarget.Switch -> listOf(DeviceCommand.SetOn(target.on))
        is DeviceTarget.Thermostat -> buildList {
            target.mode?.let { add(DeviceCommand.SetThermostatMode(it)) }
            // Modus mitgeben: der Moduswechsel davor ist beim Gerät evtl. noch nicht zurückgemeldet
            add(DeviceCommand.SetTargetTemperature(target.targetCelsius, target.mode))
        }
        is DeviceTarget.Cover -> listOf(
            when (target.openPercent) {
                100 -> DeviceCommand.OpenCover
                0 -> DeviceCommand.CloseCover
                else -> DeviceCommand.SetCoverPosition(target.openPercent)
            }
        )
    }

    /**
     * Rekonstruiert den Zielzustand aus gespeicherten Befehlen (spätere Befehle gewinnen).
     * [device] entscheidet bei reinem Ein/Aus, ob es ein Licht oder ein Stecker ist.
     */
    fun fromCommands(commands: List<DeviceCommand>, device: Device?): DeviceTarget? {
        if (commands.isEmpty()) return null
        val isCover = commands.any {
            it is DeviceCommand.SetCoverPosition || it == DeviceCommand.OpenCover ||
                it == DeviceCommand.CloseCover || it == DeviceCommand.StopCover
        }
        val isThermostat = commands.any { it is DeviceCommand.SetTargetTemperature || it is DeviceCommand.SetThermostatMode }
        val isSwitch = device?.capabilities?.find<SwitchCapability>() != null &&
            device.capabilities.find<LightCapability>() == null

        return when {
            isCover -> commands.fold(DeviceTarget.Cover(device?.capabilities?.find<CoverCapability>()?.openPercent ?: 0)) { t, c ->
                when (c) {
                    DeviceCommand.OpenCover -> t.copy(openPercent = 100)
                    DeviceCommand.CloseCover -> t.copy(openPercent = 0)
                    is DeviceCommand.SetCoverPosition -> t.copy(openPercent = c.openPercent.coerceIn(0, 100))
                    else -> t
                }
            }
            isThermostat -> commands.fold(
                DeviceTarget.Thermostat(device?.capabilities?.find<ThermostatCapability>()?.targetCelsius ?: 20.0)
            ) { t, c ->
                when (c) {
                    is DeviceCommand.SetTargetTemperature -> t.copy(targetCelsius = c.celsius)
                    is DeviceCommand.SetThermostatMode -> t.copy(mode = c.mode)
                    else -> t
                }
            }
            isSwitch -> commands.fold(DeviceTarget.Switch(false)) { t, c ->
                if (c is DeviceCommand.SetOn) t.copy(on = c.on) else t
            }
            else -> commands.fold(DeviceTarget.Light(on = false)) { t, c ->
                when (c) {
                    is DeviceCommand.SetOn -> t.copy(on = c.on)
                    is DeviceCommand.SetBrightness -> t.copy(brightnessPercent = c.percent, on = c.percent > 0 || t.on)
                    is DeviceCommand.SetColorTemperature -> t.copy(colorTemperatureKelvin = c.kelvin, color = null, on = true)
                    is DeviceCommand.SetColor -> t.copy(color = c.color, colorTemperatureKelvin = null, on = true)
                    else -> t
                }
            }
        }
    }

    /** Übernimmt den aktuellen Zustand eines Geräts als Zielzustand (SCN-004). */
    fun capture(device: Device): DeviceTarget? {
        val caps = device.capabilities
        caps.find<LightCapability>()?.let { l ->
            if (!l.isOn) return DeviceTarget.Light(on = false)
            val useColor = l.supportsColor && (l.colorMode == LightColorMode.COLOR || !l.supportsColorTemperature)
            return DeviceTarget.Light(
                on = true,
                brightnessPercent = l.brightnessPercent,
                colorTemperatureKelvin = if (!useColor) l.colorTemperatureKelvin else null,
                color = if (useColor) l.rgbColor else null,
            )
        }
        caps.find<SwitchCapability>()?.let { return DeviceTarget.Switch(it.isOn) }
        caps.find<ThermostatCapability>()?.let { return DeviceTarget.Thermostat(it.targetCelsius, it.mode) }
        caps.find<CoverCapability>()?.let { return DeviceTarget.Cover(it.openPercent) }
        return null
    }

    /** Zielzustand beim Hinzufügen zu einer Szene: der aktuelle Zustand (SCN-004). */
    fun defaultFor(device: Device): DeviceTarget? = capture(device)

    /**
     * Voreinstellung für eine Automationsaktion: Schaltbares wird eingeschaltet
     * (den Ist-Zustand zu wiederholen wäre als Aktion meist wirkungslos).
     */
    fun defaultActionFor(device: Device): DeviceTarget? = when (val t = capture(device)) {
        is DeviceTarget.Light -> if (t.on) t else {
            val l = device.capabilities.find<LightCapability>()
            t.copy(on = true, brightnessPercent = l?.brightnessPercent)
        }
        is DeviceTarget.Switch -> t.copy(on = true)
        else -> t
    }
}
