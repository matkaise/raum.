package app.raum.data.backup

import app.raum.i18n.Strings
import app.raum.R
import app.raum.BuildConfig
import app.raum.data.database.RaumDatabase
import app.raum.data.database.RoomHomeRepository
import app.raum.data.preferences.SettingsStore
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogLevel
import app.raum.matter.controller.MatterController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sicherung erstellen, prüfen und wiederherstellen (Spez. 7.10). */
class BackupService(
    private val repository: RoomHomeRepository,
    private val settings: SettingsStore,
    private val controller: MatterController,
    private val log: EventLog,
    private val strings: Strings,
) {
    /** Verschlüsselte Sicherung als Bytes (zum Schreiben auf USB/Netzwerkfreigabe über den Dateiauswahldialog). */
    suspend fun create(password: String): ByteArray = withContext(Dispatchers.Default) {
        val home = repository.home.value ?: error("no home configured")
        val doc = BackupMapper.toDocument(
            home = home,
            rooms = repository.rooms.value,
            devices = repository.deviceMetadata.value,
            scenes = repository.scenes.value,
            automations = repository.automations.value,
            settings = settings.toBackup(),
            appVersion = BuildConfig.VERSION_NAME,
            dbSchema = RaumDatabase.VERSION,
        )
        BackupCodec.encode(doc, password).also {
            log.info(LogCategory.SYSTEM, strings.get(R.string.log_backup_created, doc.devices.size, doc.scenes.size, doc.automations.size))
        }
    }

    /** Entschlüsseln und prüfen – noch ohne etwas zu verändern (BAK-004). */
    suspend fun inspect(bytes: ByteArray, password: String): RestorePlan = withContext(Dispatchers.Default) {
        val doc = BackupCodec.decode(bytes, password)
        BackupMapper.plan(doc, RaumDatabase.VERSION, controller.devices.value.keys)
    }

    suspend fun restore(plan: RestorePlan) {
        repository.replaceAll(plan.home, plan.rooms, plan.devices, plan.scenes, plan.automations)
        settings.restoreFrom(plan.settings)
        log.record(
            LogCategory.SYSTEM, if (plan.warnings.isEmpty()) LogLevel.INFO else LogLevel.WARNING,
            strings.get(R.string.log_backup_restored, plan.source.createdAt.take(10), plan.warnings.size),
        )
    }
}
