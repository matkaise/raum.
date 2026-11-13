package app.raum.homeconnect

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive

enum class HcMode {
    /** Ab Werk: keine Verbindung, keine Seite „Haushalt“. */
    OFF,
    /** Echte Geräte über die Home-Connect-Cloud. */
    CLOUD,
}

/** Gespeichertes Lieblingsgetränk („Meine Getränke“). */
@Serializable
data class SavedDrink(val programKey: String, val label: String, val options: Map<String, JsonPrimitive>)

/** Einstellungen von Home Connect – getrennt von raum_settings, Zugangsdaten liegen im Keystore (HcAuth). */
interface HcSettings : HcBudgetStore {
    val mode: StateFlow<HcMode>
    fun setMode(mode: HcMode)
    val clientId: StateFlow<String>
    fun setClientId(id: String)
    val drinks: StateFlow<List<SavedDrink>>
    fun setDrinks(list: List<SavedDrink>)
    /** Programmlisten und -details (24 h), nicht geheim. */
    var catalogCache: String?
    fun clearAll()
}

class HcPrefs(context: Context) : HcSettings {
    private val prefs = context.getSharedPreferences("raum_homeconnect", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(runCatching { HcMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(HcMode.OFF))
    override val mode: StateFlow<HcMode> = _mode.asStateFlow()
    override fun setMode(mode: HcMode) { prefs.edit().putString(KEY_MODE, mode.name).apply(); _mode.value = mode }

    private val _clientId = MutableStateFlow(prefs.getString(KEY_CLIENT, null).orEmpty())
    override val clientId: StateFlow<String> = _clientId.asStateFlow()
    override fun setClientId(id: String) { prefs.edit().putString(KEY_CLIENT, id.trim()).apply(); _clientId.value = id.trim() }

    private val drinkSerializer = ListSerializer(SavedDrink.serializer())
    private val _drinks = MutableStateFlow(
        prefs.getString(KEY_DRINKS, null)?.let { runCatching { HcJson.json.decodeFromString(drinkSerializer, it) }.getOrNull() }.orEmpty(),
    )
    override val drinks: StateFlow<List<SavedDrink>> = _drinks.asStateFlow()
    override fun setDrinks(list: List<SavedDrink>) {
        prefs.edit().putString(KEY_DRINKS, HcJson.json.encodeToString(drinkSerializer, list)).apply()
        _drinks.value = list
    }

    override var catalogCache: String?
        get() = prefs.getString(KEY_CATALOG, null)
        set(v) { prefs.edit().putString(KEY_CATALOG, v).apply() }

    override fun load(): Pair<String, Int> = (prefs.getString(KEY_BUDGET_DAY, null) ?: "") to prefs.getInt(KEY_BUDGET_COUNT, 0)

    // commit: der Zähler muss einen Absturz überstehen, sonst überzieht ein Neustart das Tageskontingent
    @SuppressLint("ApplySharedPref")
    override fun save(day: String, count: Int) { prefs.edit().putString(KEY_BUDGET_DAY, day).putInt(KEY_BUDGET_COUNT, count).commit() }

    @SuppressLint("ApplySharedPref")
    override fun clearAll() {
        prefs.edit().clear().commit()
        _mode.value = HcMode.OFF; _clientId.value = ""; _drinks.value = emptyList()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_CLIENT = "client_id"
        const val KEY_DRINKS = "drinks"
        const val KEY_CATALOG = "catalog"
        const val KEY_BUDGET_DAY = "budget_day"
        const val KEY_BUDGET_COUNT = "budget_count"
    }
}
