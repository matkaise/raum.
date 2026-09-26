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
import app.raum.domain.models.deviceIdForChannel
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
import kotlinx.coroutines.launch
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

    /** Laufende Befehle je Gerät (Kanal) – nicht je Node, sonst überlagern sich Kanäle eines Nodes. */
    private val pending = MutableStateFlow<Map<UUID, Pending>>(emptyMap())
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

    /**
     * Angezeigte Geräte ohne gespeicherte Metadaten: extern hinzugefügte Nodes und weitere Kanäle. Sie werden
     * einmalig gespeichert (siehe init), damit Szenen, Favoriten und Automationen sie dauerhaft referenzieren können –
     * nur als Identität mit leerem Namen: Name und Raum bleiben abgeleitet, bis der Nutzer etwas ändert ([resolve]).
     */
    private fun discovered(states: Map<ULong, app.raum.domain.models.DeviceState>, metadata: List<DeviceMetadata>): List<DeviceMetadata> {
        val (primaryMeta, channelMeta) = metadata.partition { it.endpointId == null }
        val primaryNodes = primaryMeta.map { it.matterNodeId }.toSet()
        val storedChannels = channelMeta.map { it.id }.toSet()
        val orphans = states.filterKeys { it !in primaryNodes }.map { (nodeId, st) ->
            DeviceMetadata(deviceIdForNode(nodeId), nodeId, "", null, st.vendorName, st.productName, false)
        }
        val newChannels = states.values.flatMap { st ->
            st.channels.map { ch -> DeviceMetadata(deviceIdForChannel(st.nodeId, ch.endpoint), st.nodeId, "", null, null, null, false, ch.endpoint) }
                .filter { it.id !in storedChannels }
        }
        return orphans + newChannels
    }

    init {
        // Entdeckte Geräte speichern – nur falls noch nicht vorhanden, damit beim Start (Metadaten noch nicht
        // geladen) nie ein vergebener Name oder Raum überschrieben wird.
        scope.launch {
            combine(controller.devices, repository.deviceMetadata, ::discovered).collect { found ->
                found.forEach { runCatching { repository.addDeviceIfMissing(it) } }
            }
        }
    }

    /**
     * Unveränderte Geräte (leerer gespeicherter Name) bekommen Name und Raum abgeleitet: ein Node aus den Angaben des
     * Geräts, ein weiterer Kanal als „<Hauptgerät> · Kanal n“ im Raum des Hauptgeräts – so folgt er dem Namen, den das
     * Hauptgerät z. B. erst am Ende der Kopplung bekommt.
     */
    private fun resolve(
        meta: DeviceMetadata,
        all: List<DeviceMetadata>,
        states: Map<ULong, app.raum.domain.models.DeviceState>,
    ): DeviceMetadata {
        if (meta.displayName.isNotBlank()) return meta
        val st = states[meta.matterNodeId]
        if (meta.endpointId == null) {
            val name = st?.label ?: st?.productName ?: meta.productName
                ?: strings.get(R.string.device_unnamed, "%X".format(meta.matterNodeId.toLong()))
            return meta.copy(displayName = name)
        }
        val primary = all.firstOrNull { it.matterNodeId == meta.matterNodeId && it.endpointId == null }?.let { resolve(it, all, states) }
        val number = all.filter { it.matterNodeId == meta.matterNodeId && it.endpointId != null }
            .mapNotNull { it.endpointId }.sorted().indexOf(meta.endpointId) + 2
        return meta.copy(
            displayName = strings.get(R.string.device_channel_name, primary?.displayName.orEmpty(), number),
            roomId = primary?.roomId,
            vendorName = meta.vendorName ?: primary?.vendorName,
            productName = meta.productName ?: primary?.productName,
        )
    }

    private fun merge(
        states: Map<ULong, app.raum.domain.models.DeviceState>,
        metadata: List<DeviceMetadata>,
        overlay: Map<UUID, Pending>,
    ): List<Device> {
        val all = metadata + discovered(states, metadata)
        return all.map { resolve(it, all, states) }.map { meta ->
            val state = states[meta.matterNodeId]
            val live = if (meta.endpointId == null) state?.capabilities
                else state?.channels?.firstOrNull { it.endpoint == meta.endpointId }?.capabilities
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
                capabilities = overlay[meta.id]?.capabilities ?: live.orEmpty(),
                network = state?.network,
                endpointId = meta.endpointId,
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
            val base = map[device.id]?.capabilities ?: device.capabilities
            map + (device.id to Pending(version, CapabilityReducer.apply(base, command)))
        }

        val result = try {
            controller.execute(MatterCommand(nodeId, command, device.endpointId))
        } finally {
            // Auch bei Abbruch/Exception: nur die eigene Überlagerung entfernen – ein neuerer Befehl hat Vorrang.
            pending.update { map -> if (map[device.id]?.version == version) map - device.id else map }
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
    /** Über die Metadaten, damit auch ein noch nicht gespeichertes (entdecktes) Gerät Favorit werden kann. */
    suspend fun setFavorite(device: Device, favorite: Boolean) = updateMetadata(device) { it.copy(favorite = favorite) }

    /** DEV-007: entfernt das Gerät aus der eigenen Fabric und aus der lokalen Konfiguration – mit allen Kanälen des Nodes. */
    suspend fun remove(device: Device) {
        controller.removeDevice(device.matterNodeId)
        repository.removeDevice(device.matterNodeId)
        log.record(LogCategory.DEVICE, LogLevel.INFO, strings.get(R.string.log_device_removed), device.displayName, device.id)
        messages.info(strings.get(R.string.msg_device_removed, device.displayName))
    }

    private suspend fun updateMetadata(device: Device, transform: (DeviceMetadata) -> DeviceMetadata) {
        val stored = repository.deviceMetadata.value.firstOrNull { it.id == device.id }
            ?: DeviceMetadata(device.id, device.matterNodeId, device.displayName, device.roomId,
                device.vendorName, device.productName, device.favorite, device.endpointId)
        // Erste Änderung eines unveränderten Geräts: abgeleiteten Namen und Raum festschreiben
        val current = if (stored.displayName.isBlank()) stored.copy(displayName = device.displayName, roomId = device.roomId) else stored
        repository.upsertDevice(transform(current))
    }
}
