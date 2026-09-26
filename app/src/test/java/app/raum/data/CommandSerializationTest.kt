package app.raum.data

import app.raum.data.database.decodeCommand
import app.raum.data.database.encodeCommand
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.RgbColor
import app.raum.domain.models.ThermostatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Das JSON-Format ist Teil der Datenbank (und später der Backups) – Änderungen brechen Bestandsdaten. */
class CommandSerializationTest {

    @Test
    fun `stored format is stable`() {
        assertEquals("""{"type":"on","on":true}""", encodeCommand(DeviceCommand.SetOn(true)))
        assertEquals("""{"type":"brightness","percent":40}""", encodeCommand(DeviceCommand.SetBrightness(40)))
        assertEquals("""{"type":"cover_open"}""", encodeCommand(DeviceCommand.OpenCover))
        assertEquals("""{"type":"thermostat_mode","mode":"HEAT"}""", encodeCommand(DeviceCommand.SetThermostatMode(ThermostatMode.HEAT)))
    }

    @Test
    fun `all commands round-trip`() {
        listOf(
            DeviceCommand.SetOn(false),
            DeviceCommand.SetBrightness(1),
            DeviceCommand.SetColorTemperature(2700),
            DeviceCommand.SetColor(RgbColor(10, 20, 30)),
            DeviceCommand.SetTargetTemperature(21.5),
            DeviceCommand.SetTargetTemperature(24.0, ThermostatMode.COOL),
            DeviceCommand.SetThermostatMode(ThermostatMode.AUTO),
            DeviceCommand.SetCoverPosition(55),
            DeviceCommand.OpenCover,
            DeviceCommand.CloseCover,
            DeviceCommand.StopCover,
        ).forEach { assertEquals(it, decodeCommand(encodeCommand(it))) }
    }

    @Test
    fun `unknown command type from newer version is skipped`() {
        assertNull(decodeCommand("""{"type":"lock_door"}"""))
        assertNull(decodeCommand("kaputt"))
    }

    @Test
    fun `unknown fields are ignored`() {
        assertEquals(DeviceCommand.SetOn(true), decodeCommand("""{"type":"on","on":true,"transitionMs":400}"""))
    }

    @Test
    fun `stored thermostat setpoints without mode still load`() {
        assertEquals(DeviceCommand.SetTargetTemperature(21.0), decodeCommand("""{"type":"target_temperature","celsius":21.0}"""))
        assertEquals("""{"type":"target_temperature","celsius":21.0}""", encodeCommand(DeviceCommand.SetTargetTemperature(21.0)))
    }
}
