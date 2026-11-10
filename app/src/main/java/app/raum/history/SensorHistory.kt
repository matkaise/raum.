package app.raum.history

import app.raum.data.database.SensorSampleDao
import app.raum.data.database.SensorSampleEntity
import app.raum.domain.models.ContactSensorCapability
import app.raum.domain.models.Device
import app.raum.domain.models.HumiditySensorCapability
import app.raum.domain.models.OccupancyCapability
import app.raum.domain.models.TemperatureSensorCapability
import app.raum.domain.models.ThermostatCapability
import app.raum.domain.models.find
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Was aufgezeichnet wird. [key] ist Teil des Speicherformats. */
enum class SensorMetric(val key: String, val binary: Boolean) {
    TEMPERATURE("temp", false),
    HUMIDITY("hum", false),
    CONTACT("contact", true),
    OCCUPANCY("occ", true),
    ONLINE("online", true),
}

data class Sample(val at: Instant, val value: Double)

/** Lesen des Verlaufs (für die Oberfläche; im Test ersetzbar). */
interface SensorHistorySource {
    suspend fun samples(deviceId: UUID, metric: SensorMetric, from: Instant, to: Instant): List<Sample>
    suspend fun lastBefore(deviceId: UUID, metric: SensorMetric, at: Instant): Sample?
}

/**
 * Sensorverlauf – nur lokal (Spez. 11.1). Messwerte (Temperatur, Luftfeuchte) werden alle
 * [interval] auf die Uhr ausgerichtet gespeichert, zweistellige Zustände (offen, belegt,
 * erreichbar) bei jedem Wechsel. Nach [retention] wird gelöscht, ebenso der Verlauf entfernter Geräte.
 */
class SensorHistory(
    private val dao: SensorSampleDao,
    private val devices: StateFlow<List<Device>>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val interval: Duration = Duration.ofMinutes(5),
    private val retention: Duration = Duration.ofDays(8),
) : SensorHistorySource {

    fun start() {
        // Zustandswechsel sofort
        scope.launch {
            var previous: Map<UUID, Map<SensorMetric, Double>>? = null
            devices.collect { list ->
                val now = clock.instant()
                val current = list.associate { it.id to binaryValues(it) }
                val changes = binaryChanges(previous, current, now)
                previous = current
                if (changes.isNotEmpty()) runCatching { dao.insert(changes) }
            }
        }
        // Messwerte im Raster, dazwischen Aufräumen
        scope.launch {
            prune()
            var sincePrune = Duration.ZERO
            while (true) {
                val nowMs = clock.millis()
                val step = interval.toMillis()
                delay(step - nowMs % step)
                val at = clock.instant()
                val rows = devices.value.flatMap { d -> analogValues(d).map { (m, v) -> entity(d.id, m, at, v) } }
                if (rows.isNotEmpty()) runCatching { dao.insert(rows) }
                sincePrune += interval
                if (sincePrune >= Duration.ofHours(6)) { prune(); sincePrune = Duration.ZERO }
            }
        }
    }

    private suspend fun prune() {
        runCatching {
            dao.deleteOlderThan(clock.instant().minus(retention).toEpochMilli())
            dao.deleteOrphans()
        }
    }

    override suspend fun samples(deviceId: UUID, metric: SensorMetric, from: Instant, to: Instant): List<Sample> =
        dao.range(deviceId.toString(), metric.key, from.toEpochMilli(), to.toEpochMilli()).map { Sample(Instant.ofEpochMilli(it.timestampMs), it.value) }

    override suspend fun lastBefore(deviceId: UUID, metric: SensorMetric, at: Instant): Sample? =
        dao.lastBefore(deviceId.toString(), metric.key, at.toEpochMilli())?.let { Sample(Instant.ofEpochMilli(it.timestampMs), it.value) }

    companion object {
        /** Messwerte eines erreichbaren Geräts; Thermostate liefern ihre Ist-Temperatur. */
        fun analogValues(d: Device): Map<SensorMetric, Double> {
            if (!d.isOnline) return emptyMap()
            val c = d.capabilities
            return buildMap {
                (c.find<TemperatureSensorCapability>()?.celsius ?: c.find<ThermostatCapability>()?.currentCelsius)
                    ?.let { put(SensorMetric.TEMPERATURE, it) }
                c.find<HumiditySensorCapability>()?.let { put(SensorMetric.HUMIDITY, it.percent) }
            }
        }

        /** Zweistellige Zustände; offen/belegt nur, solange das Gerät erreichbar ist. */
        fun binaryValues(d: Device): Map<SensorMetric, Double> = buildMap {
            put(SensorMetric.ONLINE, if (d.isOnline) 1.0 else 0.0)
            if (d.isOnline) {
                d.capabilities.find<ContactSensorCapability>()?.let { put(SensorMetric.CONTACT, if (it.isOpen) 1.0 else 0.0) }
                d.capabilities.find<OccupancyCapability>()?.let { put(SensorMetric.OCCUPANCY, if (it.isOccupied) 1.0 else 0.0) }
            }
        }

        /** Neue Zeilen für geänderte Zustände; beim ersten Stand (previous = null) der Ausgangszustand aller Geräte. */
        fun binaryChanges(
            previous: Map<UUID, Map<SensorMetric, Double>>?,
            current: Map<UUID, Map<SensorMetric, Double>>,
            at: Instant,
        ): List<SensorSampleEntity> = current.flatMap { (id, values) ->
            val before = previous?.get(id)
            values.filter { (m, v) -> before == null || before[m] != v }.map { (m, v) -> entity(id, m, at, v) }
        }

        private fun entity(id: UUID, m: SensorMetric, at: Instant, v: Double) =
            SensorSampleEntity(deviceId = id.toString(), metric = m.key, timestampMs = at.toEpochMilli(), value = v)
    }
}

/** Aufbereitung für Diagramme – rein rechnerisch, ohne Android. */
object HistoryMath {
    /**
     * Mittelwerte in [count] Fenstern der Länge [step] ab [from]; null = keine Messung im Fenster (Lücke).
     */
    fun buckets(samples: List<Sample>, from: Instant, step: Duration, count: Int): List<Double?> {
        val sums = DoubleArray(count)
        val counts = IntArray(count)
        val stepMs = step.toMillis()
        for (s in samples) {
            val i = ((s.at.toEpochMilli() - from.toEpochMilli()) / stepMs).toInt()
            if (i in 0 until count) { sums[i] += s.value; counts[i]++ }
        }
        return List(count) { i -> if (counts[i] == 0) null else sums[i] / counts[i] }
    }

    data class Interval(val start: Instant, val end: Instant, val ongoing: Boolean) {
        val duration: Duration get() = Duration.between(start, end)
    }

    /**
     * Zeiträume, in denen der Zustand 1 war (offen, belegt, erreichbar), begrenzt auf [from] … [now].
     * [initial] ist der letzte Wert vor [from].
     */
    fun intervals(samples: List<Sample>, initial: Sample?, from: Instant, now: Instant): List<Interval> {
        val out = mutableListOf<Interval>()
        var openSince: Instant? = if (initial?.value == 1.0) from else null
        for (s in samples.sortedBy { it.at }) {
            if (s.at.isBefore(from) || s.at.isAfter(now)) continue
            if (s.value == 1.0 && openSince == null) openSince = s.at
            if (s.value != 1.0 && openSince != null) { out += Interval(openSince, s.at, false); openSince = null }
        }
        openSince?.let { out += Interval(it, now, true) }
        return out
    }
}
