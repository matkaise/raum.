package app.raum.diagnostics

import app.raum.R
import androidx.annotation.StringRes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class LogCategory(@StringRes val labelRes: Int) {
    SYSTEM(R.string.log_system), DEVICE(R.string.log_device), SCENE(R.string.log_scene), AUTOMATION(R.string.log_automation)
}
enum class LogLevel { INFO, WARNING, ERROR }

data class LogEntry(
    val timestamp: Instant,
    val category: LogCategory,
    val level: LogLevel,
    val message: String,
    val deviceName: String? = null,
    val deviceId: UUID? = null,
    val automationId: UUID? = null,
)

/** Dauerhafte Ablage des Protokolls (Room); ohne Sink bleibt das Protokoll nur im Speicher. */
fun interface EventLogSink {
    fun append(entry: LogEntry)
}

/**
 * Lokales Ereignisprotokoll (LOG-001, LOG-002).
 * Schlüssel und Zugangsdaten dürfen nie übergeben werden (LOG-003).
 *
 * [recent] hält die jüngsten Einträge im Speicher; mit [sink] werden alle Einträge zusätzlich
 * dauerhaft gespeichert (Rotation dort).
 */
class EventLog(
    private val maxRecent: Int = 500,
    private val maxAge: Duration = Duration.ofDays(30),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val sink: EventLogSink? = null,
) {
    private val _recent = MutableStateFlow<List<LogEntry>>(emptyList())
    /** Neueste zuerst. */
    val recent: StateFlow<List<LogEntry>> = _recent.asStateFlow()

    fun record(
        category: LogCategory,
        level: LogLevel,
        message: String,
        deviceName: String? = null,
        deviceId: UUID? = null,
        automationId: UUID? = null,
    ) {
        val now = clock.instant()
        val entry = LogEntry(now, category, level, message, deviceName, deviceId, automationId)
        val cutoff = now.minus(maxAge)
        _recent.update { list ->
            (listOf(entry) + list).asSequence().filter { it.timestamp.isAfter(cutoff) }.take(maxRecent).toList()
        }
        sink?.append(entry)
    }

    fun info(category: LogCategory, message: String, deviceName: String? = null) =
        record(category, LogLevel.INFO, message, deviceName)

    fun warning(category: LogCategory, message: String, deviceName: String? = null) =
        record(category, LogLevel.WARNING, message, deviceName)

    fun error(category: LogCategory, message: String, deviceName: String? = null) =
        record(category, LogLevel.ERROR, message, deviceName)
}
