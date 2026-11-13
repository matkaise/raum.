package app.raum.homeconnect

import app.raum.diagnostics.EventLog
import app.raum.i18n.XmlStrings
import app.raum.security.InMemorySecretStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HomeConnectTest {

    // --- SSE ------------------------------------------------------------------------------------

    @Test
    fun `sse parser builds events and ignores comments and keep-alives`() {
        val p = SseParser()
        val lines = listOf(
            ": comment", "event: KEEP-ALIVE", "", "event: STATUS", "id: SIEMENS-1",
            """data: {"items":[{"key":"BSH.Common.Status.OperationState","value":"BSH.Common.EnumType.OperationState.Run","timestamp":1}]}""", "",
        )
        val events = lines.mapNotNull(p::feed)
        assertEquals(listOf("KEEP-ALIVE", "STATUS"), events.map { it.type })
        val status = events[1]
        assertEquals("SIEMENS-1", status.haId)
        assertEquals(HcKeys.OPERATION_STATE, status.items.single().key)
        assertEquals(OperationState.RUN, OperationState.from(status.items.single().value?.content))
    }

    @Test
    fun `sse parser takes haId from body for CONNECTED and rejects garbage`() {
        val p = SseParser()
        assertNull(p.feed("event: CONNECTED"))
        assertNull(p.feed("""data: {"haId":"BOSCH-2"}"""))
        assertEquals("BOSCH-2", p.feed("")?.haId)
        try {
            p.feed("1f4")
            fail("Fremdzeile muss den Strom neu starten")
        } catch (_: SseParser.SseCorrupt) { }
    }

    // --- JSON -----------------------------------------------------------------------------------

    @Test
    fun `parses appliances, program details and errors`() {
        val list = HcJson.appliances("""{"data":{"homeappliances":[{"name":"Kaffee","brand":"Siemens","type":"CoffeeMaker","connected":true,"haId":"S-1"},{"type":"Hood","haId":"S-2","connected":false}]}}""")
        assertEquals(2, list.size)
        assertEquals(HcType.COFFEE, list[0].type)
        assertEquals(HcType.OTHER, list[1].type)
        assertFalse(list[1].connected)

        val detail = HcJson.programDetail(
            """{"data":{"key":"ConsumerProducts.CoffeeMaker.Program.Beverage.Espresso","name":"Espresso","options":[
              {"key":"ConsumerProducts.CoffeeMaker.Option.FillQuantity","type":"Int","unit":"ml","constraints":{"min":35,"max":60,"stepsize":5,"default":40}},
              {"key":"ConsumerProducts.CoffeeMaker.Option.BeanAmount","type":"ConsumerProducts.CoffeeMaker.EnumType.BeanAmount",
               "constraints":{"allowedvalues":["ConsumerProducts.CoffeeMaker.EnumType.BeanAmount.Mild","ConsumerProducts.CoffeeMaker.EnumType.BeanAmount.Strong"],"displayvalues":["Mild","Stark"]}}]}}""",
        )!!
        assertTrue(detail.detailed)
        val fill = detail.option(HcKeys.COFFEE_FILL)!!
        assertEquals(35.0, fill.min!!, 0.0); assertEquals(60.0, fill.max!!, 0.0); assertEquals(40, fill.default!!.content.toInt())
        val beans = detail.option(HcKeys.COFFEE_BEANS)!!
        assertEquals("Stark", beans.label("ConsumerProducts.CoffeeMaker.EnumType.BeanAmount.Strong"))

        assertEquals("SDK.Error.RemoteStartNotActivated" to "nope", HcJson.error("""{"error":{"key":"SDK.Error.RemoteStartNotActivated","description":"nope"}}"""))
        assertEquals("authorization_pending" to null, HcJson.error("""{"error":"authorization_pending"}"""))
        assertEquals(HcFailure.REMOTE_START_OFF, httpFailure(403, """{"error":{"key":"SDK.Error.RemoteStartNotActivated"}}""", null).failure)
        assertEquals(60L, httpFailure(429, null, null).retryAfterSeconds)
    }

    @Test
    fun `program body carries key and options`() {
        val body = HcJson.programBody("A.B.Program.X", mapOf("A.B.Option.Y" to JsonPrimitive(40)))
        assertEquals("""{"data":{"key":"A.B.Program.X","options":[{"key":"A.B.Option.Y","value":40}]}}""", body)
        // Wie openHAB: Menge mit Einheit – ohne verwirft der Kaffeevollautomat sie
        val withUnit = HcJson.programBody("P", mapOf(HcKeys.COFFEE_FILL to JsonPrimitive(50), "B" to JsonPrimitive(false)), mapOf(HcKeys.COFFEE_FILL to "ml"))
        assertEquals("""{"data":{"key":"P","options":[{"key":"ConsumerProducts.CoffeeMaker.Option.FillQuantity","value":50,"unit":"ml"},{"key":"B","value":false}]}}""", withUnit)
    }

    @Test
    fun `prettify falls back to readable names`() {
        assertEquals("Eco 50", prettify("Eco50"))
        assertEquals("Top Bottom Heating", prettify("TopBottomHeating"))
    }

    // --- Zustand --------------------------------------------------------------------------------

    @Test
    fun `remaining time counts down while running and freezes when paused`() {
        val t0 = Instant.parse("2026-11-10T20:00:00Z")
        val a = HcAppliance(
            "W", "Waschmaschine", "Siemens", HcType.WASHER, true,
            values = mapOf(HcKeys.OPERATION_STATE to JsonPrimitive("BSH.Common.EnumType.OperationState.Run"), HcKeys.REMAINING to JsonPrimitive(5040)),
            remainingAt = t0,
        )
        assertEquals(5040 - 120L, a.remainingSeconds(t0.plusSeconds(120)))
        val paused = a.copy(values = a.values + (HcKeys.OPERATION_STATE to JsonPrimitive("BSH.Common.EnumType.OperationState.Pause")))
        assertEquals(5040L, paused.remainingSeconds(t0.plusSeconds(120)))
    }

    // --- Kontingent -----------------------------------------------------------------------------

    private class MemBudget : HcBudgetStore {
        var day = ""; var count = 0
        override fun load() = day to count
        override fun save(day: String, count: Int) { this.day = day; this.count = count }
    }

    @Test
    fun `budget spares reads before the daily limit and resets next day`() {
        val store = MemBudget()
        var clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC)
        val budget = HcBudget(store, clock)
        store.save("2026-11-10", HcBudget.READ_LIMIT)
        assertEquals(HcFailure.BUDGET, runCatching { budget.acquire(essential = false) }.exceptionOrNull().let { (it as HcException).failure })
        budget.acquire(essential = true) // Nutzerbefehle dürfen noch
        assertEquals(HcBudget.READ_LIMIT + 1, budget.usedToday)
        clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC)
        val tomorrow = HcBudget(store, clock)
        assertEquals(0, tomorrow.usedToday)
        tomorrow.block(30)
        assertEquals(HcFailure.RATE_LIMITED, (runCatching { tomorrow.acquire(true) }.exceptionOrNull() as HcException).failure)
    }

    // --- Anmeldung ------------------------------------------------------------------------------

    private class FakeHttp : HcHttp {
        val replies = ArrayDeque<HcResponse>()
        val bodies = mutableListOf<String?>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): HcResponse {
            bodies += body
            return replies.removeFirst()
        }
        override fun openStream(url: String, headers: Map<String, String>, readTimeoutMs: Int): HcLineStream = error("not used")
    }

    @Test
    fun `device flow stores tokens and refresh rotates them`() = runTest {
        val http = FakeHttp()
        val secrets = InMemorySecretStore()
        var now = Instant.parse("2026-11-10T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val auth = HcAuth(secrets, http, clock = clock)
        http.replies += HcResponse(200, """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://x/verify","interval":5,"expires_in":300}""", null)
        val code = auth.startDeviceFlow("client")
        assertEquals("ABCD-1234", code.userCode)

        http.replies += HcResponse(400, """{"error":"authorization_pending"}""", null)
        assertEquals(HcPoll.Pending, auth.poll("client", code))
        http.replies += HcResponse(200, """{"access_token":"a1","refresh_token":"r1","expires_in":86400}""", null)
        assertEquals(HcPoll.Success, auth.poll("client", code))
        assertEquals("client", auth.signedInClient)
        assertEquals("a1", auth.accessToken())

        // Neu geladen aus dem (verschlüsselten) Speicher
        val again = HcAuth(secrets, http, clock = clock)
        assertEquals("a1", again.accessToken())

        // 23,5 h später: innerhalb des Erneuerungsfensters
        now = now.plusSeconds(23 * 3600 + 1800)
        http.replies += HcResponse(200, """{"access_token":"a2","refresh_token":"r2","expires_in":86400}""", null)
        assertEquals("a2", again.accessToken())
        assertTrue(http.bodies.last()!!.contains("refresh_token=r1"))

        // Widerrufen → neu anmelden
        now = now.plusSeconds(24 * 3600)
        http.replies += HcResponse(400, """{"error":"invalid_grant"}""", null)
        assertEquals(HcFailure.AUTH, (runCatching { again.accessToken() }.exceptionOrNull() as HcException).failure)
        assertTrue(again.needsReauth)
    }

    // --- Dienst ---------------------------------------------------------------------------------

    /** Angemeldet: gespeicherte Tokens, damit der Dienst das Backend benutzt. */
    private fun signedInStore() = InMemorySecretStore().apply {
        put("homeconnect_tokens", """{"clientId":"c","access":"a","refresh":"r","expiresAtMs":${Long.MAX_VALUE}}""".toByteArray())
    }

    private class MemSettings(mode: HcMode) : HcSettings {
        override val mode = MutableStateFlow(mode)
        override fun setMode(mode: HcMode) { this.mode.value = mode }
        override val clientId = MutableStateFlow("")
        override fun setClientId(id: String) { clientId.value = id }
        override val drinks: StateFlow<List<SavedDrink>> = MutableStateFlow(emptyList())
        override fun setDrinks(list: List<SavedDrink>) {}
        override var catalogCache: String? = null
        override fun clearAll() {}
        var day = ""; var count = 0
        override fun load() = day to count
        override fun save(day: String, count: Int) { this.day = day; this.count = count }
    }

    /** Ein Gerät, Ereignisse von außen steuerbar. */
    private class FakeBackend(var appliance: HcAppliance, val statusItems: List<HcItem>) : HcBackend {
        val events = Channel<HcEvent>(Channel.UNLIMITED)
        val started = mutableListOf<String>()
        val settingsWritten = mutableListOf<Pair<String, JsonPrimitive>>()
        var onSetting: (() -> Unit)? = null
        override suspend fun appliances() = listOf(appliance)
        override suspend fun status(haId: String) = statusItems
        override suspend fun settings(haId: String) = emptyList<HcItem>()
        var active: Pair<String, List<HcItem>>? = null
        override suspend fun activeProgram(haId: String): Pair<String, List<HcItem>>? = active
        override suspend fun availablePrograms(haId: String) = listOf(HcProgram("P.Eco50", "Eco 50°"))
        override suspend fun allPrograms(haId: String) = listOf(HcProgram("P.Eco50", "Eco"), HcProgram("P.Quick45", "Quick 45°"))
        var detailOptions: List<HcOptionDef> = emptyList()
        override suspend fun programDetail(haId: String, key: String) = HcProgram(key, "Eco 50°", detailOptions, detailed = true)
        override suspend fun powerValues(haId: String) = listOf("BSH.Common.EnumType.PowerState.On", "BSH.Common.EnumType.PowerState.Off")
        override suspend fun commands(haId: String) = emptySet<String>()
        val startedUnits = mutableListOf<Map<String, String>>()
        override suspend fun start(haId: String, key: String, options: Map<String, JsonPrimitive>, units: Map<String, String>) { started += key; startedUnits += units; calls += "start $key ${options.size}" }
        val calls = mutableListOf<String>()
        override suspend fun select(haId: String, key: String) { calls += "select $key" }
        override suspend fun setSelectedOption(haId: String, key: String, value: JsonPrimitive, unit: String?) { calls += "option $key=$value ${unit.orEmpty()}".trim() }
        override suspend fun stop(haId: String) {}
        override suspend fun command(haId: String, key: String) {}
        override suspend fun setSetting(haId: String, key: String, value: JsonPrimitive) { settingsWritten += key to value; onSetting?.invoke() }
        override fun events(): Flow<HcEvent> = flow {
            emit(HcEvent(HcCloudBackend.START, null))
            emitAll(events.receiveAsFlow())
        }
    }

    private fun op(s: String) = HcItem(HcKeys.OPERATION_STATE, JsonPrimitive("BSH.Common.EnumType.OperationState.$s"))

    private fun service(backend: FakeBackend, log: EventLog = EventLog()) = HomeConnectService(
        settings = MemSettings(HcMode.CLOUD), auth = HcAuth(signedInStore(), FakeHttp()), budget = HcBudget(MemBudget()),
        cloud = backend, language = { "de-DE" }, log = log, strings = XmlStrings("de"),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start is refused locally without remote start`() = runTest {
        val b = FakeBackend(HcAppliance("D", "Geschirrspüler", "Siemens", HcType.DISHWASHER, true),
            listOf(op("Ready"), HcItem(HcKeys.REMOTE_START, JsonPrimitive(false))))
        val s = service(b)
        s.start(backgroundScope)
        runCurrent()
        assertEquals(OperationState.READY, s.state.value.appliance("D")!!.operation)
        val r = s.start("D", "P.Eco50", emptyMap())
        assertEquals(HcFailure.REMOTE_START_OFF, (r.exceptionOrNull() as HcException).failure)
        assertTrue("kein Aufruf, der das Kontingent belastet", b.started.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start powers an appliance on first and waits until ready`() = runTest {
        val b = FakeBackend(HcAppliance("W", "Waschmaschine", "Siemens", HcType.WASHER, true),
            listOf(op("Inactive"), HcItem(HcKeys.POWER_STATE, JsonPrimitive("BSH.Common.EnumType.PowerState.Off")), HcItem(HcKeys.REMOTE_START, JsonPrimitive(true))))
        b.onSetting = { b.events.trySend(HcEvent("STATUS", "W", listOf(op("Ready")))) }
        val s = service(b)
        s.start(backgroundScope)
        runCurrent()
        s.ensurePrograms("W")
        val job = launch { assertTrue(s.start("W", "P.Eco50", emptyMap()).isSuccess) }
        runCurrent()
        job.join()
        assertEquals(HcKeys.POWER_STATE, b.settingsWritten.single().first)
        assertEquals(listOf("P.Eco50"), b.started)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `finished event notifies once and state tracks the program`() = runTest {
        val received = mutableListOf<String>()
        val b = FakeBackend(HcAppliance("T", "Trockner", "Bosch", HcType.DRYER, true), listOf(op("Run")))
        val s = service(b)
        backgroundScope.launch { s.notices.collect { if (it.global) received += it.text } }
        s.start(backgroundScope)
        runCurrent()
        b.events.trySend(HcEvent("NOTIFY", "T", listOf(HcItem(HcKeys.ACTIVE_PROGRAM, JsonPrimitive("LaundryCare.Dryer.Program.Mix")), HcItem(HcKeys.REMAINING, JsonPrimitive(600)))))
        runCurrent()
        assertEquals("LaundryCare.Dryer.Program.Mix", s.state.value.appliance("T")!!.activeProgram)
        assertNotNull(s.state.value.appliance("T")!!.remainingAt)

        val present = HcItem(HcKeys.PROGRAM_FINISHED, JsonPrimitive("BSH.Common.EnumType.EventPresentState.Present"))
        b.events.trySend(HcEvent("STATUS", "T", listOf(op("Finished"))))
        b.events.trySend(HcEvent("EVENT", "T", listOf(present)))
        b.events.trySend(HcEvent("EVENT", "T", listOf(present))) // Wiederholung beim Verbinden
        runCurrent()
        val t = s.state.value.appliance("T")!!
        assertEquals(OperationState.FINISHED, t.operation)
        assertNotNull(t.finishedAt)
        assertEquals(listOf("Trockner ist fertig"), received)

        b.events.trySend(HcEvent("STATUS", "T", listOf(op("Ready"))))
        runCurrent()
        assertNull(s.state.value.appliance("T")!!.activeProgram)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `program list is the full list with availability marked`() = runTest {
        val b = FakeBackend(HcAppliance("W", "Waschmaschine", "Siemens", HcType.WASHER, true), listOf(op("Ready")))
        val s = service(b)
        s.start(backgroundScope)
        runCurrent()
        s.ensurePrograms("W")
        val programs = s.state.value.programs["W"]!!
        assertEquals(listOf("P.Eco50", "P.Quick45"), programs.map { it.key })
        assertEquals("Eco 50°", programs[0].name) // Name aus „available“ (lokalisiert) bevorzugt
        assertTrue(programs[0].available)
        assertFalse(programs[1].available)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ignored options are reported in the event log`() = runTest {
        val log = EventLog()
        val b = FakeBackend(HcAppliance("K", "Kaffeevollautomat", "Siemens", HcType.COFFEE, true),
            listOf(op("Ready"), HcItem(HcKeys.REMOTE_START, JsonPrimitive(true))))
        val s = service(b, log)
        s.start(backgroundScope)
        runCurrent()
        b.active = "P.Eco50" to listOf(HcItem(HcKeys.COFFEE_FILL, JsonPrimitive(140)))
        assertTrue(s.start("K", "P.Eco50", mapOf(HcKeys.COFFEE_FILL to JsonPrimitive(20))).isSuccess)
        // Kaffee: auswählen, Option am ausgewählten Programm setzen, mit denselben Optionen starten
        assertEquals(listOf("select P.Eco50", "option ${HcKeys.COFFEE_FILL}=20 ml", "start P.Eco50 1"), b.calls)
        advanceTimeBy(5_000)
        runCurrent()
        val warning = log.recent.value.firstOrNull { it.message.contains("nicht übernommen") }
        assertNotNull("Abweichung muss im Protokoll stehen: ${log.recent.value.map { it.message }}", warning)
        assertTrue(warning!!.message.contains("20") && warning.message.contains("140"))
    }

    @Test
    fun `fit snaps numbers to the appliance grid and drops unknown enum values`() {
        val fill = HcOptionDef(HcKeys.COFFEE_FILL, "Menge", "Int", "ml", 100.0, 400.0, 20.0)
        assertEquals(100, fill.fit(JsonPrimitive(60))!!.content.toInt())
        assertEquals(140, fill.fit(JsonPrimitive(145))!!.content.toInt())
        assertEquals(400, fill.fit(JsonPrimitive(999))!!.content.toInt())
        val temp = HcOptionDef(HcKeys.HOT_WATER_TEMP, "Temperatur", "X", allowed = listOf("A.90C", "A.Max"))
        assertNull(temp.fit(JsonPrimitive("B.88C")))
        assertEquals("A.Max", temp.fit(JsonPrimitive("A.Max"))!!.content)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `coffee start uses the limits reported after selection`() = runTest {
        val b = FakeBackend(HcAppliance("K", "Kaffeevollautomat", "Siemens", HcType.COFFEE, true),
            listOf(op("Ready"), HcItem(HcKeys.REMOTE_START, JsonPrimitive(true))))
        // Heißwasser am echten EQ.9: erst nach der Auswahl 100–400 ml in 20er-Schritten
        b.detailOptions = listOf(HcOptionDef(HcKeys.COFFEE_FILL, "Menge", "Int", "ml", 100.0, 400.0, 20.0))
        val s = service(b)
        s.start(backgroundScope)
        runCurrent()
        assertTrue(s.start("K", "P.HotWater", mapOf(HcKeys.COFFEE_FILL to JsonPrimitive(60), HcKeys.COFFEE_TEMP to JsonPrimitive("X"))).isSuccess)
        assertEquals(listOf("select P.HotWater", "option ${HcKeys.COFFEE_FILL}=100 ml", "start P.HotWater 1"), b.calls)
        assertEquals(mapOf(HcKeys.COFFEE_FILL to "ml"), b.startedUnits.last())
        assertTrue(s.state.value.programs["K"]!!.first { it.key == "P.HotWater" }.refined)
    }
}
