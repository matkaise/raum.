package app.raum.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.raum.data.database.DatabaseSafety
import app.raum.data.database.RaumDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Recovery-Pfad (Spez. 11.4): Sicherungspunkt vor Migrationen. */
@RunWith(AndroidJUnit4::class)
class DatabaseSafetyTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RaumDatabase::class.java)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
        DatabaseSafety.recoveryDir(context).listFiles()?.filter { it.name.startsWith(TEST_DB) }?.forEach { it.delete() }
    }

    @Test
    fun snapshotIsTakenBeforeMigrationAndIsReadable() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO home (id, name, createdAtEpochMs) VALUES ('h', 'Alt', 0)")
        }
        val snapshot = DatabaseSafety.snapshotBeforeMigration(context, TEST_DB, targetVersion = 2)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.name.contains("vor-migration-v1"))
        SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
            assertEquals(1, copy.version)
            copy.rawQuery("SELECT name FROM home", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Alt", c.getString(0))
            }
        }
    }

    @Test
    fun noSnapshotWhenAlreadyCurrent() {
        helper.createDatabase(TEST_DB, 2).close()
        assertNull(DatabaseSafety.snapshotBeforeMigration(context, TEST_DB, targetVersion = 2))
    }

    @Test
    fun onlyTheLastThreeSnapshotsAreKept() {
        helper.createDatabase(TEST_DB, 2).close()
        repeat(5) { i ->
            DatabaseSafety.snapshot(context, TEST_DB, "test-$i")
            Thread.sleep(1100) // Zeitstempel im Dateinamen sekundengenau
        }
        val kept = DatabaseSafety.recoveryDir(context).listFiles()!!.filter { it.name.startsWith(TEST_DB) }
        assertEquals(3, kept.size)
    }

    private companion object {
        const val TEST_DB = "safety-test.db"
    }
}
