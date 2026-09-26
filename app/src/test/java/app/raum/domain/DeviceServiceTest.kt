package app.raum.domain

import app.raum.data.repository.InMemoryHomeRepository
import app.raum.diagnostics.EventLog
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.CommissioningResult
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceServiceTest {

    private class Fixture(scope: CoroutineScope, failureRate: Double = 0.0, latencyMs: LongRange = 0L..0L) {
        val controller = MockMatterController(
            scope = scope, latencyMs = latencyMs, failureRate = failureRate, simulationTickMs = null,
        )
        val repository = InMemoryHomeRepository("Test", MockHomeSeed.rooms, MockHomeSeed.deviceMetadata, MockHomeSeed.scenes, MockHomeSeed.automations)
        val messages = UiMessageBus()
        val log = EventLog()
        val strings = app.raum.i18n.XmlStrings("de")
        val service = DeviceService(controller, repository, messages, log, scope, strings)
        val scenes = SceneRunner(service, messages, log, strings)
        fun device(name: String) = service.devices.value.first { it.displayName == name }
    }

    private fun TestScope.fixture(failureRate: Double = 0.0) = Fixture(backgroundScope, failureRate).also { runCurrent() }

    @Test
    fun `all seeded devices are exposed with metadata`() = runTest {
        val f = fixture()
        assertEquals(MockHomeSeed.devices.size, f.service.devices.value.size)
    }

    @Test
    fun `successful command updates device state`() = runTest {
        val f = fixture()
        val lamp = f.device("Stehlampe")
        val result = f.service.send(lamp, DeviceCommand.SetOn(true))
        runCurrent()
        assertEquals(CommandResult.Success, result)
        assertTrue(f.device("Stehlampe").capabilities.find<LightCapability>()!!.isOn)
    }

    @Test
    fun `failed command rolls back optimistic state`() = runTest {
        val f = fixture(failureRate = 1.0)
        val lamp = f.device("Stehlampe")
        val result = f.service.send(lamp, DeviceCommand.SetOn(true))
        runCurrent()
        assertTrue(result is CommandResult.Failure)
        assertFalse(f.device("Stehlampe").capabilities.find<LightCapability>()!!.isOn)
    }

    @Test
    fun `cancelled command removes optimistic state`() = runTest {
        val f = Fixture(backgroundScope, latencyMs = 1_000L..1_000L).also { runCurrent() }
        val lamp = f.device("Stehlampe")
        assertFalse(lamp.capabilities.find<LightCapability>()!!.isOn)

        val job = launch { f.service.send(lamp, DeviceCommand.SetOn(true)) }
        runCurrent()
        assertTrue(f.device("Stehlampe").capabilities.find<LightCapability>()!!.isOn)

        job.cancel()
        runCurrent()
        assertFalse(f.device("Stehlampe").capabilities.find<LightCapability>()!!.isOn)

        // Spätere echte Zustandsmeldungen dürfen nicht verdeckt bleiben
        f.controller.simulateExternalChange(lamp.matterNodeId) { caps ->
            caps.map { if (it is LightCapability) it.copy(isOn = true) else it }
        }
        runCurrent()
        assertTrue(f.device("Stehlampe").capabilities.find<LightCapability>()!!.isOn)
    }

    // --- Mehrkanalgeräte ----------------------------------------------------------------------

    /** Online-Zwischenstecker, der einen zweiten Kanal (Endpunkt 2) bekommt; liefert den Hauptkanal. */
    private fun Fixture.plugWithSecondChannel(): app.raum.domain.models.Device {
        val plug = service.devices.value.first { it.isOnline && it.capabilities.find<SwitchCapability>() != null }
        controller.addChannel(plug.matterNodeId, 2, listOf(SwitchCapability(isOn = false)))
        return plug
    }

    @Test
    fun `second channel appears as its own device and is switched independently`() = runTest {
        val f = fixture()
        val plug = f.plugWithSecondChannel()
        runCurrent()
        val channel = f.service.devices.value.single { it.matterNodeId == plug.matterNodeId && it.endpointId == 2 }
        assertEquals("${plug.displayName} · Kanal 2", channel.displayName)
        assertEquals(plug.roomId, channel.roomId)
        assertFalse(channel.isPrimaryChannel)
        val mainOn = plug.capabilities.find<SwitchCapability>()!!.isOn

        assertEquals(CommandResult.Success, f.service.send(channel, DeviceCommand.SetOn(true)))
        runCurrent()

        assertTrue(f.service.device(channel.id)!!.capabilities.find<SwitchCapability>()!!.isOn)
        assertEquals(mainOn, f.service.device(plug.id)!!.capabilities.find<SwitchCapability>()!!.isOn)
    }

    @Test
    fun `renaming a channel stores its own metadata and keeps the main device`() = runTest {
        val f = fixture()
        val plug = f.plugWithSecondChannel()
        runCurrent()
        val channel = f.service.devices.value.single { it.matterNodeId == plug.matterNodeId && it.endpointId == 2 }

        f.service.rename(channel, "Kaffeemühle")
        runCurrent()

        val stored = f.repository.deviceMetadata.value.filter { it.matterNodeId == plug.matterNodeId }
        assertEquals(setOf(null, 2), stored.map { it.endpointId }.toSet())
        assertEquals("Kaffeemühle", f.service.device(channel.id)!!.displayName)
        assertEquals(plug.displayName, f.service.device(plug.id)!!.displayName)
    }

    @Test
    fun `a discovered channel is stored without renaming it first`() = runTest {
        val f = fixture()
        val plug = f.plugWithSecondChannel()
        runCurrent()
        val stored = f.repository.deviceMetadata.value.single { it.matterNodeId == plug.matterNodeId && it.endpointId == 2 }
        assertEquals("${plug.displayName} · Kanal 2", f.service.device(stored.id)!!.displayName)

        // Favorit auf einem frisch entdeckten Kanal bleibt erhalten
        val channel = f.service.devices.value.single { it.id == stored.id }
        f.service.setFavorite(channel, true)
        runCurrent()
        assertTrue(f.repository.deviceMetadata.value.single { it.id == stored.id }.favorite)
    }

    @Test
    fun `an untouched channel follows the main device name until it is edited`() = runTest {
        val f = fixture()
        val plug = f.plugWithSecondChannel()
        runCurrent()
        val channelId = app.raum.domain.models.deviceIdForChannel(plug.matterNodeId, 2)

        // Hauptgerät bekommt (z. B. am Ende der Kopplung) seinen Namen – der Kanal zieht mit
        f.service.rename(plug, "Relais Küche"); runCurrent()
        assertEquals("Relais Küche · Kanal 2", f.service.device(channelId)!!.displayName)

        // Erste Änderung am Kanal schreibt den abgeleiteten Namen fest
        f.service.setFavorite(f.service.device(channelId)!!, true); runCurrent()
        f.service.rename(plug, "Relais Bad"); runCurrent()
        assertEquals("Relais Küche · Kanal 2", f.service.device(channelId)!!.displayName)
        assertTrue(f.service.device(channelId)!!.favorite)
    }

    @Test
    fun `storing discovered devices never overwrites names or rooms`() = runTest {
        val f = fixture()
        val plug = f.plugWithSecondChannel()
        runCurrent()
        val channel = f.service.devices.value.single { it.matterNodeId == plug.matterNodeId && it.endpointId == 2 }
        f.service.rename(channel, "Kaffeemühle")
        runCurrent()
        // Erneutes „Entdecken“ (z. B. beim Start, bevor Metadaten geladen sind) darf nichts überschreiben
        f.repository.addDeviceIfMissing(channel.let {
            app.raum.domain.models.DeviceMetadata(it.id, it.matterNodeId, "Standardname", null, null, null, false, 2)
        })
        runCurrent()
        assertEquals("Kaffeemühle", f.service.device(channel.id)!!.displayName)
    }

    @Test
    fun `removing the node removes all its channels`() = runTest {
        val f = fixture()
        val plug = f.plugWithSecondChannel()
        runCurrent()
        f.service.remove(plug)
        runCurrent()
        assertTrue(f.service.devices.value.none { it.matterNodeId == plug.matterNodeId })
    }

    @Test
    fun `offline device rejects commands without success`() = runTest {
        val f = fixture()
        val door = f.device("Balkontür")
        val result = f.service.send(door, DeviceCommand.SetOn(true))
        assertEquals(CommandFailure.OFFLINE, (result as CommandResult.Failure).reason)
    }

    @Test
    fun `external change is reflected in device list`() = runTest {
        val f = fixture()
        val lamp = f.device("Deckenleuchte")
        f.controller.simulateExternalChange(lamp.matterNodeId) { caps ->
            caps.map { if (it is LightCapability) it.copy(isOn = false) else it }
        }
        runCurrent()
        assertFalse(f.device("Deckenleuchte").capabilities.find<LightCapability>()!!.isOn)
    }

    @Test
    fun `cover moves to target in simulation`() = runTest {
        val f = fixture()
        val cover = f.device("Rollladen Schlafzimmer")
        f.service.send(cover, DeviceCommand.SetCoverPosition(50))
        repeat(10) { f.controller.simulationTick(it.toLong()) }
        runCurrent()
        val c = f.device("Rollladen Schlafzimmer").capabilities.find<CoverCapability>()!!
        assertEquals(50, c.openPercent)
        assertEquals(CoverMovement.STOPPED, c.movement)
    }

    @Test
    fun `scene reports offline devices as partial failure`() = runTest {
        val f = fixture()
        val gutenacht = MockHomeSeed.scenes.first { it.name == "Gute Nacht" }
        val result = f.scenes.run(gutenacht)
        assertTrue(result.isComplete)

        f.controller.setOnline(f.device("Deckenleuchte").matterNodeId, false)
        runCurrent()
        val again = f.scenes.run(gutenacht)
        assertEquals(listOf("Deckenleuchte"), again.failedDevices)
    }

    @Test
    fun `commissioning same code twice is detected`() = runTest {
        val f = fixture()
        val first = f.controller.commission("3497-011-2332")
        assertTrue(first is CommissioningResult.Success)
        val second = f.controller.commission("34970112332")
        assertTrue(second is CommissioningResult.AlreadyCommissioned)
    }

    @Test
    fun `removing a device drops it from scenes`() = runTest {
        val f = fixture()
        val lamp = f.device("Deckenleuchte")
        f.service.remove(lamp)
        runCurrent()
        assertTrue(f.service.devices.value.none { it.id == lamp.id })
        assertTrue(f.repository.scenes.value.all { s -> s.actions.none { it.deviceId == lamp.id } })
    }
}
