package app.raum.data.geo

import app.raum.automation.triggers.GeoLocation
import app.raum.data.weather.WeatherHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** Treffer einer Ortssuche – [online] sagt, ob sie aus dem Netz oder aus der eingebauten Liste kommen. */
data class PlaceResults(val cities: List<City>, val online: Boolean)

/**
 * Ortssuche für den Standort: zuerst die Ortssuche von Open-Meteo (frei, ohne Konto – übertragen wird nur der
 * eingegebene Ortsname und die App-Sprache), bei Fehlern, ohne Netz oder ohne Treffer die eingebaute Ortsliste.
 */
class PlaceSearch(
    private val index: CityIndex,
    private val http: WeatherHttp,
    private val userAgent: String,
) {
    suspend fun search(query: String, preferredZone: String?, language: String, limit: Int = 8): PlaceResults = withContext(Dispatchers.IO) {
        val q = query.trim()
        // Ein Zeichen findet online nichts Sinnvolles – dann gleich lokal
        val online = if (q.length >= 2) runCatching { online(q, language, limit) }.getOrNull() else null
        val local = runCatching { index.search(q, preferredZone, limit) }.getOrDefault(emptyList())
        if (online.isNullOrEmpty()) return@withContext PlaceResults(local, online = false)
        // Nachbarorte zuerst: Treffer in der eigenen Zeitzone (online und aus der Liste), dann der Rest der Welt
        val near = (online + local).filter { it.timeZone == preferredZone }.distinctBy { it.name.lowercase() to it.countryCode }
        PlaceResults((near + online.filter { it.timeZone != preferredZone }).take(limit), online = true)
    }

    private fun online(query: String, language: String, limit: Int): List<City> {
        // Mehr holen als gezeigt – Orte in der eigenen Zeitzone kommen nach vorn
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}" +
            "&count=${(limit * 3).coerceAtMost(30)}&language=${URLEncoder.encode(language, "UTF-8")}&format=json"
        val r = http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        check(r.code in 200..299) { "HTTP ${r.code}" }
        return OpenMeteoGeocoding.parse(r.body ?: return emptyList())
    }
}

/** Antwort der Open-Meteo-Ortssuche → [City]. */
object OpenMeteoGeocoding {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(val results: List<Result> = emptyList())

    @Serializable
    private data class Result(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country_code: String? = null,
        val admin1: String? = null,
        val timezone: String? = null,
        /** GeoNames-Merkmal: PPL… = Ort; Länder (PCL…) und Verwaltungsgebiete (ADM…) taugen nicht als Standort. */
        val feature_code: String? = null,
    )

    fun parse(body: String): List<City> = json.decodeFromString(Response.serializer(), body).results.mapNotNull { r ->
        if (r.feature_code != null && !r.feature_code.startsWith("PPL")) return@mapNotNull null
        val zone = r.timezone?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        City(
            name = r.name,
            countryCode = r.country_code.orEmpty(),
            region = r.admin1.orEmpty(),
            location = GeoLocation(r.latitude, r.longitude),
            timeZone = zone,
        )
    }
}
