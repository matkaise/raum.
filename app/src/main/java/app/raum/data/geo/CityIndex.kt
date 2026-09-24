package app.raum.data.geo

import app.raum.automation.triggers.GeoLocation
import java.io.InputStream
import java.lang.ref.SoftReference
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/** Ein Ort aus der Offline-Ortsliste (GeoNames, CC BY 4.0). */
data class City(
    /** Anzeigename – bei einem Treffer über einen Alternativnamen dieser (z. B. „München“ statt „Munich“). */
    val name: String,
    val countryCode: String,
    val region: String,
    val location: GeoLocation,
    val timeZone: String,
)

/**
 * Offline-Ortssuche für den Standort (Sonnenzeiten). Keine Ortung, kein Netz: die Liste liegt als
 * `assets/cities.dat` (gzip-komprimiertes TSV) in der App (erzeugt mit `tools/build-cities.py`), nach Einwohnern sortiert.
 *
 * Die Liste (~5 MB Text) wird erst bei der ersten Suche geladen und nur weich referenziert,
 * damit sie im Dauerbetrieb keinen Speicher belegt.
 */
class CityIndex(private val open: () -> InputStream) {

    private class Data(
        val names: Array<String>,
        val alternates: Array<List<String>>,
        val keys: Array<List<String>>, // [0] = Name, danach Alternativnamen – normalisiert
        val countries: Array<String>,
        val regions: Array<String>,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val zones: Array<String>,
    )

    private var cache = SoftReference<Data>(null)

    @Synchronized
    private fun data(): Data = cache.get() ?: load().also { cache = SoftReference(it) }

    private fun load(): Data {
        val lines = GZIPInputStream(open()).bufferedReader(Charsets.UTF_8).useLines { seq -> seq.filter { it.isNotBlank() }.toList() }
        val n = lines.size
        val names = arrayOfNulls<String>(n)
        val alternates = arrayOfNulls<List<String>>(n)
        val keys = arrayOfNulls<List<String>>(n)
        val countries = arrayOfNulls<String>(n)
        val regions = arrayOfNulls<String>(n)
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        val zones = arrayOfNulls<String>(n)
        lines.forEachIndexed { i, line ->
            val f = line.split('\t')
            names[i] = f[0]
            val alts = if (f[1].isEmpty()) emptyList() else f[1].split('|')
            alternates[i] = alts
            keys[i] = (listOf(f[0]) + alts).map(::searchKey)
            countries[i] = f[2]
            regions[i] = f[3]
            lat[i] = f[4].toDouble()
            lon[i] = f[5].toDouble()
            zones[i] = f[6].intern()
        }
        @Suppress("UNCHECKED_CAST")
        return Data(
            names as Array<String>, alternates as Array<List<String>>, keys as Array<List<String>>,
            countries as Array<String>, regions as Array<String>, lat, lon, zones as Array<String>,
        )
    }

    /**
     * Sucht nach Namensanfang, danach nach Teilwort. Orte der eigenen Zeitzone und größere Orte zuerst.
     * Groß-/Kleinschreibung, Akzente und ä/ae, ö/oe, ü/ue, ß/ss werden gleich behandelt.
     */
    fun search(query: String, preferredZone: String? = null, limit: Int = 20): List<City> {
        val q = searchKey(query)
        if (q.length < 2) return emptyList()
        val d = data()
        // Rang: 0 exakt, 1 beginnt mit, 2 Wortanfang, 3 enthält – Name und Alternativnamen gleichwertig,
        // danach eigene Zeitzone, danach Größe (Zeilenreihenfolge)
        val hits = ArrayList<Triple<Int, Int, Int>>() // (rang, index, alternativ-index oder -1)
        for (i in d.names.indices) {
            val keys = d.keys[i]
            var best = Int.MAX_VALUE
            var via = -1
            for ((k, key) in keys.withIndex()) {
                val rank = when {
                    key == q -> 0
                    key.startsWith(q) -> 1
                    key.contains(" $q") || key.contains("-$q") -> 2
                    key.contains(q) -> 3
                    else -> continue
                }
                // bei gleichem Rang den Hauptnamen bevorzugen (k == 0 kommt zuerst)
                if (rank < best) { best = rank; via = k - 1 }
                if (best == 0) break
            }
            if (best != Int.MAX_VALUE) hits += Triple(best, i, via)
        }
        return hits
            // Großstädte (die ~1000 größten) brauchen keinen Zeitzonen-Bonus: „Mün…“ → München vor Münchenstein
            .sortedWith(compareBy({ it.first }, { if (it.second < MAJOR || d.zones[it.second] == preferredZone) 0 else 1 }, { it.second }))
            .take(limit)
            .map { (_, i, via) -> city(d, i, if (via >= 0) d.alternates[i][via] else d.names[i]) }
    }

    /** Größte Orte einer Zeitzone – Vorschläge, bevor etwas eingegeben wurde. */
    fun largestIn(zone: String, limit: Int = 8): List<City> {
        val d = data()
        return d.zones.indices.asSequence().filter { d.zones[it] == zone }.take(limit).map { city(d, it, d.names[it]) }.toList()
    }

    private fun city(d: Data, i: Int, name: String) =
        City(name, d.countries[i], d.regions[i], GeoLocation(d.lat[i], d.lon[i]), d.zones[i])

    companion object {
        private const val MAJOR = 1_000
        const val ASSET = "cities.dat" // gzip; bewusst nicht .gz – der Build würde die Datei sonst entpacken

        fun searchKey(s: String): String {
            val lower = s.trim().lowercase().replace("ß", "ss")
            val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            return stripped.replace("ae", "a").replace("oe", "o").replace("ue", "u")
        }
    }
}
