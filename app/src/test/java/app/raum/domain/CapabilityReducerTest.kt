package app.raum.domain

import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.usecases.CapabilityReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReducerTest {

    @Test
    fun `brightness turns light on and is clamped`() {
        val caps = listOf(LightCapability(false, 30))
        val result = CapabilityReducer.apply(caps, DeviceCommand.SetBrightness(150)).single() as LightCapability
        assertTrue(result.isOn)
        assertEquals(100, result.brightnessPercent)
    }

    @Test
    fun `brightness command is not supported by on-off light`() {
        assertFalse(CapabilityReducer.supports(listOf(LightCapability(true)), DeviceCommand.SetBrightness(50)))
        assertTrue(CapabilityReducer.supports(listOf(LightCapability(true)), DeviceCommand.SetOn(false)))
    }

    @Test
    fun `color temperature is clamped to device range`() {
        val caps = listOf(LightCapability(true, 50, 3000, 2700..6500))
        val result = CapabilityReducer.apply(caps, DeviceCommand.SetColorTemperature(1000)).single() as LightCapability
        assertEquals(2700, result.colorTemperatureKelvin)
    }

    @Test
    fun `thermostat target is clamped and unsupported modes are ignored`() {
        val caps = listOf(ThermostatCapability(20.0, 21.0, ThermostatMode.HEAT, minTargetCelsius = 5.0, maxTargetCelsius = 30.0))
        val t = CapabilityReducer.apply(caps, DeviceCommand.SetTargetTemperature(40.0)).single() as ThermostatCapability
        assertEquals(30.0, t.targetCelsius, 0.0)
        assertFalse(CapabilityReducer.supports(caps, DeviceCommand.SetThermostatMode(ThermostatMode.COOL)))
    }

    @Test
    fun `cover movement follows target direction`() {
        val caps = listOf(CoverCapability(40))
        assertEquals(CoverMovement.OPENING, (CapabilityReducer.apply(caps, DeviceCommand.SetCoverPosition(80)).single() as CoverCapability).movement)
        assertEquals(CoverMovement.CLOSING, (CapabilityReducer.apply(caps, DeviceCommand.CloseCover).single() as CoverCapability).movement)
        assertEquals(CoverMovement.STOPPED, (CapabilityReducer.apply(caps, DeviceCommand.SetCoverPosition(40)).single() as CoverCapability).movement)
    }

    @Test
    fun `switch reports zero power when turned off`() {
        val s = CapabilityReducer.apply(listOf(SwitchCapability(true, 40.0)), DeviceCommand.SetOn(false)).single() as SwitchCapability
        assertFalse(s.isOn)
        assertEquals(0.0, s.powerWatts!!, 0.0)
    }
}
