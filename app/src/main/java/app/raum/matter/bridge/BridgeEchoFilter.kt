package app.raum.matter.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Unterscheidet neue Bedienwünsche anderer Apps von Echos (z. B. meldet die Bridge beim Einschalten die gespeicherte
 * Helligkeit). Eigene Zustandsübertragungen unterdrückt schon der native Code (`mApplying`).
 *
 * Referenz ist der zuletzt weitergeleitete Wunsch je Endpunkt und Art, sonst der veröffentlichte Zustand. Nur mit dem
 * veröffentlichten Zustand würde „Aus“ nach einem noch nicht ausgeführten „Ein“ als Echo verworfen – am Ende wäre die
 * Lampe an. Ändert sich der veröffentlichte Zustand eines Endpunkts (ausgeführt, zurückgerollt, von außen geändert),
 * gilt wieder er; so geht auch ein erneuter Versuch nach einem Fehlschlag durch.
 */
class BridgeEchoFilter {
    private val wishes = ConcurrentHashMap<Pair<Int, String>, Int>()

    /**
     * true = neuer Wunsch: weiterleiten (er wird als letzter Wunsch gemerkt); false = Echo, verwerfen.
     * @param published veröffentlichter Wert dieser Art am Endpunkt; null = unbekannt.
     */
    fun accept(endpoint: Int, kind: String, value: Int, published: Int?): Boolean {
        val key = endpoint to kind
        if ((wishes[key] ?: published) == value) return false
        wishes[key] = value
        return true
    }

    /** Der veröffentlichte Zustand dieser Endpunkte hat sich geändert – offene Wünsche sind damit erledigt. */
    fun published(changed: Collection<Int>) {
        if (changed.isNotEmpty()) wishes.keys.removeIf { it.first in changed }
    }
}
