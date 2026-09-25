package app.raum.data.backup

import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Condition
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.Home
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.SceneAction
import app.raum.domain.models.Trigger
import java.time.Instant
import java.util.UUID

/** Geprüfter, bereinigter Inhalt einer Sicherung – bereit zum Wiederherstellen. */
data class RestorePlan(
    val source: BackupDocument,
    val home: Home,
    val rooms: List<Room>,
    val devices: List<DeviceMetadata>,
    val scenes: List<Scene>,
    val automations: List<Automation>,
    val settings: SettingsDto,
    val warnings: List<RestoreWarning>,
)

/** Reparierbare Probleme einer Sicherung – der Text entsteht in der Oberfläche (übersetzbar). */
sealed interface RestoreWarning {
    val count: Int
    data class InvalidDevices(override val count: Int) : RestoreWarning
    data class LostRooms(override val count: Int) : RestoreWarning
    data class DroppedSceneActions(override val count: Int) : RestoreWarning
    data class DisabledAutomations(override val count: Int) : RestoreWarning
    /** BAK-006: Geräte fehlen in der Fabric dieses Panels, die Sicherung enthält keine Fabric-Schlüssel. */
    data class MissingFromFabric(override val count: Int) : RestoreWarning
}

class IncompatibleBackupException(val reason: Reason, detail: String) : Exception(detail) {
    enum class Reason { NEWER_FORMAT, NEWER_SCHEMA, INVALID }
}

object BackupMapper {

    fun toDocument(
        home: Home,
        rooms: List<Room>,
        devices: List<DeviceMetadata>,
        scenes: List<Scene>,
        automations: List<Automation>,
        settings: SettingsDto,
        appVersion: String,
        dbSchema: Int,
        now: Instant = Instant.now(),
    ) = BackupDocument(
        createdAt = now.toString(),
        appVersion = appVersion,
        dbSchema = dbSchema,
        home = HomeDto(home.id.toString(), home.name, home.createdAt.toEpochMilli()),
        rooms = rooms.map { RoomDto(it.id.toString(), it.name, it.icon, it.sortOrder) },
        devices = devices.map {
            DeviceDto(it.id.toString(), it.matterNodeId.toString(), it.displayName, it.roomId?.toString(), it.vendorName, it.productName, it.favorite, it.endpointId)
        },
        scenes = scenes.map { s -> SceneDto(s.id.toString(), s.name, s.icon, s.actions.map { SceneActionDto(it.deviceId.toString(), it.command) }) },
        automations = automations.map { AutomationDto(it.id.toString(), it.name, it.enabled, it.triggers, it.conditions, it.actions) },
        settings = settings,
    )

    /**
     * Kompatibilitätsprüfung vor dem Wiederherstellen (BAK-004).
     * Harte Fehler → [IncompatibleBackupException]; reparierbare Probleme → Warnungen.
     *
     * @param knownNodeIds Nodes der Matter-Fabric dieses Panels (für den Hinweis auf fehlende Geräte).
     */
    fun plan(doc: BackupDocument, currentDbSchema: Int, knownNodeIds: Set<ULong>): RestorePlan {
        if (doc.format > BackupDocument.CURRENT_FORMAT) {
            throw IncompatibleBackupException(IncompatibleBackupException.Reason.NEWER_FORMAT, "format ${doc.format}")
        }
        if (doc.dbSchema > currentDbSchema) {
            throw IncompatibleBackupException(IncompatibleBackupException.Reason.NEWER_SCHEMA, "schema ${doc.dbSchema} > $currentDbSchema")
        }
        val warnings = mutableListOf<RestoreWarning>()
        fun uuid(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()

        val home = Home(
            uuid(doc.home.id) ?: throw IncompatibleBackupException(IncompatibleBackupException.Reason.INVALID, "home id"),
            doc.home.name.ifBlank { "raum." },
            Instant.ofEpochMilli(doc.home.createdAtEpochMs),
        )

        val rooms = doc.rooms.mapNotNull { r -> uuid(r.id)?.let { Room(it, r.name, r.icon, r.sortOrder) } }.distinctBy { it.id }
        val roomIds = rooms.map { it.id }.toSet()

        var badDevices = 0
        var lostRooms = 0
        val devices = doc.devices.mapNotNull { d ->
            val id = uuid(d.id)
            val node = d.matterNodeId.toULongOrNull()
            if (id == null || node == null) { badDevices++; return@mapNotNull null }
            val room = d.roomId?.let(::uuid)?.takeIf { it in roomIds }
            if (d.roomId != null && room == null) lostRooms++
            DeviceMetadata(id, node, d.displayName, room, d.vendorName, d.productName, d.favorite, d.endpointId)
        }.distinctBy { it.matterNodeId to it.endpointId }
        if (badDevices > 0) warnings += RestoreWarning.InvalidDevices(badDevices)
        if (lostRooms > 0) warnings += RestoreWarning.LostRooms(lostRooms)
        val deviceIds = devices.map { it.id }.toSet()

        var droppedActions = 0
        val scenes = doc.scenes.mapNotNull { s ->
            val id = uuid(s.id) ?: return@mapNotNull null
            val actions = s.actions.mapNotNull { a ->
                uuid(a.deviceId)?.takeIf { it in deviceIds }?.let { SceneAction(it, a.command) } ?: run { droppedActions++; null }
            }
            Scene(id, s.name, s.icon, actions)
        }
        if (droppedActions > 0) warnings += RestoreWarning.DroppedSceneActions(droppedActions)
        val sceneIds = scenes.map { it.id }.toSet()

        var disabledAutomations = 0
        val automations = doc.automations.mapNotNull { a ->
            val id = uuid(a.id) ?: return@mapNotNull null
            val refs = references(a.triggers, a.conditions, a.actions)
            val broken = refs.devices.any { it !in deviceIds } || refs.scenes.any { it !in sceneIds }
            if (broken && a.enabled) disabledAutomations++
            Automation(id, a.name, a.enabled && !broken, a.triggers, a.conditions, a.actions)
        }
        if (disabledAutomations > 0) warnings += RestoreWarning.DisabledAutomations(disabledAutomations)

        val unknownNodes = devices.count { it.matterNodeId !in knownNodeIds }
        if (!doc.containsFabric && unknownNodes > 0) {
            warnings += RestoreWarning.MissingFromFabric(unknownNodes)
        }

        return RestorePlan(doc, home, rooms, devices, scenes, automations, doc.settings, warnings)
    }

    private data class Refs(val devices: Set<UUID>, val scenes: Set<UUID>)

    private fun references(triggers: List<Trigger>, conditions: List<Condition>, actions: List<AutomationAction>): Refs {
        val devices = mutableSetOf<UUID>()
        val scenes = mutableSetOf<UUID>()
        triggers.forEach { t ->
            when (t) {
                is Trigger.DeviceStateChanged -> devices += t.deviceId
                is Trigger.SensorThreshold -> devices += t.deviceId
                is Trigger.Connectivity -> devices += t.deviceId
                else -> Unit
            }
        }
        conditions.forEach { c ->
            when (c) {
                is Condition.DeviceStateIs -> devices += c.deviceId
                is Condition.SensorValue -> devices += c.deviceId
                else -> Unit
            }
        }
        actions.forEach { a ->
            when (a) {
                is AutomationAction.ControlDevice -> devices += a.deviceId
                is AutomationAction.RunScene -> scenes += a.sceneId
                else -> Unit
            }
        }
        return Refs(devices, scenes)
    }
}
