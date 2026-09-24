package app.raum.data

import app.raum.data.geo.CityIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prüft die echte Ortsliste aus `src/main/assets`. */
class CityIndexTest {

    private val index = CityIndex { File("src/main/assets/${CityIndex.ASSET}").inputStream() }

    @Test fun `deutsche Schreibweise findet Exonym und zeigt sie an`() {
        val hit = index.search("München").first()
        assertEquals("München", hit.name)
        assertEquals("DE", hit.countryCode)
        assertEquals(48.137, hit.location.latitude, 0.01)
    }

    @Test fun `Umlaute, ae-oe-ue und Groß-Kleinschreibung sind gleichwertig`() {
        listOf("zürich", "ZUERICH", "Zurich").forEach { q ->
            val hit = index.search(q, preferredZone = "Europe/Zurich").first()
            assertEquals(q, "CH", hit.countryCode)
            assertEquals(q, "Europe/Zurich", hit.timeZone)
        }
    }

    @Test fun `eigene Zeitzone wird bevorzugt`() {
        // „Frankfurt“: am Main (Europe/Berlin) vor anderen gleichnamigen Orten
        val hit = index.search("Frankfurt", preferredZone = "Europe/Berlin").first()
        assertEquals("Europe/Berlin", hit.timeZone)
        assertEquals(50.11, hit.location.latitude, 0.05)
    }

    @Test fun `Großstadt vor kleinen Orten der eigenen Zeitzone`() {
        val hits = index.search("Münch", preferredZone = "Europe/Zurich").map { it.name }
        assertEquals("München", hits.first())
        assertTrue(hits.contains("Münchenstein"))
    }

    @Test fun `kleine Orte sind enthalten`() {
        assertTrue(index.search("Wädenswil").any { it.countryCode == "CH" })
    }

    @Test fun `Vorschläge sind die größten Orte der Zeitzone`() {
        val names = index.largestIn("Europe/Vienna", limit = 3).map { it.name }
        assertEquals("Vienna", names.first())
    }

    @Test fun `zu kurze Eingaben liefern nichts`() {
        assertTrue(index.search("m").isEmpty())
    }

    @Test fun `Laden und Suchen sind schnell genug für das Panel`() {
        val t0 = System.nanoTime()
        index.search("Berlin")
        val load = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        repeat(10) { index.search("Sankt") }
        val search = (System.nanoTime() - t1) / 10_000_000
        println("CityIndex: laden+suchen $load ms, suchen $search ms")
        assertTrue("Laden $load ms", load < 3_000)
        assertTrue("Suchen $search ms", search < 150)
    }
}
