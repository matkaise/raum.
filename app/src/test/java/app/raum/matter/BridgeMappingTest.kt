package app.raum.matter

import app.raum.domain.models.BatteryCapability
import app.raum.domain.models.Capability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.CoverMovement
import app.raum.domain.models.Device
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.OnlineState
import app.raum.domain.models.RgbColor
import app.raum.domain.models.LightColorMode
import app.raum.matter.bridge.ColorMath
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.matter.bridge.BridgeKind
import app.raum.matter.bridge.BridgeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Welche raum.-Geräte die echte Bridge wie zeigt – und mit welchen Werten. */
class BridgeMappingTest {

    private fun device(vararg caps: Capability, name: String = "Gerät", online: Boolean = true) = Device(
        id = UUID.fromString("3f2b8c1e-0000-4000-8000-00000000abcd"), matterNodeId = 7u, displayName = name, roomId = null,
        vendorName = "Shelly", productName = "Shelly 1", onlineState = if (online) OnlineState.ONLINE else OnlineState.OFFLINE,
        favorite = false, lastSeenAt = null, capabilities = caps.toList(),
    )

    @Test fun `Gerätearten`() {
        assertEquals(BridgeKind.DIMMABLE_LIGHT, BridgeMapping.kind(device(LightCapability(true, 40))))
        assertEquals(BridgeKind.COLOR_TEMP_LIGHT, BridgeMapping.kind(device(LightCapability(true, 40, 3000, 2200..6500))))
        assertEquals(BridgeKind.COLOR_LIGHT, BridgeMapping.kind(device(LightCapability(true, 40, 3000, 2200..6500, RgbColor(255, 0, 0)))))
        assertEquals(BridgeKind.ON_OFF_LIGHT, BridgeMapping.kind(device(LightCapability(true))))
        assertEquals(BridgeKind.ON_OFF_PLUG, BridgeMapping.kind(device(SwitchCapability(false))))
        assertEquals(BridgeKind.CONTACT_SENSOR, BridgeMapping.kind(device(ContactSensorCapability(true), BatteryCapability(80))))
        assertEquals(BridgeKind.OCCUPANCY_SENSOR, BridgeMapping.kind(device(OccupancyCapability(false))))
        assertEquals(BridgeKind.TEMP_HUMIDITY_SENSOR, BridgeMapping.kind(device(TemperatureSensorCapability(21.5), HumiditySensorCapability(40.0))))
        assertEquals(BridgeKind.TEMPERATURE_SENSOR, BridgeMapping.kind(device(TemperatureSensorCapability(21.5))))
        assertEquals(BridgeKind.HUMIDITY_SENSOR, BridgeMapping.kind(device(HumiditySensorCapability(40.0))))
        assertEquals(BridgeKind.THERMOSTAT, BridgeMapping.kind(device(ThermostatCapability(21.0, 20.0, ThermostatMode.HEAT))))
        assertEquals(BridgeKind.COVER, BridgeMapping.kind(device(CoverCapability(50, CoverMovement.STOPPED))))
        // Storen-Aktor mit Leistungsmessung (Shelly 2PM als Store) bleibt ein Store
        assertEquals(BridgeKind.COVER, BridgeMapping.kind(device(CoverCapability(50), SwitchCapability(false))))
        // Geräte ohne bekannte Fähigkeiten (noch keine Daten) nicht
        assertNull(BridgeMapping.kind(device()))
    }

    @Test fun `Werte und Namen`() {
        val light = BridgeMapping.entry(device(LightCapability(true, 30), name = "Deckenleuchte"), 5, BridgeKind.DIMMABLE_LIGHT)
        assertEquals(5, light.endpoint)
        assertEquals("3f2b8c1e000040008000" + "00000000abcd", light.uniqueId)
        assertEquals(32, light.uniqueId.length)
        assertTrue(light.on)
        assertEquals(76, light.level)
        assertTrue(light.reachable)

        val sensor = BridgeMapping.entry(
            device(TemperatureSensorCapability(21.57), HumiditySensorCapability(40.4), online = false), 3, BridgeKind.TEMP_HUMIDITY_SENSOR,
        )
        assertEquals(2157, sensor.tempC100)
        assertEquals(4040, sensor.humidity100)
        assertFalse(sensor.reachable)
        assertEquals(-1, sensor.level)

        // Kontakt: Matter-BooleanState TRUE = geschlossen
        assertTrue(BridgeMapping.entry(device(ContactSensorCapability(isOpen = false)), 4, BridgeKind.CONTACT_SENSOR).contact)
        assertFalse(BridgeMapping.entry(device(ContactSensorCapability(isOpen = true)), 4, BridgeKind.CONTACT_SENSOR).contact)
    }

    @Test fun `Helligkeit hin und zurück`() {
        assertEquals(254, BridgeMapping.percentToLevel(100))
        assertEquals(3, BridgeMapping.percentToLevel(1))
        assertEquals(3, BridgeMapping.percentToLevel(0)) // 0 % gibt es als Helligkeit nicht (dafür „aus“)
        for (p in 1..100) assertEquals(p, BridgeMapping.levelToPercent(BridgeMapping.percentToLevel(p)))
        assertEquals(100, BridgeMapping.levelToPercent(254))
    }

    @Test fun `Namen auf 32 Byte UTF-8 gekürzt, ohne Zeichen zu zerschneiden`() {
        val name = "Schlafzimmer-Stehleuchte über dem Bett"
        val cut = BridgeMapping.utf8Prefix(name, 32)
        assertTrue(cut.encodeToByteArray().size <= 32)
        assertTrue(name.startsWith(cut))
        val umlauts = "ÄÖÜäöüÄÖÜäöüÄÖÜäöüÄ" // 2 Byte je Zeichen
        assertEquals(16, BridgeMapping.utf8Prefix(umlauts, 32).length)
        assertEquals("kurz", BridgeMapping.utf8Prefix("kurz", 32))
        assertEquals("ab", BridgeMapping.utf8Prefix("ab😀", 5)) // 4-Byte-Zeichen passt nicht mehr ganz
    }

    @Test fun `Thermostat und Storen`() {
        val t = BridgeMapping.entry(
            device(ThermostatCapability(21.14, 19.5, ThermostatMode.HEAT, setOf(ThermostatMode.OFF, ThermostatMode.HEAT), 5.0, 30.0)),
            6, BridgeKind.THERMOSTAT,
        )
        assertEquals(2114, t.tempC100)
        assertEquals(1950, t.setpointC100)
        assertEquals(4, t.systemMode)          // Matter: Heizen
        assertEquals(0x1, t.flags)             // nur Heizen
        assertEquals(500, t.minC100)
        assertEquals(3000, t.maxC100)
        val all = BridgeMapping.entry(
            device(ThermostatCapability(null, 22.0, ThermostatMode.AUTO, ThermostatMode.entries.toSet())), 6, BridgeKind.THERMOSTAT,
        )
        assertEquals(0x7, all.flags)
        assertEquals(Int.MIN_VALUE, all.tempC100) // keine Ist-Temperatur bekannt
        for (m in ThermostatMode.entries) assertEquals(m, BridgeMapping.fromMatterMode(BridgeMapping.toMatterMode(m)))
        assertNull(BridgeMapping.fromMatterMode(7)) // Lüften – kennt raum. nicht

        val c = BridgeMapping.entry(device(CoverCapability(openPercent = 30, movement = CoverMovement.CLOSING)), 7, BridgeKind.COVER)
        assertEquals(7000, c.coverClosed100ths) // Matter zählt „geschlossen“
        assertEquals(2, c.coverMovement)
        assertEquals(0, BridgeMapping.entry(device(CoverCapability(100)), 7, BridgeKind.COVER).coverClosed100ths)
    }

    @Test fun `Farbleuchten`() {
        val warm = BridgeMapping.entry(
            device(LightCapability(true, 50, colorTemperatureKelvin = 2700, colorTemperatureRange = 2200..6500, colorMode = LightColorMode.TEMPERATURE)),
            8, BridgeKind.COLOR_TEMP_LIGHT,
        )
        assertEquals(0x10, warm.flags)
        assertEquals(2, warm.colorMode)
        assertEquals(370, warm.mireds)          // 1 000 000 / 2700
        assertEquals(154, warm.minC100)         // 6500 K
        assertEquals(455, warm.maxC100)         // 2200 K

        val red = BridgeMapping.entry(
            device(LightCapability(true, 50, 3000, 2200..6500, RgbColor(255, 0, 0), LightColorMode.COLOR)), 9, BridgeKind.COLOR_LIGHT,
        )
        assertEquals(0x18, red.flags)
        assertEquals(0, red.colorMode)
        assertEquals(0, red.hue)
        assertEquals(254, red.saturation)
        assertEquals(0.64, red.colorX / 65536.0, 0.01)   // sRGB-Primärfarbe Rot
        assertEquals(0.33, red.colorY / 65536.0, 0.01)
    }

    @Test fun `Farbumrechnung hin und zurück`() {
        for (c in listOf(RgbColor(255, 0, 0), RgbColor(0, 255, 0), RgbColor(0, 0, 255), RgbColor(255, 128, 0), RgbColor(128, 0, 255))) {
            val (x, y) = ColorMath.rgbToXy(c)
            val back = ColorMath.xyToRgb(x, y)
            // Gleicher Farbton (Helligkeit getrennt) – je Kanal höchstens wenig Abweichung
            assertTrue("$c → $back", kotlin.math.abs(back.red - c.red) <= 3 && kotlin.math.abs(back.green - c.green) <= 3 && kotlin.math.abs(back.blue - c.blue) <= 3)
            val (h, s) = app.raum.matter.chip.ClusterMapper.rgbToHueSat(c)
            val hs = BridgeMapping.hueSatToRgb(h.toInt(), s.toInt())
            assertTrue("$c ↔ $hs", kotlin.math.abs(hs.red - c.red) <= 4 && kotlin.math.abs(hs.green - c.green) <= 4 && kotlin.math.abs(hs.blue - c.blue) <= 4)
        }
        val (wx, wy) = ColorMath.rgbToXy(RgbColor(255, 255, 255))
        assertEquals(0.3127, wx / 65536.0, 0.002)             // Weißpunkt D65
        assertEquals(0.3290, wy / 65536.0, 0.002)
        assertEquals(RgbColor(255, 255, 255), ColorMath.rgbToXy(RgbColor(0, 0, 0)).let { ColorMath.xyToRgb(it.first, it.second) })
        assertEquals(4000, ColorMath.miredsToKelvin(ColorMath.kelvinToMireds(4000)))
    }
}
