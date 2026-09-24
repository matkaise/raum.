package app.raum.domain.usecases

import app.raum.domain.models.Capability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability

/**
 * Wendet einen Befehl rein funktional auf eine Capability-Liste an.
 * Genutzt für die optimistische Darstellung (CTRL-001) und vom Mock-Controller.
 */
object CapabilityReducer {

    fun supports(capabilities: List<Capability>, command: DeviceCommand): Boolean =
        capabilities.any { cap ->
            when (command) {
                is DeviceCommand.SetOn -> cap is LightCapability || cap is SwitchCapability
                is DeviceCommand.SetBrightness -> cap is LightCapability && cap.isDimmable
                is DeviceCommand.SetColorTemperature -> cap is LightCapability && cap.supportsColorTemperature
                is DeviceCommand.SetColor -> cap is LightCapability && cap.supportsColor
                is DeviceCommand.SetTargetTemperature -> cap is ThermostatCapability
                is DeviceCommand.SetThermostatMode -> cap is ThermostatCapability && command.mode in cap.supportedModes
                is DeviceCommand.SetCoverPosition,
                DeviceCommand.OpenCover,
                DeviceCommand.CloseCover,
                DeviceCommand.StopCover -> cap is CoverCapability
            }
        }

    fun apply(capabilities: List<Capability>, command: DeviceCommand): List<Capability> =
        capabilities.map { cap -> applyTo(cap, command) }

    private fun applyTo(cap: Capability, command: DeviceCommand): Capability = when (cap) {
        is LightCapability -> when (command) {
            is DeviceCommand.SetOn -> cap.copy(isOn = command.on)
            is DeviceCommand.SetBrightness ->
                if (cap.isDimmable) {
                    val p = command.percent.coerceIn(0, 100)
                    cap.copy(brightnessPercent = if (p == 0) cap.brightnessPercent else p, isOn = p > 0)
                } else cap
            is DeviceCommand.SetColorTemperature -> cap.colorTemperatureRange?.let { range ->
                cap.copy(colorTemperatureKelvin = command.kelvin.coerceIn(range), colorMode = LightColorMode.TEMPERATURE, isOn = true)
            } ?: cap
            is DeviceCommand.SetColor -> if (cap.supportsColor) cap.copy(rgbColor = command.color, colorMode = LightColorMode.COLOR, isOn = true) else cap
            else -> cap
        }
        is SwitchCapability -> when (command) {
            is DeviceCommand.SetOn -> cap.copy(isOn = command.on, powerWatts = if (command.on) cap.powerWatts else cap.powerWatts?.let { 0.0 })
            else -> cap
        }
        is ThermostatCapability -> when (command) {
            is DeviceCommand.SetTargetTemperature ->
                cap.copy(targetCelsius = command.celsius.coerceIn(cap.minTargetCelsius, cap.maxTargetCelsius))
            is DeviceCommand.SetThermostatMode ->
                if (command.mode in cap.supportedModes) cap.copy(mode = command.mode) else cap
            else -> cap
        }
        is CoverCapability -> when (command) {
            is DeviceCommand.SetCoverPosition -> {
                val target = command.openPercent.coerceIn(0, 100)
                cap.copy(
                    movement = when {
                        target > cap.openPercent -> CoverMovement.OPENING
                        target < cap.openPercent -> CoverMovement.CLOSING
                        else -> CoverMovement.STOPPED
                    }
                )
            }
            DeviceCommand.OpenCover -> if (cap.openPercent < 100) cap.copy(movement = CoverMovement.OPENING) else cap
            DeviceCommand.CloseCover -> if (cap.openPercent > 0) cap.copy(movement = CoverMovement.CLOSING) else cap
            DeviceCommand.StopCover -> cap.copy(movement = CoverMovement.STOPPED)
            else -> cap
        }
        else -> cap
    }
}
