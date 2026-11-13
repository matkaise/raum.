package app.raum.grohe

import android.content.Context
import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant

enum class GroheMode { OFF, CLOUD }

interface GroheSettings {
    val mode: StateFlow<GroheMode>
    fun setMode(mode: GroheMode)
    fun clearAll()
}

class GrohePrefs(context: Context) : GroheSettings {
    private val prefs = context.getSharedPreferences("raum_grohe", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(runCatching { GroheMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(GroheMode.OFF))
    override val mode: StateFlow<GroheMode> = _mode.asStateFlow()
    override fun setMode(mode: GroheMode) { prefs.edit().putString("mode", mode.name).apply(); _mode.value = mode }
    override fun clearAll() { prefs.edit().clear().apply(); _mode.value = GroheMode.OFF }
}

data class GroheState(
    val mode: GroheMode = GroheMode.OFF,
    val signedIn: Boolean = false,
    val needsReauth: Boolean = false,
    val devices: List<GroheDevice> = emptyList(),
    val loading: Boolean = false,
    val error: GroheException? = null,
    val updatedAt: Instant? = null,
    /** Gerät → bis wann es voraussichtlich zapft (für die Füll-Animation). */
    val pouring: Map<String, Pouring> = emptyMap(),
) {
    val active: Boolean get() = mode == GroheMode.CLOUD && signedIn
    fun device(id: String) = devices.firstOrNull { it.id == id }
}

data class Pouring(val type: WaterType, val ml: Int, val start: Instant, val end: Instant)

/**
 * Grohe Blue über die inoffizielle Grohe-Cloud – freiwillig, ab Werk aus (docs/SECURITY.md). Kein Ereignisstrom:
 * Stand alle 10 min abfragen, nach dem Zapfen kurz danach noch einmal (Filter/CO₂ sinken).
 */
class GroheService(
    private val settings: GroheSettings,
    private val auth: GroheAuth,
    private val cloud: GroheBackend,
    private val log: EventLog,
    private val strings: Strings,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _state = MutableStateFlow(GroheState(mode = settings.mode.value, signedIn = auth.signedIn))
    val state: StateFlow<GroheState> = _state.asStateFlow()

    private val authVersion = MutableStateFlow(0)
    private val nudge = MutableStateFlow(0)
    @Volatile private var backend: GroheBackend? = null
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            combine(settings.mode, authVersion) { m, _ -> m to auth.signedIn }.distinctUntilChanged().collectLatest { (mode, signedIn) ->
                _state.update { it.copy(mode = mode, signedIn = signedIn, needsReauth = auth.needsReauth, devices = emptyList(), error = null) }
                val b = when (mode) {
                    GroheMode.CLOUD -> if (signedIn) cloud else null
                    GroheMode.OFF -> null
                }
                backend = b
                if (b == null) return@collectLatest
                // Abfrage alle 10 min; „nudge“ holt sofort (nach dem Zapfen, beim Öffnen der Seite)
                nudge.collectLatest {
                    while (true) {
                        refresh(b)
                        if (_state.value.needsReauth) return@collectLatest
                        delay(POLL_MS)
                    }
                }
            }
        }
    }

    private suspend fun refresh(b: GroheBackend) {
        _state.update { it.copy(loading = true) }
        try {
            val list = b.devices()
            _state.update { it.copy(devices = list, loading = false, error = null, updatedAt = clock.instant(), needsReauth = false) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: GroheException) {
            if (e.kind == GroheException.Kind.AUTH && auth.needsReauth) log.warning(LogCategory.SYSTEM, strings.get(R.string.grohe_log_reauth))
            _state.update { it.copy(loading = false, error = e, needsReauth = auth.needsReauth) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = GroheException(GroheException.Kind.NETWORK, e.message)) }
        }
    }

    /** Jetzt neu laden (z. B. beim Öffnen der Seite) – höchstens jede Minute. */
    fun refreshSoon() {
        val last = _state.value.updatedAt
        if (last == null || clock.instant().isAfter(last.plusSeconds(60))) nudge.value++
    }

    /** Wasser zapfen. Etwa 30 ml/s – daraus die Füll-Animation; danach Messwerte anfordern und neu laden. */
    suspend fun dispense(id: String, type: WaterType, ml: Int): Result<Unit> {
        val b = backend ?: return Result.failure(GroheException(GroheException.Kind.NETWORK))
        val d = _state.value.device(id) ?: return Result.failure(GroheException(GroheException.Kind.NETWORK))
        if (!validAmount(ml)) return Result.failure(GroheException(GroheException.Kind.INVALID, "$ml ml"))
        return try {
            b.dispense(d, type, ml)
            val now = clock.instant()
            val p = Pouring(type, ml, now, now.plusMillis((ml / ML_PER_SECOND * 1000).toLong() + 1500))
            _state.update { it.copy(pouring = it.pouring + (id to p)) }
            log.info(LogCategory.DEVICE, strings.get(R.string.grohe_log_dispensed, ml, strings.get(type.labelRes)), d.name)
            scope?.launch {
                delay(java.time.Duration.between(clock.instant(), p.end).toMillis().coerceAtLeast(0))
                _state.update { it.copy(pouring = it.pouring - id) }
                runCatching { b.requestMeasurement(d) }
                delay(MEASURE_WAIT_MS)
                nudge.value++
            }
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (e: GroheException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(GroheException(GroheException.Kind.NETWORK, e.message))
        }
    }

    fun setMode(mode: GroheMode) {
        if (mode == settings.mode.value) return
        settings.setMode(mode)
        log.info(LogCategory.SYSTEM, strings.get(R.string.grohe_log_mode, strings.get(mode.labelRes)))
    }

    suspend fun completeLogin(ondusUrl: String, cookies: String?): Result<Unit> = try {
        auth.completeLogin(ondusUrl, cookies)
        settings.setMode(GroheMode.CLOUD)
        authVersion.value++
        log.info(LogCategory.SYSTEM, strings.get(R.string.grohe_log_signed_in))
        // kurz warten, bis die erste Abfrage durch ist – dann zeigt die Seite gleich das Gerät
        withTimeoutOrNull(5_000) { state.first { it.updatedAt != null || it.error != null } }
        Result.success(Unit)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun signOut() {
        auth.signOut()
        authVersion.value++
        log.info(LogCategory.SYSTEM, strings.get(R.string.grohe_log_signed_out))
    }

    /** Werksreset / Übergabe. */
    fun clearAll() {
        auth.signOut()
        settings.clearAll()
        authVersion.value++
    }

    companion object {
        private const val POLL_MS = 10 * 60 * 1000L
        private const val MEASURE_WAIT_MS = 15_000L
        /** Grohe Blue gibt grob 2 l/min ab */
        const val ML_PER_SECOND = 33.0
    }
}

val WaterType.labelRes: Int
    get() = when (this) {
        WaterType.STILL -> R.string.grohe_still
        WaterType.MEDIUM -> R.string.grohe_medium
        WaterType.SPARKLING -> R.string.grohe_sparkling
    }

val GroheMode.labelRes: Int
    get() = when (this) {
        GroheMode.OFF -> R.string.hc_mode_off
        GroheMode.CLOUD -> R.string.grohe_mode_cloud
    }
