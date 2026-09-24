package app.raum

import app.raum.i18n.XmlStrings
import app.raum.automation.AutomationDescriber
import app.raum.automation.TestClock
import app.raum.automation.engine.AutomationEngine
import app.raum.automation.triggers.GeoLocation
import app.raum.data.backup.BackupCodec
import app.raum.data.backup.BackupMapper
import app.raum.data.backup.SettingsDto
import app.raum.data.repository.InMemoryHomeRepository
import app.raum.diagnostics.EventLog
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.Home
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.SceneAction
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.Trigger
import app.raum.domain.models.deviceIdForNode
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.controller.mock.MockHomeSeed
import app.raum.matter.controller.mock.MockMatterController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.system.measureNanoTime

/**
 * Lastprofil nach Spez. 12.1: 100 Geräte (PERF-005), 30 Räume (PERF-006),
 * 100 Szenen und 100 Automationen (PERF-007). Die Grenzwerte sind bewusst großzügig für
 * CI-Maschinen gewählt; das Panel (RK3576) ist schneller als ein ausgelasteter CI-Runner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private val rooms = (0 until 30).map { Room(UUID.randomUUID(), "Raum $it", "home", it) }
    private val seed = (0 until 100).map { i ->
        val node = (0x1000 + i).toULong()
        MockHomeSeed.SeedDevice(
            node, "Gerät $i", "x", "Hersteller", "Produkt",
            when (i % 3) {
                0 -> listOf(LightCapability(false, 50, 3000, 2200..6500))
                1 -> listOf(OccupancyCapability(false))
                else -> listOf(TemperatureSensorCapability(20.0))
            },
        )
    }
    private val metadata = seed.mapIndexed { i, d ->
        DeviceMetadata(deviceIdForNode(d.nodeId), d.nodeId, d.name, rooms[i % 30].id, d.vendor, d.product, i % 10 == 0)
    }
    private val lights = metadata.filterIndexed { i, _ -> i % 3 == 0 }
    private val sensors = metadata.filterIndexed { i, _ -> i % 3 == 1 }
    private val temps = metadata.filterIndexed { i, _ -> i % 3 == 2 }

    private val scenes = (0 until 100).map { s ->
        Scene(UUID.randomUUID(), "Szene $s", "home", lights.shuffled().take(8).map { SceneAction(it.id, DeviceCommand.SetOn(s % 2 == 0)) })
    }
    private val automations = (0 until 100).map { a ->
        Automation(
            UUID.randomUUID(), "Automation $a", true,
            triggers = listOf(
                if (a % 2 == 0) Trigger.DeviceStateChanged(sensors[a % sensors.size].id, DeviceProperty.OCCUPIED, true)
                else Trigger.SensorThreshold(temps[a % temps.size].id, SensorMetric.TEMPERATURE, Comparison.ABOVE, 25.0 + a % 5),
                Trigger.TimeOfDay((a * 13) % 1440),
            ),
            conditions = listOf(Condition.TimeWindow(0, 0)),
            actions = listOf(AutomationAction.ControlDevice(lights[a % lights.size].id, listOf(DeviceCommand.SetOn(true)))),
        )
    }

    private fun ms(nanos: Long) = nanos / 1_000_000.0

    @Test
    fun `device list, triggers and scheduler scale to spec load`() = runTest {
        val clock = TestClock(ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, zone))
        val controller = MockMatterController(backgroundScope, latencyMs = 0L..0L, simulationTickMs = null, clock = clock, seed = seed)
        val repo = InMemoryHomeRepository("Last", rooms, metadata, scenes, automations)
        val log = EventLog(clock = clock)
        val messages = UiMessageBus()
        val strings = XmlStrings("de")
        val devices = DeviceService(controller, repo, messages, log, backgroundScope, strings)
        val engine = AutomationEngine(repo, devices, SceneRunner(devices, messages, log, strings), messages, log,
            { GeoLocation(52.5, 13.4) }, backgroundScope, strings, clock = clock)
        runCurrent()
        assertEquals(100, devices.devices.value.size)

        // Gerätezustand ändern → neue Geräteliste (was die UI bei jedem Ereignis bekommt)
        val listNs = measureNanoTime {
            repeat(50) { i ->
                controller.simulateExternalChange(seed[i * 2].nodeId) { it.map { c -> if (c is LightCapability) c.copy(isOn = !c.isOn) else c } }
                runCurrent()
            }
        } / 50
        println("Geräteliste nach Zustandsänderung: %.2f ms".format(ms(listNs)))
        assertTrue("Geräteliste zu langsam: ${ms(listNs)} ms", ms(listNs) < 50)

        // Trigger-Auswertung: 100 Automationen gegen ein Geräteereignis
        val before = devices.confirmedDevices.value.associateBy { it.id }
        val after = before.mapValues { (_, d) -> d.copy(capabilities = d.capabilities.map { c -> if (c is OccupancyCapability) c.copy(isOccupied = true) else c }) }
        val triggerNs = measureNanoTime { repeat(100) { engine.onDevicesChanged(before, after) } } / 100
        println("Trigger-Auswertung (100 Automationen): %.2f ms".format(ms(triggerNs)))
        assertTrue(ms(triggerNs) < 20)

        // Scheduler: ein ganzer Tag Minuten-Ticks
        val dayNs = measureNanoTime {
            var t = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, zone)
            repeat(1440) { engine.onMinute(t); t = t.plusMinutes(1) }
        }
        println("Scheduler 1440 Minuten: %.1f ms gesamt".format(ms(dayNs)))
        assertTrue(ms(dayNs) < 2_000)
        runCurrent()
    }

    @Test
    fun `summaries and backup of full load are fast`() {
        val devicesMap = metadata.associate { m ->
            m.id to app.raum.domain.models.Device(m.id, m.matterNodeId, m.displayName, m.roomId, null, null,
                app.raum.domain.models.OnlineState.ONLINE, false, null, seed.first { it.nodeId == m.matterNodeId }.capabilities)
        }
        val describer = AutomationDescriber(devicesMap, scenes.associate { it.id to it.name }, XmlStrings("de"))
        val descNs = measureNanoTime { automations.forEach { describer.sentence(it) } }
        println("100 Zusammenfassungen: %.1f ms".format(ms(descNs)))
        assertTrue(ms(descNs) < 500)

        val doc = BackupMapper.toDocument(Home(UUID.randomUUID(), "Last", Instant.now()), rooms, metadata, scenes, automations, SettingsDto(), "t", 2)
        lateinit var bytes: ByteArray
        val encNs = measureNanoTime { bytes = BackupCodec.encode(doc, "passwort-123", iterations = 10_000) }
        println("Sicherung (verschlüsselt, ohne KDF-Kosten): %.1f ms, %d KB".format(ms(encNs), bytes.size / 1024))
        assertTrue(bytes.size < 200_000)
        val plan = BackupMapper.plan(BackupCodec.decode(bytes, "passwort-123"), 2, seed.map { it.nodeId }.toSet())
        assertEquals(100, plan.automations.size)
    }
}
