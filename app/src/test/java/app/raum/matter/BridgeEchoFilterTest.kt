package app.raum.matter

import app.raum.matter.bridge.BridgeEchoFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Echo oder neuer Wunsch? Referenz ist der zuletzt weitergeleitete Wunsch, nicht nur der veröffentlichte Zustand. */
class BridgeEchoFilterTest {

    @Test fun `Ein und gleich wieder Aus - beide Wünsche kommen durch, der letzte gilt`() {
        val f = BridgeEchoFilter()
        // Lampe ist aus (veröffentlicht 0); „Ein“ ist angenommen, aber noch nicht ausgeführt
        assertTrue(f.accept(5, "onoff", 1, published = 0))
        // „Aus“ entspricht dem (noch) veröffentlichten Zustand – ist aber ein neuer Wunsch
        assertTrue(f.accept(5, "onoff", 0, published = 0))
        // Dasselbe „Aus“ ein zweites Mal ist tatsächlich ein Echo
        assertFalse(f.accept(5, "onoff", 0, published = 0))
    }

    @Test fun `Echo des veröffentlichten Zustands wird verworfen`() {
        val f = BridgeEchoFilter()
        assertFalse(f.accept(5, "level", 40, published = 40))
        assertTrue(f.accept(5, "level", 60, published = 40))
    }

    @Test fun `Nach geändertem Zustand gilt wieder er - Wiederholung nach Fehlschlag geht durch`() {
        val f = BridgeEchoFilter()
        assertTrue(f.accept(5, "onoff", 1, published = 0))
        // Befehl scheiterte: raum. veröffentlicht wieder „aus“ (Überlagerung zurückgerollt)
        f.published(listOf(5))
        assertTrue(f.accept(5, "onoff", 1, published = 0))
    }

    @Test fun `Endpunkte und Arten sind getrennt`() {
        val f = BridgeEchoFilter()
        assertTrue(f.accept(5, "onoff", 1, published = 0))
        assertTrue(f.accept(6, "onoff", 1, published = 0))
        assertTrue(f.accept(5, "level", 1, published = 50))
        f.published(listOf(6))
        assertFalse(f.accept(5, "onoff", 1, published = 0)) // Wunsch an 5 bleibt offen
    }
}
