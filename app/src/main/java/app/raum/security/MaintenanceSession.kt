package app.raum.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Freigeschalteter Wartungsmodus (SYS-006) mit automatischer Sperre nach Inaktivität (Spez. 11.3).
 */
class MaintenanceSession(
    private val clock: Clock = Clock.systemUTC(),
    private val idleTimeout: Duration = Duration.ofMinutes(5),
) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var lastActivity: Instant = Instant.EPOCH

    private val _kioskPaused = MutableStateFlow(false)
    /** Kiosk vorübergehend verlassen (z. B. für Android-Einstellungen); endet mit der Sitzung. */
    val kioskPaused: StateFlow<Boolean> = _kioskPaused.asStateFlow()

    fun unlock() { lastActivity = clock.instant(); _unlocked.value = true }
    fun lock() { _unlocked.value = false; _kioskPaused.value = false }

    fun pauseKiosk() { if (_unlocked.value) _kioskPaused.value = true }
    fun resumeKiosk() { _kioskPaused.value = false }

    /** Jede Bedienung im Wartungsmodus verlängert die Sitzung. */
    fun touch() { if (_unlocked.value) lastActivity = clock.instant() }

    /** Periodisch aufrufen; sperrt nach Ablauf. @return true, wenn weiterhin entsperrt. */
    fun checkTimeout(): Boolean {
        if (_unlocked.value && clock.instant().isAfter(lastActivity.plus(idleTimeout))) lock()
        return _unlocked.value
    }
}
