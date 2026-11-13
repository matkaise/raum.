package app.raum.grohe

import app.raum.diagnostics.EventLog
import app.raum.homeconnect.HcHttp
import app.raum.homeconnect.HcLineStream
import app.raum.homeconnect.HcResponse
import app.raum.i18n.XmlStrings
import app.raum.security.InMemorySecretStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GroheTest {

    private val dashboard = """
        {"locations":[{"id":11,"rooms":[{"id":22,"appliances":[
          {"appliance_id":"sense-1","type":101,"name":"Sense"},
          {"appliance_id":"blue-1","type":104,"name":"Küche Blue",
           "status":[{"type":"connection","value":1}],
           "state":{"co2_empty":false,"filter_empty":false,"cleaning_needed":true},
           "data_latest":{"measurement":{"remaining_filter":75,"remaining_filter_liters":1500,"remaining_co2":8,"remaining_co2_liters":4.5,"timestamp":"2026-11-11T10:00:00Z"}}}
        ]}]}]}
    """.trimIndent()

    @Test
    fun `dashboard yields only Grohe Blue appliances with levels and warnings`() {
        val list = GroheJson.dashboard(dashboard)
        assertEquals(1, list.size)
        val d = list.single()
        assertEquals("grohe:blue-1", d.id)
        assertEquals("11", d.locationId); assertEquals("22", d.roomId)
        assertEquals(75.0, d.filterPercent!!, 0.0)
        assertEquals(4.5, d.co2Liters!!, 0.0)
        assertTrue(d.cleaningNeeded)
        assertTrue("CO₂ unter 10 % gilt als knapp", d.co2Low)
        assertFalse(d.filterLow)
    }

    @Test
    fun `tap command matches the Grohe app`() {
        val body = GroheJson.commandBody("blue-1", WaterType.SPARKLING.code, 250)
        assertTrue(body.contains("\"tap_type\":3"))
        assertTrue(body.contains("\"tap_amount\":250"))
        assertTrue(body.contains("\"appliance_id\":\"blue-1\""))
        assertTrue(body.contains("\"get_current_measurement\":false"))
        assertTrue(validAmount(50)); assertTrue(validAmount(2000))
        assertFalse(validAmount(0)); assertFalse(validAmount(275)); assertFalse(validAmount(2050))
    }

    private class FakeHttp : HcHttp {
        val replies = ArrayDeque<HcResponse>()
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val bodies = mutableListOf<String?>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): HcResponse {
            requests += Triple(method, url, headers); bodies += body
            return replies.removeFirst()
        }
        override fun openStream(url: String, headers: Map<String, String>, readTimeoutMs: Int): HcLineStream = error("unused")
    }

    @Test
    fun `login redirect is redeemed with session cookies and tokens refresh`() = runTest {
        val http = FakeHttp()
        var now = Instant.parse("2026-11-11T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val auth = GroheAuth(InMemorySecretStore(), http, clock)
        http.replies += HcResponse(200, """{"access_token":"a1","expires_in":3600,"refresh_token":"r1","refresh_expires_in":15552000}""", null)
        auth.completeLogin("ondus://idp2-apigw.cloud.grohe.com/v3/iot/oidc/token?code=x", "AUTH_SESSION=1")
        assertEquals("https://idp2-apigw.cloud.grohe.com/v3/iot/oidc/token?code=x", http.requests.last().second)
        assertEquals("AUTH_SESSION=1", http.requests.last().third["Cookie"])
        assertEquals("a1", auth.accessToken())

        now = now.plusSeconds(3600)
        http.replies += HcResponse(200, """{"access_token":"a2","expires_in":3600,"refresh_token":"r2"}""", null)
        assertEquals("a2", auth.accessToken())
        assertTrue(http.bodies.last()!!.contains("\"refresh_token\":\"r1\""))

        now = now.plusSeconds(3600)
        http.replies += HcResponse(401, "{}", null)
        assertEquals(GroheException.Kind.AUTH, (runCatching { auth.accessToken() }.exceptionOrNull() as GroheException).kind)
        assertTrue(auth.needsReauth)
    }

    private class MemSettings : GroheSettings {
        override val mode = MutableStateFlow(GroheMode.CLOUD)
        override fun setMode(mode: GroheMode) { this.mode.value = mode }
        override fun clearAll() {}
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispense validates the amount and sends the tap command`() = runTest {
        val demo = object : GroheBackend {
            val poured = mutableListOf<Pair<WaterType, Int>>()
            override suspend fun devices() = GroheJson.dashboard(dashboard)
            override suspend fun dispense(device: GroheDevice, type: WaterType, ml: Int) { poured += type to ml }
            override suspend fun requestMeasurement(device: GroheDevice) {}
        }
        // Angemeldet: gespeicherte Tokens, damit der Dienst das Backend benutzt
        val store = InMemorySecretStore().apply { put("grohe_tokens", """{"access":"a","refresh":"r","expiresAtMs":${Long.MAX_VALUE}}""".toByteArray()) }
        val s = GroheService(MemSettings(), GroheAuth(store, FakeHttp()), demo, EventLog(), XmlStrings("de"))
        s.start(backgroundScope)
        runCurrent()
        assertEquals(1, s.state.value.devices.size)
        assertTrue(s.dispense("grohe:blue-1", WaterType.STILL, 275).isFailure)
        assertTrue(s.dispense("grohe:blue-1", WaterType.SPARKLING, 250).isSuccess)
        assertEquals(listOf(WaterType.SPARKLING to 250), demo.poured)
        assertTrue("Füll-Animation läuft", "grohe:blue-1" in s.state.value.pouring)
    }
}
