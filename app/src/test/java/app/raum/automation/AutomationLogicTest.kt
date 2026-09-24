package app.raum.automation

import app.raum.i18n.XmlStrings
import app.raum.automation.conditions.ConditionEvaluator
import app.raum.automation.triggers.GeoLocation
import app.raum.automation.triggers.SunCalculator
import app.raum.data.database.toDomain
import app.raum.data.database.toEntity
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.OnlineState
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SunEvent
import app.raum.domain.models.Trigger
import app.raum.matter.controller.mock.MockHomeSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class AutomationLogicTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val berlinLoc = GeoLocation(52.52, 13.405)

    private fun hm(t: ZonedDateTime?) = t?.let { "%02d:%02d".format(it.hour, it.minute) }

    private fun assertWithinMinute(expected: String, actual: ZonedDateTime?) {
        val (h, m) = expected.split(":").map { it.toInt() }
        val diff = kotlin.math.abs(actual!!.hour * 60 + actual.minute - (h * 60 + m))
        assertTrue("erwartet $expected ±1 min, war ${hm(actual)}", diff <= 1)
    }

    @Test
    fun `sun times for Berlin match published values within a minute`() {
        // Referenz: timeanddate.com für Berlin
        assertWithinMinute("04:43", SunCalculator.time(SunEvent.SUNRISE, LocalDate.of(2026, 6, 21), berlinLoc, berlin))
        assertWithinMinute("21:33", SunCalculator.time(SunEvent.SUNSET, LocalDate.of(2026, 6, 21), berlinLoc, berlin))
        assertWithinMinute("08:16", SunCalculator.time(SunEvent.SUNRISE, LocalDate.of(2026, 12, 21), berlinLoc, berlin))
        assertWithinMinute("15:54", SunCalculator.time(SunEvent.SUNSET, LocalDate.of(2026, 12, 21), berlinLoc, berlin))
    }

    @Test
    fun `polar night has no sunrise`() {
        assertNull(SunCalculator.time(SunEvent.SUNRISE, LocalDate.of(2026, 12, 21), GeoLocation(78.2, 15.6), ZoneId.of("Arctic/Longyearbyen")))
    }

    @Test
    fun `time window crossing midnight`() {
        assertTrue(ConditionEvaluator.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(ConditionEvaluator.inWindow(5 * 60 + 59, 22 * 60, 6 * 60))
        assertFalse(ConditionEvaluator.inWindow(6 * 60, 22 * 60, 6 * 60))
        assertFalse(ConditionEvaluator.inWindow(12 * 60, 22 * 60, 6 * 60))
        assertTrue(ConditionEvaluator.inWindow(12 * 60, 8 * 60, 18 * 60))
        assertTrue(ConditionEvaluator.inWindow(3, 0, 0)) // ganztägig
    }

    private fun sensor(online: Boolean = true, occupied: Boolean = true, humidity: Double = 50.0) = Device(
        UUID.randomUUID(), 1u, "Sensor", null, null, null,
        if (online) OnlineState.ONLINE else OnlineState.OFFLINE, false, null,
        listOf(OccupancyCapability(occupied), HumiditySensorCapability(humidity)),
    )

    @Test
    fun `device conditions are false for offline devices`() {
        val now = ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, berlin)
        val on = sensor(occupied = true)
        val off = sensor(online = false, occupied = true)
        val c1 = Condition.DeviceStateIs(on.id, DeviceProperty.OCCUPIED, true)
        val c2 = Condition.DeviceStateIs(off.id, DeviceProperty.OCCUPIED, true)
        val devices = mapOf(on.id to on, off.id to off)
        assertTrue(ConditionEvaluator.evaluate(listOf(c1), now, devices).satisfied)
        val r = ConditionEvaluator.evaluate(listOf(c1, c2), now, devices)
        assertFalse(r.satisfied)
        assertEquals(c2, r.failed)
    }

    @Test
    fun `sensor and weekday conditions`() {
        val monday = ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, berlin)
        val s = sensor(humidity = 72.0)
        val devices = mapOf(s.id to s)
        assertTrue(ConditionEvaluator.isSatisfied(Condition.SensorValue(s.id, SensorMetric.HUMIDITY, Comparison.ABOVE, 70.0), monday, devices))
        assertFalse(ConditionEvaluator.isSatisfied(Condition.SensorValue(s.id, SensorMetric.HUMIDITY, Comparison.BELOW, 70.0), monday, devices))
        assertTrue(ConditionEvaluator.isSatisfied(Condition.OnWeekdays(setOf(1)), monday, devices))
        assertFalse(ConditionEvaluator.isSatisfied(Condition.OnWeekdays(setOf(6, 7)), monday, devices))
    }

    @Test
    fun `describer produces readable German sentence`() {
        val devices = MockHomeSeed.devices.associate { d ->
            d.id to Device(d.id, d.nodeId, d.name, null, null, null, OnlineState.ONLINE, false, null, d.capabilities)
        }
        val describer = AutomationDescriber(devices, MockHomeSeed.scenes.associate { it.id to it.name }, XmlStrings("de"), locale = java.util.Locale.GERMANY)
        val flur = MockHomeSeed.automations.first { it.name == "Flurlicht bei Bewegung" }
        assertEquals(
            "Wenn „Bewegung Flur“ Bewegung erkennt, und es zwischen 18:00 und 07:00 Uhr ist, " +
                "dann „Flurlicht“ einschalten (40 %), 3 Min. warten, „Flurlicht“ ausschalten.",
            describer.sentence(flur),
        )
        assertEquals("es 06:30 Uhr ist (Mo–Fr)", describer.trigger(Trigger.TimeOfDay(390, (1..5).toSet())))
        assertEquals("es 30 Min. nach Sonnenuntergang ist", describer.trigger(Trigger.Sun(SunEvent.SUNSET, 30)))
        assertEquals("die Sonne aufgeht", describer.trigger(Trigger.Sun(SunEvent.SUNRISE)))
        assertTrue(describer.sentence(Automation(UUID.randomUUID(), "x", true)).startsWith("Unvollständig"))
    }

    @Test
    fun `describer produces English sentence with English word order`() {
        val devices = MockHomeSeed.devices.associate { d ->
            d.id to Device(d.id, d.nodeId, d.name, null, null, null, OnlineState.ONLINE, false, null, d.capabilities)
        }
        val describer = AutomationDescriber(devices, MockHomeSeed.scenes.associate { it.id to it.name }, XmlStrings("en"), locale = java.util.Locale.ENGLISH)
        val flur = MockHomeSeed.automations.first { it.name == "Flurlicht bei Bewegung" }
        assertEquals(
            "When “Bewegung Flur” detects motion, and it is between 18:00 and 07:00, " +
                "then turn on “Flurlicht” (40 %), wait 3 min, turn off “Flurlicht”.",
            describer.sentence(flur),
        )
        assertEquals("it is 06:30 (Mon–Fri)", describer.trigger(Trigger.TimeOfDay(390, (1..5).toSet())))
        assertEquals("it is 30 min after sunset", describer.trigger(Trigger.Sun(SunEvent.SUNSET, 30)))
    }

    @Test
    fun `temperatures follow the chosen unit`() {
        val sensor = MockHomeSeed.devices.first { it.name == "Klima Bad" }
        val t = Trigger.SensorThreshold(sensor.id, SensorMetric.TEMPERATURE, Comparison.ABOVE, 25.0)
        val devices = mapOf(sensor.id to Device(sensor.id, sensor.nodeId, sensor.name, null, null, null, OnlineState.ONLINE, false, null, sensor.capabilities))
        val de = AutomationDescriber(devices, emptyMap(), XmlStrings("de"), locale = java.util.Locale.GERMANY)
        val enF = AutomationDescriber(devices, emptyMap(), XmlStrings("en"), app.raum.data.preferences.TemperatureUnit.FAHRENHEIT, java.util.Locale.ENGLISH)
        assertEquals("„Klima Bad“ über 25,0 °C steigt", de.trigger(t))
        assertEquals("“Klima Bad” rises above 77 °F", enF.trigger(t))
    }

    @Test
    fun `automation definition round-trips through database entity`() {
        MockHomeSeed.automations.forEach { a -> assertEquals(a, a.toEntity().toDomain()) }
    }

    @Test
    fun `stored automation format is stable`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val e = Automation(
            id, "A", true,
            triggers = listOf(Trigger.TimeOfDay(600, setOf(1))),
            actions = listOf(AutomationAction.ControlDevice(id, listOf(DeviceCommand.SetOn(true))), AutomationAction.Delay(5)),
        ).toEntity()
        assertEquals("""[{"type":"time","minuteOfDay":600,"weekdays":[1]}]""", e.triggersJson)
        assertEquals(
            """[{"type":"control_device","deviceId":"00000000-0000-0000-0000-000000000001","commands":[{"type":"on","on":true}]},{"type":"delay","seconds":5}]""",
            e.actionsJson,
        )
    }

    @Test
    fun `unknown elements from a newer version are skipped individually`() {
        val e = Automation(UUID.randomUUID(), "A", true, triggers = listOf(Trigger.SystemStart)).toEntity()
        val withUnknown = e.copy(triggersJson = """[{"type":"weather","rain":true},{"type":"system_start"}]""")
        assertEquals(listOf(Trigger.SystemStart), withUnknown.toDomain().triggers)
    }
}
