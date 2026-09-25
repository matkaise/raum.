package app.raum.data.backup

import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Condition
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.Trigger
import kotlinx.serialization.Serializable

/**
 * Inhalt einer raum.-Sicherung (BAK-001). Bewusst eigene, stabile DTOs statt Datenbank-Entities:
 * das Format ist unabhängig vom DB-Schema und bleibt über App-Versionen lesbar.
 *
 * Nicht enthalten: Administrator-PIN, Ereignisprotokoll, Matter-Fabric-Schlüssel (siehe [containsFabric]).
 */
@Serializable
data class BackupDocument(
    val format: Int = CURRENT_FORMAT,
    val createdAt: String,
    val appVersion: String,
    val dbSchema: Int,
    val home: HomeDto,
    val rooms: List<RoomDto>,
    val devices: List<DeviceDto>,
    val scenes: List<SceneDto>,
    val automations: List<AutomationDto>,
    val settings: SettingsDto,
    /** BAK-005/006: Fabric-Credentials sind (noch) nicht exportierbar. */
    val containsFabric: Boolean = false,
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

@Serializable data class HomeDto(val id: String, val name: String, val createdAtEpochMs: Long)
@Serializable data class RoomDto(val id: String, val name: String, val icon: String, val sortOrder: Int)

@Serializable
data class DeviceDto(
    val id: String,
    /** Matter-Node-ID als Dezimalzahl (ULong passt nicht in JSON-Number ohne Verlust). */
    val matterNodeId: String,
    val displayName: String,
    val roomId: String?,
    val vendorName: String?,
    val productName: String?,
    val favorite: Boolean,
    /** Weiterer Kanal des Nodes; null = Hauptkanal (ältere Sicherungen kennen das Feld nicht). */
    val endpointId: Int? = null,
)

@Serializable data class SceneActionDto(val deviceId: String, val command: DeviceCommand)
@Serializable data class SceneDto(val id: String, val name: String, val icon: String, val actions: List<SceneActionDto>)

@Serializable
data class AutomationDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val triggers: List<Trigger>,
    val conditions: List<Condition>,
    val actions: List<AutomationAction>,
)

@Serializable
data class SettingsDto(
    val themeMode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sleepAfterSeconds: Int? = null,
    val sleepAction: String? = null,
    val proximityWake: Boolean? = null,
    val autoBrightness: Boolean? = null,
    val manualBrightness: Float? = null,
    val minBrightness: Float? = null,
    /** Ab App 0.8 – ältere Sicherungen haben diese Felder nicht (bleiben dann unverändert). */
    val language: String? = null,
    val temperatureUnit: String? = null,
    /** Ab App 0.9 */
    val locationName: String? = null,
    val weatherEnabled: Boolean? = null,
    val outdoorSensorId: String? = null,
)
