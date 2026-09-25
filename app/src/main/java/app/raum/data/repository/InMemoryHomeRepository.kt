package app.raum.data.repository

import app.raum.domain.models.Automation
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.Home
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.repositories.HomeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** Flüchtige Implementierung für Unit-Tests und Previews. */
class InMemoryHomeRepository(
    homeName: String,
    rooms: List<Room>,
    devices: List<DeviceMetadata>,
    scenes: List<Scene>,
    automations: List<Automation>,
) : HomeRepository {

    private val _home = MutableStateFlow<Home?>(Home(UUID.randomUUID(), homeName, Instant.now()))
    private val _rooms = MutableStateFlow(rooms.sortedBy { it.sortOrder })
    private val _devices = MutableStateFlow(devices)
    private val _scenes = MutableStateFlow(scenes)
    private val _automations = MutableStateFlow(automations)

    override val home: StateFlow<Home?> = _home.asStateFlow()
    override val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()
    override val deviceMetadata: StateFlow<List<DeviceMetadata>> = _devices.asStateFlow()
    override val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()
    override val automations: StateFlow<List<Automation>> = _automations.asStateFlow()

    override suspend fun upsertDevice(metadata: DeviceMetadata) {
        _devices.update { list ->
            val idx = list.indexOfFirst { it.matterNodeId == metadata.matterNodeId && it.endpointId == metadata.endpointId }
            if (idx >= 0) list.toMutableList().also { it[idx] = metadata } else list + metadata
        }
    }

    override suspend fun removeDevice(nodeId: ULong) {
        val removed = _devices.value.filter { it.matterNodeId == nodeId }.map { it.id }.toSet()
        _devices.update { list -> list.filterNot { it.matterNodeId == nodeId } }
        _scenes.update { scenes -> scenes.map { s -> s.copy(actions = s.actions.filterNot { it.deviceId in removed }) } }
    }

    override suspend fun setFavorite(deviceId: UUID, favorite: Boolean) {
        _devices.update { list -> list.map { if (it.id == deviceId) it.copy(favorite = favorite) else it } }
    }

    override suspend fun addRoom(name: String, icon: String): Room {
        val room = Room(UUID.randomUUID(), name.trim(), icon, (_rooms.value.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        _rooms.update { it + room }
        return room
    }

    override suspend fun updateRoom(room: Room) {
        _rooms.update { list -> list.map { if (it.id == room.id) room else it }.sortedBy { it.sortOrder } }
    }

    override suspend fun deleteRoom(roomId: UUID) {
        _rooms.update { list -> list.filterNot { it.id == roomId } }
        _devices.update { list -> list.map { if (it.roomId == roomId) it.copy(roomId = null) else it } }
    }

    override suspend fun scene(sceneId: UUID): Scene? = _scenes.value.firstOrNull { it.id == sceneId }

    override suspend fun upsertScene(scene: Scene) {
        _scenes.update { list ->
            val idx = list.indexOfFirst { it.id == scene.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = scene } else list + scene
        }
    }

    override suspend fun deleteScene(sceneId: UUID) {
        _scenes.update { list -> list.filterNot { it.id == sceneId } }
    }

    override suspend fun automation(automationId: UUID): Automation? = _automations.value.firstOrNull { it.id == automationId }

    override suspend fun upsertAutomation(automation: Automation) {
        _automations.update { list ->
            val idx = list.indexOfFirst { it.id == automation.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = automation } else list + automation
        }
    }

    override suspend fun deleteAutomation(automationId: UUID) {
        _automations.update { list -> list.filterNot { it.id == automationId } }
    }

    override suspend fun setAutomationEnabled(automationId: UUID, enabled: Boolean) {
        _automations.update { list -> list.map { if (it.id == automationId) it.copy(enabled = enabled) else it } }
    }
}
