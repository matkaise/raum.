package app.raum.data.weather

import app.raum.R
import app.raum.automation.triggers.GeoLocation
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

enum class WeatherError { OFFLINE, BLOCKED, THROTTLED, SERVER, PARSE }

val WeatherError.labelRes: Int
    get() = when (this) {
        WeatherError.OFFLINE -> R.string.weather_error_offline
        WeatherError.BLOCKED -> R.string.weather_error_blocked
        WeatherError.THROTTLED -> R.string.weather_error_throttled
        WeatherError.SERVER -> R.string.weather_error_server
        WeatherError.PARSE -> R.string.weather_error_parse
    }

data class WeatherState(
    val forecast: Forecast? = null,
    val fetchedAt: Instant? = null,
    val error: WeatherError? = null,
    val fetching: Boolean = false,
)

data class HttpResponse(val code: Int, val body: String?, val lastModified: String?, val expires: Instant?)

fun interface WeatherHttp {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** HTTPS über HttpURLConnection – keine zusätzliche Bibliothek. */
class UrlConnectionHttp : WeatherHttp {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
            val expires = c.getHeaderFieldDate("Expires", -1L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            return HttpResponse(code, body, c.getHeaderField("Last-Modified"), expires)
        } finally {
            c.disconnect()
        }
    }
}

/** Wann darf wieder abgefragt werden? (Nutzungsbedingungen MET Norway: Expires beachten, nicht öfter als nötig.) */
object WeatherSchedule {
    val MIN_INTERVAL: Duration = Duration.ofMinutes(30)
    private val MAX_INTERVAL: Duration = Duration.ofHours(2)
    private val BACKOFF = listOf(1L, 2L, 5L, 15L, 30L, 60L).map(Duration::ofMinutes)

    fun next(now: Instant, fetchedAt: Instant?, expires: Instant?, failures: Int, lastAttempt: Instant?): Instant {
        if (failures > 0 && lastAttempt != null) return lastAttempt.plus(BACKOFF[(failures - 1).coerceAtMost(BACKOFF.lastIndex)])
        if (fetchedAt == null) return now
        val earliest = fetchedAt.plus(MIN_INTERVAL)
        val byExpiry = expires?.coerceAtMost(fetchedAt.plus(MAX_INTERVAL)) ?: earliest
        return maxOf(earliest, byExpiry)
    }

    /** Standort auf 2 Nachkommastellen (~1 km) – mehr gibt raum. nicht preis. */
    fun round(loc: GeoLocation): Pair<Double, Double> =
        (loc.latitude * 100).roundToInt() / 100.0 to (loc.longitude * 100).roundToInt() / 100.0
}

/** Was der Wetterdienst aus den Einstellungen braucht (in Tests ersetzbar). */
interface WeatherPrefs {
    val weatherEnabled: StateFlow<Boolean>
    val location: StateFlow<GeoLocation?>
    fun setWeatherEnabled(enabled: Boolean)
}

/**
 * Wettervorhersage von MET Norway (api.met.no, CC BY 4.0) – freiwillig, ab Werk aus.
 *
 * Bewusste Ausnahme vom Grundsatz „kein Internet“ (Spez. 11.1, siehe docs/SECURITY.md): übertragen wird nur der
 * gerundete Standort, kein Konto, keine Kennung. Alles andere in raum. hängt nicht davon ab.
 */
class WeatherService(
    private val settings: WeatherPrefs,
    private val cacheFile: File,
    private val http: WeatherHttp,
    private val userAgent: String,
    private val log: EventLog,
    private val strings: Strings,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Serializable
    private data class Cache(
        val lat: Double, val lon: Double, val body: String,
        val lastModified: String? = null, val expiresMs: Long? = null, val fetchedAtMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cache: Cache? = runCatching { json.decodeFromString(Cache.serializer(), cacheFile.readText()) }.getOrNull()
    private var failures = 0
    private var lastAttempt: Instant? = null

    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    /** Nur Debug-Build: Wetterlage für die Animationen vorgeben. */
    val preview = MutableStateFlow<WeatherCondition?>(null)

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(settings.weatherEnabled, settings.location) { enabled, loc -> if (enabled) loc else null }
                .collectLatest { loc ->
                    if (loc == null) { _state.value = WeatherState(); return@collectLatest }
                    val (lat, lon) = WeatherSchedule.round(loc)
                    showCache(lat, lon)
                    while (isActive) {
                        val c = cache?.takeIf { it.lat == lat && it.lon == lon }
                        val next = WeatherSchedule.next(
                            clock.instant(), c?.fetchedAtMs?.let(Instant::ofEpochMilli),
                            c?.expiresMs?.let(Instant::ofEpochMilli), failures, lastAttempt,
                        )
                        val wait = Duration.between(clock.instant(), next).toMillis()
                        if (wait > 0) delay(wait)
                        fetch(lat, lon)
                    }
                }
        }
    }

    /** „Jetzt aktualisieren“ – höchstens alle 10 Minuten, damit niemand den Dienst überlastet. */
    suspend fun refresh(): Boolean {
        val loc = settings.location.value ?: return false
        if (!settings.weatherEnabled.value) return false
        val (lat, lon) = WeatherSchedule.round(loc)
        val last = cache?.takeIf { it.lat == lat && it.lon == lon }?.fetchedAtMs?.let(Instant::ofEpochMilli)
        if (last != null && Duration.between(last, clock.instant()) < Duration.ofMinutes(10)) return true
        fetch(lat, lon)
        return _state.value.error == null
    }

    /** Werksreset / Abschalten: nichts zurücklassen. */
    fun clearCache() {
        cache = null
        runCatching { cacheFile.delete() }
        _state.value = WeatherState()
    }

    fun setEnabled(enabled: Boolean) {
        settings.setWeatherEnabled(enabled)
        if (!enabled) clearCache()
        log.info(LogCategory.SYSTEM, strings.get(if (enabled) R.string.log_weather_on else R.string.log_weather_off))
    }

    private fun showCache(lat: Double, lon: Double) {
        val c = cache?.takeIf { it.lat == lat && it.lon == lon }
        val forecast = c?.let { runCatching { MetNorwayParser.parse(it.body) }.getOrNull() }
        _state.value = WeatherState(forecast = forecast, fetchedAt = c?.fetchedAtMs?.let(Instant::ofEpochMilli))
    }

    private suspend fun fetch(lat: Double, lon: Double) = mutex.withLock {
        _state.update { it.copy(fetching = true) }
        val c = cache?.takeIf { it.lat == lat && it.lon == lon }
        val url = String.format(Locale.ROOT, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.2f&lon=%.2f", lat, lon)
        val headers = buildMap {
            put("User-Agent", userAgent)
            c?.lastModified?.let { put("If-Modified-Since", it) }
        }
        val now = clock.instant()
        lastAttempt = now
        val response = runCatching { withContext(Dispatchers.IO) { http.get(url, headers) } }.getOrNull()
        when {
            response == null -> fail(WeatherError.OFFLINE)
            response.code == 200 || response.code == 203 -> {
                val body = response.body.orEmpty()
                val forecast = runCatching { MetNorwayParser.parse(body) }.getOrNull()
                if (forecast == null) { fail(WeatherError.PARSE); return@withLock }
                if (response.code == 203) log.warning(LogCategory.SYSTEM, strings.get(R.string.log_weather_deprecated))
                store(Cache(lat, lon, body, response.lastModified, response.expires?.toEpochMilli(), now.toEpochMilli()))
                succeed(forecast, now)
            }
            response.code == 304 && c != null -> {
                store(c.copy(expiresMs = response.expires?.toEpochMilli(), fetchedAtMs = now.toEpochMilli()))
                succeed(_state.value.forecast ?: MetNorwayParser.parse(c.body), now)
            }
            response.code == 403 -> fail(WeatherError.BLOCKED)
            response.code == 429 -> fail(WeatherError.THROTTLED, extra = 3) // deutlich länger warten
            else -> fail(WeatherError.SERVER)
        }
    }

    private fun store(c: Cache) {
        cache = c
        runCatching { cacheFile.writeText(json.encodeToString(Cache.serializer(), c)) }
    }

    private fun succeed(forecast: Forecast, now: Instant) {
        val hadError = _state.value.error != null || _state.value.forecast == null
        failures = 0
        _state.value = WeatherState(forecast = forecast, fetchedAt = now)
        if (hadError) log.info(LogCategory.SYSTEM, strings.get(R.string.log_weather_ok))
    }

    private fun fail(error: WeatherError, extra: Int = 0) {
        val first = _state.value.error == null
        failures += 1 + extra
        _state.update { it.copy(error = error, fetching = false) }
        // Nur beim Übergang protokollieren, nicht bei jedem Wiederholversuch
        if (first) log.warning(LogCategory.SYSTEM, strings.get(R.string.log_weather_failed, strings.get(error.labelRes)))
    }
}
