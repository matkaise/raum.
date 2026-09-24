package app.raum.data

import app.raum.automation.triggers.GeoLocation
import app.raum.data.weather.HttpResponse
import app.raum.data.weather.Intensity
import app.raum.data.weather.MetNorwayParser
import app.raum.data.weather.PrecipType
import app.raum.data.weather.SkyCover
import app.raum.data.weather.WeatherCondition
import app.raum.data.weather.WeatherError
import app.raum.data.weather.WeatherHttp
import app.raum.data.weather.WeatherNow
import app.raum.data.weather.WeatherPrefs
import app.raum.data.weather.WeatherSchedule
import app.raum.data.weather.WeatherService
import app.raum.diagnostics.EventLog
import app.raum.i18n.XmlStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Wetter von MET Norway: Parser, Wetterlage, Ausblick, Abrufplan, Dienst (mit simuliertem Server). */
class WeatherTest {
    private val body = javaClass.getResource("/met_compact.json")!!.readText()
    private val zone = ZoneId.of("Europe/Zurich")
    private fun at(iso: String) = Instant.parse(iso)

    // --- Parser und Ableitung ---------------------------------------------------------------

    @Test fun `Antwort wird gelesen, 6-Stunden-Menge auf Stunden umgerechnet`() {
        val f = MetNorwayParser.parse(body)
        assertEquals(at("2026-09-23T09:41:12Z"), f.updatedAt)
        assertEquals(8, f.hours.size)
        assertEquals(14.2, f.hours[0].temperature!!, 0.001)
        assertEquals("rain", f.hours[3].symbol)
        // letzter Eintrag hat nur next_6_hours: 0,6 mm / 6 h
        assertEquals(0.1, f.hours.last().precipitation!!, 0.001)
        assertEquals("fair_night", f.hours.last().symbol)
    }

    @Test fun `Symbolcodes werden richtig eingeordnet`() {
        assertEquals(WeatherCondition(SkyCover.CLEAR), WeatherCondition.fromSymbol("clearsky_night"))
        assertEquals(SkyCover.PARTLY_CLOUDY, WeatherCondition.fromSymbol("partlycloudy_day").sky)
        WeatherCondition.fromSymbol("lightrainshowers_day").let {
            assertEquals(PrecipType.RAIN, it.precip); assertEquals(Intensity.LIGHT, it.intensity); assertTrue(it.showers)
        }
        WeatherCondition.fromSymbol("heavysnowandthunder").let {
            assertEquals(PrecipType.SNOW, it.precip); assertEquals(Intensity.HEAVY, it.intensity); assertTrue(it.thunder)
        }
        // Tippfehler aus der offiziellen Liste
        assertEquals(PrecipType.SLEET, WeatherCondition.fromSymbol("lightssleetshowersandthunder_day").precip)
        assertEquals(SkyCover.FOG, WeatherCondition.fromSymbol("fog").sky)
    }

    @Test fun `aktuelle Lage, Tageswerte und Regen ab`() {
        val w = WeatherNow.from(MetNorwayParser.parse(body), at("2026-09-23T10:20:00Z"), zone)!!
        assertEquals(14.2, w.temperature!!, 0.001)
        assertEquals(SkyCover.CLOUDY, w.condition.sky)
        assertEquals(0.85, w.cloudCover, 0.001)
        assertEquals(11.0, w.todayMin!!, 0.001)
        assertEquals(14.8, w.todayMax!!, 0.001)
        val o = w.outlook!!
        assertFalse(o.raining)
        assertEquals(PrecipType.RAIN, o.type)
        assertEquals(at("2026-09-23T12:00:00Z"), o.startsAt)
    }

    @Test fun `bei Regen wird das Ende angekündigt`() {
        val w = WeatherNow.from(MetNorwayParser.parse(body), at("2026-09-23T12:30:00Z"), zone)!!
        assertEquals(PrecipType.RAIN, w.condition.precip)
        assertTrue(w.outlook!!.raining)
        assertEquals(at("2026-09-23T15:00:00Z"), w.outlook!!.endsAt)
    }

    @Test fun `trocken für 12 Stunden bedeutet kein Hinweis`() {
        val w = WeatherNow.from(MetNorwayParser.parse(body), at("2026-09-23T15:10:00Z"), zone)!!
        // 18:00 hat 0,1 mm/h – liegt im Fenster
        assertEquals(at("2026-09-23T18:00:00Z"), w.outlook?.startsAt)
        val dry = MetNorwayParser.parse(body.replace("\"precipitation_amount\": 0.6", "\"precipitation_amount\": 0.0"))
        assertNull(WeatherNow.from(dry, at("2026-09-23T15:10:00Z"), zone)!!.outlook)
    }

    @Test fun `Tagesvorhersage fasst Stunden und 6-Stunden-Blöcke zusammen`() {
        val days = app.raum.data.weather.ForecastDays.from(MetNorwayParser.parse(body), at("2026-09-23T10:20:00Z"), zone)
        val today = days.single()
        assertEquals(java.time.LocalDate.of(2026, 9, 23), today.date)
        assertEquals(11.0, today.min!!, 0.001)
        assertEquals(14.8, today.max!!, 0.001)
        // 0,3 + 1,2 + 0,2 stündlich + 0,1 mm/h × 6 h
        assertEquals(2.3, today.precipitation, 0.001)
        // 6-Stunden-Symbol um 12 Uhr Ortszeit (10:00 UTC)
        assertEquals("lightrain", today.symbol)
        assertEquals(8, today.hours.size)
    }

    // --- Abrufplan (Nutzungsbedingungen) --------------------------------------------------

    @Test fun `Abrufplan beachtet Expires, Mindestabstand und Rückoff`() {
        val t0 = at("2026-09-23T10:00:00Z")
        assertEquals(t0, WeatherSchedule.next(t0, null, null, 0, null))
        // Expires vor Mindestabstand → 30 min
        assertEquals(t0.plus(Duration.ofMinutes(30)), WeatherSchedule.next(t0, t0, t0.plus(Duration.ofMinutes(10)), 0, null))
        // Expires später → Expires
        assertEquals(t0.plus(Duration.ofMinutes(55)), WeatherSchedule.next(t0, t0, t0.plus(Duration.ofMinutes(55)), 0, null))
        // höchstens 2 h
        assertEquals(t0.plus(Duration.ofHours(2)), WeatherSchedule.next(t0, t0, t0.plus(Duration.ofHours(9)), 0, null))
        // Fehler: 1, 2, 5 … min nach dem letzten Versuch
        assertEquals(t0.plus(Duration.ofMinutes(1)), WeatherSchedule.next(t0, t0, null, 1, t0))
        assertEquals(t0.plus(Duration.ofMinutes(5)), WeatherSchedule.next(t0, t0, null, 3, t0))
        assertEquals(t0.plus(Duration.ofMinutes(60)), WeatherSchedule.next(t0, t0, null, 99, t0))
    }

    @Test fun `Standort wird auf etwa 1 km gerundet`() {
        assertEquals(47.37 to 8.55, WeatherSchedule.round(GeoLocation(47.3712, 8.5478)))
        assertEquals(-33.87 to 151.21, WeatherSchedule.round(GeoLocation(-33.8688, 151.2093)))
    }

    // --- Dienst -----------------------------------------------------------------------------

    private class Prefs : WeatherPrefs {
        override val weatherEnabled = MutableStateFlow(true)
        override val location = MutableStateFlow<GeoLocation?>(GeoLocation(47.3712, 8.5478))
        override fun setWeatherEnabled(enabled: Boolean) { weatherEnabled.value = enabled }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private class FakeHttp(var respond: (Map<String, String>) -> HttpResponse) : WeatherHttp {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url to headers
            return respond(headers)
        }
    }

    private fun service(file: File, http: FakeHttp, clock: MutableClock, prefs: Prefs = Prefs()) =
        WeatherService(prefs, file, http, "raum-panel/test", EventLog(clock = clock), XmlStrings("de"), clock)

    @Test fun `Abruf mit Kennung, bedingter Folgeabruf und Zwischenspeicher`() = runTest {
        val file = File.createTempFile("weather", ".json").apply { delete() }
        val clock = MutableClock(at("2026-09-23T10:00:00Z"))
        val http = FakeHttp { HttpResponse(200, body, "Wed, 23 Sep 2026 09:41:12 GMT", at("2026-09-23T10:30:00Z")) }
        val s = service(file, http, clock)

        assertTrue(s.refresh())
        val (url, headers) = http.requests.single()
        assertTrue(url, url.endsWith("compact?lat=47.37&lon=8.55"))
        assertEquals("raum-panel/test", headers["User-Agent"])
        assertNull(headers["If-Modified-Since"])
        assertNotNull(s.state.value.forecast)
        assertTrue(file.exists())

        // innerhalb von 10 min kein weiterer Abruf („Jetzt aktualisieren“ gebremst)
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        s.refresh()
        assertEquals(1, http.requests.size)

        // später: nur „geändert seit …“, Antwort 304 behält die Daten
        clock.now = at("2026-09-23T10:40:00Z")
        http.respond = { HttpResponse(304, null, null, at("2026-09-23T11:10:00Z")) }
        assertTrue(s.refresh())
        assertEquals("Wed, 23 Sep 2026 09:41:12 GMT", http.requests.last().second["If-Modified-Since"])
        assertNotNull(s.state.value.forecast)
        assertEquals(clock.now, s.state.value.fetchedAt)

        // Neustart: Zwischenspeicher sofort sichtbar, kein Abruf solange nicht fällig
        val http2 = FakeHttp { error("darf nicht abrufen") }
        val restarted = service(file, http2, clock)
        restarted.start(backgroundScope)
        runCurrent()
        assertNotNull(restarted.state.value.forecast)
        assertTrue(http2.requests.isEmpty())

        // Abschalten löscht die gespeicherte Vorhersage
        restarted.setEnabled(false)
        assertFalse(file.exists())
    }

    @Test fun `Fehler behalten die letzten Daten und werden gemeldet`() = runTest {
        val file = File.createTempFile("weather", ".json").apply { delete() }
        val clock = MutableClock(at("2026-09-23T10:00:00Z"))
        val http = FakeHttp { HttpResponse(200, body, null, null) }
        val s = service(file, http, clock)
        s.refresh()

        clock.now = clock.now.plus(Duration.ofMinutes(45))
        http.respond = { throw java.io.IOException("offline") }
        assertFalse(s.refresh())
        assertEquals(WeatherError.OFFLINE, s.state.value.error)
        assertNotNull(s.state.value.forecast)

        clock.now = clock.now.plus(Duration.ofMinutes(15))
        http.respond = { HttpResponse(403, null, null, null) }
        s.refresh()
        assertEquals(WeatherError.BLOCKED, s.state.value.error)

        clock.now = clock.now.plus(Duration.ofMinutes(15))
        http.respond = { HttpResponse(200, body, null, null) }
        assertTrue(s.refresh())
        assertNull(s.state.value.error)
    }

    @Test fun `ohne Standort oder ausgeschaltet wird nichts abgerufen`() = runTest {
        val file = File.createTempFile("weather", ".json").apply { delete() }
        val prefs = Prefs().apply { weatherEnabled.value = false }
        val http = FakeHttp { error("kein Abruf erwartet") }
        val s = service(file, http, MutableClock(at("2026-09-23T10:00:00Z")), prefs)
        assertFalse(s.refresh())
        prefs.weatherEnabled.value = true
        prefs.location.value = null
        assertFalse(s.refresh())
        s.start(backgroundScope)
        runCurrent()
        assertTrue(http.requests.isEmpty())
    }
}
