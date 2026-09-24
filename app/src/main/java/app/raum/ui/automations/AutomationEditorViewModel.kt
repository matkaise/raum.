package app.raum.ui.automations

import app.raum.i18n.Strings
import app.raum.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.automation.engine.AutomationEngine
import app.raum.data.preferences.SettingsStore
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.domain.models.Automation
import app.raum.domain.models.AutomationAction
import app.raum.domain.models.Condition
import app.raum.domain.models.Device
import app.raum.domain.models.Room
import app.raum.domain.models.Scene
import app.raum.domain.models.Trigger
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.UiMessageBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AutomationEditorState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val isNew: Boolean = true,
    val id: UUID = UUID.randomUUID(),
    val name: String = "",
    val enabled: Boolean = true,
    val triggers: List<Trigger> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val actions: List<AutomationAction> = emptyList(),
    val dirty: Boolean = false,
    val showErrors: Boolean = false,
    val closed: Boolean = false,
) {
    val draft: Automation get() = Automation(id, name.trim(), enabled, triggers, conditions, actions)
    /** Ressourcen-IDs der Fehlermeldungen. */
    val errors: List<Int>
        get() = buildList {
            if (name.isBlank()) add(R.string.error_name_required)
            if (triggers.isEmpty()) add(R.string.error_trigger_required)
            if (actions.isEmpty()) add(R.string.error_action_required)
        }
}

/** Automationseditor (AUT-001, AUT-002, AUT-008, AUT-009). */
class AutomationEditorViewModel(
    private val automationId: UUID?,
    private val repository: HomeRepository,
    deviceService: DeviceService,
    private val engine: AutomationEngine,
    private val settings: SettingsStore,
    private val messages: UiMessageBus,
    private val log: EventLog,
    private val strings: Strings,
) : ViewModel() {

    private val _state = MutableStateFlow(AutomationEditorState())
    val state: StateFlow<AutomationEditorState> = _state.asStateFlow()
    val devices: StateFlow<List<Device>> = deviceService.devices
    val scenes: StateFlow<List<Scene>> = repository.scenes
    val rooms: StateFlow<List<Room>> = repository.rooms
    val hasLocation: Boolean get() = settings.location.value != null

    init {
        viewModelScope.launch {
            val existing = automationId?.let { repository.automation(it) }
            _state.value = when {
                automationId == null -> AutomationEditorState(loading = false)
                existing == null -> AutomationEditorState(loading = false, notFound = true)
                else -> AutomationEditorState(
                    loading = false, isNew = false, id = existing.id, name = existing.name, enabled = existing.enabled,
                    triggers = existing.triggers, conditions = existing.conditions, actions = existing.actions,
                )
            }
        }
    }

    private fun edit(t: (AutomationEditorState) -> AutomationEditorState) = _state.update { t(it).copy(dirty = true) }

    fun setName(v: String) = edit { it.copy(name = v.take(40)) }
    fun setEnabled(v: Boolean) = edit { it.copy(enabled = v) }

    /** index = null → anhängen */
    fun putTrigger(index: Int?, t: Trigger) = edit { it.copy(triggers = it.triggers.put(index, t)) }
    fun removeTrigger(index: Int) = edit { it.copy(triggers = it.triggers.without(index)) }
    fun putCondition(index: Int?, c: Condition) = edit { it.copy(conditions = it.conditions.put(index, c)) }
    fun removeCondition(index: Int) = edit { it.copy(conditions = it.conditions.without(index)) }
    fun putAction(index: Int?, a: AutomationAction) = edit { it.copy(actions = it.actions.put(index, a)) }
    fun removeAction(index: Int) = edit { it.copy(actions = it.actions.without(index)) }
    fun moveAction(index: Int, delta: Int) = edit { s ->
        val to = index + delta
        if (to !in s.actions.indices) s else s.copy(actions = s.actions.toMutableList().apply { add(to, removeAt(index)) })
    }

    /** AUT-008: Entwurf sofort ausführen (Bedingungen werden dabei ignoriert). */
    fun tryOut() {
        val draft = state.value.draft
        if (draft.actions.isEmpty()) return
        engine.runNow(draft.copy(name = draft.name.ifBlank { strings.get(R.string.draft) }))
        messages.info(strings.get(R.string.msg_actions_running))
    }

    fun save() {
        val s = state.value
        if (s.errors.isNotEmpty()) { _state.update { it.copy(showErrors = true) }; return }
        viewModelScope.launch {
            repository.upsertAutomation(s.draft)
            engine.resume(s.id) // nach einer Korrektur nicht weiter pausieren
            log.record(LogCategory.AUTOMATION, app.raum.diagnostics.LogLevel.INFO,
                strings.get(if (s.isNew) R.string.msg_created else R.string.msg_saved, s.draft.name), automationId = s.id)
            messages.info(strings.get(R.string.msg_saved, s.draft.name))
            _state.update { it.copy(dirty = false, closed = true) }
        }
    }

    fun delete() {
        val id = automationId ?: return
        viewModelScope.launch {
            repository.deleteAutomation(id)
            messages.info(strings.get(R.string.msg_deleted, state.value.name))
            _state.update { it.copy(dirty = false, closed = true) }
        }
    }
}

private fun <T> List<T>.put(index: Int?, item: T): List<T> =
    if (index == null || index !in indices) this + item else toMutableList().also { it[index] = item }

private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }
