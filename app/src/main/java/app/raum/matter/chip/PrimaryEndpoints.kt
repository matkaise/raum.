package app.raum.matter.chip

import app.raum.security.KeyValueStore

/**
 * Fester Endpunkt des Hauptkanals je Node. Einmal gewählt, bleibt er: Sonst würde dieselbe Geräte-ID (Name, Raum,
 * Szenen) nach einer Änderung der Endpunkte – etwa eine neue Leuchte an einer Bridge – unbemerkt einen anderen
 * Verbraucher steuern. Gebunden wird erst, wenn die Gerätetypen aller On/Off-Endpunkte bekannt sind; bis dahin gilt
 * der Vorschlag vorläufig. Bestehende Installationen binden so beim ersten Start den bisher gewählten Endpunkt.
 */
class PrimaryEndpoints(private val store: KeyValueStore) {

    @Synchronized
    fun resolve(node: ULong, n: NodeData): Int? {
        load()[node]?.let { return it }
        val suggested = ClusterMapper.defaultPrimaryEndpoint(n) ?: return null
        if (ClusterMapper.onOffTypesKnown(n)) save(load() + (node to suggested))
        return suggested
    }

    /** Gebundener Endpunkt, ohne neu zu binden (null = noch keiner). */
    @Synchronized
    fun bound(node: ULong): Int? = load()[node]

    @Synchronized
    fun forget(node: ULong) = save(load() - node)

    @Synchronized
    fun clear() = store.putString(KEY, null)

    private fun load(): Map<ULong, Int> = store.getString(KEY).orEmpty().split(";").filter { it.isNotBlank() }.mapNotNull { e ->
        val (node, ep) = e.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { node.toULong(16) to ep.toInt() }.getOrNull()
    }.toMap()

    private fun save(map: Map<ULong, Int>) =
        store.putString(KEY, map.entries.joinToString(";") { (node, ep) -> "${node.toString(16)}:$ep" }.ifEmpty { null })

    private companion object {
        const val KEY = "primary_endpoints"
    }
}
