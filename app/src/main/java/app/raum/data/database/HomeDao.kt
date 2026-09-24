package app.raum.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HomeDao {

    // --- Zuhause ---------------------------------------------------------------
    @Query("SELECT * FROM home LIMIT 1")
    abstract fun observeHome(): Flow<HomeEntity?>

    @Query("SELECT * FROM home LIMIT 1")
    abstract suspend fun home(): HomeEntity?

    @Query("UPDATE home SET name = :name")
    abstract suspend fun renameHome(name: String)

    @Query("SELECT COUNT(*) FROM home")
    abstract suspend fun homeCount(): Int

    @Upsert
    abstract suspend fun upsertHome(home: HomeEntity)

    // --- Räume -----------------------------------------------------------------
    @Query("SELECT * FROM rooms ORDER BY sortOrder, name")
    abstract fun observeRooms(): Flow<List<RoomEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM rooms")
    abstract suspend fun maxRoomSortOrder(): Int

    @Upsert
    abstract suspend fun upsertRooms(rooms: List<RoomEntity>)

    @Query("UPDATE devices SET roomId = NULL WHERE roomId = :roomId")
    protected abstract suspend fun clearRoomAssignments(roomId: String)

    @Query("DELETE FROM rooms WHERE id = :roomId")
    protected abstract suspend fun deleteRoomRow(roomId: String)

    /** Geräte bleiben erhalten und verlieren nur ihre Zuordnung (explizit, nicht nur per FK). */
    @Transaction
    open suspend fun deleteRoom(roomId: String) {
        clearRoomAssignments(roomId)
        deleteRoomRow(roomId)
    }

    // --- Geräte ----------------------------------------------------------------
    @Query("SELECT * FROM devices ORDER BY displayName")
    abstract fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE matterNodeId = :nodeId")
    abstract suspend fun deviceByNode(nodeId: Long): DeviceEntity?

    @Query("SELECT matterNodeId FROM devices")
    abstract suspend fun nodeIds(): List<Long>

    @Query("SELECT id FROM devices")
    abstract suspend fun deviceIds(): List<String>

    @Upsert
    abstract suspend fun upsertDevices(devices: List<DeviceEntity>)

    @Query("UPDATE devices SET favorite = :favorite WHERE id = :deviceId")
    abstract suspend fun setFavorite(deviceId: String, favorite: Boolean)

    @Query("DELETE FROM scene_actions WHERE deviceId IN (SELECT id FROM devices WHERE matterNodeId = :nodeId)")
    protected abstract suspend fun deleteActionsForNode(nodeId: Long)

    @Query("DELETE FROM devices WHERE matterNodeId = :nodeId")
    protected abstract suspend fun deleteDeviceRow(nodeId: Long)

    /** Entfernt das Gerät samt aller Szenenaktionen, die es betreffen. */
    @Transaction
    open suspend fun deleteDeviceByNode(nodeId: Long) {
        deleteActionsForNode(nodeId)
        deleteDeviceRow(nodeId)
    }

    /**
     * Upsert per Node-ID: Existiert der Node bereits unter anderer Geräte-ID, wird die vorhandene ID
     * beibehalten, damit Szenenaktionen gültig bleiben.
     */
    @Transaction
    open suspend fun upsertDeviceByNode(device: DeviceEntity) {
        val existing = deviceByNode(device.matterNodeId)
        upsertDevices(listOf(if (existing != null) device.copy(id = existing.id) else device))
    }

    // --- Szenen ----------------------------------------------------------------
    @Transaction
    @Query("SELECT * FROM scenes ORDER BY sortOrder, name")
    abstract fun observeScenes(): Flow<List<SceneWithActions>>

    @Transaction
    @Query("SELECT * FROM scenes WHERE id = :sceneId")
    abstract suspend fun scene(sceneId: String): SceneWithActions?

    @Query("SELECT sortOrder FROM scenes WHERE id = :sceneId")
    abstract suspend fun sceneSortOrder(sceneId: String): Int?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM scenes")
    abstract suspend fun maxSceneSortOrder(): Int

    @Upsert
    protected abstract suspend fun upsertSceneRow(scene: SceneEntity)

    @Query("DELETE FROM scene_actions WHERE sceneId = :sceneId")
    protected abstract suspend fun deleteSceneActions(sceneId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSceneActions(actions: List<SceneActionEntity>)

    @Query("DELETE FROM scenes WHERE id = :sceneId")
    protected abstract suspend fun deleteSceneRow(sceneId: String)

    /** Ersetzt Szene und Aktionen atomar – ein Absturz hinterlässt nie eine halbe Szene (Spez. 12.2). */
    @Transaction
    open suspend fun replaceScene(scene: SceneEntity, actions: List<SceneActionEntity>) {
        upsertSceneRow(scene)
        deleteSceneActions(scene.id)
        if (actions.isNotEmpty()) insertSceneActions(actions)
    }

    @Transaction
    open suspend fun deleteScene(sceneId: String) {
        deleteSceneActions(sceneId)
        deleteSceneRow(sceneId)
    }

    // --- Massenoperationen (Wiederherstellen, Zurücksetzen) --------------------------
    @Query("DELETE FROM scene_actions") abstract suspend fun deleteAllSceneActions()
    @Query("DELETE FROM scenes") abstract suspend fun deleteAllScenes()
    @Query("DELETE FROM automations") abstract suspend fun deleteAllAutomations()
    @Query("DELETE FROM devices") abstract suspend fun deleteAllDevices()
    @Query("DELETE FROM rooms") abstract suspend fun deleteAllRooms()
    @Query("DELETE FROM home") abstract suspend fun deleteHome()

    // --- Automationen ------------------------------------------------------------
    @Query("SELECT * FROM automations WHERE id = :id")
    abstract suspend fun automation(id: String): AutomationEntity?

    @Query("DELETE FROM automations WHERE id = :id")
    abstract suspend fun deleteAutomation(id: String)

    @Query("SELECT * FROM automations ORDER BY name")
    abstract fun observeAutomations(): Flow<List<AutomationEntity>>

    @Upsert
    abstract suspend fun upsertAutomations(automations: List<AutomationEntity>)

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setAutomationEnabled(id: String, enabled: Boolean)
}
