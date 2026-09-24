package app.raum.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Recovery-Pfad für Datenbank-Migrationen und Updates (Spez. 11.4).
 *
 * Vor dem ersten Öffnen mit neuerem Schema wird eine konsistente Kopie der alten Datenbank
 * angelegt (`VACUUM INTO`). Schlägt eine Migration fehl oder zeigt sich später ein Fehler,
 * kann ein Techniker diesen Stand zurückspielen. Es werden die letzten [keep] Kopien behalten.
 */
object DatabaseSafety {

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun recoveryDir(context: Context) = File(context.noBackupFilesDir, "recovery").apply { mkdirs() }

    /** @return angelegte Kopie oder null, wenn keine Migration ansteht. */
    fun snapshotBeforeMigration(context: Context, name: String = RaumDatabase.NAME, targetVersion: Int = RaumDatabase.VERSION): File? {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return null
        val version = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrNull() ?: return null
        if (version == 0 || version >= targetVersion) return null
        return snapshot(context, name, "vor-migration-v$version")
    }

    /** Konsistente Kopie der laufenden Datenbank (z. B. vor einem Update). */
    fun snapshot(context: Context, name: String = RaumDatabase.NAME, label: String): File? {
        val source = context.getDatabasePath(name)
        if (!source.exists()) return null
        val dir = recoveryDir(context)
        val target = File(dir, "$name-$label-${LocalDateTime.now().format(STAMP)}.db")
        return runCatching {
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("VACUUM INTO ?", arrayOf(target.path))
            }
            prune(dir, name)
            target
        }.getOrNull()
    }

    fun snapshots(context: Context): List<File> =
        recoveryDir(context).listFiles()?.filter { it.name.endsWith(".db") }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun prune(dir: File, name: String, keep: Int = 3) {
        dir.listFiles()?.filter { it.name.startsWith(name) }?.sortedByDescending { it.lastModified() }
            ?.drop(keep)?.forEach { it.delete() }
    }
}
