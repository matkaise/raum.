package app.raum.data.database

import androidx.room.withTransaction
import app.raum.domain.models.Automation
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.Home
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.repositories.HomeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.util.UUID

/** Startdaten für ein neues Zuhause (Entwicklung: Mock-Wohnung). */
data class InitialHomeData(
    val rooms: List<Room> = emptyList(),
    val devices: List<DeviceMetadata> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val automations: List<Automation> = emptyList(),
)

/** Persistente Konfiguration auf Room/SQLite (M3). */
class RoomHomeRepository(
    private val db: RaumDatabase,
    scope: CoroutineScope,
) : HomeRepository {

    private val dao = db.homeDao()

    override val home: StateFlow<Home?> = dao.observeHome()
        .map { it?.toDomain() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val rooms: StateFlow<List<Room>> = dao.observeRooms()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val deviceMetadata: StateFlow<List<DeviceMetadata>> = dao.observeDevices()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val scenes: StateFlow<List<Scene>> = dao.observeScenes()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val automations: StateFlow<List<Automation>> = dao.observeAutomations()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Legt beim allerersten Start das Zuhause an (ONB-001) und übernimmt optional Startdaten.
     * Idempotent: bei vorhandenem Zuhause passiert nichts.
     * @return true, wenn ein neues Zuhause angelegt wurde.
     */
    suspend fun initializeIfEmpty(homeName: String, initial: InitialHomeData = InitialHomeData()): Boolean =
        db.withTransaction {
            if (dao.homeCount() > 0) return@withTransaction false
            dao.upsertHome(Home(UUID.randomUUID(), homeName, Instant.now()).toEntity())
            dao.upsertRooms(initial.rooms.map { it.toEntity() })
            dao.upsertDevices(initial.devices.map { it.toEntity() })
            initial.scenes.forEachIndexed { i, scene ->
                val (entity, actions) = scene.toEntities(i)
                dao.replaceScene(entity, actions)
            }
            dao.upsertAutomations(initial.automations.map { it.toEntity() })
            true
        }

    /**
     * Ersetzt die komplette Konfiguration atomar (Wiederherstellung, BAK-001).
     * Das Ereignisprotokoll bleibt erhalten. Bricht etwas ab, bleibt der alte Stand vollständig bestehen.
     */
    suspend fun replaceAll(
        home: Home,
        rooms: List<Room>,
        devices: List<DeviceMetadata>,
        scenes: List<Scene>,
        automations: List<Automation>,
    ) = db.withTransaction {
        dao.deleteAllSceneActions(); dao.deleteAllScenes(); dao.deleteAllAutomations()
        dao.deleteAllDevices(); dao.deleteAllRooms(); dao.deleteHome()
        dao.upsertHome(home.toEntity())
        dao.upsertRooms(rooms.map { it.toEntity() })
        dao.upsertDevices(devices.map { it.toEntity() })
        scenes.forEachIndexed { i, scene -> scene.toEntities(i).let { (e, a) -> dao.replaceScene(e, a) } }
        dao.upsertAutomations(automations.map { it.toEntity() })
    }

    /**
     * Entfernt persönliche Daten (RST-001, RST-005).
     * @param keepRooms true = Übergabe: Zuhause und Räume bleiben, Geräte/Szenen/Automationen/Protokoll gehen.
     */
    suspend fun erase(keepRooms: Boolean) {
        db.withTransaction {
            dao.deleteAllSceneActions(); dao.deleteAllScenes(); dao.deleteAllAutomations(); dao.deleteAllDevices()
            if (!keepRooms) { dao.deleteAllRooms(); dao.deleteHome() }
            db.eventLogDao().clear()
        }
        // Freigegebene Seiten überschreiben und WAL leeren – keine Altdaten in der Datei (RST-001).
        db.openHelper.writableDatabase.apply {
            query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            execSQL("VACUUM")
        }
    }

    suspend fun hasHome(): Boolean = dao.homeCount() > 0

    /** Name des vorhandenen Zuhauses (z. B. nach „Übergabe vorbereiten“), sonst null. */
    suspend fun homeName(): String? = dao.home()?.name

    suspend fun renameHome(name: String) = dao.renameHome(name)

    /** Alle Matter-Nodes, die raum. kennt – direkt aus der Datenbank, unabhängig vom Flow-Stand. */
    suspend fun knownNodeIds(): List<ULong> = dao.nodeIds().map { it.toULong() }

    override suspend fun upsertDevice(metadata: DeviceMetadata) = dao.upsertDeviceByChannel(metadata.toEntity())

    override suspend fun removeDevice(nodeId: ULong) = dao.deleteDeviceByNode(nodeId.toLong())

    override suspend fun setFavorite(deviceId: UUID, favorite: Boolean) = dao.setFavorite(deviceId.toString(), favorite)

    override suspend fun addRoom(name: String, icon: String): Room {
        val room = Room(UUID.randomUUID(), name.trim(), icon, dao.maxRoomSortOrder() + 1)
        dao.upsertRooms(listOf(room.toEntity()))
        return room
    }

    override suspend fun updateRoom(room: Room) = dao.upsertRooms(listOf(room.toEntity()))

    override suspend fun deleteRoom(roomId: UUID) = dao.deleteRoom(roomId.toString())

    override suspend fun scene(sceneId: UUID): Scene? = dao.scene(sceneId.toString())?.toDomain()

    override suspend fun upsertScene(scene: Scene) {
        db.withTransaction {
            val order = dao.sceneSortOrder(scene.id.toString()) ?: (dao.maxSceneSortOrder() + 1)
            // Aktionen für inzwischen gelöschte Geräte verwerfen (FK-Verletzung vermeiden).
            val known = dao.deviceIds().toSet()
            val valid = scene.copy(actions = scene.actions.filter { it.deviceId.toString() in known })
            val (entity, actions) = valid.toEntities(order)
            dao.replaceScene(entity, actions)
        }
    }

    override suspend fun deleteScene(sceneId: UUID) = dao.deleteScene(sceneId.toString())

    override suspend fun automation(automationId: UUID): Automation? = dao.automation(automationId.toString())?.toDomain()

    override suspend fun upsertAutomation(automation: Automation) = dao.upsertAutomations(listOf(automation.toEntity()))

    override suspend fun deleteAutomation(automationId: UUID) = dao.deleteAutomation(automationId.toString())

    override suspend fun setAutomationEnabled(automationId: UUID, enabled: Boolean) =
        dao.setAutomationEnabled(automationId.toString(), enabled)
}
