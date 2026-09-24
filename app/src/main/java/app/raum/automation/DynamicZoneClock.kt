package app.raum.automation

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Systemuhr, die immer die *aktuelle* System-Zeitzone meldet. `Clock.systemDefaultZone()` merkt sich die
 * Zone beim Erzeugen – eine Zeitzonenänderung in der Einrichtung würde sonst erst nach einem Neustart
 * bei den Zeit-Triggern ankommen.
 */
object DynamicZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}
