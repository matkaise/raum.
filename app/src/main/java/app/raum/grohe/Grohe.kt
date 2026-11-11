package app.raum.grohe

import app.raum.homeconnect.HcHttp
import app.raum.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Clock

/**
 * Grohe Blue (Wassersprudler mit Filter) über die Grohe-Cloud „ONDUS“, die auch die Grohe-App nutzt.
 * Inoffiziell – Grohe dokumentiert sie nicht. Vorbild: lennartkaden/Grohe-Blue-API-Python und koproductions-code/groheblue.
 */
enum class WaterType(val code: Int) { STILL(1), MEDIUM(2), SPARKLING(3) }

data class GroheDevice(
    val applianceId: String,
    val locationId: String,
    val roomId: String,
    val name: String,
    /** 104 = Blue Home, 105 = Blue Professional */
    val type: Int,
    val online: Boolean = true,
    val filterPercent: Double? = null,
    val filterLiters: Double? = null,
    val co2Percent: Double? = null,
    val co2Liters: Double? = null,
    val filterEmpty: Boolean = false,
    val co2Empty: Boolean = false,
    val cleaningNeeded: Boolean = false,
    val measuredAt: String? = null,
) {
    /** Raum. verwendet „grohe:“-Präfix, damit sich IDs nicht mit Home-Connect-Geräten mischen. */
    val id: String get() = "$PREFIX$applianceId"
    val filterLow: Boolean get() = filterEmpty || (filterPercent ?: 100.0) <= 10.0
    val co2Low: Boolean get() = co2Empty || (co2Percent ?: 100.0) <= 10.0

    companion object {
        const val PREFIX = "grohe:"
        val BLUE_TYPES = setOf(104, 105)
    }
}

class GroheException(val kind: Kind, val detail: String? = null) : Exception(detail ?: kind.name) {
    enum class Kind { AUTH, NETWORK, REJECTED, INVALID }
}

object GroheJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = this as? JsonArray
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.let { s -> s == "1" || s.equals("true", true) } } ?: false

    /** GET /v3/iot/dashboard → alle Grohe-Blue-Geräte (andere Grohe-Geräte wie Sense bleiben außen vor). */
    fun dashboard(body: String): List<GroheDevice> {
        val root = runCatching { json.parseToJsonElement(body).obj() }.getOrNull() ?: return emptyList()
        return root["locations"].arr().orEmpty().flatMap { l ->
            val loc = l.obj() ?: return@flatMap emptyList()
            loc["rooms"].arr().orEmpty().flatMap { r ->
                val room = r.obj() ?: return@flatMap emptyList()
                room["appliances"].arr().orEmpty().mapNotNull { a -> a.obj()?.let { appliance(loc["id"].str(), room["id"].str(), it) } }
            }
        }
    }

    private fun appliance(locationId: String?, roomId: String?, a: JsonObject): GroheDevice? {
        val id = a["appliance_id"].str() ?: return null
        val type = a["type"].num()?.toInt() ?: (a["type"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
        if (type !in GroheDevice.BLUE_TYPES) return null
        val m = a["data_latest"].obj()?.get("measurement").obj()
        val state = a["state"].obj()
        // „status“: [{type: "connection", value: 1}] – fehlt die Angabe, gilt das Gerät als erreichbar
        val connection = a["status"].arr()?.mapNotNull { it.obj() }?.firstOrNull { it["type"].str() == "connection" }
        return GroheDevice(
            applianceId = id, locationId = locationId ?: return null, roomId = roomId ?: return null,
            name = a["name"].str()?.takeIf { it.isNotBlank() } ?: "Grohe Blue", type = type,
            online = connection?.let { it["value"].str() == "1" || it["value"].bool() } ?: true,
            filterPercent = m?.get("remaining_filter").num(), filterLiters = m?.get("remaining_filter_liters").num(),
            co2Percent = m?.get("remaining_co2").num(), co2Liters = m?.get("remaining_co2_liters").num(),
            filterEmpty = state?.get("filter_empty").bool(), co2Empty = state?.get("co2_empty").bool(),
            cleaningNeeded = state?.get("cleaning_needed").bool(), measuredAt = m?.get("timestamp").str(),
        )
    }

    /** Befehl an das Gerät; alles außer den gesetzten Feldern bleibt aus. */
    fun commandBody(applianceId: String, tapType: Int? = null, amount: Int? = null, measure: Boolean = false): String {
        fun n(v: Int?) = v?.let { JsonPrimitive(it) } ?: JsonNull
        val command = JsonObject(
            mapOf(
                "co2_status_reset" to JsonPrimitive(false), "tap_type" to n(tapType), "cleaning_mode" to JsonPrimitive(false),
                "filter_status_reset" to JsonPrimitive(false), "get_current_measurement" to JsonPrimitive(measure),
                "tap_amount" to n(amount), "factory_reset" to JsonPrimitive(false),
                "revoke_flush_confirmation" to JsonPrimitive(false), "exec_auto_flush" to JsonPrimitive(false),
            ),
        )
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("type" to JsonNull, "appliance_id" to JsonPrimitive(applianceId), "command" to command, "commandb64" to JsonNull, "timestamp" to JsonNull)),
        )
    }
}

/** Zapfmenge prüfen: 50–2000 ml in 50er-Schritten (Grenzen des Geräts). */
fun validAmount(ml: Int): Boolean = ml in 50..2000 && ml % 50 == 0

// ---------------------------------------------------------------------------------------------
// Anmeldung
// ---------------------------------------------------------------------------------------------

/**
 * Tokens der Grohe-Cloud. Angemeldet wird auf Grohes eigener Seite (WebView im Panel); die leitet am Ende auf
 * „ondus://…“ weiter – daraus holt raum. die Tokens. Das Passwort sieht raum. nie. Tokens liegen verschlüsselt im Keystore.
 */
class GroheAuth(private val secrets: SecretStore, private val http: HcHttp, private val clock: Clock = Clock.systemUTC()) {
    @Serializable
    internal data class Tokens(val access: String, val refresh: String, val expiresAtMs: Long, val refreshExpiresAtMs: Long? = null)

    private val lock = Mutex()
    @Volatile private var tokens: Tokens? = secrets.get(KEY)?.let { runCatching { GroheJson.json.decodeFromString(Tokens.serializer(), String(it)) }.getOrNull() }
    @Volatile var needsReauth = false
        private set

    val signedIn: Boolean get() = tokens != null

    private fun save(t: Tokens?) {
        tokens = t
        secrets.put(KEY, t?.let { GroheJson.json.encodeToString(Tokens.serializer(), it).toByteArray() })
    }

    private fun store(body: String?) {
        val o = runCatching { GroheJson.json.parseToJsonElement(body.orEmpty()) as JsonObject }.getOrNull()
            ?: throw GroheException(GroheException.Kind.AUTH, "no token response")
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val access = s("access_token") ?: throw GroheException(GroheException.Kind.AUTH, "no access_token")
        val refresh = s("refresh_token") ?: tokens?.refresh ?: throw GroheException(GroheException.Kind.AUTH, "no refresh_token")
        val now = clock.millis()
        save(Tokens(access, refresh, now + (s("expires_in")?.toLongOrNull() ?: 3600) * 1000,
            s("refresh_expires_in")?.toLongOrNull()?.let { now + it * 1000 }))
        needsReauth = false
    }

    /** Weiterleitung „ondus://…“ aus der Login-Seite einlösen. [cookies] = Sitzungs-Cookies der Login-Seite. */
    suspend fun completeLogin(ondusUrl: String, cookies: String?) = withContext(Dispatchers.IO) {
        require(ondusUrl.startsWith("ondus://")) { "unexpected redirect" }
        val url = "https://" + ondusUrl.removePrefix("ondus://")
        val headers = buildMap { put("Accept", "application/json"); cookies?.let { put("Cookie", it) } }
        val r = http.request("GET", url, headers)
        if (r.code !in 200..299) throw GroheException(GroheException.Kind.AUTH, "HTTP ${r.code}")
        store(r.body)
    }

    suspend fun accessToken(force: Boolean = false): String {
        val t = tokens ?: throw GroheException(GroheException.Kind.AUTH, "not signed in")
        if (!force && clock.millis() < t.expiresAtMs - 60_000) return t.access
        return lock.withLock {
            val cur = tokens ?: throw GroheException(GroheException.Kind.AUTH, "not signed in")
            if (cur.access != t.access && clock.millis() < cur.expiresAtMs - 60_000) return@withLock cur.access
            val body = """{"refresh_token":${JsonPrimitive(cur.refresh)},"grant_type":"refresh_token"}"""
            val r = withContext(Dispatchers.IO) { http.request("POST", REFRESH_URL, mapOf("Accept" to "application/json"), body, "application/json") }
            if (r.code in 200..299) { store(r.body); tokens!!.access }
            else {
                if (r.code == 400 || r.code == 401) { needsReauth = true; throw GroheException(GroheException.Kind.AUTH, "HTTP ${r.code}") }
                throw GroheException(GroheException.Kind.NETWORK, "HTTP ${r.code}")
            }
        }
    }

    fun signOut() { save(null); needsReauth = false }

    companion object {
        const val LOGIN_URL = "https://idp2-apigw.cloud.grohe.com/v3/iot/oidc/login"
        private const val REFRESH_URL = "https://idp2-apigw.cloud.grohe.com/v3/iot/oidc/refresh"
        private const val KEY = "grohe_tokens"
    }
}

// ---------------------------------------------------------------------------------------------
// Zugang: Cloud und Demo
// ---------------------------------------------------------------------------------------------

interface GroheBackend {
    suspend fun devices(): List<GroheDevice>
    suspend fun dispense(device: GroheDevice, type: WaterType, ml: Int)
    /** Gerät um frische Messwerte bitten (Filter, CO₂). */
    suspend fun requestMeasurement(device: GroheDevice)
}

class GroheCloudBackend(private val auth: GroheAuth, private val http: HcHttp) : GroheBackend {
    private suspend fun call(method: String, url: String, body: String? = null): String? = withContext(Dispatchers.IO) {
        suspend fun once(force: Boolean) = http.request(
            method, url, mapOf("Authorization" to "Bearer ${auth.accessToken(force)}", "Accept" to "application/json"),
            body, if (body != null) "application/json" else null,
        )
        var r = once(false)
        if (r.code == 401) r = once(true)
        if (method != "GET") runCatching { android.util.Log.i("Grohe", "$method ${url.substringAfter("/iot/").take(40)}… ${body.orEmpty().take(400)} → ${r.code} ${r.body.orEmpty().take(300)}") }
        when {
            r.code in 200..299 -> r.body
            r.code == 401 -> throw GroheException(GroheException.Kind.AUTH, "HTTP 401")
            r.code >= 500 -> throw GroheException(GroheException.Kind.NETWORK, "HTTP ${r.code}")
            else -> throw GroheException(GroheException.Kind.REJECTED, "HTTP ${r.code} ${r.body.orEmpty().take(120)}")
        }
    }

    override suspend fun devices() = GroheJson.dashboard(call("GET", "$BASE/dashboard").orEmpty())

    private fun commandUrl(d: GroheDevice) = "$BASE/locations/${d.locationId}/rooms/${d.roomId}/appliances/${d.applianceId}/command"

    override suspend fun dispense(device: GroheDevice, type: WaterType, ml: Int) {
        if (!validAmount(ml)) throw GroheException(GroheException.Kind.INVALID, "$ml ml")
        call("POST", commandUrl(device), GroheJson.commandBody(device.applianceId, type.code, ml))
    }

    override suspend fun requestMeasurement(device: GroheDevice) {
        call("POST", commandUrl(device), GroheJson.commandBody(device.applianceId, measure = true))
    }

    private companion object { const val BASE = "https://idp2-apigw.cloud.grohe.com/v3/iot" }
}

/** Ein simulierter Grohe Blue Home zum Ausprobieren ohne Konto. */
class DemoGrohe : GroheBackend {
    private var filterLiters = 1500.0
    private var co2Liters = 56.0

    override suspend fun devices() = listOf(
        GroheDevice(
            "demo-blue-1", "loc", "room", "Grohe Blue", 104,
            filterPercent = (filterLiters / 2000 * 100).toInt().toDouble(), filterLiters = filterLiters,
            co2Percent = (co2Liters / 60 * 100).coerceAtMost(100.0).toInt().toDouble(), co2Liters = co2Liters,
        ),
    )

    override suspend fun dispense(device: GroheDevice, type: WaterType, ml: Int) {
        if (!validAmount(ml)) throw GroheException(GroheException.Kind.INVALID, "$ml ml")
        kotlinx.coroutines.delay(400)
        filterLiters = (filterLiters - ml / 1000.0).coerceAtLeast(0.0)
        if (type != WaterType.STILL) co2Liters = (co2Liters - ml / 1000.0 * if (type == WaterType.SPARKLING) 1.0 else 0.5).coerceAtLeast(0.0)
    }

    override suspend fun requestMeasurement(device: GroheDevice) = Unit
}
