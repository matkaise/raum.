package app.raum.ui.automations

import app.raum.data.preferences.SettingsStore
import app.raum.i18n.Strings
import app.raum.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.automation.AutomationDescriber
import app.raum.automation.engine.AutomationEngine
import app.raum.automation.engine.AutomationStatus
import app.raum.data.database.PersistentEventLog
import app.raum.diagnostics.LogEntry
import app.raum.domain.models.Automation
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.UiMessageBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class AutomationRow(
    val automation: Automation,
    val sentence: String,
    val status: AutomationStatus,
    val lastEntry: LogEntry?,
)

class AutomationsViewModel(
    private val repository: HomeRepository,
    deviceService: DeviceService,
    private val engine: AutomationEngine,
    eventLog: PersistentEventLog,
    private val messages: UiMessageBus,
    private val strings: Strings,
    private val settings: SettingsStore,
) : ViewModel() {

    val rows: StateFlow<List<AutomationRow>> = combine(
        repository.automations,
        deviceService.devices,
        repository.scenes,
        engine.status,
        eventLog.observeLatestPerAutomation(),
    ) { automations, devices, scenes, status, latest ->
        val describer = AutomationDescriber(devices.associateBy { it.id }, scenes.associate { it.id to it.name }, strings, settings.temperatureUnit.value)
        automations.map { a -> AutomationRow(a, describer.sentence(a), status[a.id] ?: AutomationStatus(), latest[a.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(a: Automation, enabled: Boolean) = viewModelScope.launch {
        if (enabled && !a.isComplete) {
            messages.error(strings.get(R.string.msg_automation_incomplete, a.name))
            return@launch
        }
        repository.setAutomationEnabled(a.id, enabled)
        if (enabled) engine.resume(a.id)
    }

    fun runNow(a: Automation) {
        if (a.actions.isEmpty()) return
        engine.runNow(a)
        messages.info(strings.get(R.string.msg_automation_running, a.name))
    }

    fun resume(a: Automation) = engine.resume(a.id)

    fun duplicate(a: Automation) = viewModelScope.launch {
        // Kopien starten deaktiviert – sonst lösen zwei gleiche Automationen doppelt aus.
        repository.upsertAutomation(a.copy(id = UUID.randomUUID(), name = strings.get(R.string.copy_suffix, a.name).take(40), enabled = false))
        messages.info(strings.get(R.string.msg_copy_created_disabled))
    }

    fun delete(a: Automation) = viewModelScope.launch {
        repository.deleteAutomation(a.id)
        messages.info(strings.get(R.string.msg_deleted, a.name))
    }
}
