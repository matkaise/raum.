package app.raum.domain.usecases

import app.raum.i18n.ErrorTexts
import app.raum.i18n.Strings
import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogLevel
import app.raum.domain.models.Capability
import app.raum.domain.models.Device
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.OnlineState
import app.raum.domain.models.deviceIdForNode
import app.raum.domain.repositories.HomeRepository
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import app.raum.matter.controller.MatterCommand
import app.raum.matter.controller.MatterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Zentrale Stelle für Gerätezustände und Befehle.
 *
 * Kombiniert Live-Zustände des Matter-Controllers mit lokalen Metadaten und
 * überlagert laufende Befehle optimistisch (CTRL-001). Schlägt ein Befehl fehl,
 * verschwindet die Überlagerung und der bestätigte Zustand wird wieder sichtbar.
 */
class DeviceService(
    private val controller: MatterController,
    private val repository: HomeRepository,
    private val messages: UiMessageBus,
    private val log: EventLog,
    scope: CoroutineScope,
    private val strings: Strings,
) {
    private data class Pending(val version: Long, val capabilities: List<Capability>)

    private val pending = MutableStateFlow<Map<ULong, Pending>>(emptyMap())
    private val versions = AtomicLong()

    val devices: StateFlow<List<Device>> = combine(
        controller.devices,
        repository.deviceMetadata,
        pending,
    ) { states, metadata, overlay -> merge(states, metadata, overlay) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Nur vom Gerät bestätigte Zustände, ohne optimistische Überlagerung.
     * Grundlage für Automationen: ein zurückgerollter Befehl darf nichts auslösen.
     */
    val confirmedDevices: StateFlow<List<Device>> = combine(
        controller.devices,
        repository.deviceMetadata,
    ) { states, metadata -> merge(states, metadata, emptyMap()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun merge(
        states: Map<ULong, app.raum.domain.models.DeviceState>,
        metadata: List<DeviceMetadata>,
        overlay: Map<ULong, Pending>,
    ): List<Device> {
        val known = metadata.associateBy { it.matterNodeId }
        // Nodes ohne lokale Metadaten (z. B. extern hinzugefügt) trotzdem anzeigen.
        val orphans = states.filterKeys { it !in known }.map { (nodeId, st) ->
            // Name aus dem Gerät (anderswo vergebener Name, sonst Produkt), sonst „Gerät …“
            val name = st.label ?: st.productName ?: strings.get(R.string.device_unnamed, "%X".format(nodeId.toLong()))
            DeviceMetadata(deviceIdForNode(nodeId), nodeId, name, null, st.vendorName, st.productName, false)
        }
        return (metadata + orphans).map { meta ->
            val state = states[meta.matterNodeId]
            Device(
                id = meta.id,
                matterNodeId = meta.matterNodeId,
                displayName = meta.displayName,
                roomId = meta.roomId,
                vendorName = meta.vendorName ?: state?.vendorName,
                productName = meta.productName ?: state?.productName,
                onlineState = state?.onlineState ?: OnlineState.UNKNOWN,
                favorite = meta.favorite,
                lastSeenAt = state?.lastSeenAt,
                capabilities = overlay[meta.matterNodeId]?.capabilities ?: state?.capabilities.orEmpty(),
                network = state?.network,
            )
        }
    }

    fun device(id: UUID): Device? = devices.value.firstOrNull { it.id == id }

    /**
     * Sendet einen Befehl mit optimistischer Anzeige.
     * @param notify false unterdrückt Einzelmeldungen (z. B. bei Szenen, die gesammelt melden).
     */
    suspend fun send(device: Device, command: DeviceCommand, notify: Boolean = true): CommandResult {
        val nodeId = device.matterNodeId
        if (!device.isOnline) {
            // CTRL-005: keine irreführende Erfolgsanzeige bei Offline-Geräten.
            if (notify) messages.error(strings.get(R.string.msg_device_unreachable, device.displayName))
            log.record(LogCategory.DEVICE, LogLevel.ERROR, strings.get(R.string.log_command_rejected_offline), device.displayName, device.id)
            return CommandResult.Failure(CommandFailure.OFFLINE, "offline")
        }
        if (!CapabilityReducer.supports(device.capabilities, command)) {
            return CommandResult.Failure(CommandFailure.UNSUPPORTED)
        }

        val version = versions.incrementAndGet()
        pending.update { map ->
            val base = map[nodeId]?.capabilities ?: device.capabilities
            map + (nodeId to Pending(version, CapabilityReducer.apply(base, command)))
        }

        val result = try {
            controller.execute(MatterCommand(nodeId, command))
        } finally {
            // Auch bei Abbruch/Exception: nur die eigene Überlagerung entfernen – ein neuerer Befehl hat Vorrang.
            pending.update { map -> if (map[nodeId]?.version == version) map - nodeId else map }
        }

        if (result is CommandResult.Failure) {
            val reason = strings.get(ErrorTexts.command(result.reason))
            log.record(
                LogCategory.DEVICE, LogLevel.ERROR,
                strings.get(R.string.log_command_failed, reason) + (result.detail?.let { " ($it)" } ?: ""),
                device.displayName, device.id,
            )
            if (notify) messages.error("${device.displayName}: $reason")
        }
        return result
    }

    /** Gruppenaktion (ROM-004): führt den Befehl parallel auf allen fähigen Geräten aus. */
    suspend fun sendToAll(devices: List<Device>, command: DeviceCommand, label: String): Int = coroutineScope {
        val targets = devices.filter { it.isOnline && CapabilityReducer.supports(it.capabilities, command) }
        val failures = targets.map { d -> async { send(d, command, notify = false) } }
            .awaitAll()
            .count { it is CommandResult.Failure }
        val skipped = devices.count { !it.isOnline && CapabilityReducer.supports(it.capabilities, command) }
        when {
            failures > 0 -> messages.error(strings.get(R.string.msg_group_failures, label, failures, targets.size))
            skipped > 0 -> messages.info(strings.get(R.string.msg_group_skipped, label, skipped))
        }
        failures
    }

    suspend fun rename(device: Device, name: String) = updateMetadata(device) { it.copy(displayName = name.trim()) }
    suspend fun assignRoom(device: Device, roomId: UUID?) = updateMetadata(device) { it.copy(roomId = roomId) }
    suspend fun setFavorite(device: Device, favorite: Boolean) = repository.setFavorite(device.id, favorite)

    /** DEV-007: entfernt das Gerät aus der eigenen Fabric und aus der lokalen Konfiguration. */
    suspend fun remove(device: Device) {
        controller.removeDevice(device.matterNodeId)
        repository.removeDevice(device.matterNodeId)
        log.record(LogCategory.DEVICE, LogLevel.INFO, strings.get(R.string.log_device_removed), device.displayName, device.id)
        messages.info(strings.get(R.string.msg_device_removed, device.displayName))
    }

    private suspend fun updateMetadata(device: Device, transform: (DeviceMetadata) -> DeviceMetadata) {
        val current = repository.deviceMetadata.value.firstOrNull { it.id == device.id }
            ?: DeviceMetadata(device.id, device.matterNodeId, device.displayName, device.roomId,
                device.vendorName, device.productName, device.favorite)
        repository.upsertDevice(transform(current))
    }
}
