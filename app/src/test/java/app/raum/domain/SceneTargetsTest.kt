package app.raum.domain

import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.OnlineState
import app.raum.domain.models.RgbColor
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.Capability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.usecases.DeviceTarget
import app.raum.domain.usecases.SceneTargets
import app.raum.matter.controller.mock.MockHomeSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SceneTargetsTest {

    private fun device(vararg caps: Capability) = Device(
        UUID.randomUUID(), 1u, "Test", null, null, null, OnlineState.ONLINE, false, null, caps.toList(),
    )

    @Test
    fun `light target round-trips through commands`() {
        val target = DeviceTarget.Light(on = true, brightnessPercent = 40, colorTemperatureKelvin = 3000)
        val cmds = SceneTargets.toCommands(target)
        assertEquals(DeviceCommand.SetOn(true), cmds.first())
        assertEquals(DeviceCommand.SetBrightness(40), cmds.last()) // Helligkeit zuletzt
        assertEquals(target, SceneTargets.fromCommands(cmds, device(LightCapability(true, 10, 2700, 2200..6500))))
    }

    @Test
    fun `light off produces a single command`() {
        assertEquals(listOf(DeviceCommand.SetOn(false)), SceneTargets.toCommands(DeviceTarget.Light(on = false, brightnessPercent = 50)))
    }

    @Test
    fun `plain on-off is a switch when device is a plug`() {
        val plug = device(SwitchCapability(false, 0.0))
        assertEquals(DeviceTarget.Switch(true), SceneTargets.fromCommands(listOf(DeviceCommand.SetOn(true)), plug))
    }

    @Test
    fun `cover open and close map to 100 and 0 percent`() {
        assertEquals(DeviceTarget.Cover(0), SceneTargets.fromCommands(listOf(DeviceCommand.CloseCover), null))
        assertEquals(listOf(DeviceCommand.OpenCover), SceneTargets.toCommands(DeviceTarget.Cover(100)))
        assertEquals(listOf(DeviceCommand.SetCoverPosition(35)), SceneTargets.toCommands(DeviceTarget.Cover(35)))
    }

    @Test
    fun `thermostat keeps mode optional`() {
        assertEquals(listOf(DeviceCommand.SetTargetTemperature(19.5)), SceneTargets.toCommands(DeviceTarget.Thermostat(19.5)))
        val t = SceneTargets.fromCommands(
            listOf(DeviceCommand.SetThermostatMode(ThermostatMode.OFF), DeviceCommand.SetTargetTemperature(17.0)), null,
        )
        assertEquals(DeviceTarget.Thermostat(17.0, ThermostatMode.OFF), t)
    }

    @Test
    fun `capture uses active color mode`() {
        val colorLight = device(LightCapability(true, 30, 2700, 2200..6500, RgbColor(1, 2, 3), LightColorMode.COLOR))
        assertEquals(DeviceTarget.Light(true, 30, null, RgbColor(1, 2, 3)), SceneTargets.capture(colorLight))
        val whiteLight = device(LightCapability(true, 30, 2700, 2200..6500, RgbColor(1, 2, 3), LightColorMode.TEMPERATURE))
        assertEquals(DeviceTarget.Light(true, 30, 2700, null), SceneTargets.capture(whiteLight))
    }

    @Test
    fun `capture thermostat and cover`() {
        assertEquals(DeviceTarget.Thermostat(21.0, ThermostatMode.HEAT), SceneTargets.capture(device(ThermostatCapability(20.0, 21.0, ThermostatMode.HEAT))))
        assertEquals(DeviceTarget.Cover(60), SceneTargets.capture(device(CoverCapability(60))))
    }

    @Test
    fun `sensors cannot be part of a scene`() {
        assertFalse(SceneTargets.isSceneCapable(device(TemperatureSensorCapability(20.0))))
        assertTrue(SceneTargets.isSceneCapable(device(LightCapability(false))))
    }

    @Test
    fun `all seeded scenes can be opened in the editor`() {
        MockHomeSeed.scenes.forEach { scene ->
            scene.actions.groupBy { it.deviceId }.forEach { (_, actions) ->
                assertNotNull(scene.name, SceneTargets.fromCommands(actions.map { it.command }, null))
            }
        }
    }

    @Test
    fun `automation action default switches things on`() {
        assertEquals(DeviceTarget.Light(true, 30), SceneTargets.defaultActionFor(device(LightCapability(false, 30))))
        assertEquals(DeviceTarget.Switch(true), SceneTargets.defaultActionFor(device(SwitchCapability(false))))
        assertEquals(DeviceTarget.Cover(60), SceneTargets.defaultActionFor(device(CoverCapability(60))))
    }
}
