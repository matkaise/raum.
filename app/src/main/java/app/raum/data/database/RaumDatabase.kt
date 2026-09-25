package app.raum.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Lokale Anwendungsdatenbank (Spez. 10.3).
 *
 * Versionierung: Jede Schemaänderung erhöht [version] und bringt eine Migration mit
 * (`AutoMigration` oder manuell). Die Schemata liegen unter `app/schemas/` und werden
 * in `RaumDatabaseMigrationTest` geprüft. Destruktive Migrationen sind nicht erlaubt.
 */
@Database(
    entities = [
        HomeEntity::class,
        RoomEntity::class,
        DeviceEntity::class,
        SceneEntity::class,
        SceneActionEntity::class,
        AutomationEntity::class,
        EventLogEntity::class,
    ],
    version = RaumDatabase.VERSION,
    exportSchema = true,
)
abstract class RaumDatabase : RoomDatabase() {
    abstract fun homeDao(): HomeDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        const val NAME = "raum.db"
        const val VERSION = 3

        fun create(context: Context): RaumDatabase {
            DatabaseSafety.snapshotBeforeMigration(context)
            return Room.databaseBuilder(context, RaumDatabase::class.java, NAME)
                // WAL: Schreibvorgänge sind atomar und überstehen Stromausfälle (Spez. 12.2).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(object : Callback() {
                    // Gelöschte Inhalte werden mit Nullen überschrieben statt nur freigegeben.
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.query("PRAGMA secure_delete = ON").close()
                    }
                })
                .build()
        }
    }
}
