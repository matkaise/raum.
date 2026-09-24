package app.raum.matter

import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.find
import app.raum.domain.models.OnlineState
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import app.raum.security.InMemoryKeyValueStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockFabricTest {

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, store: InMemoryKeyValueStore) =
        MockMatterController(scope, latencyMs = 0L..0L, simulationTickMs = null, seed = emptyList(), fabricStore = store)

    @Test fun `Fabric wird einmal angelegt, gespeichert und nach Neustart wiedergefunden`() = runTest {
        val store = InMemoryKeyValueStore()
        val first = controller(backgroundScope, store)
        assertNull(first.fabric.value)
        val info = first.ensureFabric()
        assertEquals(info, first.ensureFabric())

        val restarted = controller(backgroundScope, store)
        assertEquals(info, restarted.fabric.value)

        restarted.resetFabric()
        assertNull(restarted.fabric.value)
        assertNull(controller(backgroundScope, store).fabric.value)
        assertTrue(restarted.ensureFabric().fabricId != 0uL)
    }

    @Test fun `bekannte Nodes kommen mit ihren Beispiel-Fähigkeiten zurück, fremde als Leuchte`() = runTest {
        val c = controller(backgroundScope, InMemoryKeyValueStore())
        val door = MockHomeSeed.devices.first { !it.online }
        val light = MockHomeSeed.devices.first { it.capabilities.find<LightCapability>() != null }
        c.adoptNodes(listOf(door.nodeId, light.nodeId, 999uL))

        val devices = c.devices.value
        assertEquals(OnlineState.OFFLINE, devices.getValue(door.nodeId).onlineState)
        assertNotNull(devices.getValue(door.nodeId).capabilities.find<ContactSensorCapability>())
        assertEquals(light.capabilities, devices.getValue(light.nodeId).capabilities)
        assertNotNull(devices.getValue(999uL).capabilities.find<LightCapability>())
        assertEquals(OnlineState.ONLINE, devices.getValue(999uL).onlineState)
    }

    @Test fun `Multi-Admin - Fenster öffnen, andere App koppelt, Zugriff entziehen`() = runTest {
        val c = MockMatterController(backgroundScope, latencyMs = 0L..0L, simulationTickMs = null, seed = MockHomeSeed.devices)
        val light = MockHomeSeed.devices.first { it.online }.nodeId
        val offline = MockHomeSeed.devices.first { !it.online }.nodeId
        runCurrent()
        assertEquals(listOf(true), c.adminFabrics.value[light]!!.map { it.own })

        // offline: kein Fenster
        val refused = c.openPairingWindow(offline)
        assertTrue(refused is app.raum.matter.controller.PairingWindowResult.Failure)

        val open = c.openPairingWindow(light) as app.raum.matter.controller.PairingWindowResult.Open
        val parsed = app.raum.matter.commissioning.SetupCodeParser.parse(open.window.manualCode)
        assertTrue(parsed is app.raum.matter.commissioning.SetupCodeParser.ParseResult.Valid)
        assertTrue(open.window.qrPayload.startsWith("MT:"))

        assertTrue(c.simulateExternalAdmin(light, 0x1349, ""))
        runCurrent()
        val admins = c.adminFabrics.value[light]!!
        assertEquals(2, admins.size)
        assertEquals(0x1349, admins.last().vendorId)
        // Fenster ist nach der Kopplung zu
        assertFalse(c.simulateExternalAdmin(light, 0x6006, ""))

        // eigene Fabric bleibt, fremde lässt sich entfernen
        assertTrue(c.removeAdmin(light, 1) is app.raum.matter.controller.CommandResult.Failure)
        assertEquals(app.raum.matter.controller.CommandResult.Success, c.removeAdmin(light, admins.last().fabricIndex))
        runCurrent()
        assertEquals(1, c.adminFabrics.value[light]!!.size)
    }

    @Test fun `neue Geräte per Bluetooth brauchen Thread- bzw WLAN-Zugangsdaten`() = runTest {
        val creds = app.raum.thread.NetworkCredentialStore(app.raum.security.InMemorySecretStore(), InMemoryKeyValueStore())
        val c = MockMatterController(backgroundScope, latencyMs = 0L..0L, simulationTickMs = null, seed = emptyList(), credentials = creds)
        val threadCode = app.raum.matter.commissioning.SetupCodeGenerator.manualCode(0xE00, 20202021)
        val wifiCode = app.raum.matter.commissioning.SetupCodeGenerator.manualCode(0xD00, 20202021)
        // In OPERATIONS.md dokumentierte Simulationscodes
        assertEquals("33331712336", threadCode.filter { it.isDigit() })
        assertEquals("31693312339", wifiCode.filter { it.isDigit() })

        val noThread = c.commission(threadCode) as app.raum.matter.controller.CommissioningResult.Failure
        assertEquals(app.raum.matter.controller.CommissioningFailure.NO_THREAD_NETWORK, noThread.reason)
        val noWifi = c.commission(wifiCode) as app.raum.matter.controller.CommissioningResult.Failure
        assertEquals(app.raum.matter.controller.CommissioningFailure.NO_WIFI_CREDENTIALS, noWifi.reason)

        creds.setThread(app.raum.thread.Datasets.dataset("raum-test"), app.raum.thread.DatasetSource.MANUAL)
        creds.setWifi("Heimnetz", "geheim123")
        val sensor = c.commission(threadCode) as app.raum.matter.controller.CommissioningResult.Success
        val plug = c.commission(wifiCode) as app.raum.matter.controller.CommissioningResult.Success
        runCurrent()
        val net = c.devices.value[sensor.nodeId]!!.network!!
        assertEquals(app.raum.domain.models.NetworkTransport.THREAD, net.transport)
        assertEquals("raum-test", net.threadNetworkName)
        assertEquals("dead00beef00cafe", net.threadExtPanId)
        assertEquals(app.raum.domain.models.NetworkTransport.WIFI, c.devices.value[plug.nodeId]!!.network!!.transport)
    }
}
