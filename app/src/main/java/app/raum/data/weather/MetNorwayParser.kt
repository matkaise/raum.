package app.raum.data.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Liest die Antwort von MET Norway „locationforecast/2.0/compact“.
 * https://api.met.no/weatherapi/locationforecast/2.0/documentation
 */
object MetNorwayParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): Forecast {
        val doc = json.decodeFromString(Doc.serializer(), body)
        val hours = doc.properties.timeseries.map { t ->
            val i = t.data.instant.details
            // Stündliche Werte gibt es nur für die ersten ~2,5 Tage, danach nur 6-Stunden-Blöcke
            val next = t.data.next1h ?: t.data.next6h
            val perHour = if (t.data.next1h != null) 1.0 else 6.0
            ForecastHour(
                time = Instant.parse(t.time),
                temperature = i.airTemperature,
                cloudCover = i.cloudAreaFraction,
                humidity = i.relativeHumidity,
                windSpeed = i.windSpeed,
                windFrom = i.windFromDirection,
                symbol = next?.summary?.symbolCode,
                // auf 0,01 mm runden – 0,6 / 6 wäre sonst 0,0999… und fiele unter die Regen-Schwelle
                precipitation = next?.details?.precipitationAmount?.div(perHour)?.let { kotlin.math.round(it * 100) / 100 },
                periodHours = perHour.toInt(),
                symbol6 = t.data.next6h?.summary?.symbolCode,
            )
        }
        return Forecast(Instant.parse(doc.properties.meta.updatedAt), hours)
    }

    @Serializable private class Doc(val properties: Properties)
    @Serializable private class Properties(val meta: Meta, val timeseries: List<TimeStep>)
    @Serializable private class Meta(@SerialName("updated_at") val updatedAt: String)
    @Serializable private class TimeStep(val time: String, val data: StepData)
    @Serializable private class StepData(
        val instant: Instant_,
        @SerialName("next_1_hours") val next1h: Period? = null,
        @SerialName("next_6_hours") val next6h: Period? = null,
    )
    @Serializable private class Instant_(val details: InstantDetails)
    @Serializable private class InstantDetails(
        @SerialName("air_temperature") val airTemperature: Double? = null,
        @SerialName("cloud_area_fraction") val cloudAreaFraction: Double? = null,
        @SerialName("relative_humidity") val relativeHumidity: Double? = null,
        @SerialName("wind_speed") val windSpeed: Double? = null,
        @SerialName("wind_from_direction") val windFromDirection: Double? = null,
    )
    @Serializable private class Period(val summary: Summary? = null, val details: PeriodDetails? = null)
    @Serializable private class Summary(@SerialName("symbol_code") val symbolCode: String? = null)
    @Serializable private class PeriodDetails(@SerialName("precipitation_amount") val precipitationAmount: Double? = null)
}
