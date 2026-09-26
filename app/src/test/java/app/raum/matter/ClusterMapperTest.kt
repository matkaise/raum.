package app.raum.matter

import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.RgbColor
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.UnknownCapability
import app.raum.domain.models.find
import app.raum.matter.chip.Attr
import app.raum.matter.chip.AttrPath
import app.raum.matter.chip.Cluster
import app.raum.matter.chip.ClusterMapper
import app.raum.matter.chip.Cmd
import app.raum.matter.chip.DeviceType
import app.raum.matter.chip.MatterAction
import app.raum.matter.chip.NodeData
import app.raum.matter.chip.TlvReader
import app.raum.matter.chip.TlvStruct
import app.raum.matter.chip.TlvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Übersetzung Matter ↔ raum. – ohne Matter-SDK, mit Attributwerten wie sie echte Geräte liefern. */
class ClusterMapperTest {

    private fun types(vararg t: Long) = t.map { TlvStruct(mapOf(0 to it, 1 to 1L)) }

    private fun node(vararg entries: Pair<Triple<Int, Long, Long>, Any?>) =
        NodeData(entries.associate { (k, v) -> AttrPath(k.first, k.second, k.third) to v })

    private fun at(ep: Int, cluster: Long, attr: Long) = Triple(ep, cluster, attr)

    // --- TLV ---------------------------------------------------------------------------------

    @Test fun `TLV - Struktur schreiben und lesen`() {
        val bytes = TlvWriter().startStructure().uint(0, 127).uint(1, 5).int(2, -2150).bool(3, true).nul(4).endContainer().bytes()
        val s = TlvReader.read(bytes) as TlvStruct
        assertEquals(127L, s[0]); assertEquals(5L, s[1]); assertEquals(-2150L, s[2]); assertEquals(true, s[3]); assertNull(s[4])
        assertTrue(4 in s.fields)
    }

    @Test fun `TLV - Listen, Zeichenketten und breite Zahlen`() {
        // Anonyme Liste mit String (UTF-8, 1-Byte-Länge) und uint32
        val bytes = byteArrayOf(0x17, 0x0C, 0x03, 'E'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte(), 0x06, 0x78, 0x56, 0x34, 0x12, 0x18)
        assertEquals(listOf("Eve", 0x12345678L), TlvReader.read(bytes))
        assertEquals(-1L, TlvReader.read(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(65535L, TlvReader.read(byteArrayOf(0x05, 0xFF.toByte(), 0xFF.toByte())))
    }

    // --- Lesen -------------------------------------------------------------------------------

    @Test fun `Thread-Temperatursensor mit Batterie`() {
        val n = node(
            at(0, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.ROOT_NODE),
            at(0, Cluster.BASIC_INFORMATION, Attr.VENDOR_NAME) to "Eve Systems",
            at(0, Cluster.BASIC_INFORMATION, Attr.PRODUCT_NAME) to "Eve Weather",
            at(0, Cluster.BASIC_INFORMATION, Attr.NODE_LABEL) to "",
            at(0, Cluster.POWER_SOURCE, Attr.BAT_PERCENT_REMAINING) to 181L,
            at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.TEMPERATURE_SENSOR),
            at(1, Cluster.TEMPERATURE_MEASUREMENT, Attr.VALUE) to 2134L,
        )
        val caps = ClusterMapper.capabilities(n)
        assertEquals(21.34, caps.find<TemperatureSensorCapability>()!!.celsius, 0.001)
        assertEquals(91, caps.find<BatteryCapability>()!!.percent)
        assertNull(caps.find<UnknownCapability>())
        val info = ClusterMapper.info(n)
        assertEquals("Eve Systems", info.vendorName); assertEquals("Eve Weather", info.productName); assertNull(info.nodeLabel)
    }

    @Test fun `Sensor ohne Messwert (null) liefert keinen erfundenen Wert`() {
        val n = node(at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.TEMPERATURE_SENSOR), at(1, Cluster.TEMPERATURE_MEASUREMENT, Attr.VALUE) to null)
        assertNull(ClusterMapper.capabilities(n).find<TemperatureSensorCapability>())
    }

    @Test fun `Farbtemperatur-Leuchte`() {
        val n = node(
            at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.EXTENDED_COLOR_LIGHT),
            at(1, Cluster.ON_OFF, Attr.VALUE) to true,
            at(1, Cluster.LEVEL_CONTROL, Attr.VALUE) to 127L,
            at(1, Cluster.COLOR_CONTROL, Attr.COLOR_CAPABILITIES) to 0x1FL,
            at(1, Cluster.COLOR_CONTROL, Attr.COLOR_TEMPERATURE_MIREDS) to 370L,
            at(1, Cluster.COLOR_CONTROL, Attr.COLOR_TEMP_MIN_MIREDS) to 153L,
            at(1, Cluster.COLOR_CONTROL, Attr.COLOR_TEMP_MAX_MIREDS) to 500L,
            at(1, Cluster.COLOR_CONTROL, Attr.COLOR_MODE) to 2L,
            at(1, Cluster.COLOR_CONTROL, Attr.CURRENT_HUE) to 0L,
            at(1, Cluster.COLOR_CONTROL, Attr.CURRENT_SATURATION) to 254L,
        )
        val l = ClusterMapper.capabilities(n).find<LightCapability>()!!
        assertTrue(l.isOn); assertEquals(50, l.brightnessPercent)
        assertEquals(2702, l.colorTemperatureKelvin); assertEquals(2000..6535, l.colorTemperatureRange)
        assertEquals(LightColorMode.TEMPERATURE, l.colorMode)
        assertEquals(RgbColor(255, 0, 0), l.rgbColor)

        val dim = ClusterMapper.actions(DeviceCommand.SetBrightness(50), n)!!.single() as MatterAction.Invoke
        assertEquals(Cluster.LEVEL_CONTROL, dim.cluster); assertEquals(Cmd.MOVE_TO_LEVEL_WITH_ON_OFF, dim.command)
        assertEquals(127L, (TlvReader.read(dim.fields) as TlvStruct)[0])

        val ct = ClusterMapper.actions(DeviceCommand.SetColorTemperature(2700), n)!!.single() as MatterAction.Invoke
        assertEquals(370L, (TlvReader.read(ct.fields) as TlvStruct)[0])
        // außerhalb des Bereichs wird begrenzt
        val cold = ClusterMapper.actions(DeviceCommand.SetColorTemperature(9000), n)!!.single() as MatterAction.Invoke
        assertEquals(153L, (TlvReader.read(cold.fields) as TlvStruct)[0])

        val off = ClusterMapper.actions(DeviceCommand.SetOn(false), n)!!.single() as MatterAction.Invoke
        assertEquals(Cluster.ON_OFF, off.cluster); assertEquals(Cmd.OFF, off.command)
    }

    @Test fun `Zwischenstecker mit Leistungsmessung`() {
        val n = node(
            at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.ON_OFF_PLUG),
            at(1, Cluster.ON_OFF, Attr.VALUE) to true,
            at(2, Cluster.ELECTRICAL_POWER_MEASUREMENT, Attr.ACTIVE_POWER) to 45_300L,
            at(2, Cluster.ELECTRICAL_ENERGY_MEASUREMENT, Attr.CUMULATIVE_ENERGY_IMPORTED) to TlvStruct(mapOf(0 to 12_500_000L)),
        )
        val s = ClusterMapper.capabilities(n).find<SwitchCapability>()!!
        assertTrue(s.isOn); assertEquals(45.3, s.powerWatts!!, 0.001); assertEquals(12.5, s.energyKwh!!, 0.001)
        assertNull(ClusterMapper.actions(DeviceCommand.SetBrightness(40), n))
    }

    @Test fun `Storen - Matter zählt geschlossen, raum offen`() {
        val n = node(
            at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.WINDOW_COVERING),
            at(1, Cluster.WINDOW_COVERING, Attr.CURRENT_LIFT_PERCENT_100THS) to 2500L,
            at(1, Cluster.WINDOW_COVERING, Attr.OPERATIONAL_STATUS) to 0b0110L, // global: schließt
        )
        assertEquals(CoverCapability(75, CoverMovement.CLOSING), ClusterMapper.capabilities(n).find<CoverCapability>())
        val go = ClusterMapper.actions(DeviceCommand.SetCoverPosition(30), n)!!.single() as MatterAction.Invoke
        assertEquals(7000L, (TlvReader.read(go.fields) as TlvStruct)[0])
        assertEquals(Cmd.COVER_STOP, (ClusterMapper.actions(DeviceCommand.StopCover, n)!!.single() as MatterAction.Invoke).command)
    }

    @Test fun `Thermostat - Werte in Hundertstel-Grad, Sollwert wird geschrieben`() {
        val n = node(
            at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.THERMOSTAT),
            at(1, Cluster.THERMOSTAT, Attr.LOCAL_TEMPERATURE) to 2087L,
            at(1, Cluster.THERMOSTAT, Attr.OCCUPIED_HEATING_SETPOINT) to 2100L,
            at(1, Cluster.THERMOSTAT, Attr.SYSTEM_MODE) to 4L,
            at(1, Cluster.THERMOSTAT, Attr.CONTROL_SEQUENCE) to 2L,
            at(1, Cluster.THERMOSTAT, Attr.MIN_HEAT_LIMIT) to 700L,
            at(1, Cluster.THERMOSTAT, Attr.MAX_HEAT_LIMIT) to 3000L,
        )
        val t = ClusterMapper.capabilities(n).find<ThermostatCapability>()!!
        assertEquals(20.87, t.currentCelsius!!, 0.001); assertEquals(21.0, t.targetCelsius, 0.001)
        assertEquals(ThermostatMode.HEAT, t.mode); assertEquals(setOf(ThermostatMode.OFF, ThermostatMode.HEAT), t.supportedModes)
        assertEquals(7.0, t.minTargetCelsius, 0.001)
        val w = ClusterMapper.actions(DeviceCommand.SetTargetTemperature(21.5), n)!!.single() as MatterAction.Write
        assertEquals(Attr.OCCUPIED_HEATING_SETPOINT, w.attribute); assertEquals(2150L, TlvReader.read(w.value))
        val m = ClusterMapper.actions(DeviceCommand.SetThermostatMode(ThermostatMode.OFF), n)!!.single() as MatterAction.Write
        assertEquals(0L, TlvReader.read(m.value))
    }

    @Test fun `Kontaktsensor - StateValue true heißt geschlossen`() {
        val n = node(at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.CONTACT_SENSOR), at(1, Cluster.BOOLEAN_STATE, Attr.VALUE) to true)
        assertFalse(ClusterMapper.capabilities(n).find<ContactSensorCapability>()!!.isOpen)
    }

    @Test fun `Unbekanntes Gerät wird nur gelesen`() {
        val n = node(at(0, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.ROOT_NODE), at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(0x0850L))
        assertEquals(UnknownCapability(listOf(0x0850L)), ClusterMapper.capabilities(n).single())
        assertNull(ClusterMapper.actions(DeviceCommand.SetOn(true), n))
    }

    @Test fun `Verbundene Apps aus der Fabric-Liste`() {
        val n = node(
            at(0, Cluster.OPERATIONAL_CREDENTIALS, Attr.CURRENT_FABRIC_INDEX) to 3L,
            at(0, Cluster.OPERATIONAL_CREDENTIALS, Attr.FABRICS) to listOf(
                TlvStruct(mapOf(2 to 0x6006L, 5 to "", 0xFE to 1L)),
                TlvStruct(mapOf(2 to 0xFFF1L, 5 to "raum.", 0xFE to 3L)),
            ),
        )
        val admins = ClusterMapper.admins(n)
        assertTrue(admins.first().own); assertEquals(3, admins.first().fabricIndex)
        assertEquals(0x6006, admins.last().vendorId)
    }

    @Test fun `Farben - RGB und Hue-Sättigung passen zusammen`() {
        listOf(RgbColor(255, 0, 0), RgbColor(0, 128, 255), RgbColor(255, 200, 0)).forEach { c ->
            val (h, s) = ClusterMapper.rgbToHueSat(c)
            val back = ClusterMapper.hsvToRgb(h * 360.0 / 254, s / 254.0)
            // Helligkeit steckt bei Matter in LevelControl – Vergleich nach Normierung auf das hellste Signal
            val scale = 255.0 / maxOf(c.red, c.green, c.blue)
            assertTrue("$c → $back", abs(back.red - c.red * scale) < 6 && abs(back.green - c.green * scale) < 6 && abs(back.blue - c.blue * scale) < 6)
        }
    }

    @Test fun `Verbindungsart und Thread-Diagnose`() {
        val thread = node(
            Triple(0, Cluster.NETWORK_COMMISSIONING, Attr.FEATURE_MAP) to 0x2L,
            Triple(0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.ROUTING_ROLE) to 2L,
            Triple(0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.THREAD_NETWORK_NAME) to "MyHome1234",
            Triple(0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.EXTENDED_PAN_ID) to 0x0EAD00BEEF00CAFEL,
            Triple(0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.THREAD_CHANNEL) to 25L,
        )
        val n = ClusterMapper.network(thread)!!
        assertEquals(app.raum.domain.models.NetworkTransport.THREAD, n.transport)
        assertEquals(app.raum.domain.models.ThreadRole.SLEEPY_END_DEVICE, n.threadRole)
        assertEquals("MyHome1234", n.threadNetworkName)
        assertEquals("0ead00beef00cafe", n.threadExtPanId)
        assertEquals(25, n.threadChannel)

        val wifi = ClusterMapper.network(node(Triple(0, Cluster.NETWORK_COMMISSIONING, Attr.FEATURE_MAP) to 0x1L))!!
        assertEquals(app.raum.domain.models.NetworkTransport.WIFI, wifi.transport)
        assertNull(wifi.threadRole)
        // Nur Thread-Diagnose ohne Feature-Map (ältere Geräte) → Thread; gar nichts → unbekannt
        assertEquals(app.raum.domain.models.NetworkTransport.THREAD,
            ClusterMapper.network(node(Triple(0, Cluster.THREAD_NETWORK_DIAGNOSTICS, Attr.ROUTING_ROLE) to 5L))?.transport)
        assertNull(ClusterMapper.network(node()))
    }

    @Test fun `Abo nur auf vorhandene Cluster, ohne Beschreibung alles`() {
        val full = ClusterMapper.subscriptionPaths(null)
        assertEquals(ClusterMapper.SUBSCRIBED.size + ClusterMapper.READ_ONCE.size + 2 + ClusterMapper.NETWORK_PATHS.size, full.size)

        // WLAN-Schalter (z. B. Shelly): Endpunkt 0 Basis + NetworkCommissioning, Endpunkte 1/2 OnOff + Leistung
        val shelly = node(
            at(0, Cluster.DESCRIPTOR, Attr.SERVER_LIST) to listOf(Cluster.DESCRIPTOR, Cluster.BASIC_INFORMATION, Cluster.NETWORK_COMMISSIONING, Cluster.OPERATIONAL_CREDENTIALS),
            at(1, Cluster.DESCRIPTOR, Attr.SERVER_LIST) to listOf(Cluster.DESCRIPTOR, Cluster.ON_OFF, Cluster.ELECTRICAL_POWER_MEASUREMENT),
            at(2, Cluster.DESCRIPTOR, Attr.SERVER_LIST) to listOf(Cluster.DESCRIPTOR, Cluster.ON_OFF),
        )
        val paths = ClusterMapper.subscriptionPaths(shelly)
        val clusters = paths.map { it.cluster }.toSet()
        assertTrue(Cluster.ON_OFF in clusters)
        assertTrue(Cluster.ELECTRICAL_POWER_MEASUREMENT in clusters)
        assertFalse(Cluster.THERMOSTAT in clusters)
        assertFalse(Cluster.THREAD_NETWORK_DIAGNOSTICS in clusters)
        assertTrue(paths.any { it.cluster == Cluster.NETWORK_COMMISSIONING && it.attribute == Attr.FEATURE_MAP && it.endpoint == 0 })
        assertTrue(paths.size < full.size / 2)
    }

    // --- Mehrkanalgeräte -----------------------------------------------------------------------

    private fun onOff(ep: Int, type: Long, on: Boolean) = arrayOf(
        at(ep, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(type),
        at(ep, Cluster.ON_OFF, Attr.VALUE) to on,
    )

    @Test fun `Zwei Relais - zweiter Kanal eigenständig, Befehle an den richtigen Endpunkt`() {
        val n = node(
            *onOff(1, DeviceType.ON_OFF_PLUG, true), *onOff(2, DeviceType.ON_OFF_PLUG, false),
            at(1, Cluster.ELECTRICAL_POWER_MEASUREMENT, Attr.ACTIVE_POWER) to 10_000L,
            at(2, Cluster.ELECTRICAL_POWER_MEASUREMENT, Attr.ACTIVE_POWER) to 60_000L,
        )
        val main = ClusterMapper.capabilities(n).find<SwitchCapability>()!!
        assertTrue(main.isOn); assertEquals(10.0, main.powerWatts!!, 0.001)

        val ch = ClusterMapper.channels(n).single()
        assertEquals(2, ch.endpoint)
        val second = ch.capabilities.find<SwitchCapability>()!!
        assertFalse(second.isOn); assertEquals(60.0, second.powerWatts!!, 0.001)

        assertEquals(1, (ClusterMapper.actions(DeviceCommand.SetOn(false), n)!!.single() as MatterAction.Invoke).endpoint)
        val toSecond = ClusterMapper.actions(DeviceCommand.SetOn(true), n, channel = 2)!!.single() as MatterAction.Invoke
        assertEquals(2, toSecond.endpoint); assertEquals(Cmd.ON, toSecond.command)
        // Unbekannter Kanal oder Hauptkanal-Endpunkt als „Kanal“: nicht adressierbar
        assertNull(ClusterMapper.actions(DeviceCommand.SetOn(true), n, channel = 3))
        assertNull(ClusterMapper.actions(DeviceCommand.SetOn(true), n, channel = 1))
    }

    @Test fun `Leuchte und Steckdose in einem Gerät - Schaltfunktion geht nicht mehr verloren`() {
        val n = node(
            *onOff(1, DeviceType.DIMMABLE_LIGHT, true),
            at(1, Cluster.LEVEL_CONTROL, Attr.VALUE) to 127L,
            *onOff(2, DeviceType.ON_OFF_PLUG, true),
        )
        val caps = ClusterMapper.capabilities(n)
        assertTrue(caps.find<LightCapability>()!!.isOn)
        assertNull(caps.find<SwitchCapability>()) // Hauptkanal ist die Leuchte
        val plug = ClusterMapper.channels(n).single()
        assertEquals(2, plug.endpoint)
        assertTrue(plug.capabilities.find<SwitchCapability>()!!.isOn)

        // Dimmen am Hauptkanal, nicht am Steckdosen-Kanal
        assertEquals(1, ClusterMapper.actions(DeviceCommand.SetBrightness(50), n)!!.single().endpoint)
        assertNull(ClusterMapper.actions(DeviceCommand.SetBrightness(50), n, channel = 2))
        assertEquals(2, ClusterMapper.actions(DeviceCommand.SetOn(false), n, channel = 2)!!.single().endpoint)
    }

    @Test fun `Kanal kann nur schalten und dimmen - Storen- und Thermostatbefehle nicht`() {
        val n = node(*onOff(1, DeviceType.ON_OFF_LIGHT, true), *onOff(2, DeviceType.ON_OFF_LIGHT, false),
            at(3, Cluster.WINDOW_COVERING, Attr.CURRENT_LIFT_PERCENT_100THS) to 0L)
        assertNull(ClusterMapper.actions(DeviceCommand.OpenCover, n, channel = 2))
        assertTrue(ClusterMapper.channels(n).single().capabilities.single() is LightCapability)
    }

    @Test fun `On-Off an anderem Gerätetyp ist kein eigener Kanal`() {
        // z. B. Klimagerät mit On/Off neben einer Leuchte: bleibt wie bisher Teil des Hauptgeräts
        val n = node(*onOff(1, DeviceType.ON_OFF_LIGHT, true), *onOff(2, DeviceType.THERMOSTAT, true))
        assertTrue(ClusterMapper.channels(n).isEmpty())
        assertTrue(ClusterMapper.channels(node(*onOff(1, DeviceType.ON_OFF_PLUG, true))).isEmpty())
    }

    @Test fun `Kleinste Helligkeitsstufe wird nicht als 0 Prozent angezeigt`() {
        fun level(raw: Long) = ClusterMapper.capabilities(node(
            at(1, Cluster.DESCRIPTOR, Attr.DEVICE_TYPE_LIST) to types(DeviceType.DIMMABLE_LIGHT),
            at(1, Cluster.ON_OFF, Attr.VALUE) to true,
            at(1, Cluster.LEVEL_CONTROL, Attr.VALUE) to raw,
        )).find<LightCapability>()!!.brightnessPercent
        assertEquals(1, level(1))
        assertEquals(0, level(0))
        assertEquals(100, level(254))
    }

    @Test fun `Sollwert mit Modus aus der Szene - nicht aus dem veralteten Zwischenspeicher`() {
        // Gerät steht laut Zwischenspeicher noch auf Heizen (Moduswechsel noch nicht zurückgemeldet)
        val n = node(at(1, Cluster.THERMOSTAT, Attr.SYSTEM_MODE) to 4L, at(1, Cluster.THERMOSTAT, Attr.LOCAL_TEMPERATURE) to 2100L)
        val cool = ClusterMapper.actions(DeviceCommand.SetTargetTemperature(24.0, app.raum.domain.models.ThermostatMode.COOL), n)!!.single() as MatterAction.Write
        assertEquals(Attr.OCCUPIED_COOLING_SETPOINT, cool.attribute)
        val heat = ClusterMapper.actions(DeviceCommand.SetTargetTemperature(21.0), n)!!.single() as MatterAction.Write
        assertEquals(Attr.OCCUPIED_HEATING_SETPOINT, heat.attribute)
    }
}
