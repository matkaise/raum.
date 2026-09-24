package app.raum.ui.appliance

import app.raum.i18n.ErrorTexts
import app.raum.i18n.Strings
import app.raum.R
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.MainActivity
import app.raum.data.backup.BackupService
import app.raum.data.backup.RestorePlan
import app.raum.data.database.DatabaseSafety
import app.raum.data.database.LogFilter
import app.raum.data.database.PersistentEventLog
import app.raum.data.reset.ResetMode
import app.raum.data.reset.ResetService
import app.raum.diagnostics.Reports
import app.raum.domain.repositories.HomeRepository
import app.raum.domain.usecases.DeviceService
import app.raum.domain.usecases.UiMessageBus
import app.raum.matter.controller.MatterController
import app.raum.matter.controller.mock.MockMatterController
import app.raum.platform.kiosk.KioskManager
import app.raum.platform.update.UpdateCandidate
import app.raum.platform.update.UpdateInstaller
import app.raum.security.AdminPinStore
import app.raum.security.AdminPinStore.VerifyResult
import app.raum.security.MaintenanceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/** Datenaktionen im Wartungsmodus: Sicherung, Wiederherstellung, Export, Update, Zurücksetzen (M7). */
class DataMaintenanceViewModel(
    private val context: Application,
    private val backup: BackupService,
    private val reset: ResetService,
    private val updates: UpdateInstaller,
    private val repository: HomeRepository,
    private val devices: DeviceService,
    private val eventLog: PersistentEventLog,
    private val kiosk: KioskManager,
    private val pins: AdminPinStore,
    private val session: MaintenanceSession,
    private val messages: UiMessageBus,
    private val strings: Strings,
    controller: MatterController,
) : ViewModel() {

    val isMock = controller is MockMatterController

    private val _busy = MutableStateFlow<Int?>(null)
    /** Laufende Aktion (Ressourcen-ID des Textes) oder null. */
    val busy: StateFlow<Int?> = _busy.asStateFlow()

    private val _restorePlan = MutableStateFlow<RestorePlan?>(null)
    val restorePlan: StateFlow<RestorePlan?> = _restorePlan.asStateFlow()

    private val _update = MutableStateFlow<UpdateCandidate?>(null)
    val update: StateFlow<UpdateCandidate?> = _update.asStateFlow()

    val recoveryPoints: Int get() = DatabaseSafety.snapshots(context).size

    private fun run(label: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = label
            session.touch()
            try {
                block()
            } catch (e: Exception) {
                messages.error(ErrorTexts.maintenance(e, strings) ?: strings.get(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
            } finally {
                _busy.value = null; session.touch()
            }
        }
    }

    private suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("target not writable")
    }

    // --- Sicherung ---------------------------------------------------------------

    fun createBackup(uri: Uri, password: String) = run(R.string.busy_backup_create) {
        write(uri, backup.create(password))
        messages.info(strings.get(R.string.msg_backup_saved))
    }

    fun inspectBackup(uri: Uri, password: String) = run(R.string.busy_backup_check) {
        val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            ?: throw app.raum.platform.update.UpdateRejectedException(app.raum.platform.update.UpdateRejectedException.Reason.UNREADABLE)
        // Falsches Passwort, fremde Datei oder neuere Version → Meldung über run()
        _restorePlan.value = backup.inspect(bytes, password)
    }

    fun dismissRestore() { _restorePlan.value = null }

    fun restore() {
        val plan = _restorePlan.value ?: return
        _restorePlan.value = null
        run(R.string.busy_restore) {
            backup.restore(plan)
            messages.info(strings.get(R.string.msg_backup_restored))
        }
    }

    // --- Export -----------------------------------------------------------------

    private fun system() = Reports.SystemInfo(Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL)

    fun exportHandover(uri: Uri) = run(R.string.busy_handover) {
        write(uri, Reports.handover(repository.rooms.value, devices.devices.value, system(), strings).toByteArray())
        messages.info(strings.get(R.string.msg_handover_saved))
    }

    fun exportDiagnostics(uri: Uri) = run(R.string.busy_diagnostics) {
        val log = eventLog.observe(LogFilter(limit = 2000)).first()
        val text = Reports.diagnostics(
            system(), kiosk.status(), devices.devices.value,
            repository.rooms.value.size, repository.scenes.value.size, repository.automations.value.size, log, strings,
            extra = mapOf("Recovery points" to recoveryPoints.toString(), "Admin PIN set" to pins.isSet.toString()),
        )
        write(uri, text.toByteArray())
        messages.info(strings.get(R.string.msg_diagnostics_saved))
    }

    // --- Update -------------------------------------------------------------------

    fun inspectUpdate(uri: Uri) = run(R.string.busy_update_check) {
        _update.value = updates.inspect(uri)
    }

    fun dismissUpdate() { _update.value = null }

    fun installUpdate() {
        val c = _update.value ?: return
        _update.value = null
        run(R.string.busy_update_install) { updates.install(c) }
    }

    // --- Zurücksetzen -------------------------------------------------------------

    fun verifyPin(pin: String): VerifyResult = pins.verify(pin)
    val pinSet: Boolean get() = pins.isSet

    /** Nach bestätigter PIN (RST-003). */
    fun reset(activity: Activity, mode: ResetMode) = run(R.string.busy_reset) {
        reset.reset(mode)
        restart(activity)
    }


    private fun restart(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finishAffinity()
        exitProcess(0)
    }
}
