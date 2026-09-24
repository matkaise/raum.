package app.raum.ui

import app.raum.R
import app.raum.ui.overview.Sky
import app.raum.ui.overview.SkyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Himmel der Übersicht: Tageszeit aus Sonnenzeiten, Sonnenbogen, Begrüßung. */
class SkyTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 9, 22, h, m, 0, 0, zone)
    private val rise = at(7, 10)
    private val set = at(19, 20)

    @Test fun `Phasen folgen den echten Sonnenzeiten`() {
        assertEquals(SkyPhase.NIGHT, Sky.phase(at(5, 0), rise, set))
        assertEquals(SkyPhase.DAWN, Sky.phase(at(6, 30), rise, set))
        assertEquals(SkyPhase.DAWN, Sky.phase(at(8, 0), rise, set))
        assertEquals(SkyPhase.DAY, Sky.phase(at(13, 0), rise, set))
        assertEquals(SkyPhase.DUSK, Sky.phase(at(19, 0), rise, set))
        assertEquals(SkyPhase.DUSK, Sky.phase(at(20, 10), rise, set))
        assertEquals(SkyPhase.NIGHT, Sky.phase(at(20, 30), rise, set))
    }

    @Test fun `ohne Standort grob nach Uhrzeit`() {
        assertEquals(SkyPhase.NIGHT, Sky.phase(at(3), null, null))
        assertEquals(SkyPhase.DAWN, Sky.phase(at(7), null, null))
        assertEquals(SkyPhase.DAY, Sky.phase(at(12), null, null))
        assertEquals(SkyPhase.DUSK, Sky.phase(at(19), null, null))
    }

    @Test fun `Sonnenbogen von Aufgang bis Untergang`() {
        assertEquals(0f, Sky.sunProgress(rise, rise, set)!!, 0.001f)
        assertEquals(0.5f, Sky.sunProgress(at(13, 15), rise, set)!!, 0.01f)
        assertEquals(1f, Sky.sunProgress(set, rise, set)!!, 0.001f)
        assertNull(Sky.sunProgress(at(22), rise, set))
        assertNull(Sky.sunProgress(at(12), null, set))
    }

    @Test fun `Begrüßung je Tageszeit`() {
        assertEquals(R.string.greeting_morning, Sky.greetingRes(7))
        assertEquals(R.string.greeting_day, Sky.greetingRes(13))
        assertEquals(R.string.greeting_evening, Sky.greetingRes(19))
        assertEquals(R.string.greeting_night, Sky.greetingRes(23))
        assertEquals(R.string.greeting_night, Sky.greetingRes(2))
    }
}
