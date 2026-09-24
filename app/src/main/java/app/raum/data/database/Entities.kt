package app.raum.data.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// UUIDs werden als Text, Matter-Node-IDs (ULong) als Long (gleiche Bitbreite) gespeichert.

@Entity(tableName = "home")
data class HomeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "devices",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("roomId"), Index(value = ["matterNodeId"], unique = true)],
)
data class DeviceEntity(
    @PrimaryKey val id: String,
    val matterNodeId: Long,
    val displayName: String,
    val roomId: String?,
    val vendorName: String?,
    val productName: String?,
    val favorite: Boolean,
)

@Entity(tableName = "scenes")
data class SceneEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "scene_actions",
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["sceneId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sceneId"), Index("deviceId")],
)
data class SceneActionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sceneId: String,
    /** Reihenfolge innerhalb der Szene – relevant, z. B. Farbe vor Helligkeit. */
    val position: Int,
    val deviceId: String,
    /** [app.raum.domain.models.DeviceCommand] als JSON (stabile SerialNames). */
    val commandJson: String,
)

data class SceneWithActions(
    @Embedded val scene: SceneEntity,
    @Relation(parentColumn = "id", entityColumn = "sceneId")
    val actions: List<SceneActionEntity>,
)

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    /** JSON-Listen von Trigger / Condition / AutomationAction (stabile SerialNames). */
    @ColumnInfo(defaultValue = "[]") val triggersJson: String,
    @ColumnInfo(defaultValue = "[]") val conditionsJson: String,
    @ColumnInfo(defaultValue = "[]") val actionsJson: String,
)

/** Persistentes Ereignisprotokoll (LOG-001, LOG-002, AUT-007). Keine Schlüssel/Zugangsdaten (LOG-003). */
@Entity(
    tableName = "event_log",
    indices = [Index("timestampMs"), Index("category"), Index("deviceId"), Index("automationId")],
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val category: String,
    val level: String,
    val message: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val automationId: String? = null,
)
