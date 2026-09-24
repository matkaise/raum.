package app.raum.ui.overview

import app.raum.R
import java.time.Duration
import java.time.ZonedDateTime

/** Tageszeit für den Himmel der Übersicht – aus echten Sonnenzeiten, sonst aus der Uhrzeit. */
enum class SkyPhase { NIGHT, DAWN, DAY, DUSK }

object Sky {
    /** Dämmerung: eine Stunde vor bis eine Stunde nach Sonnenauf- bzw. -untergang. */
    private val TWILIGHT: Duration = Duration.ofMinutes(60)

    fun phase(now: ZonedDateTime, sunrise: ZonedDateTime?, sunset: ZonedDateTime?): SkyPhase {
        if (sunrise == null || sunset == null) {
            // Ohne Standort (oder Polartag/-nacht): grobe Einteilung nach Uhrzeit
            return when (now.hour) {
                in 6..7 -> SkyPhase.DAWN
                in 8..17 -> SkyPhase.DAY
                in 18..19 -> SkyPhase.DUSK
                else -> SkyPhase.NIGHT
            }
        }
        return when {
            now.isBefore(sunrise.minus(TWILIGHT)) -> SkyPhase.NIGHT
            now.isBefore(sunrise.plus(TWILIGHT)) -> SkyPhase.DAWN
            now.isBefore(sunset.minus(TWILIGHT)) -> SkyPhase.DAY
            now.isBefore(sunset.plus(TWILIGHT)) -> SkyPhase.DUSK
            else -> SkyPhase.NIGHT
        }
    }

    /** Anteil des Sonnenbogens (0 = Aufgang, 1 = Untergang), null wenn die Sonne nicht am Himmel steht. */
    fun sunProgress(now: ZonedDateTime, sunrise: ZonedDateTime?, sunset: ZonedDateTime?): Float? {
        if (sunrise == null || sunset == null || now.isBefore(sunrise) || now.isAfter(sunset)) return null
        val total = Duration.between(sunrise, sunset).seconds.toFloat()
        return if (total <= 0f) null else Duration.between(sunrise, now).seconds / total
    }

    fun greetingRes(hour: Int): Int = when (hour) {
        in 5..10 -> R.string.greeting_morning
        in 11..16 -> R.string.greeting_day
        in 17..21 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }
}
