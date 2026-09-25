package app.raum.matter

import app.raum.data.repository.InMemoryHomeRepository
import app.raum.diagnostics.EventLog
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.UiMessageBus
import app.raum.i18n.XmlStrings
import app.raum.matter.bridge.BridgePrefs
import app.raum.matter.bridge.BridgeSync
import app.raum.matter.bridge.MockMatterBridge
import app.raum.matter.controller.Ecosystems
import app.raum.matter.controller.PairingWindowResult
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import app.raum.security.InMemoryKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** raum. als Matter-Bridge: Freigabe, Kopplung, Befehle anderer Apps. */
class BridgeTest {

    private class Prefs : BridgePrefs {
        override val bridgeEnabled = MutableStateFlow(false)
        override val bridgeExcluded = MutableStateFlow<Set<UUID>>(emptySet())
    }

    private class Fixture(scope: TestScope) {
        val controller = MockMatterController(scope.backgroundScope, latencyMs = 0L..0L, simulationTickMs = null)
        val repository = InMemoryHomeRepository("Test", MockHomeSeed.rooms, MockHomeSeed.deviceMetadata, MockHomeSeed.scenes, MockHomeSeed.automations)
        val log = EventLog()
        val service = DeviceService(controller, repository, UiMessageBus(), log, scope.backgroundScope, XmlStrings("de"))
        val store = InMemoryKeyValueStore()
        val bridge = MockMatterBridge(store)
        val prefs = Prefs()
        init { BridgeSync(bridge, service, prefs, log, XmlStrings("de")).start(scope.backgroundScope) }
        fun device(name: String) = service.devices.value.first { it.displayName == name }
    }

    @Test fun `Bridge folgt der Einstellung und gibt alle Geräte frei - außer abgewählten`() = runTest {
        val f = Fixture(this); runCurrent()
        assertFalse(f.bridge.state.value.running)

        f.prefs.bridgeEnabled.value = true; runCurrent()
        assertTrue(f.bridge.state.value.running)
        assertEquals(MockHomeSeed.devices.size, f.bridge.state.value.exposed.size)

        val lamp = f.device("Stehlampe")
        f.prefs.bridgeExcluded.value = setOf(lamp.id); runCurrent()
        assertFalse(lamp.id in f.bridge.state.value.exposed)
        assertEquals(MockHomeSeed.devices.size - 1, f.bridge.published.size)

        f.prefs.bridgeEnabled.value = false; runCurrent()
        assertFalse(f.bridge.state.value.running)
    }

    @Test fun `Kopplung übersteht einen Neustart und lässt sich entfernen`() = runTest {
        val f = Fixture(this)
        f.prefs.bridgeEnabled.value = true; runCurrent()
        assertTrue(f.bridge.openPairingWindow() is PairingWindowResult.Open)
        assertTrue(f.bridge.simulateJoin(Ecosystems.APPLE))
        assertFalse("Fenster ist nach der Kopplung zu", f.bridge.simulateJoin(Ecosystems.GOOGLE))

        val restarted = MockMatterBridge(f.store)
        assertEquals(listOf(Ecosystems.APPLE), restarted.state.value.admins.map { it.vendorId })

        restarted.removeAdmin(restarted.state.value.admins.single().fabricIndex)
        assertTrue(MockMatterBridge(f.store).state.value.admins.isEmpty())
    }

    @Test fun `Befehle gekoppelter Apps schalten freigegebene Geräte`() = runTest {
        val f = Fixture(this)
        f.prefs.bridgeEnabled.value = true; runCurrent()
        f.bridge.openPairingWindow(); f.bridge.simulateJoin(Ecosystems.APPLE)

        val lamp = f.device("Stehlampe")
        val wasOn = lamp.capabilities.find<LightCapability>()!!.isOn
        assertTrue(f.bridge.simulateCommand(lamp.id, DeviceCommand.SetOn(!wasOn), Ecosystems.APPLE))
        runCurrent()
        assertEquals(!wasOn, f.device("Stehlampe").capabilities.find<LightCapability>()!!.isOn)
        assertTrue(f.log.recent.value.any { it.message.contains("Apple Home") && it.deviceName == "Stehlampe" })

        // nicht gekoppelte App und abgewählte Geräte werden abgewiesen
        assertFalse(f.bridge.simulateCommand(lamp.id, DeviceCommand.SetOn(wasOn), Ecosystems.GOOGLE))
        f.prefs.bridgeExcluded.value = setOf(lamp.id); runCurrent()
        assertFalse(f.bridge.simulateCommand(lamp.id, DeviceCommand.SetOn(wasOn), Ecosystems.APPLE))
    }

    @Test fun `Fehlgeschlagener Bridge-Reset hat eine eigene, verständliche Meldung`() {
        val e = app.raum.matter.bridge.BridgeResetException("bridge process still running")
        assertTrue(app.raum.i18n.ErrorTexts.maintenance(e, XmlStrings("de"))!!.contains("nichts gelöscht"))
        assertTrue(app.raum.i18n.ErrorTexts.maintenance(e, XmlStrings("en"))!!.contains("nothing was deleted"))
    }
}
