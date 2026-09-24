package app.raum.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Zugangsdaten im Android Keystore (Spez. 10.3, 11.2): nie im Klartext auf dem Datenträger. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("raum_secrets", Context.MODE_PRIVATE)

    @After
    fun cleanUp() { KeystoreSecretStore(context).put(KEY, null) }

    @Test
    fun verschluesselt_ablegen_und_lesen() {
        val secret = "geheimes-thread-dataset-0011223344".toByteArray()
        val store = KeystoreSecretStore(context)
        store.put(KEY, secret)

        assertArrayEquals(secret, store.get(KEY))
        // Neue Instanz (wie nach Neustart) liest mit demselben Keystore-Schlüssel
        assertArrayEquals(secret, KeystoreSecretStore(context).get(KEY))

        val raw = prefs.getString(KEY, null)!!
        assertFalse(raw.contains("geheimes"))
        assertFalse(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP).decodeToString().contains("geheimes"))

        store.put(KEY, null)
        assertNull(store.get(KEY))
    }

    @Test
    fun chiffrat_unter_anderem_namen_ist_wertlos() {
        val store = KeystoreSecretStore(context)
        store.put(KEY, byteArrayOf(1, 2, 3))
        // Chiffrat unter einen anderen Namen kopieren: AAD passt nicht → nicht lesbar
        prefs.edit().putString(OTHER, prefs.getString(KEY, null)).commit()
        assertNull(store.get(OTHER))
        prefs.edit().remove(OTHER).commit()
    }

    private companion object {
        const val KEY = "instrumented_test_secret"
        const val OTHER = "instrumented_test_other"
    }
}
