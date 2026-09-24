package app.raum.ui.devices

import app.raum.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.domain.models.DeviceMetadata
import app.raum.domain.models.deviceIdForNode
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.commissioning.SetupCodeParser
import app.raum.matter.controller.CommissioningResult
import app.raum.matter.controller.CommissioningStep
import app.raum.matter.controller.CommissioningFailure
import app.raum.i18n.ErrorTexts
import app.raum.i18n.Strings
import app.raum.matter.controller.MatterController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface CommissioningUiState {
    data class EnterCode(val code: String = "", val error: SetupCodeParser.InvalidReason? = null) : CommissioningUiState
    data class Running(val step: CommissioningStep) : CommissioningUiState
    data class NameDevice(
        val nodeId: ULong,
        val vendorName: String?,
        val productName: String?,
        val name: String,
        val roomId: UUID?,
        val favorite: Boolean,
    ) : CommissioningUiState
    data class Failed(val reason: CommissioningFailure, val invalidCode: SetupCodeParser.InvalidReason?, val code: String) : CommissioningUiState
    data class AlreadyPresent(val deviceName: String) : CommissioningUiState
}

/** Gerät hinzufügen per numerischem Setup-Code (COM-001, COM-005 – COM-007, COM-010). */
class CommissioningViewModel(
    private val controller: MatterController,
    private val repository: HomeRepository,
    private val messages: UiMessageBus,
    private val log: EventLog,
    private val strings: Strings,
    private val requests: app.raum.ui.UiRequests,
) : ViewModel() {

    private val _state = MutableStateFlow<CommissioningUiState>(CommissioningUiState.EnterCode())
    val state: StateFlow<CommissioningUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        _state.value = CommissioningUiState.EnterCode()
    }

    fun updateCode(input: String) {
        // Ziffern plus die auf Etiketten üblichen Trenner zulassen.
        val filtered = input.filter { it.isDigit() || it == '-' || it == ' ' }.take(26)
        _state.value = CommissioningUiState.EnterCode(filtered)
    }

    /** Nach Warnhinweis: Gerät trotz fehlgeschlagener Echtheitsprüfung aufnehmen. */
    fun startUncertified() = start(allowUncertified = true)

    fun start(allowUncertified: Boolean = false) {
        val code = (state.value as? CommissioningUiState.EnterCode)?.code
            ?: (state.value as? CommissioningUiState.Failed)?.code
            ?: return
        val parsed = SetupCodeParser.parse(code)
        if (parsed is SetupCodeParser.ParseResult.Invalid) {
            _state.value = CommissioningUiState.EnterCode(code, parsed.reason)
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = CommissioningUiState.Running(CommissioningStep.PARSING)
            val result = controller.commission(code, allowUncertified) { step -> _state.value = CommissioningUiState.Running(step) }
            if (allowUncertified && result is CommissioningResult.Success) {
                log.warning(LogCategory.DEVICE, strings.get(R.string.log_device_uncertified, "%X".format(result.nodeId.toLong())))
            }
            _state.value = when (result) {
                is CommissioningResult.Success -> {
                    val name = result.suggestedName ?: strings.get(R.string.device_new_default)
                    log.record(
                        LogCategory.DEVICE, app.raum.diagnostics.LogLevel.INFO,
                        strings.get(R.string.log_device_added, "0x%X".format(result.nodeId.toLong())),
                        name, deviceIdForNode(result.nodeId),
                    )
                    CommissioningUiState.NameDevice(
                        nodeId = result.nodeId,
                        vendorName = result.vendorName,
                        productName = result.productName,
                        name = name,
                        roomId = null,
                        favorite = false,
                    )
                }
                is CommissioningResult.AlreadyCommissioned -> {
                    val name = repository.deviceMetadata.value.firstOrNull { it.matterNodeId == result.nodeId }?.displayName
                    CommissioningUiState.AlreadyPresent(name ?: "Node 0x%X".format(result.nodeId.toLong()))
                }
                is CommissioningResult.Failure -> {
                    log.error(
                        LogCategory.DEVICE,
                        strings.get(R.string.log_commissioning_failed, strings.get(ErrorTexts.commissioning(result.reason))) +
                            (result.detail?.let { " ($it)" } ?: ""),
                    )
                    CommissioningUiState.Failed(result.reason, result.invalidCode, code)
                }
            }
        }
    }

    fun updateNaming(name: String? = null, roomId: UUID? = null, clearRoom: Boolean = false, favorite: Boolean? = null) {
        val s = state.value as? CommissioningUiState.NameDevice ?: return
        _state.value = s.copy(
            name = name?.take(40) ?: s.name,
            roomId = if (clearRoom) null else roomId ?: s.roomId,
            favorite = favorite ?: s.favorite,
        )
    }

    /** Speichert Name, Raum und Favoritenstatus (COM-007). Wird auch beim Schließen aufgerufen. */
    fun finish() {
        val s = state.value as? CommissioningUiState.NameDevice ?: run { reset(); return }
        viewModelScope.launch {
            repository.upsertDevice(
                DeviceMetadata(
                    id = deviceIdForNode(s.nodeId),
                    matterNodeId = s.nodeId,
                    displayName = s.name.ifBlank { strings.get(R.string.device_new_default) }.trim(),
                    roomId = s.roomId,
                    vendorName = s.vendorName,
                    productName = s.productName,
                    favorite = s.favorite,
                )
            )
            messages.info(strings.get(R.string.msg_device_added, s.name.ifBlank { strings.get(R.string.device_new_default) }))
            reset()
        }
    }

    /** Nach „kein Thread-Netz/WLAN hinterlegt“: direkt zu Einstellungen → Matter und Thread. */
    fun openNetworkSettings() {
        reset()
        requests.openSettings.value = "MATTER"
    }

    fun cancel() {
        if (state.value is CommissioningUiState.NameDevice) finish() else reset()
    }
}
