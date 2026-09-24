package app.raum.matter.chip

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import app.raum.security.KeyProtection
import app.raum.security.KeystoreCipher
import chip.platform.KeyValueStoreManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Schlüsselspeicher des Matter-SDK, verschlüsselt mit einem Schlüssel im Android Keystore (Spez. 10.3, 11.2).
 *
 * Das SDK legt hier u. a. den Root-CA-Schlüssel der Fabric, den Betriebsschlüssel, Zertifikate und
 * Sitzungsschlüssel ab. Jeder Wert wird einzeln mit AES-256-GCM verschlüsselt, der Eintragsname dient als AAD.
 * Auf dem Datenträger liegt nur Chiffrat; der Schlüssel dazu ist nicht exportierbar.
 *
 * Bestehende Installationen: Der bisherige Klartextspeicher des SDK wird beim ersten Start übernommen, jeder Eintrag
 * nach dem Schreiben zurückgelesen und erst dann der Klartext gelöscht. Bricht das ab, wiederholt der nächste
 * Start die Übernahme – die Fabric bleibt erhalten, Geräte müssen nicht neu gekoppelt werden.
 */
class EncryptedKeyValueStore(
    context: Context,
    private val storeName: String = STORE,
    private val legacyName: String? = LEGACY_STORE,
    alias: String = ALIAS,
) : KeyValueStoreManager {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(alias)

    /** Entschlüsselte Werte – das SDK liest häufig, der Keystore ist dafür zu langsam. */
    private val cache = ConcurrentHashMap<String, String>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Einträge, die sich nicht entschlüsseln ließen (Keystore-Schlüssel verloren oder Datei manipuliert). */
    @Volatile var unreadable: Int = 0
        private set

    /** Wie viele Einträge beim Start aus dem Klartextspeicher übernommen wurden (0 = nichts zu tun). */
    val migrated: Int = migrateLegacy()

    val protection: KeyProtection get() = cipher.protection()

    @Synchronized
    override fun get(key: String): String? {
        cache[key]?.let { return it }
        if (key in missing) return null
        val stored = prefs.getString(key, null) ?: run { missing += key; return null }
        return runCatching { cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.toByteArray()).decodeToString() }
            .onFailure { unreadable++; Log.e(TAG, "Eintrag nicht lesbar: $key", it) }
            .getOrNull()
            ?.also { cache[key] = it }
    }

    @Synchronized
    @SuppressLint("UseKtx")
    override fun set(key: String, value: String) {
        if (cache[key] == value) return
        val sealed = cipher.encrypt(value.toByteArray(), key.toByteArray())
        cache[key] = value
        missing -= key
        // apply wie der SDK-Standardspeicher: Zähler und Sitzungen werden oft geschrieben
        prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    @SuppressLint("UseKtx")
    override fun delete(key: String) {
        cache.remove(key)
        missing += key
        prefs.edit().remove(key).apply()
    }

    /** Werksreset: alles weg (danach erzeugt das SDK eine neue Fabric). */
    @Synchronized
    @SuppressLint("ApplySharedPref", "UseKtx")
    fun clear() {
        cache.clear(); missing.clear()
        prefs.edit().clear().commit()
        legacyName?.let { appContext.deleteSharedPreferences(it) }
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun migrateLegacy(): Int {
        val name = legacyName ?: return 0
        val legacy = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val entries = legacy.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
        if (entries.isEmpty()) return 0
        // Bereits übernommene Einträge nicht überschreiben (Neustart mitten in einer früheren Übernahme)
        val todo = entries.filter { (k, _) -> !prefs.contains(k) }
        val edit = prefs.edit()
        todo.forEach { (k, v) ->
            edit.putString(k, Base64.encodeToString(cipher.encrypt(v.toByteArray(), k.toByteArray()), Base64.NO_WRAP))
        }
        check(edit.commit()) { "Schlüsselspeicher konnte nicht geschrieben werden" }
        // Zurücklesen und vergleichen – erst dann den Klartext löschen. Schon früher übernommene Einträge gelten
        // (das SDK kann sie seither geändert haben; der Klartext wäre dann veraltet).
        val verified = todo.all { (k, v) ->
            runCatching {
                cipher.decrypt(Base64.decode(prefs.getString(k, null)!!, Base64.NO_WRAP), k.toByteArray()).decodeToString() == v
            }.getOrDefault(false)
        }
        check(verified) { "Übernahme des Schlüsselspeichers nicht bestätigt – Klartext bleibt vorerst erhalten" }
        legacy.edit().clear().commit()
        appContext.deleteSharedPreferences(name)
        Log.i(TAG, "Matter-Schlüsselspeicher verschlüsselt übernommen: ${entries.size} Einträge")
        return entries.size
    }

    companion object {
        private const val TAG = "raum.matter"
        const val STORE = "raum_matter_kvs"
        /** Klartextspeicher von PreferencesKeyValueStoreManager */
        const val LEGACY_STORE = "chip.platform.KeyValueStore"
        const val ALIAS = "raum_matter_kvs_v1"
    }
}
