package app.raum.data.geo

import app.raum.data.weather.HttpResponse
import app.raum.data.weather.WeatherHttp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class PlaceSearchTest {

    private val body = """
        {"results":[
          {"id":2659070,"name":"Romanshorn","latitude":47.56586,"longitude":9.37869,"country_code":"CH","admin1":"Thurgau","timezone":"Europe/Zurich","country":"Schweiz"},
          {"id":1,"name":"Ohne Zone","latitude":1.0,"longitude":2.0,"country_code":"XX"},
          {"id":2,"name":"Rumänien","latitude":46.0,"longitude":25.0,"country_code":"RO","timezone":"Europe/Bucharest","feature_code":"PCLI"},
          {"id":3,"name":"Roman","latitude":46.9,"longitude":26.9,"country_code":"RO","admin1":"Neamț","timezone":"Europe/Bucharest","feature_code":"PPL"}
        ],"generationtime_ms":0.5}
    """.trimIndent()

    /** Kleine Ortsliste im Format von assets/cities.dat. */
    private fun index(): CityIndex {
        val tsv = "Rorschach\t\tCH\tSt. Gallen\t47.478\t9.49\tEurope/Zurich\n"
        val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(tsv.toByteArray()) } }.toByteArray()
        return CityIndex { ByteArrayInputStream(bytes) }
    }

    @Test
    fun `parses open-meteo results and drops entries without time zone`() {
        val cities = OpenMeteoGeocoding.parse(body)
        assertEquals(listOf("Romanshorn", "Roman"), cities.map { it.name })
        val c = cities.first()
        assertEquals("Romanshorn", c.name)
        assertEquals("CH", c.countryCode)
        assertEquals("Thurgau", c.region)
        assertEquals("Europe/Zurich", c.timeZone)
        assertEquals(47.56586, c.location.latitude, 1e-9)
    }

    @Test
    fun `online results win, sending only the query and language`() = runTest {
        val urls = mutableListOf<String>()
        val http = WeatherHttp { url, _ -> urls += url; HttpResponse(200, body, null, null) }
        val r = PlaceSearch(index(), http, "test").search("Roman", "Europe/Zurich", "de")
        assertTrue(r.online)
        // eigene Zeitzone zuerst
        assertEquals(listOf("Romanshorn", "Roman"), r.cities.map { it.name })
        assertTrue(urls.single().contains("name=Roman") && urls.single().contains("language=de"))
    }

    @Test
    fun `places in the own time zone come first`() = runTest {
        val http = WeatherHttp { _, _ -> HttpResponse(200, body, null, null) }
        val r = PlaceSearch(index(), http, "test").search("Roman", "Europe/Bucharest", "de")
        assertEquals("Roman", r.cities.first().name)
    }

    @Test
    fun `nearby places from the local list join online results`() = runTest {
        val http = WeatherHttp { _, _ -> HttpResponse(200, body, null, null) }
        // „Rorschach“ kennt nur die eingebaute Liste – liegt in der eigenen Zeitzone, also vorn dabei
        val r = PlaceSearch(index(), http, "test").search("Ro", "Europe/Zurich", "de")
        assertTrue(r.online)
        assertEquals(listOf("Romanshorn", "Rorschach", "Roman"), r.cities.map { it.name })
    }

    @Test
    fun `falls back to the local list when offline`() = runTest {
        val http = WeatherHttp { _, _ -> throw java.io.IOException("offline") }
        val r = PlaceSearch(index(), http, "test").search("Rorsch", "Europe/Zurich", "de")
        assertFalse(r.online)
        assertEquals("Rorschach", r.cities.single().name)
    }
}
