package app.raum.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.domain.usecases.UiMessageBus
import app.raum.homeconnect.HcException
import app.raum.homeconnect.HcFailure
import app.raum.homeconnect.HcKeys
import app.raum.homeconnect.HomeConnectService
import app.raum.homeconnect.OperationState
import app.raum.homeconnect.SavedDrink
import app.raum.homeconnect.labelRes
import app.raum.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/** Seite „Haushalt“: Auswahl, Befehle und Fehlermeldungen. */
class HouseholdViewModel(
    val service: HomeConnectService,
    val grohe: app.raum.grohe.GroheService,
    private val messages: UiMessageBus,
    private val strings: Strings,
) : ViewModel() {
    val state = service.state
    val drinks: StateFlow<List<SavedDrink>> = service.drinks

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Geräte mit laufendem Befehl – Knöpfe so lange gesperrt. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    val groheState = grohe.state

    fun select(haId: String) {
        _selected.value = haId
        if (haId.startsWith(app.raum.grohe.GroheDevice.PREFIX)) grohe.refreshSoon()
        else viewModelScope.launch { service.ensurePrograms(haId) }
    }

    /** Grohe Blue: Wasser zapfen. */
    fun dispense(id: String, type: app.raum.grohe.WaterType, ml: Int) = act(id) {
        grohe.dispense(id, type, ml).fold({ Result.success(Unit) }, { e ->
            val ge = e as? app.raum.grohe.GroheException
            messages.error(listOfNotNull(strings.get(if (ge?.kind == app.raum.grohe.GroheException.Kind.AUTH) app.raum.R.string.grohe_fail_auth else app.raum.R.string.grohe_fail), ge?.detail).joinToString(" – "))
            Result.success(Unit)
        })
    }

    fun ensureDetail(haId: String, key: String) {
        viewModelScope.launch { service.ensureDetail(haId, key) }
    }

    fun refine(haId: String, key: String) {
        viewModelScope.launch { service.refineProgram(haId, key) }
    }

    fun start(haId: String, key: String, options: Map<String, JsonPrimitive>) = act(haId) { service.start(haId, key, options) }
    fun stop(haId: String) = act(haId) { service.stop(haId) }
    fun pause(haId: String, pause: Boolean) = act(haId) { service.pause(haId, pause) }
    fun setPower(haId: String, on: Boolean) = act(haId) { service.setPower(haId, on) }

    /** Startzeitvorwahl verwerfen und sofort starten: anhalten, auf „Bereit“ warten, ohne Verzögerung neu starten. */
    fun startNow(haId: String) = act(haId) {
        val a = state.value.appliance(haId) ?: return@act Result.failure(HcException(HcFailure.OFFLINE))
        val key = a.activeProgram ?: return@act Result.failure(HcException(HcFailure.NOT_RUNNING))
        val options = a.values.filterKeys { it.contains(".Option.") && it !in TRANSIENT }
        service.stop(haId).onFailure { return@act Result.failure(it) }
        withTimeoutOrNull(15_000) { state.first { it.appliance(haId)?.operation == OperationState.READY } }
            ?: return@act Result.failure(HcException(HcFailure.NOT_READY))
        service.start(haId, key, options)
    }

    fun saveDrink(drink: SavedDrink) = service.saveDrink(drink)
    fun removeDrink(drink: SavedDrink) = service.removeDrink(drink)

    private fun act(haId: String, block: suspend () -> Result<Unit>) {
        if (haId in _busy.value) return
        _busy.update { it + haId }
        viewModelScope.launch {
            try {
                block().onFailure { e ->
                    val f = (e as? HcException)?.failure ?: HcFailure.NETWORK
                    val text = strings.get(f.labelRes)
                    val detail = (e as? HcException)?.detail?.takeIf { f == HcFailure.REJECTED || f == HcFailure.NETWORK }
                    messages.error(listOfNotNull(text, detail).joinToString(" – "))
                }
            } finally {
                _busy.update { it - haId }
            }
        }
    }

    private companion object {
        /** Laufzeitwerte, die keine Programmoptionen zum Setzen sind */
        val TRANSIENT = setOf(HcKeys.REMAINING, HcKeys.PROGRESS, HcKeys.ELAPSED, HcKeys.START_IN_RELATIVE, HcKeys.OVEN_CAVITY)
    }
}
