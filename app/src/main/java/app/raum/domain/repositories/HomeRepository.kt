package app.raum.domain.repositories

import app.raum.domain.models.Automation
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.Home
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Lokale Konfiguration des Zuhauses. Produktiv: Room/SQLite ([app.raum.data.database.RoomHomeRepository]),
 * in Unit-Tests: [app.raum.data.repository.InMemoryHomeRepository].
 */
interface HomeRepository {
    /** null, solange noch kein Zuhause angelegt wurde (Erstinbetriebnahme). */
    val home: StateFlow<Home?>
    val rooms: StateFlow<List<Room>>
    val deviceMetadata: StateFlow<List<DeviceMetadata>>
    val scenes: StateFlow<List<Scene>>
    val automations: StateFlow<List<Automation>>

    suspend fun upsertDevice(metadata: DeviceMetadata)
    suspend fun removeDevice(nodeId: ULong)
    suspend fun setFavorite(deviceId: UUID, favorite: Boolean)

    suspend fun addRoom(name: String, icon: String): Room
    suspend fun updateRoom(room: Room)
    /** Geräte des Raums verlieren ihre Zuordnung, werden aber nicht gelöscht. */
    suspend fun deleteRoom(roomId: UUID)

    suspend fun scene(sceneId: UUID): Scene?
    /** Legt eine Szene an oder ersetzt sie vollständig (inkl. Aktionen) – atomar. */
    suspend fun upsertScene(scene: Scene)
    suspend fun deleteScene(sceneId: UUID)

    suspend fun automation(automationId: UUID): Automation?
    suspend fun upsertAutomation(automation: Automation)
    suspend fun deleteAutomation(automationId: UUID)
    suspend fun setAutomationEnabled(automationId: UUID, enabled: Boolean)
}
