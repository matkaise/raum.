package app.raum.matter

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.raum.matter.chip.EncryptedKeyValueStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fabric-Schlüssel des Matter-SDK verschlüsselt im Keystore (Spez. 11.2) – mit eigenen Testdateien. */
@RunWith(AndroidJUnit4::class)
class EncryptedKeyValueStoreTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun store() = EncryptedKeyValueStore(context, STORE, LEGACY, ALIAS)

    @Before @After
    fun cleanUp() {
        context.deleteSharedPreferences(STORE)
        context.deleteSharedPreferences(LEGACY)
    }

    @Test
    fun speichert_nur_chiffrat_und_liest_nach_neustart() {
        val s = store()
        assertNull(s.get("AndroidDeviceControllerKey"))
        s.set("AndroidDeviceControllerKey", SECRET)
        s.set("f/1/n", "zertifikat")
        assertEquals(SECRET, s.get("AndroidDeviceControllerKey"))
        Thread.sleep(200) // apply()

        val raw = prefs(STORE).all.values.joinToString()
        assertFalse(raw.contains(SECRET))
        assertFalse(raw.contains("zertifikat"))

        val again = store()
        assertEquals(SECRET, again.get("AndroidDeviceControllerKey"))
        again.delete("f/1/n")
        assertNull(again.get("f/1/n"))
        again.clear()
        assertNull(store().get("AndroidDeviceControllerKey"))
    }

    @Test
    fun uebernimmt_klartextspeicher_und_loescht_ihn() {
        prefs(LEGACY).edit().putString("AndroidDeviceControllerKey", SECRET).putString("g/fidx", "AQ==").commit()
        val s = store()
        assertEquals(2, s.migrated)
        assertEquals(SECRET, s.get("AndroidDeviceControllerKey"))
        assertEquals("AQ==", s.get("g/fidx"))
        assertTrue(prefs(LEGACY).all.isEmpty())
        // zweiter Start: nichts mehr zu tun
        assertEquals(0, store().migrated)
    }

    @Test
    fun abgebrochene_uebernahme_ueberschreibt_neuere_werte_nicht() {
        // Früherer Lauf hat übernommen, das SDK hat seither „g/gcc“ geändert, der Klartext ist noch da (Absturz)
        store().set("g/gcc", "neu")
        Thread.sleep(200)
        prefs(LEGACY).edit().putString("g/gcc", "alt").putString("f/1/r", "root").commit()
        val s = store()
        assertEquals("neu", s.get("g/gcc"))
        assertEquals("root", s.get("f/1/r"))
        assertTrue(prefs(LEGACY).all.isEmpty())
    }

    @Test
    fun vertauschte_eintraege_sind_nicht_lesbar() {
        store().set("a", SECRET)
        Thread.sleep(200)
        val sealed = prefs(STORE).getString("a", null)!!
        prefs(STORE).edit().putString("b", sealed).commit()
        val s = store()
        assertNull(s.get("b"))
        assertEquals(1, s.unreadable)
        // Manipuliertes Chiffrat
        val bytes = Base64.decode(sealed, Base64.NO_WRAP).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        prefs(STORE).edit().putString("a", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        assertNull(store().get("a"))
    }

    private companion object {
        const val STORE = "test_matter_kvs"
        const val LEGACY = "test_chip_kvs"
        const val ALIAS = "test_matter_kvs_v1"
        const val SECRET = "BDEvAc0ffeeGeheimerRootSchluessel=="
    }
}
