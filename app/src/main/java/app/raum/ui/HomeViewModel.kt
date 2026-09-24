package app.raum.ui

import app.raum.i18n.Strings
import app.raum.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.domain.models.Automation
import app.raum.domain.models.Capability
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.CoverCapability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.Home
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.LightCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.SwitchCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.find
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.UiMessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Zusammenfassung eines Raums für Kacheln und Kopfzeilen (ROM-003). */
data class RoomSummary(
    val room: Room,
    val devices: List<Device>,
    val temperatureCelsius: Double?,
    val humidityPercent: Double?,
    val lightsOn: Int,
    val lightsTotal: Int,
    val coversOpenAverage: Int?,
    val thermostatTarget: Double?,
    val occupied: Boolean?,
    val openContacts: Int,
    val offlineCount: Int,
)

enum class NoticeKind(val isWarning: Boolean) { OFFLINE(true), OPEN(false), LOW_BATTERY(true), BORDER_ROUTER(true) }

/** Systemhinweis für die Übersicht; der Text entsteht erst in der Oberfläche (übersetzbar). */
data class HomeNotice(val kind: NoticeKind, val deviceName: String) {
    val isWarning: Boolean get() = kind.isWarning
}

data class HomeUiState(
    val home: Home? = null,
    val rooms: List<RoomSummary> = emptyList(),
    val devices: List<Device> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val automations: List<Automation> = emptyList(),
    val runningSceneId: UUID? = null,
    /** Thread-Geräte unerreichbar und kein Border Router im Netz (Spez. 9.4, 8.3) */
    val borderRouterUnreachable: Boolean = false,
) {
    val favorites: List<Device> get() = devices.filter { it.favorite }
    val unassigned: List<Device> get() = devices.filter { d -> d.roomId == null || rooms.none { it.room.id == d.roomId } }
    fun room(id: UUID): RoomSummary? = rooms.firstOrNull { it.room.id == id }
    fun roomName(id: UUID?): String? = rooms.firstOrNull { it.room.id == id }?.room?.name

    val lightsOn: Int get() = devices.count { d -> d.isOnline && d.capabilities.find<LightCapability>()?.isOn == true }
    val offline: List<Device> get() = devices.filterNot { it.isOnline }

    /** Systemhinweise für die Übersicht (Spez. 9.4). */
    val notices: List<HomeNotice>
        get() = buildList {
            // Ursache vor den Folgen: fehlender Border Router erklärt die offline-Thread-Geräte
            if (borderRouterUnreachable) add(HomeNotice(NoticeKind.BORDER_ROUTER, ""))
            offline.forEach { add(HomeNotice(NoticeKind.OFFLINE, it.displayName)) }
            devices.filter { d -> d.isOnline && d.capabilities.find<ContactSensorCapability>()?.isOpen == true }
                .forEach { add(HomeNotice(NoticeKind.OPEN, it.displayName)) }
            devices.filter { d -> d.isOnline && (d.capabilities.find<app.raum.domain.models.BatteryCapability>()?.percent ?: 100) < 15 }
                .forEach { add(HomeNotice(NoticeKind.LOW_BATTERY, it.displayName)) }
        }
}

class HomeViewModel(
    private val repository: HomeRepository,
    private val deviceService: DeviceService,
    private val sceneRunner: SceneRunner,
    private val messages: UiMessageBus,
    private val strings: Strings,
    thread: app.raum.thread.ThreadService,
) : ViewModel() {

    private val runningScene = MutableStateFlow<UUID?>(null)

    val state: StateFlow<HomeUiState> = combine(
        repository.home,
        repository.rooms,
        deviceService.devices,
        combine(repository.scenes, repository.automations, ::Pair),
        combine(runningScene, thread.status) { r, t -> r to t.borderRouterUnreachable },
    ) { home, rooms, devices, (scenes, automations), (running, brUnreachable) ->
        HomeUiState(
            home = home,
            rooms = rooms.map { room -> summarize(room, devices.filter { it.roomId == room.id }) },
            devices = devices,
            scenes = scenes,
            automations = automations,
            runningSceneId = running,
            borderRouterUnreachable = brUnreachable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val messageFlow = messages.messages

    fun send(device: Device, command: DeviceCommand) {
        viewModelScope.launch { deviceService.send(device, command) }
    }

    /** Primäraktion einer Karte: Licht/Stecker umschalten. */
    fun toggle(device: Device) {
        val isOn = device.capabilities.firstNotNullOfOrNull {
            when (it) {
                is LightCapability -> it.isOn
                is SwitchCapability -> it.isOn
                else -> null
            }
        } ?: return
        send(device, DeviceCommand.SetOn(!isOn))
    }

    fun roomAction(devices: List<Device>, command: DeviceCommand, label: String) {
        viewModelScope.launch { deviceService.sendToAll(devices, command, label) }
    }

    fun runScene(scene: Scene) {
        if (runningScene.value != null) return
        viewModelScope.launch {
            runningScene.value = scene.id
            try { sceneRunner.run(scene) } finally { runningScene.value = null }
        }
    }

    /** SCN-005: Kopie mit neuer ID, landet am Ende der Liste. */
    fun duplicateScene(scene: Scene) = viewModelScope.launch {
        val copy = scene.copy(id = UUID.randomUUID(), name = strings.get(R.string.copy_suffix, scene.name).take(30))
        repository.upsertScene(copy)
        messages.info(strings.get(R.string.msg_created, copy.name))
    }

    fun deleteScene(scene: Scene) = viewModelScope.launch {
        repository.deleteScene(scene.id)
        messages.info(strings.get(R.string.msg_deleted, scene.name))
    }

    fun setFavorite(device: Device, favorite: Boolean) = viewModelScope.launch { deviceService.setFavorite(device, favorite) }
    fun rename(device: Device, name: String) = viewModelScope.launch { if (name.isNotBlank()) deviceService.rename(device, name) }
    fun assignRoom(device: Device, roomId: UUID?) = viewModelScope.launch { deviceService.assignRoom(device, roomId) }
    fun remove(device: Device) = viewModelScope.launch { deviceService.remove(device) }

    fun addRoom(name: String, icon: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.addRoom(name, icon)
    }
    fun updateRoom(room: Room) = viewModelScope.launch { repository.updateRoom(room) }
    fun deleteRoom(room: Room) = viewModelScope.launch {
        repository.deleteRoom(room.id)
        messages.info(strings.get(R.string.msg_room_deleted, room.name))
    }

    /** Verschiebt einen Raum in der Sortierung um [delta] Positionen (ROM-001). */
    fun moveRoom(room: Room, delta: Int) = viewModelScope.launch {
        val ordered = repository.rooms.value.sortedBy { it.sortOrder }.toMutableList()
        val from = ordered.indexOfFirst { it.id == room.id }
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (from < 0 || from == to) return@launch
        ordered.add(to, ordered.removeAt(from))
        ordered.forEachIndexed { i, r -> if (r.sortOrder != i) repository.updateRoom(r.copy(sortOrder = i)) }
    }


    private fun summarize(room: Room, devices: List<Device>): RoomSummary {
        val online = devices.filter { it.isOnline }
        fun <T : Capability> all(pick: (Capability) -> T?) = online.flatMap { d -> d.capabilities.mapNotNull(pick) }
        val lights = devices.mapNotNull { it.capabilities.find<LightCapability>() }
        val temps = all { it as? TemperatureSensorCapability }.map { it.celsius } +
            all { it as? ThermostatCapability }.mapNotNull { it.currentCelsius }
        val hums = all { it as? HumiditySensorCapability }.map { it.percent }
        val covers = all { it as? CoverCapability }.map { it.openPercent }
        val occupancy = all { it as? OccupancyCapability }
        return RoomSummary(
            room = room,
            devices = devices,
            // Dedizierte Sensoren bevorzugen, sonst Thermostat-Istwert.
            temperatureCelsius = (all { it as? TemperatureSensorCapability }.map { it.celsius }.ifEmpty { temps }).averageOrNull(),
            humidityPercent = hums.averageOrNull(),
            lightsOn = online.count { it.capabilities.find<LightCapability>()?.isOn == true },
            lightsTotal = lights.size,
            coversOpenAverage = covers.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            thermostatTarget = all { it as? ThermostatCapability }.firstOrNull()?.targetCelsius,
            occupied = occupancy.takeIf { it.isNotEmpty() }?.any { it.isOccupied },
            openContacts = all { it as? ContactSensorCapability }.count { it.isOpen },
            offlineCount = devices.count { !it.isOnline },
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
