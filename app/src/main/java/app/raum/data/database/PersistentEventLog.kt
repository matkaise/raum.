package app.raum.data.database

import app.raum.diagnostics.EventLogSink
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogEntry
import app.raum.diagnostics.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class LogFilter(
    val category: LogCategory? = null,
    val deviceId: UUID? = null,
    val automationId: UUID? = null,
    val since: Instant = Instant.EPOCH,
    val limit: Int = 500,
)

/**
 * Schreibt Protokolleinträge gebündelt in die Datenbank und rotiert nach Alter und Anzahl (LOG-002).
 * [append] blockiert nie – Aufrufer (auch Automationen) warten nicht auf I/O.
 */
class PersistentEventLog(
    private val dao: EventLogDao,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxAge: Duration = Duration.ofDays(30),
    private val maxEntries: Int = 10_000,
) : EventLogSink {

    private val queue = Channel<LogEntry>(Channel.UNLIMITED)
    private var sincePrune = 0

    init {
        scope.launch {
            prune()
            while (true) {
                val batch = mutableListOf(queue.receive())
                while (batch.size < 100) batch += queue.tryReceive().getOrNull() ?: break
                runCatching { dao.insert(batch.map { it.toEntity() }) }
                sincePrune += batch.size
                if (sincePrune >= 200) { prune(); sincePrune = 0 }
            }
        }
    }

    override fun append(entry: LogEntry) {
        queue.trySend(entry)
    }

    private suspend fun prune() {
        runCatching {
            dao.deleteOlderThan(clock.instant().minus(maxAge).toEpochMilli())
            dao.trimTo(maxEntries)
        }
    }

    fun observe(filter: LogFilter): Flow<List<LogEntry>> =
        dao.observe(
            category = filter.category?.name,
            deviceId = filter.deviceId?.toString(),
            automationId = filter.automationId?.toString(),
            sinceMs = filter.since.toEpochMilli(),
            limit = filter.limit,
        ).map { list -> list.map { it.toDomain() } }

    /** Letzter Eintrag je Automation (Schlüssel = Automations-ID). */
    fun observeLatestPerAutomation(): Flow<Map<UUID, LogEntry>> =
        dao.observeLatestPerAutomation().map { list ->
            list.mapNotNull { e -> e.automationId?.let { UUID.fromString(it) to e.toDomain() } }.toMap()
        }

    suspend fun clear() = dao.clear()
}

private fun LogEntry.toEntity() = EventLogEntity(
    timestampMs = timestamp.toEpochMilli(),
    category = category.name,
    level = level.name,
    message = message,
    deviceId = deviceId?.toString(),
    deviceName = deviceName,
    automationId = automationId?.toString(),
)

private fun EventLogEntity.toDomain() = LogEntry(
    timestamp = Instant.ofEpochMilli(timestampMs),
    category = runCatching { LogCategory.valueOf(category) }.getOrDefault(LogCategory.SYSTEM),
    level = runCatching { LogLevel.valueOf(level) }.getOrDefault(LogLevel.INFO),
    message = message,
    deviceName = deviceName,
    deviceId = deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
    automationId = automationId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
)
