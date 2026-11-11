package app.raum.homeconnect

import app.raum.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant

/** Was raum. von einem Home-Connect-Zugang braucht – echte Cloud oder Demo. */
interface HcBackend {
    suspend fun appliances(): List<HcAppliance>
    suspend fun status(haId: String): List<HcItem>
    suspend fun settings(haId: String): List<HcItem>
    /** null: kein Programm aktiv. */
    suspend fun activeProgram(haId: String): Pair<String, List<HcItem>>?
    suspend fun availablePrograms(haId: String): List<HcProgram>
    /** Alle Programme des Geräts, unabhängig vom aktuellen Zustand. */
    suspend fun allPrograms(haId: String): List<HcProgram>
    suspend fun programDetail(haId: String, key: String): HcProgram?
    suspend fun powerValues(haId: String): List<String>?
    suspend fun commands(haId: String): Set<String>
    /** @param units Einheit je Option, wie das Gerät sie meldet (z. B. „ml", „°C", „seconds"). */
    suspend fun start(haId: String, key: String, options: Map<String, JsonPrimitive>, units: Map<String, String> = emptyMap())
    /** Programm am Gerät auswählen, ohne zu starten (Optionen lassen sich danach am ausgewählten Programm setzen). */
    suspend fun select(haId: String, key: String)
    /** Option des ausgewählten Programms setzen. */
    suspend fun setSelectedOption(haId: String, key: String, value: JsonPrimitive, unit: String?)
    suspend fun stop(haId: String)
    suspend fun command(haId: String, key: String)
    suspend fun setSetting(haId: String, key: String, value: JsonPrimitive)
    /** Ereignisstrom; endet mit Ausnahme, wenn die Verbindung abbricht. Erstes Ereignis: „START“ (verbunden). */
    fun events(): Flow<HcEvent>
}

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

data class HcResponse(val code: Int, val body: String?, val retryAfter: Long?)

/** Offener Ereignisstrom: Zeilen lesen, schließen bricht ein blockierendes Lesen ab. */
interface HcLineStream : Closeable {
    fun readLine(): String?
}

interface HcHttp {
    fun request(method: String, url: String, headers: Map<String, String>, body: String? = null, contentType: String? = null): HcResponse
    /** Wirft bei HTTP-Fehler eine [HcException] (mit Antwortcode im Text). */
    fun openStream(url: String, headers: Map<String, String>, readTimeoutMs: Int): HcLineStream
}

/** HTTPS über HttpURLConnection – keine zusätzliche Bibliothek (wie das Wetter). */
class UrlConnectionHcHttp : HcHttp {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): HcResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            return HcResponse(code, text, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        } finally {
            c.disconnect()
        }
    }

    override fun openStream(url: String, headers: Map<String, String>, readTimeoutMs: Int): HcLineStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        if (code !in 200..299) {
            val body = c.errorStream?.bufferedReader()?.use { it.readText() }
            val retry = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            c.disconnect()
            throw httpFailure(code, body, retry)
        }
        val reader: BufferedReader = c.inputStream.bufferedReader()
        return object : HcLineStream {
            override fun readLine(): String? = reader.readLine()
            override fun close() { runCatching { reader.close() }; c.disconnect() }
        }
    }
}

internal fun httpFailure(code: Int, body: String?, retryAfter: Long?): HcException {
    val (key, desc) = HcJson.error(body)
    val failure = when {
        code == 401 -> HcFailure.AUTH
        code == 429 -> HcFailure.RATE_LIMITED
        key == "SDK.Error.HomeAppliance.Connection.Initialization.Failed" || key == "SDK.Error.HomeApplianceOffline" -> HcFailure.OFFLINE
        key == "SDK.Error.RemoteStartNotActivated" || key == "SDK.Error.RemoteControlNotActive" -> HcFailure.REMOTE_START_OFF
        key == "SDK.Error.WrongOperationState" -> HcFailure.NOT_READY
        key == "SDK.Error.LocalControlActive" -> HcFailure.LOCAL_CONTROL
        key == "SDK.Error.DoorOpen" -> HcFailure.DOOR_OPEN
        code >= 500 -> HcFailure.NETWORK
        else -> HcFailure.REJECTED
    }
    return HcException(failure, listOfNotNull("HTTP $code", key, desc).joinToString(" · "), retryAfter ?: if (code == 429) 60 else null)
}

// ---------------------------------------------------------------------------------------------
// Anmeldung (OAuth Device Flow)
// ---------------------------------------------------------------------------------------------

/** Code für die Anmeldung auf dem Telefon. */
data class HcDeviceCode(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Long,
    val expiresAt: Instant,
    internal val deviceCode: String,
)

sealed interface HcPoll {
    data object Pending : HcPoll
    data object SlowDown : HcPoll
    data object Success : HcPoll
    data object Denied : HcPoll
    data object Expired : HcPoll
    data class Error(val detail: String) : HcPoll
}

/**
 * Zugang zur Home-Connect-Cloud über den „Device Flow“: raum. zeigt einen Code, der Nutzer bestätigt ihn auf dem
 * Telefon (SingleKey ID). Tokens liegen verschlüsselt im Keystore ([SecretStore]); das Refresh-Token wechselt bei
 * jeder Erneuerung, deshalb sind Erneuerungen serialisiert.
 */
class HcAuth(
    private val secrets: SecretStore,
    private val http: HcHttp,
    private val host: String = PROD_HOST,
    private val clock: Clock = Clock.systemUTC(),
    /** Fest eingebautes Client Secret (Build-Eigenschaft) – ein am Panel eingetragenes hat Vorrang. */
    private val builtInSecret: String = "",
) {
    @Serializable
    internal data class Tokens(val clientId: String, val access: String, val refresh: String?, val expiresAtMs: Long)

    private val refreshLock = Mutex()
    @Volatile private var tokens: Tokens? = load()
    /** Refresh-Token ungültig (widerrufen oder 60 Tage unbenutzt) – neu anmelden. */
    @Volatile var needsReauth: Boolean = false
        private set

    val signedInClient: String? get() = tokens?.clientId

    private fun load(): Tokens? = secrets.get(KEY_TOKENS)?.let { runCatching { HcJson.json.decodeFromString(Tokens.serializer(), String(it)) }.getOrNull() }

    private fun save(t: Tokens?) {
        tokens = t
        secrets.put(KEY_TOKENS, t?.let { HcJson.json.encodeToString(Tokens.serializer(), it).toByteArray() })
    }

    fun clientSecret(): String? = secrets.get(KEY_SECRET)?.let { String(it) }?.takeIf { it.isNotBlank() } ?: builtInSecret.takeIf { it.isNotBlank() }
    /** Am Panel eingetragenes Secret (nicht das eingebaute). */
    val hasOwnSecret: Boolean get() = secrets.get(KEY_SECRET) != null
    fun setClientSecret(secret: String?) = secrets.put(KEY_SECRET, secret?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray())

    fun signOut() { save(null); needsReauth = false }

    /** Alles löschen (Werksreset). */
    fun clearAll() { signOut(); secrets.put(KEY_SECRET, null) }

    suspend fun startDeviceFlow(clientId: String): HcDeviceCode = withContext(Dispatchers.IO) {
        val r = http.request("POST", "https://$host/security/oauth/device_authorization", FORM_HEADERS,
            form("client_id" to clientId, "scope" to SCOPES), FORM)
        if (r.code !in 200..299) throw httpFailure(r.code, r.body, r.retryAfter)
        val o = HcJson.json.parseToJsonElement(r.body.orEmpty()) as kotlinx.serialization.json.JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content
        HcDeviceCode(
            userCode = s("user_code") ?: throw HcException(HcFailure.REJECTED, "no user_code"),
            verificationUri = s("verification_uri") ?: "https://$host/security/oauth/device_verify",
            verificationUriComplete = s("verification_uri_complete"),
            intervalSeconds = s("interval")?.toLongOrNull() ?: 5,
            expiresAt = clock.instant().plusSeconds(s("expires_in")?.toLongOrNull() ?: 300),
            deviceCode = s("device_code") ?: throw HcException(HcFailure.REJECTED, "no device_code"),
        )
    }

    suspend fun poll(clientId: String, code: HcDeviceCode): HcPoll = withContext(Dispatchers.IO) {
        val secret = clientSecret()
        fun attempt(grant: String) = http.request("POST", "https://$host/security/oauth/token", FORM_HEADERS, form(
            "client_id" to clientId, "grant_type" to grant, "device_code" to code.deviceCode, "client_secret" to secret,
        ), FORM)
        var r = runCatching { attempt("device_code") }.getOrElse { return@withContext HcPoll.Error(it.message ?: "network") }
        var (key, desc) = HcJson.error(r.body)
        // Manche Konten verlangen den vollständigen URN-Namen des Grant-Typs
        if (r.code !in 200..299 && (key == "unsupported_grant_type" || key == "invalid_request")) {
            r = runCatching { attempt("urn:ietf:params:oauth:grant-type:device_code") }.getOrElse { return@withContext HcPoll.Error(it.message ?: "network") }
            HcJson.error(r.body).let { key = it.first; desc = it.second }
        }
        if (r.code in 200..299) {
            store(clientId, r.body)
            return@withContext HcPoll.Success
        }
        when (key) {
            "authorization_pending" -> HcPoll.Pending
            "slow_down" -> HcPoll.SlowDown
            "access_denied" -> HcPoll.Denied
            "expired_token" -> HcPoll.Expired
            else -> HcPoll.Error(listOfNotNull("HTTP ${r.code}", key, desc).joinToString(" · "))
        }
    }

    private fun store(clientId: String, body: String?) {
        val o = HcJson.json.parseToJsonElement(body.orEmpty()) as kotlinx.serialization.json.JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content
        val access = s("access_token") ?: throw HcException(HcFailure.AUTH, "no access_token")
        val expiresIn = s("expires_in")?.toLongOrNull() ?: 86_400
        save(Tokens(clientId, access, s("refresh_token") ?: tokens?.refresh, clock.millis() + expiresIn * 1000))
        needsReauth = false
    }

    /** Gültiges Zugriffstoken; erneuert eine Stunde vor Ablauf. */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val t = tokens ?: throw HcException(HcFailure.AUTH, "not signed in")
        if (!forceRefresh && clock.millis() < t.expiresAtMs - REFRESH_WINDOW_MS) return t.access
        return refreshLock.withLock {
            val current = tokens ?: throw HcException(HcFailure.AUTH, "not signed in")
            // Ein paralleler Aufruf hat schon erneuert – das alte Refresh-Token ist verbraucht
            if (current.access != t.access && clock.millis() < current.expiresAtMs - REFRESH_WINDOW_MS) return@withLock current.access
            val refresh = current.refresh ?: throw HcException(HcFailure.AUTH, "no refresh token").also { needsReauth = true }
            val r = withContext(Dispatchers.IO) {
                http.request("POST", "https://$host/security/oauth/token", FORM_HEADERS,
                    form("grant_type" to "refresh_token", "refresh_token" to refresh, "client_secret" to clientSecret()), FORM)
            }
            if (r.code in 200..299) {
                store(current.clientId, r.body)
                tokens!!.access
            } else {
                val (key, desc) = HcJson.error(r.body)
                if (key == "invalid_grant") {
                    // Widerrufen oder 60 Tage ungenutzt – nur eine neue Anmeldung hilft
                    needsReauth = true
                    throw HcException(HcFailure.AUTH, listOfNotNull(key, desc).joinToString(" · "))
                }
                // Vor Ablauf weiterarbeiten, solange das alte Token noch gilt
                if (clock.millis() < current.expiresAtMs) current.access else throw httpFailure(r.code, r.body, r.retryAfter)
            }
        }
    }

    private fun form(vararg pairs: Pair<String, String?>): String =
        pairs.filter { it.second != null }.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v!!)}" }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())

    companion object {
        const val PROD_HOST = "api.home-connect.com"
        const val SCOPES = "IdentifyAppliance Monitor Settings Control"
        private const val FORM = "application/x-www-form-urlencoded"
        private val FORM_HEADERS = mapOf("Accept" to "application/json")
        private const val REFRESH_WINDOW_MS = 60 * 60 * 1000L
        private const val KEY_TOKENS = "homeconnect_tokens"
        private const val KEY_SECRET = "homeconnect_client_secret"
    }
}

// ---------------------------------------------------------------------------------------------
// Kontingent
// ---------------------------------------------------------------------------------------------

/**
 * Home Connect erlaubt je Client und Konto 1000 Anfragen pro Tag, 50 pro Minute; zehn Fehler in zehn Minuten sperren
 * den Zugang. raum. zählt mit, wartet bei 429 die genannte Zeit ab und spart Lesezugriffe, bevor das Limit erreicht ist.
 */
interface HcBudgetStore {
    fun load(): Pair<String, Int>
    fun save(day: String, count: Int)
}

class HcBudget(private val store: HcBudgetStore, private val clock: Clock = Clock.systemDefaultZone()) {
    @Volatile var blockedUntil: Instant? = null
        private set

    private fun today() = java.time.LocalDate.now(clock).toString()

    val usedToday: Int @Synchronized get() = store.load().let { (d, c) -> if (d == today()) c else 0 }

    /** @param essential Befehle des Nutzers dürfen etwas länger als reine Abfragen. */
    @Synchronized
    fun acquire(essential: Boolean) {
        blockedUntil?.let { if (clock.instant().isBefore(it)) throw HcException(HcFailure.RATE_LIMITED, null, java.time.Duration.between(clock.instant(), it).seconds) }
        val used = usedToday
        if (used >= if (essential) ESSENTIAL_LIMIT else READ_LIMIT) throw HcException(HcFailure.BUDGET)
        store.save(today(), used + 1)
    }

    fun block(seconds: Long) { blockedUntil = clock.instant().plusSeconds(seconds.coerceIn(1, 24 * 3600)) }

    companion object {
        const val DAILY = 1000
        const val READ_LIMIT = 900
        const val ESSENTIAL_LIMIT = 990
    }
}

// ---------------------------------------------------------------------------------------------
// Cloud-Zugang
// ---------------------------------------------------------------------------------------------

class HcCloudBackend(
    private val auth: HcAuth,
    private val http: HcHttp,
    private val budget: HcBudget,
    /** z. B. „de-DE“ – die API liefert Programmnamen in dieser Sprache. */
    private val language: () -> String,
    private val host: String = HcAuth.PROD_HOST,
) : HcBackend {
    private val base get() = "https://$host/api/homeappliances"

    private suspend fun call(method: String, path: String, body: String? = null, essential: Boolean = false): String? = withContext(Dispatchers.IO) {
        suspend fun once(force: Boolean): HcResponse {
            budget.acquire(essential)
            val headers = mapOf(
                "Authorization" to "Bearer ${auth.accessToken(force)}",
                "Accept" to CONTENT, "Accept-Language" to language(),
            )
            return http.request(method, base + path, headers, body, if (body != null) CONTENT else null)
        }
        var r = once(false)
        if (r.code == 401) r = once(true)
        if (r.code == 429) budget.block(r.retryAfter ?: 60)
        // Diagnose: Befehle und das Lesen des aktiven Programms ins Logcat (ohne Token; haId gekürzt)
        if (method != "GET" || path.endsWith("/programs/active") || path.endsWith("/programs/selected") || "/programs/available/" in path) {
            runCatching { android.util.Log.i(TAG, "$method ${redact(path)} ${body.orEmpty()} → ${r.code} ${r.body.orEmpty().take(600)}") }
        }
        if (r.code !in 200..299) throw httpFailure(r.code, r.body, r.retryAfter)
        r.body
    }

    /** Lesen; „gibt es nicht“ (kein Programm aktiv, Funktion fehlt) ergibt null statt Fehler. */
    private suspend fun read(path: String): String? = try {
        call("GET", path)
    } catch (e: HcException) {
        if (e.failure == HcFailure.REJECTED || e.failure == HcFailure.NOT_READY) null else throw e
    }

    override suspend fun appliances() = HcJson.appliances(call("GET", "").orEmpty())
    override suspend fun status(haId: String) = read("/$haId/status")?.let { HcJson.items(it, "status") }.orEmpty()
    override suspend fun settings(haId: String) = read("/$haId/settings")?.let { HcJson.items(it, "settings") }.orEmpty()
    override suspend fun activeProgram(haId: String) = read("/$haId/programs/active")?.let(HcJson::program)
    override suspend fun availablePrograms(haId: String) = read("/$haId/programs/available")?.let(HcJson::availablePrograms).orEmpty()
    override suspend fun allPrograms(haId: String) = read("/$haId/programs")?.let(HcJson::availablePrograms).orEmpty()
    override suspend fun programDetail(haId: String, key: String) = read("/$haId/programs/available/$key")?.let(HcJson::programDetail)
    override suspend fun powerValues(haId: String) = read("/$haId/settings/${HcKeys.POWER_STATE}")?.let(HcJson::allowedValues)
    override suspend fun commands(haId: String) = read("/$haId/commands")?.let(HcJson::commands).orEmpty()

    override suspend fun start(haId: String, key: String, options: Map<String, JsonPrimitive>, units: Map<String, String>) {
        call("PUT", "/$haId/programs/active", HcJson.programBody(key, options, units), essential = true)
    }

    override suspend fun stop(haId: String) { call("DELETE", "/$haId/programs/active", essential = true) }

    override suspend fun select(haId: String, key: String) {
        call("PUT", "/$haId/programs/selected", HcJson.programBody(key, emptyMap()), essential = true)
    }

    override suspend fun setSelectedOption(haId: String, key: String, value: JsonPrimitive, unit: String?) {
        call("PUT", "/$haId/programs/selected/options/$key", HcJson.valueBody(key, value, unit), essential = true)
    }

    override suspend fun command(haId: String, key: String) {
        call("PUT", "/$haId/commands/$key", HcJson.valueBody(key, JsonPrimitive(true)), essential = true)
    }

    override suspend fun setSetting(haId: String, key: String, value: JsonPrimitive) {
        call("PUT", "/$haId/settings/$key", HcJson.valueBody(key, value), essential = true)
    }

    override fun events(): Flow<HcEvent> = flow {
        budget.acquire(essential = true)
        val token = auth.accessToken()
        val stream = try {
            http.openStream("$base/events", mapOf("Authorization" to "Bearer $token", "Accept" to "text/event-stream", "Accept-Language" to language()), STREAM_TIMEOUT_MS)
        } catch (e: HcException) {
            if (e.failure == HcFailure.RATE_LIMITED) budget.block(e.retryAfterSeconds ?: 60)
            if (e.failure == HcFailure.AUTH) auth.accessToken(forceRefresh = true)
            throw e
        }
        // Abbruch (Abmelden, App beendet) schließt die Verbindung – sonst blockiert readLine bis zum Timeout
        currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { stream.close() }
        stream.use { s ->
            emit(HcEvent(START, null))
            val parser = SseParser()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = s.readLine() ?: throw HcException(HcFailure.NETWORK, "stream closed")
                parser.feed(line)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun redact(path: String) = path.replace(Regex("^/([^/]{6})[^/]*"), "/$1…")

    companion object {
        private const val TAG = "HomeConnect"
        const val CONTENT = "application/vnd.bsh.sdk.v1+json"
        const val START = "START"
        /** Länger als das 55-s-Keep-alive: ein stummer Strom gilt dann als tot. */
        private const val STREAM_TIMEOUT_MS = 120_000
    }
}
