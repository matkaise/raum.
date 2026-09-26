package app.raum.matter

import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.find
import app.raum.matter.chip.Attr
import app.raum.matter.chip.AttrPath
import app.raum.matter.chip.Cluster
import app.raum.matter.chip.ClusterMapper
import app.raum.matter.chip.DeviceType
import app.raum.matter.chip.NodeData
import app.raum.matter.chip.PrimaryEndpoints
import app.raum.matter.chip.TlvStruct
import app.raum.security.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Das Hauptgerät bleibt an seinem Endpunkt – auch wenn sich die Endpunkte des Nodes ändern. */
class PrimaryEndpointsTest {

    private fun onOff(ep: Int, type: Long?) = buildMap<AttrPath, Any?> {
        put(AttrPath(ep, Cluster.ON_OFF, Attr.VALUE), false)
        if (type != null) put(AttrPath(ep, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST), listOf(TlvStruct(mapOf(0 to type, 1 to 1L))))
    }

    @Test fun `Neue Leuchte an einer Bridge übernimmt nicht das bisherige Steckdosen-Gerät`() {
        val store = InMemoryKeyValueStore()
        val node = 0x42uL
        val plugOnly = NodeData(onOff(1, DeviceType.ON_OFF_PLUG))
        assertEquals(1, PrimaryEndpoints(store).resolve(node, plugOnly))

        // Später kommt eine Leuchte auf Endpunkt 2 dazu – die Vorschlagsregel würde jetzt sie wählen
        val withLight = NodeData(onOff(1, DeviceType.ON_OFF_PLUG) + onOff(2, DeviceType.ON_OFF_LIGHT))
        assertEquals(2, ClusterMapper.defaultPrimaryEndpoint(withLight))
        val primary = PrimaryEndpoints(store).resolve(node, withLight) // auch nach Neustart
        assertEquals(1, primary)

        // Hauptgerät bleibt die Steckdose, die Leuchte wird ein weiterer Kanal
        assertTrue(ClusterMapper.capabilities(withLight, primary).find<SwitchCapability>() != null)
        assertNull(ClusterMapper.capabilities(withLight, primary).find<LightCapability>())
        assertEquals(listOf(2), ClusterMapper.channels(withLight, primary).map { it.endpoint })
        assertEquals(1, ClusterMapper.actions(DeviceCommand.SetOn(true), withLight, primary = primary)!!.single().endpoint)
    }

    @Test fun `Erst binden, wenn die Gerätetypen bekannt sind`() {
        val store = InMemoryKeyValueStore()
        val node = 0x43uL
        // Beschreibung noch nicht gelesen: vorläufig erster Endpunkt, aber nicht gebunden
        assertEquals(1, PrimaryEndpoints(store).resolve(node, NodeData(onOff(1, null) + onOff(2, null))))
        assertNull(PrimaryEndpoints(store).bound(node))
        // Mit Typen: Leuchte auf 2 ist der richtige Hauptkanal und wird jetzt gebunden
        assertEquals(2, PrimaryEndpoints(store).resolve(node, NodeData(onOff(1, DeviceType.ON_OFF_PLUG) + onOff(2, DeviceType.ON_OFF_LIGHT))))
        assertEquals(2, PrimaryEndpoints(store).bound(node))
    }

    @Test fun `Verschwundener Endpunkt - Hauptgerät verliert die Schaltfunktion statt umzuspringen`() {
        val store = InMemoryKeyValueStore()
        val node = 0x44uL
        PrimaryEndpoints(store).resolve(node, NodeData(onOff(1, DeviceType.ON_OFF_PLUG) + onOff(2, DeviceType.ON_OFF_PLUG)))
        val onlySecond = NodeData(onOff(2, DeviceType.ON_OFF_PLUG))
        val primary = PrimaryEndpoints(store).resolve(node, onlySecond)
        assertEquals(1, primary)
        assertNull(ClusterMapper.capabilities(onlySecond, primary).find<SwitchCapability>())
        assertNull(ClusterMapper.actions(DeviceCommand.SetOn(true), onlySecond, primary = primary))
        assertEquals(listOf(2), ClusterMapper.channels(onlySecond, primary).map { it.endpoint })
    }

    @Test fun `Entfernen und Werksreset vergessen die Bindung`() {
        val store = InMemoryKeyValueStore()
        val p = PrimaryEndpoints(store)
        p.resolve(1uL, NodeData(onOff(3, DeviceType.ON_OFF_LIGHT)))
        p.resolve(2uL, NodeData(onOff(4, DeviceType.ON_OFF_LIGHT)))
        p.forget(1uL)
        assertNull(p.bound(1uL)); assertEquals(4, p.bound(2uL))
        p.clear()
        assertNull(p.bound(2uL))
    }
}
