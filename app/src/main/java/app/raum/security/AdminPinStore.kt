package app.raum.security

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/** Minimaler Schlüssel-Wert-Speicher – SharedPreferences in der App, Map in Tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/**
 * Lokale Administrator-PIN (ONB-004, SYS-006, RST-003).
 *
 * - Nur ein gesalzener PBKDF2-Hash wird gespeichert, nie die PIN selbst (Spez. 11.2, LOG-003).
 * - Nach [freeAttempts] Fehlversuchen wird gesperrt; die Sperrzeit verdoppelt sich bis [maxLockout].
 *   Zähler und Sperre überstehen Neustarts.
 */
class AdminPinStore(
    private val store: KeyValueStore,
    private val clock: Clock = Clock.systemUTC(),
    private val freeAttempts: Int = 5,
    private val baseLockout: Duration = Duration.ofSeconds(30),
    private val maxLockout: Duration = Duration.ofMinutes(15),
) {
    sealed interface VerifyResult {
        data object Ok : VerifyResult
        data object NotSet : VerifyResult
        data class Wrong(val attemptsBeforeLockout: Int) : VerifyResult
        data class LockedOut(val until: Instant) : VerifyResult
    }

    val isSet: Boolean get() = store.getString(KEY_HASH) != null

    fun isValidFormat(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    /** Setzt oder ändert die PIN. Ist bereits eine gesetzt, muss [current] stimmen. */
    fun setPin(newPin: String, current: String? = null): VerifyResult {
        require(isValidFormat(newPin)) { "PIN must have $MIN_LENGTH–$MAX_LENGTH digits" }
        if (isSet) {
            val check = verify(current ?: "")
            if (check != VerifyResult.Ok) return check
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.putString(KEY_SALT, b64(salt))
        store.putString(KEY_HASH, b64(hash(newPin, salt)))
        resetFailures()
        return VerifyResult.Ok
    }

    fun verify(pin: String): VerifyResult {
        val hash = store.getString(KEY_HASH) ?: return VerifyResult.NotSet
        val salt = store.getString(KEY_SALT) ?: return VerifyResult.NotSet
        lockedUntil()?.let { if (clock.instant().isBefore(it)) return VerifyResult.LockedOut(it) }

        val ok = java.security.MessageDigest.isEqual(hash(pin, unb64(salt)), unb64(hash))
        if (ok) { resetFailures(); return VerifyResult.Ok }

        val failures = (store.getString(KEY_FAILURES)?.toIntOrNull() ?: 0) + 1
        store.putString(KEY_FAILURES, failures.toString())
        if (failures >= freeAttempts) {
            val factor = 1L shl min(failures - freeAttempts, 10)
            val lockout = baseLockout.multipliedBy(factor).let { if (it > maxLockout) maxLockout else it }
            val until = clock.instant().plus(lockout)
            store.putString(KEY_LOCKED_UNTIL, until.toEpochMilli().toString())
            return VerifyResult.LockedOut(until)
        }
        return VerifyResult.Wrong(freeAttempts - failures)
    }

    /** Entfernt die PIN (nur für Werksreset, M7). */
    fun clear() {
        listOf(KEY_HASH, KEY_SALT, KEY_FAILURES, KEY_LOCKED_UNTIL).forEach { store.putString(it, null) }
    }

    private fun lockedUntil(): Instant? = store.getString(KEY_LOCKED_UNTIL)?.toLongOrNull()?.let(Instant::ofEpochMilli)

    private fun resetFailures() {
        store.putString(KEY_FAILURES, null)
        store.putString(KEY_LOCKED_UNTIL, null)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        private const val ITERATIONS = 120_000
        private const val KEY_HASH = "admin_pin_hash"
        private const val KEY_SALT = "admin_pin_salt"
        private const val KEY_FAILURES = "admin_pin_failures"
        private const val KEY_LOCKED_UNTIL = "admin_pin_locked_until"
    }
}
