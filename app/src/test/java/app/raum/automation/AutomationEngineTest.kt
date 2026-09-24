package app.raum.automation

import app.raum.i18n.XmlStrings
import app.raum.automation.engine.AutomationEngine
import app.raum.automation.triggers.GeoLocation
import app.raum.automation.triggers.SunCalculator
import app.raum.data.repository.InMemoryHomeRepository
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogLevel
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Capability
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SunEvent
import app.raum.domain.models.Trigger
import app.raum.domain.models.find
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {

    private val zone = ZoneId.of("Europe/Berlin")
    /** Montag, 21.09.2026 */
    private fun monday(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 9, 21, h, m, 0, 0, zone)

    private inner class Fixture(scope: TestScope, time: ZonedDateTime) {
        val clock = TestClock(time)
        val controller = MockMatterController(scope.backgroundScope, latencyMs = 0L..0L, simulationTickMs = null, clock = clock)
        val repo = InMemoryHomeRepository("Test", MockHomeSeed.rooms, MockHomeSeed.deviceMetadata, MockHomeSeed.scenes, emptyList())
        val messages = UiMessageBus()
        val log = EventLog(clock = clock)
        val strings = XmlStrings("de")
        val devices = DeviceService(controller, repo, messages, log, scope.backgroundScope, strings)
        val engine = AutomationEngine(
            repo, devices, SceneRunner(devices, messages, log, strings), messages, log,
            location = { GeoLocation(52.52, 13.405) }, scope = scope.backgroundScope, strings = strings, clock = clock,
        )

        fun node(name: String) = MockHomeSeed.devices.first { it.name == name }
        fun id(name: String) = node(name).id
        fun caps(name: String) = devices.devices.value.first { it.displayName == name }.capabilities
        fun light(name: String) = caps(name).find<LightCapability>()!!
        fun change(name: String, transform: (Capability) -> Capability) =
            controller.simulateExternalChange(node(name).nodeId) { it.map(transform) }
        fun automationLog(level: LogLevel? = null) = log.recent.value.filter { it.automationId != null && (level == null || it.level == level) }
    }

    private suspend fun TestScope.fixture(time: ZonedDateTime, vararg automations: Automation): Fixture {
        val f = Fixture(this, time)
        automations.forEach { f.repo.upsertAutomation(it) }
        runCurrent()
        return f
    }

    private fun automation(
        name: String,
        triggers: List<Trigger>,
        actions: List<AutomationAction>,
        conditions: List<Condition> = emptyList(),
        enabled: Boolean = true,
    ) = Automation(UUID.randomUUID(), name, enabled, triggers, conditions, actions)

    private fun turnOn(id: UUID) = AutomationAction.ControlDevice(id, listOf(DeviceCommand.SetOn(true)))
    private fun turnOff(id: UUID) = AutomationAction.ControlDevice(id, listOf(DeviceCommand.SetOn(false)))

    @Test
    fun `time trigger respects weekdays`() = runTest {
        val f0 = Fixture(this, monday(21))
        val a = automation("Abend", listOf(Trigger.TimeOfDay(22 * 60, (1..5).toSet())), listOf(turnOn(f0.id("Stehlampe"))))
        val f = fixture(monday(21), a)

        f.engine.onMinute(monday(22).plusDays(5)) // Samstag
        runCurrent()
        assertFalse(f.light("Stehlampe").isOn)

        f.engine.onMinute(monday(22))
        runCurrent()
        assertTrue(f.light("Stehlampe").isOn)
        assertTrue(f.automationLog().first().message.contains("Ausgeführt"))
    }

    @Test
    fun `sun trigger fires exactly at computed sunset minute`() = runTest {
        val sunset = SunCalculator.time(SunEvent.SUNSET, monday(0).toLocalDate(), GeoLocation(52.52, 13.405), zone)!!
            .truncatedTo(ChronoUnit.MINUTES)
        val f0 = Fixture(this, monday(12))
        val a = automation("Storen", listOf(Trigger.Sun(SunEvent.SUNSET, offsetMinutes = 15)), listOf(turnOn(f0.id("Stehlampe"))))
        val f = fixture(monday(12), a)

        f.engine.onMinute(sunset.plusMinutes(14))
        runCurrent()
        assertFalse(f.light("Stehlampe").isOn)
        f.engine.onMinute(sunset.plusMinutes(15))
        runCurrent()
        assertTrue(f.light("Stehlampe").isOn)
    }

    @Test
    fun `disabled and incomplete automations never run`() = runTest {
        val f0 = Fixture(this, monday(12))
        val lamp = f0.id("Stehlampe")
        val f = fixture(
            monday(12),
            automation("Aus", listOf(Trigger.TimeOfDay(12 * 60)), listOf(turnOn(lamp)), enabled = false),
            automation("Leer", listOf(Trigger.TimeOfDay(12 * 60)), emptyList()),
        )
        f.engine.onMinute(monday(12))
        runCurrent()
        assertFalse(f.light("Stehlampe").isOn)
        assertTrue(f.automationLog().isEmpty())
    }

    @Test
    fun `motion triggers light only inside time window, then turns it off after delay`() = runTest {
        val f0 = Fixture(this, monday(12))
        val flur = f0.id("Flurlicht")
        val a = automation(
            "Flur",
            triggers = listOf(Trigger.DeviceStateChanged(f0.id("Bewegung Flur"), DeviceProperty.OCCUPIED, true)),
            conditions = listOf(Condition.TimeWindow(18 * 60, 7 * 60)),
            actions = listOf(turnOn(flur), AutomationAction.Delay(180), turnOff(flur)),
        )
        val f = fixture(monday(12), a)
        f.engine.start()
        runCurrent()

        // Mittags: Bedingung nicht erfüllt
        f.change("Bewegung Flur") { if (it is OccupancyCapability) it.copy(isOccupied = true) else it }
        runCurrent()
        assertFalse(f.light("Flurlicht").isOn)
        assertTrue(f.automationLog().first().message.contains("Bedingung nicht erfüllt"))

        // Abends: Bewegung endet und beginnt erneut → Licht an
        f.clock.now = monday(20)
        f.change("Bewegung Flur") { if (it is OccupancyCapability) it.copy(isOccupied = false) else it }
        runCurrent()
        f.change("Bewegung Flur") { if (it is OccupancyCapability) it.copy(isOccupied = true) else it }
        runCurrent()
        assertTrue(f.light("Flurlicht").isOn)

        // Erneute Bewegung während der Wartezeit wird ignoriert (läuft noch)
        f.change("Bewegung Flur") { if (it is OccupancyCapability) it.copy(isOccupied = false) else it }
        runCurrent()
        f.change("Bewegung Flur") { if (it is OccupancyCapability) it.copy(isOccupied = true) else it }
        runCurrent()
        assertTrue(f.automationLog(LogLevel.WARNING).first().message.contains("läuft noch"))

        assertTrue(f.automationLog().any { it.message.contains("Gestartet") })
        advanceTimeBy(179_000); runCurrent()
        assertTrue(f.light("Flurlicht").isOn)
        advanceTimeBy(2_000); runCurrent()
        assertFalse(f.light("Flurlicht").isOn)
        assertTrue(f.automationLog().first().message.contains("Ausgeführt"))
    }

    @Test
    fun `sensor threshold fires only when crossing`() = runTest {
        val f0 = Fixture(this, monday(12))
        val a = automation(
            "Lüften",
            listOf(Trigger.SensorThreshold(f0.id("Klima Bad"), SensorMetric.HUMIDITY, Comparison.ABOVE, 70.0)),
            listOf(AutomationAction.Notify("Bitte lüften")),
        )
        val f = fixture(monday(12), a)
        f.engine.start(); runCurrent()

        fun humidity(v: Double) = f.change("Klima Bad") { if (it is HumiditySensorCapability) it.copy(percent = v) else it }
        humidity(72.0); runCurrent()
        humidity(75.0); runCurrent()
        humidity(68.0); runCurrent()
        humidity(71.0); runCurrent()
        assertEquals(2, f.automationLog().count { it.message.contains("Ausgeführt") })
    }

    @Test
    fun `endless loop between two automations is stopped`() = runTest {
        val f0 = Fixture(this, monday(12))
        val lamp = f0.id("Stehlampe")
        val offWhenOn = automation("Aus wenn an", listOf(Trigger.DeviceStateChanged(lamp, DeviceProperty.POWER, true)), listOf(turnOff(lamp)))
        val onWhenOff = automation("An wenn aus", listOf(Trigger.DeviceStateChanged(lamp, DeviceProperty.POWER, false)), listOf(turnOn(lamp)))
        val f = fixture(monday(12), offWhenOn, onWhenOff)
        f.engine.start(); runCurrent()

        f.change("Stehlampe") { if (it is LightCapability) it.copy(isOn = true) else it }
        runCurrent() // terminiert nur, wenn der Schleifenschutz greift

        val paused = f.automationLog(LogLevel.ERROR).filter { it.message.contains("Zu häufig") }
        assertTrue(paused.isNotEmpty())
        assertNotNull(f.engine.status.value.values.firstOrNull { it.pausedUntil != null })
        val runs = f.automationLog().count { it.message.contains("Ausgeführt") }
        assertTrue("Ausführungen begrenzt, war $runs", runs <= 12)
    }

    @Test
    fun `manual run ignores conditions`() = runTest {
        val f0 = Fixture(this, monday(12))
        val a = automation(
            "Nachts", listOf(Trigger.TimeOfDay(0)), listOf(turnOn(f0.id("Stehlampe"))),
            conditions = listOf(Condition.TimeWindow(22 * 60, 6 * 60)),
        )
        val f = fixture(monday(12), a)
        f.engine.runNow(a)
        runCurrent()
        assertTrue(f.light("Stehlampe").isOn)
        assertTrue(f.automationLog().first().message.contains("manuell"))
    }

    @Test
    fun `offline device in action is reported as partial failure`() = runTest {
        val f0 = Fixture(this, monday(12))
        val a = automation("Tür", listOf(Trigger.TimeOfDay(12 * 60)), listOf(turnOn(f0.id("Stehlampe")), turnOn(f0.id("Deckenleuchte"))))
        val f = fixture(monday(12), a)
        f.controller.setOnline(f.node("Deckenleuchte").nodeId, false)
        runCurrent()
        f.engine.onMinute(monday(12))
        runCurrent()
        assertTrue(f.light("Stehlampe").isOn) // andere Aktionen laufen weiter
        val entry = f.automationLog(LogLevel.ERROR).first()
        assertTrue(entry.message, entry.message.contains("Deckenleuchte"))
    }

    @Test
    fun `connectivity trigger fires when device goes offline`() = runTest {
        val f0 = Fixture(this, monday(12))
        val a = automation("Offline", listOf(Trigger.Connectivity(f0.id("Deckenleuchte"), online = false)), listOf(AutomationAction.Notify("weg")))
        val f = fixture(monday(12), a)
        f.engine.start(); runCurrent()
        f.controller.setOnline(f.node("Deckenleuchte").nodeId, false)
        runCurrent()
        assertTrue(f.automationLog().first().message.contains("Ausgeführt"))
    }
}
