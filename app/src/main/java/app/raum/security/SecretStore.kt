package app.raum.security

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64

/**
 * Speicher für Zugangsdaten (Spez. 10.3, 11.2): Thread Operational Dataset, WLAN-Zugangsdaten fürs Koppeln.
 * Werte liegen nie im Klartext auf dem Datenträger.
 */
interface SecretStore {
    fun get(key: String): ByteArray?
    /** null löscht den Eintrag. */
    fun put(key: String, value: ByteArray?)
}

class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, ByteArray>()
    override fun get(key: String) = map[key]?.copyOf()
    override fun put(key: String, value: ByteArray?) { if (value == null) map.remove(key) else map[key] = value.copyOf() }
}

/**
 * AES-256-GCM mit einem Schlüssel im Android Keystore (hardwaregestützt, sofern das Gerät es kann).
 * Der Schlüssel verlässt den Keystore nie; auf dem Datenträger liegt nur IV + Chiffrat.
 * Nach einem Werksreset des Geräts oder Löschen der App-Daten sind die Werte unwiederbringlich weg – gewollt.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(ALIAS)

    // Schlüsselname als AAD: ein Chiffrat lässt sich nicht unter anderem Namen unterschieben
    override fun get(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.toByteArray()) }.getOrNull()
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    override fun put(key: String, value: ByteArray?) {
        if (value == null) { prefs.edit().remove(key).commit(); return }
        val sealed = cipher.encrypt(value, key.toByteArray())
        prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }

    private companion object {
        const val ALIAS = "raum_secrets_v1"
        const val PREFS = "raum_secrets"
    }
}
