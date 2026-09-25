package app.raum.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (M4):
 * - automations: Textzusammenfassung ersetzt durch echte Definition (Trigger/Bedingungen/Aktionen als JSON).
 *   Bestehende Einträge behalten ID und Namen, sind aber unvollständig und werden daher deaktiviert.
 * - event_log: neues persistentes Ereignisprotokoll.
 *
 * Das SQL entspricht exakt `app/schemas/.../2.json` (geprüft in RaumDatabaseMigrationTest).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `automations_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, `triggersJson` TEXT NOT NULL DEFAULT '[]', " +
                "`conditionsJson` TEXT NOT NULL DEFAULT '[]', `actionsJson` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`id`))"
        )
        db.execSQL("INSERT INTO `automations_new` (`id`, `name`, `enabled`) SELECT `id`, `name`, 0 FROM `automations`")
        db.execSQL("DROP TABLE `automations`")
        db.execSQL("ALTER TABLE `automations_new` RENAME TO `automations`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, `category` TEXT NOT NULL, `level` TEXT NOT NULL, `message` TEXT NOT NULL, " +
                "`deviceId` TEXT, `deviceName` TEXT, `automationId` TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_log_timestampMs` ON `event_log` (`timestampMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_log_category` ON `event_log` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_log_deviceId` ON `event_log` (`deviceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_log_automationId` ON `event_log` (`automationId`)")
    }
}

/**
 * v2 → v3 (Mehrkanalgeräte): weitere Kanäle eines Nodes sind eigene Geräte mit `endpointId`; bestehende Geräte
 * bleiben Hauptkanal (NULL). Eindeutig ist jetzt (Node, Kanal) statt nur der Node.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `devices` ADD COLUMN `endpointId` INTEGER")
        db.execSQL("DROP INDEX IF EXISTS `index_devices_matterNodeId`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_devices_matterNodeId_endpointId` ON `devices` (`matterNodeId`, `endpointId`)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
