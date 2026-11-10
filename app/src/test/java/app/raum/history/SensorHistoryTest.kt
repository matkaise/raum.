package app.raum.history

import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.Device
import app.raum.domain.models.OnlineState
import app.raum.domain.models.TemperatureSensorCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SensorHistoryTest {
    private val t0 = Instant.parse("2026-09-26T00:00:00Z")
    private fun at(min: Long) = t0.plus(Duration.ofMinutes(min))

    @Test fun `Mittelwerte je Fenster, Lücken bleiben leer`() {
        val samples = listOf(Sample(at(0), 20.0), Sample(at(10), 22.0), Sample(at(65), 25.0))
        val b = HistoryMath.buckets(samples, t0, Duration.ofMinutes(30), 3)
        assertEquals(21.0, b[0]!!, 1e-9)
        assertNull(b[1])
        assertEquals(25.0, b[2]!!, 1e-9)
    }

    @Test fun `Zeiträume mit Anfangszustand und laufendem Ereignis`() {
        val samples = listOf(Sample(at(30), 0.0), Sample(at(60), 1.0), Sample(at(90), 0.0), Sample(at(120), 1.0))
        val list = HistoryMath.intervals(samples, initial = Sample(t0.minusSeconds(60), 1.0), from = t0, now = at(150))
        assertEquals(3, list.size)
        assertEquals(Duration.ofMinutes(30), list[0].duration) // offen seit Fensterbeginn
        assertEquals(Duration.ofMinutes(30), list[1].duration)
        assertTrue(list[2].ongoing)
        assertEquals(Duration.ofMinutes(30), list[2].duration)
    }

    @Test fun `Zustandswechsel werden nur bei Änderung gespeichert`() {
        val id = UUID.randomUUID()
        val closed = device(id, open = false)
        val first = SensorHistory.binaryChanges(null, mapOf(id to SensorHistory.binaryValues(closed)), t0)
        assertEquals(setOf("online", "contact"), first.map { it.metric }.toSet())

        val same = SensorHistory.binaryChanges(mapOf(id to SensorHistory.binaryValues(closed)), mapOf(id to SensorHistory.binaryValues(closed)), t0)
        assertTrue(same.isEmpty())

        val opened = SensorHistory.binaryChanges(mapOf(id to SensorHistory.binaryValues(closed)), mapOf(id to SensorHistory.binaryValues(device(id, open = true))), t0)
        assertEquals(listOf("contact" to 1.0), opened.map { it.metric to it.value })
    }

    @Test fun `Offline-Geräte liefern keine Messwerte`() {
        val d = device(UUID.randomUUID(), open = false, online = false, temp = 21.0)
        assertTrue(SensorHistory.analogValues(d).isEmpty())
        assertEquals(mapOf(SensorMetric.ONLINE to 0.0), SensorHistory.binaryValues(d))
    }

    private fun device(id: UUID, open: Boolean, online: Boolean = true, temp: Double? = null) = Device(
        id = id, matterNodeId = 1u, displayName = "Fenster", roomId = null, vendorName = null, productName = null,
        onlineState = if (online) OnlineState.ONLINE else OnlineState.OFFLINE, favorite = false, lastSeenAt = null,
        capabilities = listOfNotNull(ContactSensorCapability(open), temp?.let { TemperatureSensorCapability(it) }),
    )
}
