package app.raum.data

import app.raum.data.reset.AdminRevocation
import app.raum.data.reset.RevocationOutcome
import app.raum.matter.controller.Ecosystems
import app.raum.matter.controller.PairingWindowResult
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Übergabe (RST-005): fremde Zugriffe entziehen, Ergebnis je Gerät, nichts stillschweigend als erledigt melden. */
class AdminRevocationTest {

    private fun TestScope.controller() =
        MockMatterController(backgroundScope, latencyMs = 0L..0L, simulationTickMs = null, seed = MockHomeSeed.devices).also { runCurrent() }

    private val online = MockHomeSeed.devices.filter { it.online }.map { it.nodeId }

    private suspend fun MockMatterController.share(node: ULong, vendorId: Int) {
        assertTrue(openPairingWindow(node) is PairingWindowResult.Open)
        assertTrue(simulateExternalAdmin(node, vendorId, ""))
    }

    @Test fun `alle erreichbar - fremde Apps entfernt, eigene Fabric bleibt, Übergabe vollständig`() = runTest {
        val c = controller()
        val (a, b) = online
        c.share(a, Ecosystems.APPLE); c.share(a, Ecosystems.GOOGLE); c.share(b, Ecosystems.AMAZON)
        runCurrent()

        val report = AdminRevocation(c).run(online)

        assertTrue(report.complete)
        assertEquals(3, report.revokedCount)
        runCurrent()
        online.forEach { assertEquals(listOf(true), c.adminFabrics.value[it]!!.map { f -> f.own }) }
    }

    @Test fun `nicht erreichbares Gerät - Übergabe unvollständig, Gerät wird genannt`() = runTest {
        val c = controller()
        val (a, b) = online
        c.share(a, Ecosystems.APPLE); c.share(b, Ecosystems.GOOGLE)
        c.setOnline(b, false)
        runCurrent()

        val report = AdminRevocation(c).run(online)

        assertFalse(report.complete)
        assertEquals(listOf(b), report.problems.map { it.nodeId })
        assertEquals(RevocationOutcome.Unreachable, report.problems.single().outcome)
        // Das erreichbare Gerät ist trotzdem bereinigt
        assertTrue(report.devices.first { it.nodeId == a }.outcome.ok)
    }

    @Test fun `Gerät bestätigt Entzug, behält die Fabric aber - wird als fehlgeschlagen erkannt`() = runTest {
        val c = controller()
        val a = online.first()
        c.share(a, Ecosystems.APPLE)
        c.simulateStuckAdmins(a)
        runCurrent()

        val report = AdminRevocation(c).run(listOf(a))

        assertFalse(report.complete)
        val failed = report.problems.single().outcome as RevocationOutcome.Failed
        assertEquals(listOf(Ecosystems.APPLE), failed.remaining.map { it.vendorId })
        assertFalse(failed.unconfirmed)
    }

    @Test fun `Geräte ohne fremde Apps zählen als erledigt`() = runTest {
        val c = controller()
        val report = AdminRevocation(c).run(online)
        assertTrue(report.complete)
        assertEquals(0, report.revokedCount)
    }
}
