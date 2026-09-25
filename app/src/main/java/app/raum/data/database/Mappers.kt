package app.raum.data.database

import app.raum.domain.models.Automation
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.Home
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.SceneAction
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Condition
import app.raum.domain.models.Trigger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.time.Instant
import java.util.UUID

/** JSON-Format für gespeicherte Befehle. Unbekannte Felder ignorieren = vorwärtskompatibel. */
internal val CommandJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

internal fun encodeCommand(command: DeviceCommand): String =
    CommandJson.encodeToString(DeviceCommand.serializer(), command)

/** null, falls der Befehl aus einer neueren Version stammt und hier unbekannt ist. */
internal fun decodeCommand(json: String): DeviceCommand? =
    runCatching { CommandJson.decodeFromString(DeviceCommand.serializer(), json) }.getOrNull()

internal fun HomeEntity.toDomain() = Home(UUID.fromString(id), name, Instant.ofEpochMilli(createdAtEpochMs))
internal fun Home.toEntity() = HomeEntity(id.toString(), name, createdAt.toEpochMilli())

internal fun RoomEntity.toDomain() = Room(UUID.fromString(id), name, icon, sortOrder)
internal fun Room.toEntity() = RoomEntity(id.toString(), name, icon, sortOrder)

internal fun DeviceEntity.toDomain() = DeviceMetadata(
    id = UUID.fromString(id),
    matterNodeId = matterNodeId.toULong(),
    displayName = displayName,
    roomId = roomId?.let(UUID::fromString),
    vendorName = vendorName,
    productName = productName,
    favorite = favorite,
    endpointId = endpointId,
)

internal fun DeviceMetadata.toEntity() = DeviceEntity(
    id = id.toString(),
    matterNodeId = matterNodeId.toLong(),
    displayName = displayName,
    roomId = roomId?.toString(),
    vendorName = vendorName,
    productName = productName,
    favorite = favorite,
    endpointId = endpointId,
)

internal fun SceneWithActions.toDomain() = Scene(
    id = UUID.fromString(scene.id),
    name = scene.name,
    icon = scene.icon,
    actions = actions.sortedBy { it.position }.mapNotNull { a ->
        decodeCommand(a.commandJson)?.let { SceneAction(UUID.fromString(a.deviceId), it) }
    },
)

internal fun Scene.toEntities(sortOrder: Int): Pair<SceneEntity, List<SceneActionEntity>> =
    SceneEntity(id.toString(), name, icon, sortOrder) to actions.mapIndexed { i, a ->
        SceneActionEntity(sceneId = id.toString(), position = i, deviceId = a.deviceId.toString(), commandJson = encodeCommand(a.command))
    }

private val TriggerList = ListSerializer(Trigger.serializer())
private val ConditionList = ListSerializer(Condition.serializer())
private val ActionList = ListSerializer(AutomationAction.serializer())

/** Elementweise dekodieren: Ein unbekannter Eintrag (neuere Version) verwirft nicht die ganze Liste. */
private fun <T> decodeList(json: String, element: kotlinx.serialization.KSerializer<T>): List<T> =
    runCatching { CommandJson.parseToJsonElement(json).jsonArray }.getOrNull()
        ?.mapNotNull { runCatching { CommandJson.decodeFromJsonElement(element, it) }.getOrNull() }
        .orEmpty()

internal fun AutomationEntity.toDomain() = Automation(
    id = UUID.fromString(id),
    name = name,
    enabled = enabled,
    triggers = decodeList(triggersJson, Trigger.serializer()),
    conditions = decodeList(conditionsJson, Condition.serializer()),
    actions = decodeList(actionsJson, AutomationAction.serializer()),
)

internal fun Automation.toEntity() = AutomationEntity(
    id = id.toString(),
    name = name,
    enabled = enabled,
    triggersJson = CommandJson.encodeToString(TriggerList, triggers),
    conditionsJson = CommandJson.encodeToString(ConditionList, conditions),
    actionsJson = CommandJson.encodeToString(ActionList, actions),
)
