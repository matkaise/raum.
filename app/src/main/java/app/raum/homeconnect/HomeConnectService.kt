package app.raum.homeconnect

import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class HcLink { IDLE, CONNECTING, LIVE, RETRYING, NEEDS_SIGN_IN }

data class HomeConnectState(
    val mode: HcMode = HcMode.OFF,
    val signedIn: Boolean = false,
    val needsReauth: Boolean = false,
    /** Laufende Anmeldung: Code für das Telefon. */
    val signIn: HcDeviceCode? = null,
    val signInError: String? = null,
    val link: HcLink = HcLink.IDLE,
    val linkError: HcException? = null,
    val retryAt: Instant? = null,
    val appliances: List<HcAppliance> = emptyList(),
    /** haId → verfügbare Programme (Details werden bei Bedarf nachgeladen). */
    val programs: Map<String, List<HcProgram>> = emptyMap(),
    val requestsToday: Int = 0,
) {
    /** Seite „Haushalt“ zeigen? */
    val active: Boolean get() = mode == HcMode.DEMO || (mode == HcMode.CLOUD && signedIn)
    fun appliance(haId: String) = appliances.firstOrNull { it.haId == haId }
}

/**
 * Meldung für die Oberfläche: auf der Seite „Haushalt“ als Hinweis oben, auf allen anderen Seiten (nur wenn [global])
 * als Snackbar – dort zeigt RaumApp sie an.
 */
data class HcNotice(val haId: String, val text: String, val at: Instant, val global: Boolean)

/**
 * Home-Connect-Geräte (Kaffeevollautomat, Backofen, Geschirrspüler, Waschmaschine, Trockner …) über die Cloud von
 * BSH – freiwillige Ausnahme vom Grundsatz „lokal“ (docs/SECURITY.md). Hält einen einzigen Ereignisstrom für alle
 * Geräte offen statt abzufragen, liest nach jedem (Wieder-)Verbinden den Zustand neu und lädt Programmlisten nur
 * bei Bedarf (24 h zwischengespeichert) – so bleibt raum. weit unter 1000 Anfragen am Tag.
 */
class HomeConnectService(
    private val settings: HcSettings,
    private val auth: HcAuth,
    private val budget: HcBudget,
    private val cloud: HcBackend,
    private val demoFactory: () -> HcBackend,
    private val language: () -> String,
    private val log: EventLog,
    private val strings: Strings,
    private val clock: Clock = Clock.systemUTC(),
    /** Fest eingebaute Client-ID (Build-Eigenschaft raumHomeConnectClientId); eine am Panel eingetragene hat Vorrang. */
    private val builtInClientId: String = "",
) {
    private val _state = MutableStateFlow(HomeConnectState(mode = settings.mode.value, signedIn = auth.signedInClient != null))
    val state: StateFlow<HomeConnectState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<HcNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<HcNotice> = _notices.asSharedFlow()

    private val authVersion = MutableStateFlow(0)
    @Volatile private var backend: HcBackend? = null
    private var scope: CoroutineScope? = null
    private var signInJob: Job? = null
    private var lastDiscovery: Instant? = null
    private var demo: HcBackend? = null

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            combine(settings.mode, authVersion) { mode, _ -> mode to (auth.signedInClient != null) }
                .distinctUntilChanged()
                .collectLatest { (mode, signedIn) ->
                    _state.update { it.copy(mode = mode, signedIn = signedIn, needsReauth = auth.needsReauth) }
                    val b = when (mode) {
                        HcMode.DEMO -> demo ?: demoFactory().also { demo = it }
                        HcMode.CLOUD -> if (signedIn) cloud else null
                        HcMode.OFF -> null
                    }
                    backend = b
                    lastDiscovery = null
                    _state.update { it.copy(appliances = emptyList(), programs = emptyMap(), link = HcLink.IDLE, linkError = null, retryAt = null) }
                    if (b != null) {
                        loadCatalog()
                        run(b)
                    }
                }
        }
    }

    // --- Verbindung ---------------------------------------------------------------------------

    private suspend fun run(b: HcBackend) {
        var failures = 0
        while (currentContextActive()) {
            _state.update { it.copy(link = HcLink.CONNECTING, requestsToday = budget.usedToday) }
            val opened = clock.instant()
            var error: HcException? = null
            try {
                coroutineScope {
                    b.events().collect { e ->
                        if (e.type == HcCloudBackend.START) {
                            _state.update { it.copy(link = HcLink.LIVE, linkError = null, retryAt = null) }
                            launch { refreshAll(b) }
                        } else {
                            apply(b, e, this)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: HcException) {
                error = e
            } catch (e: Exception) {
                error = HcException(HcFailure.NETWORK, e.message)
            }
            if (error?.failure == HcFailure.AUTH && auth.needsReauth) {
                log.warning(LogCategory.SYSTEM, strings.get(R.string.hc_log_reauth))
                _state.update { it.copy(link = HcLink.NEEDS_SIGN_IN, needsReauth = true, linkError = error) }
                awaitCancellation()
            }
            // Ein Strom, der zwei Minuten hielt, gilt als stabil – Zähler zurück
            failures = if (Duration.between(opened, clock.instant()).seconds > 120) 1 else failures + 1
            val wait = error?.retryAfterSeconds ?: backoffSeconds(failures)
            if (failures == 1) log.warning(LogCategory.SYSTEM, strings.get(R.string.hc_log_stream_lost, error?.detail ?: error?.failure?.name ?: "–"))
            _state.update { it.copy(link = HcLink.RETRYING, linkError = error, retryAt = clock.instant().plusSeconds(wait), requestsToday = budget.usedToday) }
            delay(wait * 1000)
        }
    }

    private suspend fun currentContextActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    /** Jeder Verbindungsaufbau kostet eine Anfrage – bei Dauerstörung bis 15 min warten. */
    private fun backoffSeconds(failures: Int): Long = when {
        failures <= 1 -> 2
        failures <= 5 -> minOf(60L, 1L shl failures)
        else -> 15 * 60
    }

    private suspend fun refreshAll(b: HcBackend) {
        val now = clock.instant()
        if (lastDiscovery == null || Duration.between(lastDiscovery, now).toMinutes() >= 60 || _state.value.appliances.isEmpty()) {
            discover(b)
        }
        _state.value.appliances.filter { it.connected }.forEach { a ->
            runCatching { refreshAppliance(b, a.haId) }
        }
        // Programmnamen und erlaubte Betriebszustände für Karten und „Einschalten vor Start“ – 24 h zwischengespeichert
        _state.value.appliances.filter { it.connected }.forEach { a -> runCatching { ensurePrograms(a.haId) } }
        _state.update { it.copy(requestsToday = budget.usedToday) }
    }

    private suspend fun discover(b: HcBackend) {
        val list = runCatching { b.appliances() }.getOrElse { return }
        lastDiscovery = clock.instant()
        _state.update { st ->
            st.copy(appliances = list.map { fresh -> st.appliance(fresh.haId)?.copy(name = fresh.name, brand = fresh.brand, type = fresh.type, connected = fresh.connected) ?: fresh }
                .sortedWith(compareBy({ it.type.ordinal }, { it.name })))
        }
    }

    private suspend fun refreshAppliance(b: HcBackend, haId: String) {
        val status = b.status(haId)
        val settingsItems = b.settings(haId)
        val active = if (OperationState.from(status.firstOrNull { it.key == HcKeys.OPERATION_STATE }?.value?.contentOrNull).active) b.activeProgram(haId) else null
        val now = clock.instant()
        updateAppliance(haId) { a ->
            var values = a.values
            (status + settingsItems + active?.second.orEmpty()).forEach { values = if (it.value != null) values + (it.key to it.value) else values - it.key }
            val op = OperationState.from(values[HcKeys.OPERATION_STATE]?.contentOrNull)
            a.copy(
                values = values,
                activeProgram = active?.first ?: if (op.active) a.activeProgram else null,
                remainingAt = now,
                finishedAt = if (op == OperationState.FINISHED) a.finishedAt ?: now else null,
            )
        }
    }

    private var lastUnknownDiscovery: Instant? = null

    private fun apply(b: HcBackend, e: HcEvent, scope: CoroutineScope) {
        when (e.type) {
            "KEEP-ALIVE" -> Unit
            "PAIRED", "DEPAIRED" -> scope.launch { discover(b) }
            "CONNECTED" -> {
                val haId = e.haId ?: return
                updateAppliance(haId) { it.copy(connected = true) }
                scope.launch { runCatching { refreshAppliance(b, haId) } }
            }
            "DISCONNECTED" -> e.haId?.let { id -> updateAppliance(id) { it.copy(connected = false) } }
            "STATUS", "NOTIFY" -> e.haId?.let { id -> if (known(b, id, scope)) merge(id, e.items) }
            "EVENT" -> e.haId?.let { id -> if (known(b, id, scope)) events(id, e.items) }
        }
    }

    /** Ereignis eines unbekannten Geräts → Geräteliste neu laden (höchstens alle 5 min). */
    private fun known(b: HcBackend, haId: String, scope: CoroutineScope): Boolean {
        if (_state.value.appliance(haId) != null) return true
        val now = clock.instant()
        if (lastUnknownDiscovery == null || Duration.between(lastUnknownDiscovery, now).toMinutes() >= 5) {
            lastUnknownDiscovery = now
            scope.launch { discover(b) }
        }
        return false
    }

    internal fun merge(haId: String, items: List<HcItem>) {
        val now = clock.instant()
        updateAppliance(haId) { a ->
            var values = a.values
            var active = a.activeProgram
            var selected = a.selectedProgram
            var remainingAt = a.remainingAt
            var finishedAt = a.finishedAt
            items.forEach { item ->
                when (item.key) {
                    HcKeys.ACTIVE_PROGRAM -> active = item.value?.contentOrNull?.takeIf { it.isNotBlank() }
                    HcKeys.SELECTED_PROGRAM -> selected = item.value?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> values = if (item.value != null) values + (item.key to item.value) else values - item.key
                }
                if (item.key == HcKeys.REMAINING || item.key == HcKeys.START_IN_RELATIVE) remainingAt = now
                if (item.key == HcKeys.OPERATION_STATE) {
                    when (OperationState.from(item.value?.contentOrNull)) {
                        OperationState.FINISHED -> if (a.operation != OperationState.FINISHED) finishedAt = now
                        OperationState.RUN, OperationState.DELAYED_START -> finishedAt = null
                        // Ofen aus: gemeldete Innentemperatur ist ab jetzt veraltet (keine Updates im Standby)
                        OperationState.READY, OperationState.INACTIVE -> { finishedAt = null; active = null; values = values - HcKeys.OVEN_CAVITY }
                        else -> Unit
                    }
                }
            }
            a.copy(values = values, activeProgram = active, selectedProgram = selected, remainingAt = remainingAt, finishedAt = finishedAt)
        }
    }

    internal fun events(haId: String, items: List<HcItem>) {
        val now = clock.instant()
        val before = _state.value.appliance(haId) ?: return
        updateAppliance(haId) { a ->
            var ev = a.events
            items.forEach { item ->
                val present = enumTail(item.value?.contentOrNull) in setOf("Present", "Confirmed")
                ev = if (present) ev + (item.key to (ev[item.key] ?: now)) else ev - item.key
            }
            a.copy(events = ev)
        }
        // Nur neu eingetretene Hinweise melden (manche Geräte wiederholen sie beim Verbinden)
        items.filter { enumTail(it.value?.contentOrNull) == "Present" && it.key !in before.events }.forEach { item ->
            // Global (Meldung + Protokoll) nur, was zeitkritisch ist; Nachfüllhinweise zeigt die Seite „Haushalt“ –
            // sie kämen sonst nach jedem Neustart erneut
            val global = item.key == HcKeys.PROGRAM_FINISHED || item.key == HcKeys.OVEN_PREHEAT_DONE
            val text = when (item.key) {
                HcKeys.PROGRAM_FINISHED -> strings.get(R.string.hc_notice_finished, before.name)
                HcKeys.OVEN_PREHEAT_DONE -> strings.get(R.string.hc_notice_preheated, before.name)
                in HcKeys.WARNING_EVENTS -> strings.get(R.string.hc_notice_warning, before.name, strings.get(warningLabel(item.key)))
                else -> null
            } ?: return@forEach
            _notices.tryEmit(HcNotice(haId, text, now, global))
            if (global) log.info(LogCategory.DEVICE, text, before.name)
        }
    }

    private fun updateAppliance(haId: String, transform: (HcAppliance) -> HcAppliance) {
        _state.update { st -> st.copy(appliances = st.appliances.map { if (it.haId == haId) transform(it) else it }) }
    }

    // --- Programme ----------------------------------------------------------------------------

    @Serializable
    private data class CatalogEntry(val fetchedAtMs: Long, val programs: List<HcProgram>, val powerValues: List<String>? = null, val commands: Set<String>? = null)

    @Serializable
    private data class Catalog(val language: String, val mode: HcMode, val entries: Map<String, CatalogEntry>)

    private var catalog: Catalog? = null
    private val loadingPrograms = mutableSetOf<String>()

    private fun loadCatalog() {
        val c = settings.catalogCache?.let { runCatching { HcJson.json.decodeFromString(Catalog.serializer(), it) }.getOrNull() }
        // Demo: Programme kommen aus dem App-Code – nie aus dem Zwischenspeicher, sonst fehlen Neuerungen bis zu 24 h
        catalog = c?.takeIf { it.language == language() && it.mode == settings.mode.value && it.mode != HcMode.DEMO }
        _state.update { st -> st.copy(programs = catalog?.entries?.mapValues { it.value.programs }.orEmpty()) }
    }

    private fun storeEntry(haId: String, entry: CatalogEntry) {
        val c = (catalog?.takeIf { it.language == language() && it.mode == settings.mode.value } ?: Catalog(language(), settings.mode.value, emptyMap()))
        catalog = c.copy(entries = c.entries + (haId to entry))
        settings.catalogCache = HcJson.json.encodeToString(Catalog.serializer(), catalog!!)
        _state.update { st ->
            st.copy(
                programs = st.programs + (haId to entry.programs),
                appliances = st.appliances.map { if (it.haId == haId) it.copy(powerValues = entry.powerValues, commands = entry.commands) else it },
            )
        }
    }

    /** Programmliste eines Geräts (zwischengespeichert 24 h). */
    suspend fun ensurePrograms(haId: String) {
        val b = backend ?: return
        val cached = catalog?.entries?.get(haId)
        // Eine Liste mit höchstens einem Programm stammt meist aus einem laufenden Programm – neu laden, sobald möglich
        val collapsed = cached != null && cached.programs.size <= 1 && _state.value.appliance(haId)?.operation?.active != true
        if (cached != null && clock.millis() - cached.fetchedAtMs < CATALOG_TTL_MS && cached.programs.isNotEmpty() && !collapsed) {
            updateAppliance(haId) { it.copy(powerValues = cached.powerValues, commands = cached.commands) }
            return
        }
        if (!loadingPrograms.add(haId)) return
        try {
            val available = b.availablePrograms(haId)
            // Vollständige Liste (wie das Indigo-Plugin): „available“ zeigt oft nur das Programm am Wahlschalter
            val all = runCatching { b.allPrograms(haId) }.getOrDefault(emptyList())
            val names = available.associateBy { it.key }
            val programs = if (all.isEmpty()) available
            else all.map { p -> (names[p.key] ?: p).copy(available = p.key in names) } + available.filter { a -> all.none { it.key == a.key } }
            // Leere oder geschrumpfte Liste nicht für 24 h festhalten – vorhandene behalten und bald neu versuchen
            val keep = programs.ifEmpty { cached?.programs.orEmpty() }
            val power = runCatching { b.powerValues(haId) }.getOrNull() ?: cached?.powerValues
            val commands = runCatching { b.commands(haId) }.getOrNull() ?: cached?.commands
            storeEntry(haId, CatalogEntry(if (programs.size <= 1) 0 else clock.millis(), merge(keep, cached?.programs), power, commands))
        } catch (e: HcException) {
            if (cached != null) _state.update { it.copy(programs = it.programs + (haId to cached.programs)) }
        } finally {
            loadingPrograms.remove(haId)
            _state.update { it.copy(requestsToday = budget.usedToday) }
        }
    }

    /** Bereits geladene Programmdetails beim Neuladen der Liste behalten. */
    private fun merge(fresh: List<HcProgram>, old: List<HcProgram>?): List<HcProgram> =
        fresh.map { p -> old?.firstOrNull { it.key == p.key && it.detailed && it.options.isNotEmpty() }?.copy(available = p.available, name = p.name) ?: p }

    /** Optionen eines Programms (Grenzen, erlaubte Werte) – einmalig je Programm. */
    suspend fun ensureDetail(haId: String, key: String): HcProgram? {
        val b = backend ?: return null
        _state.value.programs[haId]?.firstOrNull { it.key == key && it.detailed }?.let { return it }
        val tag = "$haId/$key"
        if (!loadingPrograms.add(tag)) return null
        return try {
            val known = _state.value.programs[haId]?.firstOrNull { it.key == key }
            // Gerade nicht verfügbare Programme haben keine Details – dann ohne Optionen starten (das Gerät entscheidet)
            val detail = runCatching { b.programDetail(haId, key) }.getOrNull()
                ?: known?.copy(detailed = true, options = emptyList(), available = false) ?: return null
            storeDetail(haId, detail)
            detail
        } finally {
            loadingPrograms.remove(tag)
            _state.update { it.copy(requestsToday = budget.usedToday) }
        }
    }

    private fun storeDetail(haId: String, detail: HcProgram) {
        val key = detail.key
        val entry = catalog?.entries?.get(haId)
        val list = (_state.value.programs[haId].orEmpty()).let { l ->
            if (l.any { it.key == key }) l.map { if (it.key == key) detail.copy(name = it.name.ifBlank { detail.name }, available = it.available && detail.options.isNotEmpty()) else it } else l + detail
        }
        storeEntry(haId, CatalogEntry(entry?.fetchedAtMs ?: clock.millis(), list, entry?.powerValues, entry?.commands))
    }

    // --- Befehle ------------------------------------------------------------------------------

    private fun require(haId: String): Pair<HcBackend, HcAppliance> {
        val b = backend ?: throw HcException(HcFailure.OFFLINE)
        val a = _state.value.appliance(haId) ?: throw HcException(HcFailure.OFFLINE)
        if (!a.connected) throw HcException(HcFailure.OFFLINE)
        return b to a
    }

    /**
     * Programm starten. Vorher lokal geprüft (Fernstart erlaubt, nicht am Gerät bedient, bereit) – eine Anfrage, die
     * das Gerät ohnehin ablehnt, kostet sonst Kontingent und zählt zu den Fehlern, nach denen BSH sperrt.
     * Ein ausgeschaltetes Gerät wird zuerst eingeschaltet (wie im Indigo-Plugin „Power on first“).
     */
    suspend fun start(haId: String, programKey: String, options: Map<String, JsonPrimitive>) = command(haId) { b, first ->
        var a = first
        if (a.operation.active) throw HcException(HcFailure.NOT_READY)
        if (a.type == HcType.OVEN && a.doorOpen == true) throw HcException(HcFailure.DOOR_OPEN)
        if (!a.poweredOn || a.operation == OperationState.INACTIVE) a = powerOn(b, a)
        if (!a.remoteStartAllowed) throw HcException(HcFailure.REMOTE_START_OFF)
        if (a.localControl) throw HcException(HcFailure.LOCAL_CONTROL)
        if (a.operation != OperationState.READY && a.operation != OperationState.UNKNOWN) throw HcException(HcFailure.NOT_READY)
        val program = _state.value.programs[haId]?.firstOrNull { it.key == programKey }
        val units = options.keys.mapNotNull { k -> (program?.option(k)?.unit ?: DEFAULT_UNITS[k])?.let { k to it } }.toMap()
        val sent = if (a.type == HcType.COFFEE && options.isNotEmpty()) startViaSelection(b, a.name, haId, programKey, options, units)
        else options.also { b.start(haId, programKey, options, units) }
        updateAppliance(haId) { it.copy(activeProgram = programKey, values = it.values + sent - HcKeys.OVEN_CAVITY, finishedAt = null) }
        log.info(LogCategory.DEVICE, strings.get(R.string.hc_log_started, startSummary(haId, programKey, sent)), a.name)
        scope?.launch { verifyOptions(b, a.name, haId, programKey, sent) }
    }

    /**
     * Kaffeevollautomaten (Siemens EQ.9) melden die echten Grenzen erst nach der Auswahl des Getränks (Heißwasser
     * 100–400 ml in 20er-Schritten statt pauschal 20–400 in 5er-Schritten); ungültige Werte verwerfen sie still oder mit
     * „NotAvailableByList“. Deshalb: auswählen, Grenzen lesen, Werte einpassen, am ausgewählten Programm setzen und mit
     * denselben Optionen starten. Abgelehnte Optionen stehen mit Begründung im Protokoll.
     */
    private suspend fun startViaSelection(
        b: HcBackend, name: String, haId: String, programKey: String,
        requested: Map<String, JsonPrimitive>, units: Map<String, String>,
    ): Map<String, JsonPrimitive> {
        b.select(haId, programKey)
        val refined = runCatching { b.programDetail(haId, programKey) }.getOrNull()?.takeIf { it.options.isNotEmpty() }
            ?.copy(refined = true)?.also { storeDetail(haId, it) }
        // Auf die echten Grenzen bringen; Optionen, die das Programm nach der Auswahl nicht kennt, weglassen
        val options = if (refined == null) requested else requested.mapNotNull { (k, v) ->
            val def = refined.option(k) ?: return@mapNotNull null
            val fitted = def.fit(v) ?: return@mapNotNull null
            if (fitted.doubleOrNull != null && fitted.doubleOrNull != v.doubleOrNull) {
                log.info(LogCategory.DEVICE, strings.get(R.string.hc_log_option_adjusted, def.name, listOfNotNull(fitted.content, def.unit).joinToString(" ")), name)
            }
            k to fitted
        }.toMap()
        options.forEach { (k, v) ->
            try {
                b.setSelectedOption(haId, k, v, refined?.option(k)?.unit ?: units[k])
            } catch (e: HcException) {
                val label = _state.value.programs[haId]?.firstOrNull { it.key == programKey }?.option(k)?.name ?: prettify(enumTail(k) ?: k)
                log.warning(LogCategory.DEVICE, strings.get(R.string.hc_log_option_rejected, label, e.detail ?: e.failure.name), name)
            }
        }
        // Start mit denselben (gültigen) Optionen samt Einheit – ohne Optionen lehnt der EQ.9 den Start ab
        // („BSH.Common.Error.400.BadRequest“, gemessen 11.11.2026). Kurz nach dem Setzen ist er manchmal noch beschäftigt:
        // dann einmal nach kurzer Pause wiederholen.
        val startUnits = options.keys.mapNotNull { k -> (refined?.option(k)?.unit ?: units[k])?.let { k to it } }.toMap()
        try {
            b.start(haId, programKey, options, startUnits)
        } catch (e: HcException) {
            if (e.failure != HcFailure.REJECTED) throw e
            delay(START_RETRY_MS)
            b.start(haId, programKey, options, startUnits)
        }
        return options
    }

    /**
     * Echte Grenzen eines Programms laden: am Gerät auswählen (zeigt das Getränk im Display, startet nichts) und
     * danach die Optionen lesen. Einmal je Programm, nur wenn das Gerät bereit ist; danach 24 h zwischengespeichert.
     */
    suspend fun refineProgram(haId: String, key: String) {
        val b = backend ?: return
        val a = _state.value.appliance(haId) ?: return
        if (!a.connected || a.operation != OperationState.READY) return
        if (_state.value.programs[haId]?.firstOrNull { it.key == key }?.refined == true) return
        val tag = "refine/$haId/$key"
        if (!loadingPrograms.add(tag)) return
        try {
            b.select(haId, key)
            b.programDetail(haId, key)?.takeIf { it.options.isNotEmpty() }?.let { storeDetail(haId, it.copy(refined = true)) }
        } catch (_: HcException) {
            // Gerät gerade nicht bereit o. Ä. – beim Start wird ohnehin noch einmal ausgewählt
        } finally {
            loadingPrograms.remove(tag)
            _state.update { it.copy(requestsToday = budget.usedToday) }
        }
    }

    /** „Heißwasser · Füllmenge 20 ml“ fürs Protokoll. */
    private fun startSummary(haId: String, programKey: String, options: Map<String, JsonPrimitive>): String {
        val program = _state.value.programs[haId]?.firstOrNull { it.key == programKey }
        val parts = options.mapNotNull { (k, v) ->
            val def = program?.option(k) ?: return@mapNotNull null
            if (def.isBoolean) return@mapNotNull null
            val shown = v.contentOrNull?.let { if (def.isEnum) def.label(it) else listOfNotNull(it, def.unit?.takeIf { u -> u != "seconds" }).joinToString(" ") }
            shown?.let { "${def.name} $it" }
        }
        return (listOf(programName(haId, programKey)) + parts).joinToString(" · ")
    }

    /**
     * Prüft kurz nach dem Start, ob das Gerät die gewählten Optionen übernommen hat (eine Anfrage). Manche Geräte
     * verwerfen Werte stillschweigend – das soll im Protokoll sichtbar werden statt unbemerkt zu bleiben.
     */
    private suspend fun verifyOptions(b: HcBackend, name: String, haId: String, programKey: String, sent: Map<String, JsonPrimitive>) {
        if (sent.isEmpty()) return
        delay(VERIFY_DELAY_MS)
        val (key, reported) = runCatching { b.activeProgram(haId) }.getOrNull() ?: return
        if (key != programKey) return
        val program = _state.value.programs[haId]?.firstOrNull { it.key == programKey }
        sent.forEach { (k, v) ->
            val got = reported.firstOrNull { it.key == k }?.value ?: return@forEach
            val same = v.contentOrNull == got.contentOrNull || (v.doubleOrNull != null && v.doubleOrNull == got.doubleOrNull)
            if (!same) {
                val def = program?.option(k)
                fun show(p: JsonPrimitive) = p.contentOrNull?.let { if (def?.isEnum == true) def.label(it) else listOfNotNull(it, def?.unit).joinToString(" ") } ?: "–"
                log.warning(LogCategory.DEVICE, strings.get(R.string.hc_log_option_ignored, def?.name ?: prettify(enumTail(k) ?: k), show(v), show(got)), name)
            }
        }
    }

    suspend fun stop(haId: String) = command(haId) { b, a ->
        if (!a.operation.active) throw HcException(HcFailure.NOT_RUNNING)
        b.stop(haId)
        log.info(LogCategory.DEVICE, strings.get(R.string.hc_log_stopped), a.name)
    }

    suspend fun pause(haId: String, pause: Boolean) = command(haId) { b, a ->
        if (!a.operation.active) throw HcException(HcFailure.NOT_RUNNING)
        b.command(haId, if (pause) HcKeys.PAUSE else HcKeys.RESUME)
    }

    suspend fun setPower(haId: String, on: Boolean) = command(haId) { b, a ->
        val allowed = a.powerValues
        val value = if (on) "BSH.Common.EnumType.PowerState.On"
        else allowed?.firstOrNull { enumTail(it) == "Off" } ?: allowed?.firstOrNull { enumTail(it) == "Standby" } ?: "BSH.Common.EnumType.PowerState.Off"
        if (allowed != null && value !in allowed) throw HcException(HcFailure.POWER_READ_ONLY)
        b.setSetting(haId, HcKeys.POWER_STATE, JsonPrimitive(value))
        updateAppliance(haId) { it.copy(values = it.values + (HcKeys.POWER_STATE to JsonPrimitive(value))) }
    }

    private suspend fun powerOn(b: HcBackend, a: HcAppliance): HcAppliance {
        val allowed = a.powerValues
        if (allowed != null && allowed.none { enumTail(it) == "On" }) throw HcException(HcFailure.POWER_READ_ONLY)
        b.setSetting(a.haId, HcKeys.POWER_STATE, JsonPrimitive("BSH.Common.EnumType.PowerState.On"))
        return withTimeoutOrNull(READY_TIMEOUT_MS) {
            state.first { it.appliance(a.haId)?.operation == OperationState.READY }.appliance(a.haId)
        } ?: throw HcException(HcFailure.POWER_ON_TIMEOUT)
    }

    private suspend fun command(haId: String, block: suspend (HcBackend, HcAppliance) -> Unit): Result<Unit> = try {
        val (b, a) = require(haId)
        block(b, a)
        Result.success(Unit)
    } catch (c: CancellationException) {
        throw c
    } catch (e: HcException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(HcException(HcFailure.NETWORK, e.message))
    } finally {
        _state.update { it.copy(requestsToday = budget.usedToday) }
    }

    fun programName(haId: String, key: String?): String {
        if (key == null) return ""
        return _state.value.programs[haId]?.firstOrNull { it.key == key }?.name ?: prettify(enumTail(key) ?: key)
    }

    // --- Lieblingsgetränke --------------------------------------------------------------------

    val drinks: StateFlow<List<SavedDrink>> get() = settings.drinks

    fun saveDrink(drink: SavedDrink) {
        settings.setDrinks((settings.drinks.value.filterNot { it.label == drink.label } + drink).takeLast(MAX_DRINKS))
    }

    fun removeDrink(drink: SavedDrink) = settings.setDrinks(settings.drinks.value - drink)

    // --- Einstellungen und Anmeldung ----------------------------------------------------------

    /** Am Panel eingetragene Client-ID (leer: eingebaute verwenden). */
    val clientId: StateFlow<String> get() = settings.clientId
    val hasBuiltInClient: Boolean get() = builtInClientId.isNotBlank()
    private val effectiveClientId: String get() = settings.clientId.value.ifBlank { builtInClientId }
    fun canSignIn(ownId: String = settings.clientId.value): Boolean = ownId.isNotBlank() || hasBuiltInClient
    val hasClientSecret: Boolean get() = auth.hasOwnSecret

    fun setMode(mode: HcMode) {
        if (mode == settings.mode.value) return
        settings.setMode(mode)
        log.info(LogCategory.SYSTEM, strings.get(R.string.hc_log_mode, strings.get(mode.labelRes)))
    }

    fun setClient(id: String, secret: String?) {
        settings.setClientId(id)
        if (secret != null) auth.setClientSecret(secret)
    }

    fun beginSignIn() {
        val scope = scope ?: return
        signInJob?.cancel()
        _state.update { it.copy(signInError = null, signIn = null) }
        signInJob = scope.launch {
            val clientId = effectiveClientId
            if (clientId.isBlank()) { _state.update { it.copy(signInError = strings.get(R.string.hc_signin_no_client)) }; return@launch }
            val code = try {
                auth.startDeviceFlow(clientId)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val detail = (e as? HcException)?.detail ?: e.message ?: "–"
                // Häufigster Einrichtungsfehler: Anwendung nicht als „Device Flow“ angelegt oder noch nicht aktiv
                val text = if ("unauthorized_client" in detail || "invalid_client" in detail) strings.get(R.string.hc_signin_wrong_flow) else detail
                _state.update { it.copy(signInError = text) }
                return@launch
            }
            _state.update { it.copy(signIn = code) }
            var interval = code.intervalSeconds.coerceAtLeast(1)
            while (isActive && clock.instant().isBefore(code.expiresAt)) {
                delay(interval * 1000)
                when (val r = auth.poll(clientId, code)) {
                    HcPoll.Pending -> Unit
                    HcPoll.SlowDown -> interval += 5
                    HcPoll.Success -> {
                        _state.update { it.copy(signIn = null, signInError = null) }
                        settings.setMode(HcMode.CLOUD)
                        authVersion.value++
                        log.info(LogCategory.SYSTEM, strings.get(R.string.hc_log_signed_in))
                        return@launch
                    }
                    HcPoll.Denied -> { _state.update { it.copy(signIn = null, signInError = strings.get(R.string.hc_signin_denied)) }; return@launch }
                    HcPoll.Expired -> break
                    is HcPoll.Error -> { _state.update { it.copy(signIn = null, signInError = r.detail) }; return@launch }
                }
            }
            _state.update { it.copy(signIn = null, signInError = strings.get(R.string.hc_signin_expired)) }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.update { it.copy(signIn = null) }
    }

    fun signOut() {
        cancelSignIn()
        auth.signOut()
        settings.catalogCache = null
        catalog = null
        authVersion.value++
        log.info(LogCategory.SYSTEM, strings.get(R.string.hc_log_signed_out))
    }

    /** Werksreset: Zugangsdaten, Einstellungen und Zwischenspeicher löschen. */
    fun clearAll() {
        cancelSignIn()
        auth.clearAll()
        settings.clearAll()
        catalog = null
        demo = null
        authVersion.value++
    }

    companion object {
        private const val CATALOG_TTL_MS = 24 * 3600 * 1000L
        private const val READY_TIMEOUT_MS = 25_000L
        private const val VERIFY_DELAY_MS = 3_000L
        private const val START_RETRY_MS = 1_500L
        /** Einheiten bekannter Optionen – falls das Gerät sie nicht meldet (Mikrowelle im Standby: keine Optionsdetails). */
        private val DEFAULT_UNITS = mapOf(
            HcKeys.DURATION to "seconds", HcKeys.START_IN_RELATIVE to "seconds", HcKeys.FINISH_IN_RELATIVE to "seconds",
            HcKeys.OVEN_SETPOINT to "°C", HcKeys.COFFEE_FILL to "ml",
        )
        private const val MAX_DRINKS = 4

        fun warningLabel(key: String): Int = when (key) {
            HcKeys.COFFEE_BEANS_EMPTY -> R.string.hc_warn_beans
            HcKeys.COFFEE_WATER_EMPTY -> R.string.hc_warn_water
            HcKeys.COFFEE_TRAY_FULL -> R.string.hc_warn_tray
            HcKeys.DISH_RINSE_AID -> R.string.hc_warn_rinse_aid
            HcKeys.DISH_SALT -> R.string.hc_warn_salt
            HcKeys.FRIDGE_DOOR_ALARM, HcKeys.FREEZER_DOOR_ALARM -> R.string.hc_warn_door
            else -> R.string.hc_warn_generic
        }
    }
}

val HcMode.labelRes: Int
    get() = when (this) {
        HcMode.OFF -> R.string.hc_mode_off
        HcMode.DEMO -> R.string.hc_mode_demo
        HcMode.CLOUD -> R.string.hc_mode_cloud
    }

val HcFailure.labelRes: Int
    get() = when (this) {
        HcFailure.OFFLINE -> R.string.hc_fail_offline
        HcFailure.REMOTE_START_OFF -> R.string.hc_fail_remote_start
        HcFailure.LOCAL_CONTROL -> R.string.hc_fail_local
        HcFailure.NOT_READY -> R.string.hc_fail_not_ready
        HcFailure.POWER_READ_ONLY -> R.string.hc_fail_power_ro
        HcFailure.POWER_ON_TIMEOUT -> R.string.hc_fail_power_timeout
        HcFailure.NOT_RUNNING -> R.string.hc_fail_not_running
        HcFailure.RATE_LIMITED -> R.string.hc_fail_rate
        HcFailure.AUTH -> R.string.hc_fail_auth
        HcFailure.BUDGET -> R.string.hc_fail_budget
        HcFailure.DOOR_OPEN -> R.string.hc_fail_door
        HcFailure.REJECTED -> R.string.hc_fail_rejected
        HcFailure.NETWORK -> R.string.hc_fail_network
    }
