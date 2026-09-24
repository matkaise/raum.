package app.raum.matter.controller.mock

import app.raum.data.database.InitialHomeData
import app.raum.i18n.Strings
import app.raum.R
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Comparison
import app.raum.domain.models.Condition
import app.raum.domain.models.DeviceProperty
import app.raum.domain.models.SensorMetric
import app.raum.domain.models.SunEvent
import app.raum.domain.models.Trigger
import app.raum.domain.models.Capability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.LightColorMode
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.RgbColor
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.SceneAction
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.ThermostatMode
import app.raum.domain.models.deviceIdForNode
import app.raum.domain.models.BatteryCapability
import java.util.UUID

/**
 * Beispielwohnung für Emulator, UI-Entwicklung und Tests (Spez. 13.2).
 * Wird durch echte Persistenz (M3) und echte Matter-Nodes (M2) ersetzt.
 */
object MockHomeSeed {

    data class SeedDevice(
        val nodeId: ULong,
        val name: String,
        val roomKey: String,
        val vendor: String,
        val product: String,
        val capabilities: List<Capability>,
        val favorite: Boolean = false,
        val online: Boolean = true,
    ) {
        val id: UUID get() = deviceId(nodeId)
    }

    fun deviceId(nodeId: ULong): UUID = deviceIdForNode(nodeId)
    private fun roomId(key: String): UUID = UUID.nameUUIDFromBytes("raum-room-$key".toByteArray())
    private fun sceneId(key: String): UUID = UUID.nameUUIDFromBytes("raum-scene-$key".toByteArray())

    val rooms: List<Room> = listOf(
        Room(roomId("wohnen"), "Wohnzimmer", "sofa", 0),
        Room(roomId("kueche"), "Küche", "kitchen", 1),
        Room(roomId("schlafen"), "Schlafzimmer", "bed", 2),
        Room(roomId("bad"), "Bad", "bath", 3),
        Room(roomId("buero"), "Büro", "desk", 4),
        Room(roomId("flur"), "Flur", "door", 5),
    )

    private val warmWhite = 2200..6500

    val devices: List<SeedDevice> = listOf(
        // Wohnzimmer
        SeedDevice(0x11u, "Deckenleuchte", "wohnen", "Nanoleaf", "Essentials Bulb",
            listOf(LightCapability(true, 70, 2900, warmWhite)), favorite = true),
        SeedDevice(0x12u, "Stehlampe", "wohnen", "Nanoleaf", "Essentials Lightstrip",
            listOf(LightCapability(false, 45, 2700, warmWhite, RgbColor(255, 170, 90), LightColorMode.TEMPERATURE)), favorite = true),
        SeedDevice(0x13u, "Storen Wohnzimmer", "wohnen", "Eve", "Eve MotionBlinds",
            listOf(CoverCapability(100)), favorite = true),
        SeedDevice(0x14u, "Thermostat Wohnzimmer", "wohnen", "Eve", "Eve Thermo",
            listOf(ThermostatCapability(21.2, 21.5, ThermostatMode.HEAT), BatteryCapability(82)), favorite = true),
        SeedDevice(0x15u, "Klima Wohnzimmer", "wohnen", "Eve", "Eve Weather",
            listOf(TemperatureSensorCapability(21.6), HumiditySensorCapability(46.0), BatteryCapability(64))),
        SeedDevice(0x16u, "Präsenz Wohnzimmer", "wohnen", "Aqara", "Presence Sensor FP300",
            listOf(OccupancyCapability(true))),
        SeedDevice(0x17u, "Balkontür", "wohnen", "Eve", "Eve Door & Window",
            listOf(ContactSensorCapability(false), BatteryCapability(12)), online = false),
        // Küche
        SeedDevice(0x21u, "Arbeitsplatte", "kueche", "Meross", "Smart LED Strip",
            listOf(LightCapability(false, 100))),
        SeedDevice(0x22u, "Kaffeemaschine", "kueche", "Eve", "Eve Energy",
            listOf(SwitchCapability(false, 0.0, 12.4)), favorite = true),
        SeedDevice(0x23u, "Fenster Küche", "kueche", "Aqara", "Door and Window Sensor P2",
            listOf(ContactSensorCapability(false), BatteryCapability(91))),
        // Schlafzimmer
        SeedDevice(0x31u, "Nachttischlampe", "schlafen", "Philips Hue", "White Ambiance",
            listOf(LightCapability(false, 30, 2400, warmWhite))),
        SeedDevice(0x32u, "Rollladen Schlafzimmer", "schlafen", "Somfy", "Tahoma Matter",
            listOf(CoverCapability(30))),
        SeedDevice(0x33u, "Thermostat Schlafzimmer", "schlafen", "tado°", "Smart Radiator Thermostat X",
            listOf(ThermostatCapability(18.9, 18.0, ThermostatMode.HEAT), BatteryCapability(55))),
        // Bad
        SeedDevice(0x41u, "Spiegelleuchte", "bad", "Shelly", "Plus 1",
            listOf(LightCapability(false))),
        SeedDevice(0x42u, "Klima Bad", "bad", "Aqara", "Climate Sensor W100",
            listOf(TemperatureSensorCapability(22.8), HumiditySensorCapability(64.0), BatteryCapability(77))),
        // Büro
        SeedDevice(0x51u, "Schreibtischlampe", "buero", "IKEA", "Tradfri Bulb",
            listOf(LightCapability(true, 80, 4000, warmWhite))),
        SeedDevice(0x52u, "Monitor & Dock", "buero", "Eve", "Eve Energy",
            listOf(SwitchCapability(true, 38.5, 41.9))),
        SeedDevice(0x53u, "Klima Büro", "buero", "Eve", "Eve Room",
            listOf(TemperatureSensorCapability(20.1), HumiditySensorCapability(41.0))),
        // Flur
        SeedDevice(0x61u, "Flurlicht", "flur", "Shelly", "Dimmer Gen3",
            listOf(LightCapability(false, 60))),
        SeedDevice(0x62u, "Bewegung Flur", "flur", "Aqara", "Motion Sensor P2",
            listOf(OccupancyCapability(false), BatteryCapability(88))),
    )

    val deviceMetadata: List<DeviceMetadata> = devices.map {
        DeviceMetadata(
            id = it.id,
            matterNodeId = it.nodeId,
            displayName = it.name,
            roomId = roomId(it.roomKey),
            vendorName = it.vendor,
            productName = it.product,
            favorite = it.favorite,
        )
    }

    private fun lightsOff(): List<SceneAction> = devices
        .filter { d -> d.capabilities.any { it is LightCapability } }
        .map { SceneAction(it.id, DeviceCommand.SetOn(false)) }

    private fun covers(command: DeviceCommand): List<SceneAction> = devices
        .filter { d -> d.capabilities.any { it is CoverCapability } }
        .map { SceneAction(it.id, command) }

    private fun thermostats(celsius: Double): List<SceneAction> = devices
        .filter { d -> d.capabilities.any { it is ThermostatCapability } }
        .map { SceneAction(it.id, DeviceCommand.SetTargetTemperature(celsius)) }

    private fun action(nodeId: Int, command: DeviceCommand) = SceneAction(deviceId(nodeId.toULong()), command)

    val scenes: List<Scene> = listOf(
        Scene(sceneId("alles-aus"), "Alles aus", "power", lightsOff() + action(0x22, DeviceCommand.SetOn(false))),
        Scene(
            sceneId("filmabend"), "Filmabend", "movie",
            listOf(
                action(0x11, DeviceCommand.SetBrightness(15)),
                action(0x11, DeviceCommand.SetColorTemperature(2400)),
                action(0x12, DeviceCommand.SetColor(RgbColor(120, 80, 255))),
                action(0x12, DeviceCommand.SetBrightness(35)),
                action(0x21, DeviceCommand.SetOn(false)),
                action(0x13, DeviceCommand.CloseCover),
            ),
        ),
        Scene(
            sceneId("gute-nacht"), "Gute Nacht", "night",
            lightsOff() + covers(DeviceCommand.CloseCover) + thermostats(18.0),
        ),
        Scene(
            sceneId("morgen"), "Morgen", "sunrise",
            covers(DeviceCommand.OpenCover) + listOf(
                action(0x21, DeviceCommand.SetBrightness(100)),
                action(0x11, DeviceCommand.SetBrightness(80)),
                action(0x11, DeviceCommand.SetColorTemperature(4000)),
                action(0x22, DeviceCommand.SetOn(true)),
            ) + thermostats(21.5),
        ),
        Scene(
            sceneId("abwesend"), "Abwesend", "away",
            lightsOff() + action(0x22, DeviceCommand.SetOn(false)) + thermostats(17.0),
        ),
    )

    private fun automationId(key: String): UUID = UUID.nameUUIDFromBytes("raum-aut-$key".toByteArray())
    private fun dev(nodeId: Int) = deviceId(nodeId.toULong())

    val automations: List<Automation> = listOf(
        Automation(
            automationId("storen-abend"), "Storen bei Sonnenuntergang", enabled = true,
            triggers = listOf(Trigger.Sun(SunEvent.SUNSET)),
            actions = listOf(AutomationAction.ControlDevice(dev(0x13), listOf(DeviceCommand.CloseCover))),
        ),
        Automation(
            automationId("flur-bewegung"), "Flurlicht bei Bewegung", enabled = true,
            triggers = listOf(Trigger.DeviceStateChanged(dev(0x62), DeviceProperty.OCCUPIED, true)),
            conditions = listOf(Condition.TimeWindow(18 * 60, 7 * 60)),
            actions = listOf(
                AutomationAction.ControlDevice(dev(0x61), listOf(DeviceCommand.SetOn(true), DeviceCommand.SetBrightness(40))),
                AutomationAction.Delay(180),
                AutomationAction.ControlDevice(dev(0x61), listOf(DeviceCommand.SetOn(false))),
            ),
        ),
        Automation(
            automationId("fenster-heizung"), "Heizung aus bei offenem Fenster", enabled = false,
            triggers = listOf(Trigger.DeviceStateChanged(dev(0x23), DeviceProperty.CONTACT_OPEN, true)),
            actions = listOf(
                AutomationAction.Notify("Fenster Küche geöffnet – Heizung Wohnzimmer wird abgeschaltet."),
                AutomationAction.ControlDevice(dev(0x14), listOf(DeviceCommand.SetThermostatMode(ThermostatMode.OFF))),
            ),
        ),
        Automation(
            automationId("morgen"), "Guten Morgen (werktags)", enabled = false,
            triggers = listOf(Trigger.TimeOfDay(6 * 60 + 30, weekdays = (1..5).toSet())),
            actions = listOf(AutomationAction.RunScene(sceneId("morgen"))),
        ),
        Automation(
            automationId("bad-lueften"), "Bad lüften", enabled = true,
            triggers = listOf(Trigger.SensorThreshold(dev(0x42), SensorMetric.HUMIDITY, Comparison.ABOVE, 70.0)),
            actions = listOf(AutomationAction.Notify("Luftfeuchte im Bad über 70 % – bitte lüften.")),
        ),
    )
}

/**
 * Beispielwohnung in der App-Sprache (die Konstanten oben sind die deutsche Referenz für Tests).
 * IDs, Node-IDs und Verknüpfungen bleiben identisch – nur die sichtbaren Namen werden übersetzt.
 */
object LocalizedDemo {
    private val NAMES: Map<String, Int> = mapOf(
        // Räume
        "Wohnzimmer" to R.string.demo_room_living, "Küche" to R.string.demo_room_kitchen,
        "Schlafzimmer" to R.string.demo_room_bedroom, "Bad" to R.string.demo_room_bath,
        "Büro" to R.string.demo_room_office, "Flur" to R.string.demo_room_hall,
        // Geräte
        "Deckenleuchte" to R.string.demo_dev_ceiling, "Stehlampe" to R.string.demo_dev_floor_lamp,
        "Storen Wohnzimmer" to R.string.demo_dev_blinds_living, "Thermostat Wohnzimmer" to R.string.demo_dev_thermostat_living,
        "Klima Wohnzimmer" to R.string.demo_dev_climate_living, "Präsenz Wohnzimmer" to R.string.demo_dev_presence_living,
        "Balkontür" to R.string.demo_dev_balcony_door, "Arbeitsplatte" to R.string.demo_dev_countertop,
        "Kaffeemaschine" to R.string.demo_dev_coffee, "Fenster Küche" to R.string.demo_dev_kitchen_window,
        "Nachttischlampe" to R.string.demo_dev_bedside, "Rollladen Schlafzimmer" to R.string.demo_dev_shutter_bedroom,
        "Thermostat Schlafzimmer" to R.string.demo_dev_thermostat_bedroom, "Spiegelleuchte" to R.string.demo_dev_mirror,
        "Klima Bad" to R.string.demo_dev_climate_bath, "Schreibtischlampe" to R.string.demo_dev_desk_lamp,
        "Monitor & Dock" to R.string.demo_dev_monitor, "Klima Büro" to R.string.demo_dev_climate_office,
        "Flurlicht" to R.string.demo_dev_hall_light, "Bewegung Flur" to R.string.demo_dev_hall_motion,
        // Szenen
        "Alles aus" to R.string.demo_scene_all_off, "Filmabend" to R.string.demo_scene_movie,
        "Gute Nacht" to R.string.demo_scene_night, "Morgen" to R.string.demo_scene_morning, "Abwesend" to R.string.demo_scene_away,
        // Automationen und Hinweise
        "Storen bei Sonnenuntergang" to R.string.demo_auto_blinds_sunset, "Flurlicht bei Bewegung" to R.string.demo_auto_hall_motion,
        "Heizung aus bei offenem Fenster" to R.string.demo_auto_window_heating, "Guten Morgen (werktags)" to R.string.demo_auto_morning,
        "Bad lüften" to R.string.demo_auto_ventilate,
        "Fenster Küche geöffnet – Heizung Wohnzimmer wird abgeschaltet." to R.string.demo_notify_window,
        "Luftfeuchte im Bad über 70 % – bitte lüften." to R.string.demo_notify_humidity,
    )

    private fun t(name: String, strings: Strings) = NAMES[name]?.let(strings::get) ?: name

    fun initialData(strings: Strings) = InitialHomeData(
        rooms = MockHomeSeed.rooms.map { it.copy(name = t(it.name, strings)) },
        devices = MockHomeSeed.deviceMetadata.map { it.copy(displayName = t(it.displayName, strings)) },
        scenes = MockHomeSeed.scenes.map { it.copy(name = t(it.name, strings)) },
        automations = MockHomeSeed.automations.map { a ->
            a.copy(
                name = t(a.name, strings),
                actions = a.actions.map { action ->
                    if (action is AutomationAction.Notify) action.copy(message = t(action.message, strings)) else action
                },
            )
        },
    )
}
