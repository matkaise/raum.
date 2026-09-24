package app.raum.ui.scenes

import app.raum.i18n.Strings
import app.raum.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.domain.models.Device
import app.raum.domain.models.Scene
import app.raum.domain.models.SceneAction
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.DeviceTarget
import app.raum.domain.usecases.SceneRunner
import app.raum.domain.usecases.SceneTargets
import app.raum.domain.usecases.UiMessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SceneEntry(val deviceId: UUID, val target: DeviceTarget)

data class SceneEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val notFound: Boolean = false,
    val name: String = "",
    val icon: String = "home",
    val entries: List<SceneEntry> = emptyList(),
    val dirty: Boolean = false,
    val showErrors: Boolean = false,
    val busy: Boolean = false,
    val closed: Boolean = false,
) {
    /** Ressourcen-IDs der Fehlermeldungen (null = in Ordnung). */
    val nameError: Int? get() = if (name.isBlank()) R.string.error_name_required else null
    val entriesError: Int? get() = if (entries.isEmpty()) R.string.error_scene_needs_device else null
    val isValid: Boolean get() = nameError == null && entriesError == null
    val actionCount: Int get() = entries.sumOf { SceneTargets.toCommands(it.target).size }
}

/** Szeneneditor (SCN-001, SCN-002, SCN-004, SCN-005). */
class SceneEditorViewModel(
    private val sceneId: UUID?,
    private val repository: HomeRepository,
    private val deviceService: DeviceService,
    private val sceneRunner: SceneRunner,
    private val messages: UiMessageBus,
    private val log: EventLog,
    private val strings: Strings,
) : ViewModel() {

    private val _state = MutableStateFlow(SceneEditorState())
    val state: StateFlow<SceneEditorState> = _state.asStateFlow()
    val devices: StateFlow<List<Device>> = deviceService.devices

    init {
        viewModelScope.launch {
            if (sceneId == null) {
                _state.value = SceneEditorState(loading = false, isNew = true)
                return@launch
            }
            val scene = repository.scene(sceneId)
            _state.value = if (scene == null) {
                SceneEditorState(loading = false, isNew = false, notFound = true)
            } else {
                SceneEditorState(
                    loading = false,
                    isNew = false,
                    name = scene.name,
                    icon = scene.icon,
                    entries = entriesFrom(scene),
                )
            }
        }
    }

    private fun entriesFrom(scene: Scene): List<SceneEntry> =
        scene.actions.groupBy { it.deviceId } // LinkedHashMap: erste Reihenfolge bleibt erhalten
            .mapNotNull { (deviceId, actions) ->
                SceneTargets.fromCommands(actions.map { it.command }, deviceService.device(deviceId))
                    ?.let { SceneEntry(deviceId, it) }
            }

    private fun edit(transform: (SceneEditorState) -> SceneEditorState) =
        _state.update { transform(it).copy(dirty = true) }

    fun setName(name: String) = edit { it.copy(name = name.take(30)) }
    fun setIcon(icon: String) = edit { it.copy(icon = icon) }

    fun addDevices(ids: Collection<UUID>) = edit { s ->
        val present = s.entries.map { it.deviceId }.toSet()
        val added = ids.filterNot { it in present }.mapNotNull { id ->
            deviceService.device(id)?.let(SceneTargets::defaultFor)?.let { SceneEntry(id, it) }
        }
        s.copy(entries = s.entries + added)
    }

    fun removeEntry(deviceId: UUID) = edit { s -> s.copy(entries = s.entries.filterNot { it.deviceId == deviceId }) }

    fun updateTarget(deviceId: UUID, target: DeviceTarget) = edit { s ->
        s.copy(entries = s.entries.map { if (it.deviceId == deviceId) it.copy(target = target) else it })
    }

    /** SCN-004: aktuelle Zustände aller enthaltenen (erreichbaren) Geräte übernehmen. */
    fun captureCurrentStates() {
        val captured = state.value.entries.associate { e ->
            e.deviceId to deviceService.device(e.deviceId)?.takeIf { it.isOnline }?.let(SceneTargets::capture)
        }
        val skipped = captured.values.count { it == null }
        edit { s -> s.copy(entries = s.entries.map { e -> captured[e.deviceId]?.let { e.copy(target = it) } ?: e }) }
        messages.info(if (skipped == 0) strings.get(R.string.msg_states_captured) else strings.get(R.string.msg_states_captured_skipped, skipped))
    }

    private fun draft(id: UUID = sceneId ?: UUID.randomUUID()): Scene {
        val s = state.value
        return Scene(
            id = id,
            name = s.name.trim(),
            icon = s.icon,
            actions = s.entries.flatMap { e -> SceneTargets.toCommands(e.target).map { SceneAction(e.deviceId, it) } },
        )
    }

    /** Führt den aktuellen Entwurf aus, ohne zu speichern. */
    fun tryOut() {
        if (state.value.entries.isEmpty() || state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try { sceneRunner.run(draft().copy(name = state.value.name.ifBlank { strings.get(R.string.draft) })) }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun save() {
        val s = state.value
        if (!s.isValid) { _state.update { it.copy(showErrors = true) }; return }
        viewModelScope.launch {
            val scene = draft()
            repository.upsertScene(scene)
            log.info(LogCategory.SCENE, strings.get(if (s.isNew) R.string.log_scene_created else R.string.log_scene_saved, scene.name))
            messages.info(strings.get(R.string.msg_saved, scene.name))
            _state.update { it.copy(dirty = false, closed = true) }
        }
    }

    fun delete() {
        val id = sceneId ?: return
        viewModelScope.launch {
            repository.deleteScene(id)
            log.info(LogCategory.SCENE, strings.get(R.string.log_scene_deleted, state.value.name))
            messages.info(strings.get(R.string.msg_deleted, state.value.name))
            _state.update { it.copy(dirty = false, closed = true) }
        }
    }
}
