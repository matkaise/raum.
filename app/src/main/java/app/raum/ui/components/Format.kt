package app.raum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.raum.R
import app.raum.data.preferences.TemperatureUnit
import app.raum.i18n.Strings
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Gewählte Temperatureinheit für die Oberfläche (ONB-003). */
val LocalTemperatureUnit = staticCompositionLocalOf { TemperatureUnit.CELSIUS }

/** Umrechnung und Formatierung von Temperaturen. Intern wird immer in °C gerechnet. */
object Units {
    fun toDisplay(celsius: Double, unit: TemperatureUnit): Double =
        if (unit == TemperatureUnit.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun fromDisplay(value: Double, unit: TemperatureUnit): Double =
        if (unit == TemperatureUnit.FAHRENHEIT) (value - 32.0) * 5.0 / 9.0 else value

    fun symbol(unit: TemperatureUnit) = if (unit == TemperatureUnit.FAHRENHEIT) "°F" else "°C"

    /** Schrittweite der ±-Tasten in °C: 0,5 °C bzw. 1 °F. */
    fun stepCelsius(unit: TemperatureUnit): Double = if (unit == TemperatureUnit.FAHRENHEIT) 5.0 / 9.0 else 0.5

    /** @param compact true = „21,5 °“ (Kacheln), false = „21,5 °C“ (Sätze, Einstellungen) */
    fun format(celsius: Double, unit: TemperatureUnit, locale: Locale, compact: Boolean = false): String {
        val v = toDisplay(celsius, unit)
        val number = String.format(locale, if (unit == TemperatureUnit.FAHRENHEIT) "%.0f" else "%.1f", v)
        return if (compact) "$number °" else "$number ${symbol(unit)}"
    }
}

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
@ReadOnlyComposable
fun formatTemperature(celsius: Double, compact: Boolean = true): String =
    Units.format(celsius, LocalTemperatureUnit.current, currentLocale(), compact)

@Composable
@ReadOnlyComposable
fun formatPercent(value: Double): String = String.format(currentLocale(), "%.0f %%", value)

@Composable
@ReadOnlyComposable
fun formatWatts(value: Double): String = String.format(currentLocale(), if (value < 10) "%.1f W" else "%.0f W", value)

@Composable
@ReadOnlyComposable
fun formatKwh(value: Double): String = String.format(currentLocale(), "%.1f kWh", value)

@Composable
fun formatAgo(instant: Instant?, now: Instant = Instant.now()): String {
    if (instant == null) return stringResource(R.string.time_unknown)
    val d = Duration.between(instant, now)
    return when {
        d.toMinutes() < 1 -> stringResource(R.string.time_just_now)
        d.toMinutes() < 60 -> pluralStringResource(R.plurals.time_minutes_ago, d.toMinutes().toInt(), d.toMinutes().toInt())
        d.toHours() < 24 -> pluralStringResource(R.plurals.time_hours_ago, d.toHours().toInt(), d.toHours().toInt())
        else -> pluralStringResource(R.plurals.time_days_ago, d.toDays().toInt(), d.toDays().toInt())
    }
}

/** Variante außerhalb von Compose (Berichte). */
fun formatAgo(instant: Instant?, strings: Strings, now: Instant = Instant.now()): String {
    if (instant == null) return strings.get(R.string.time_unknown)
    val d = Duration.between(instant, now)
    return when {
        d.toMinutes() < 1 -> strings.get(R.string.time_just_now)
        d.toMinutes() < 60 -> strings.plural(R.plurals.time_minutes_ago, d.toMinutes().toInt())
        d.toHours() < 24 -> strings.plural(R.plurals.time_hours_ago, d.toHours().toInt())
        else -> strings.plural(R.plurals.time_days_ago, d.toDays().toInt())
    }
}

/** Langes Datum („Montag, 21. September“ / „Monday, 21 September“) in der App-Sprache. */
@Composable
fun rememberLongDateFormatter(): java.time.format.DateTimeFormatter {
    val pattern = stringResource(R.string.date_pattern_long)
    val locale = currentLocale()
    return androidx.compose.runtime.remember(pattern, locale) { java.time.format.DateTimeFormatter.ofPattern(pattern, locale) }
}

/** Zeitstempel im Protokoll („21.09. 23:07:00“ / „21 Sep 23:07:00“). */
@Composable
fun rememberLogTimeFormatter(): java.time.format.DateTimeFormatter {
    val pattern = stringResource(R.string.date_pattern_log)
    val locale = currentLocale()
    return androidx.compose.runtime.remember(pattern, locale) {
        java.time.format.DateTimeFormatter.ofPattern(pattern, locale).withZone(java.time.ZoneId.systemDefault())
    }
}
