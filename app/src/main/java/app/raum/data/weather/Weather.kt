package app.raum.data.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bewölkung/Himmel ohne Niederschlag. */
enum class SkyCover { CLEAR, FAIR, PARTLY_CLOUDY, CLOUDY, FOG }
enum class PrecipType { NONE, RAIN, SLEET, SNOW }
enum class Intensity { LIGHT, MODERATE, HEAVY }

/** Wetterlage, abgeleitet aus einem MET-Norway-Symbolcode (z. B. „lightrainshowers_day“). */
data class WeatherCondition(
    val sky: SkyCover,
    val precip: PrecipType = PrecipType.NONE,
    val intensity: Intensity = Intensity.MODERATE,
    val showers: Boolean = false,
    val thunder: Boolean = false,
) {
    companion object {
        /** Siehe https://api.met.no/weatherapi/weathericon/2.0/documentation – Tippfehler „lights…“ gibt es dort wirklich. */
        fun fromSymbol(code: String): WeatherCondition {
            val c = code.substringBefore('_').lowercase()
            val intensity = when {
                c.startsWith("light") -> Intensity.LIGHT
                c.startsWith("heavy") -> Intensity.HEAVY
                else -> Intensity.MODERATE
            }
            val precip = when {
                "sleet" in c -> PrecipType.SLEET
                "snow" in c -> PrecipType.SNOW
                "rain" in c -> PrecipType.RAIN
                else -> PrecipType.NONE
            }
            val showers = "showers" in c
            val sky = when {
                c == "clearsky" -> SkyCover.CLEAR
                c == "fair" -> SkyCover.FAIR
                c == "partlycloudy" -> SkyCover.PARTLY_CLOUDY
                c == "fog" -> SkyCover.FOG
                showers -> SkyCover.PARTLY_CLOUDY // Schauer: Wolkenlücken
                else -> SkyCover.CLOUDY
            }
            return WeatherCondition(sky, precip, intensity, showers, thunder = "thunder" in c)
        }
    }
}

/** Eine Stunde der Vorhersage (Werte aus „instant“ und „next_1_hours“). */
data class ForecastHour(
    val time: Instant,
    val temperature: Double?,
    val cloudCover: Double?,
    val humidity: Double?,
    val windSpeed: Double?,
    val windFrom: Double?,
    val symbol: String?,
    /** mm pro Stunde im folgenden Zeitraum */
    val precipitation: Double?,
    /** Länge des Zeitraums: 1 h in den ersten ~2,5 Tagen, danach 6 h */
    val periodHours: Int = 1,
    /** Symbol der folgenden 6 Stunden (für die Tagesübersicht) */
    val symbol6: String? = null,
)

/** Ein Tag der Vorhersage. */
data class DayForecast(
    val date: LocalDate,
    val min: Double?,
    val max: Double?,
    /** repräsentatives Symbol (Tagesmitte) */
    val symbol: String?,
    /** Summe in mm */
    val precipitation: Double,
    /** Einträge des Tages (stündlich bzw. 6-stündlich) */
    val hours: List<ForecastHour>,
)

object ForecastDays {
    /** Die nächsten [days] Tage ab heute (Ortszeit). Tage ohne Werte entfallen. */
    fun from(forecast: Forecast, now: Instant, zone: ZoneId, days: Int = 7): List<DayForecast> {
        val today = now.atZone(zone).toLocalDate()
        val byDay = forecast.hours.sortedBy { it.time }.groupBy { it.time.atZone(zone).toLocalDate() }
        return (0 until days).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            val all = byDay[date].orEmpty()
            // heute: nur ab der laufenden Stunde
            val entries = if (offset == 0) all.filter { !it.time.plus(Duration.ofHours(it.periodHours.toLong())).isBefore(now) } else all
            if (entries.isEmpty()) return@mapNotNull null
            val temps = all.mapNotNull { it.temperature }
            DayForecast(
                date = date,
                min = temps.minOrNull(),
                max = temps.maxOrNull(),
                symbol = daySymbol(entries, zone),
                precipitation = entries.sumOf { (it.precipitation ?: 0.0) * it.periodHours },
                hours = entries,
            )
        }
    }

    /** 6-Stunden-Symbol um die Tagesmitte (12 Uhr), sonst das häufigste Stundensymbol zwischen 8 und 20 Uhr. */
    private fun daySymbol(entries: List<ForecastHour>, zone: ZoneId): String? {
        entries.filter { it.symbol6 != null }
            .minByOrNull { kotlin.math.abs(it.time.atZone(zone).hour - 12) }
            ?.let { return it.symbol6 }
        val day = entries.filter { it.time.atZone(zone).hour in 8..20 }.ifEmpty { entries }
        return day.mapNotNull { it.symbol?.substringBefore('_') }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

data class Forecast(val updatedAt: Instant, val hours: List<ForecastHour>)

/** Niederschlag in den nächsten Stunden – für den Hinweis „Regen ab 15 Uhr“. */
data class PrecipOutlook(
    val type: PrecipType,
    /** Beginn (es regnet noch nicht) */
    val startsAt: Instant? = null,
    /** Ende (es regnet gerade); null + raining = hält an */
    val endsAt: Instant? = null,
    val raining: Boolean = false,
)

/** Aufbereitete aktuelle Wetterlage für die Übersicht. */
data class WeatherNow(
    val temperature: Double?,
    val condition: WeatherCondition,
    /** 0 – 1 */
    val cloudCover: Double,
    /** m/s */
    val windSpeed: Double,
    val windFrom: Double,
    /** mm/h */
    val precipitationRate: Double,
    val todayMin: Double?,
    val todayMax: Double?,
    val outlook: PrecipOutlook?,
    val updatedAt: Instant,
) {
    companion object {
        private const val WET = 0.1 // mm/h ab hier zählt es als Niederschlag
        private val OUTLOOK: Duration = Duration.ofHours(12)

        fun from(forecast: Forecast, now: Instant, zone: ZoneId): WeatherNow? {
            val hours = forecast.hours.sortedBy { it.time }
            if (hours.isEmpty()) return null
            val current = hours.lastOrNull { !it.time.isAfter(now) } ?: hours.first()
            val condition = current.symbol?.let(WeatherCondition::fromSymbol) ?: WeatherCondition(SkyCover.CLOUDY)

            val today = now.atZone(zone).toLocalDate()
            val todays = hours.filter { it.time.atZone(zone).toLocalDate() == today && it.temperature != null }
            val rest = hours.filter { !it.time.isBefore(current.time) }

            return WeatherNow(
                temperature = current.temperature,
                condition = condition,
                cloudCover = ((current.cloudCover ?: defaultCover(condition)) / 100.0).coerceIn(0.0, 1.0),
                windSpeed = current.windSpeed ?: 0.0,
                windFrom = current.windFrom ?: 270.0,
                precipitationRate = current.precipitation ?: 0.0,
                todayMin = todays.minOfOrNull { it.temperature!! },
                todayMax = todays.maxOfOrNull { it.temperature!! },
                outlook = outlook(rest, now),
                updatedAt = forecast.updatedAt,
            )
        }

        private fun defaultCover(c: WeatherCondition) = when (c.sky) {
            SkyCover.CLEAR -> 0.0; SkyCover.FAIR -> 20.0; SkyCover.PARTLY_CLOUDY -> 50.0
            SkyCover.CLOUDY -> 95.0; SkyCover.FOG -> 100.0
        }

        private fun typeOf(h: ForecastHour): PrecipType =
            h.symbol?.let { WeatherCondition.fromSymbol(it).precip }?.takeIf { it != PrecipType.NONE } ?: PrecipType.RAIN

        internal fun outlook(rest: List<ForecastHour>, now: Instant): PrecipOutlook? {
            val window = rest.filter { it.time.isBefore(now.plus(OUTLOOK)) && it.precipitation != null }
            if (window.isEmpty()) return null
            val first = window.first()
            return if ((first.precipitation ?: 0.0) >= WET) {
                val dry = window.firstOrNull { (it.precipitation ?: 0.0) < WET }
                PrecipOutlook(typeOf(first), endsAt = dry?.time, raining = true)
            } else {
                val wet = window.firstOrNull { (it.precipitation ?: 0.0) >= WET } ?: return null
                PrecipOutlook(typeOf(wet), startsAt = wet.time)
            }
        }
    }
}
