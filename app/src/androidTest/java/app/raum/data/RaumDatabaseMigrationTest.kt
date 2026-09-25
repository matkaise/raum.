package app.raum.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.raum.data.database.ALL_MIGRATIONS
import app.raum.data.database.MIGRATION_1_2
import app.raum.data.database.MIGRATION_2_3
import app.raum.data.database.RaumDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prüft die exportierten Schemata unter app/schemas. Für jede neue DB-Version kommt hier ein Test
 * „vN → vN+1“ dazu, der Beispieldaten anlegt, migriert und das Ergebnis validiert.
 */
@RunWith(AndroidJUnit4::class)
class RaumDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RaumDatabase::class.java,
    )

    @Test
    fun version1SchemaIsValid() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO home (id, name, createdAtEpochMs) VALUES ('h', 'Test', 0)")
            db.execSQL("INSERT INTO rooms (id, name, icon, sortOrder) VALUES ('r', 'Küche', 'kitchen', 0)")
            db.execSQL("INSERT INTO devices (id, matterNodeId, displayName, roomId, vendorName, productName, favorite) VALUES ('d', 17, 'Lampe', 'r', NULL, NULL, 1)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 1, true)
    }

    @Test
    fun migrate1To2KeepsAutomationsButDisablesThem() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO automations (id, name, enabled, summary) VALUES ('a1', 'Storen abends', 1, 'alt')")
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT name, enabled, triggersJson, actionsJson FROM automations WHERE id = 'a1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Storen abends", c.getString(0))
                assertEquals(0, c.getInt(1)) // unvollständig → deaktiviert
                assertEquals("[]", c.getString(2))
                assertEquals("[]", c.getString(3))
            }
            db.execSQL("INSERT INTO event_log (timestampMs, category, level, message) VALUES (1, 'SYSTEM', 'INFO', 'ok')")
        }
    }

    @Test
    fun migrate2To3KeepsDevicesAsMainChannelAndAllowsFurtherChannels() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO devices (id, matterNodeId, displayName, roomId, vendorName, productName, favorite) VALUES ('d', 17, 'Relais', NULL, NULL, NULL, 0)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT displayName, endpointId FROM devices WHERE id = 'd'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Relais", c.getString(0))
                assertTrue(c.isNull(1)) // bestehendes Gerät = Hauptkanal
            }
            // Zweiter Kanal desselben Nodes ist erlaubt, derselbe Kanal zweimal nicht
            db.execSQL("INSERT INTO devices (id, matterNodeId, displayName, favorite, endpointId) VALUES ('d2', 17, 'Relais 2', 0, 2)")
            val duplicate = runCatching {
                db.execSQL("INSERT INTO devices (id, matterNodeId, displayName, favorite, endpointId) VALUES ('d3', 17, 'doppelt', 0, 2)")
            }
            assertTrue(duplicate.isFailure)
        }
    }

    /** Öffnet eine migrierte Datei mit Room selbst – deckt Abweichungen zwischen Migration und Entities auf. */
    @Test
    fun migratedDatabaseOpensWithRoom() {
        helper.createDatabase(TEST_DB, 1).close()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.databaseBuilder(ctx, RaumDatabase::class.java, TEST_DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        db.openHelper.writableDatabase.close()
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
