package app.raum.automation.triggers

import app.raum.domain.models.SunEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Manuell hinterlegte Position (Spez. 7.8) – es wird keine Ortung verwendet. */
data class GeoLocation(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude outside -90…90" }
        require(longitude in -180.0..180.0) { "longitude outside -180…180" }
    }
}

/**
 * Sonnenauf-/-untergang nach der Sonnenaufgangsgleichung (Genauigkeit ca. ±1 Minute in
 * mittleren Breiten). Vollständig offline.
 */
object SunCalculator {

    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5

    /** @return Zeitpunkt in [zone], oder null bei Polartag/-nacht. */
    fun time(event: SunEvent, date: LocalDate, location: GeoLocation, zone: ZoneId): ZonedDateTime? {
        val n = date.toEpochDay() + 2440588.0 - J2000 + 0.0008
        val jStar = n - location.longitude / 360.0
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val mRad = Math.toRadians(m)
        val c = 1.9148 * sin(mRad) + 0.0200 * sin(2 * mRad) + 0.0003 * sin(3 * mRad)
        val lambda = Math.toRadians((m + c + 180.0 + 102.9372).mod(360.0))
        val jTransit = J2000 + jStar + 0.0053 * sin(mRad) - 0.0069 * sin(2 * lambda)
        val sinDecl = sin(lambda) * sin(Math.toRadians(23.4397))
        val cosDecl = cos(asin(sinDecl))
        val lat = Math.toRadians(location.latitude)
        val cosOmega = (sin(Math.toRadians(-0.833)) - sin(lat) * sinDecl) / (cos(lat) * cosDecl)
        if (cosOmega < -1.0 || cosOmega > 1.0) return null
        val omegaDays = Math.toDegrees(acos(cosOmega)) / 360.0
        val jd = if (event == SunEvent.SUNRISE) jTransit - omegaDays else jTransit + omegaDays
        val epochMs = ((jd - UNIX_EPOCH_JD) * 86_400_000.0).toLong()
        return Instant.ofEpochMilli(epochMs).atZone(zone)
    }
}
