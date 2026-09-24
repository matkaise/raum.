package app.raum.automation

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Verstellbare Uhr für Tests. */
class TestClock(var now: ZonedDateTime) : Clock() {
    override fun getZone(): ZoneId = now.zone
    override fun withZone(zone: ZoneId): Clock = TestClock(now.withZoneSameInstant(zone))
    override fun instant(): Instant = now.toInstant()
}
