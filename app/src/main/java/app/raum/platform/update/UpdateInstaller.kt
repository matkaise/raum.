package app.raum.platform.update

import app.raum.i18n.Strings
import app.raum.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import app.raum.data.database.DatabaseSafety
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.diagnostics.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.security.MessageDigest

data class UpdateCandidate(val file: File, val versionName: String, val versionCode: Long, val currentVersionName: String)

class UpdateRejectedException(val reason: Reason, detail: String = "") : Exception(detail) {
    enum class Reason { UNREADABLE, NOT_AN_APK, WRONG_PACKAGE, WRONG_SIGNATURE, NOT_NEWER }
}

/**
 * Lokale, signierte Updates (Spez. 11.4, offene Entscheidung 6: „lokale Datei“).
 *
 * - Nur APKs mit gleichem Paketnamen und **identischem Signaturzertifikat** (sonst Ablehnung).
 * - Nur höhere Versionen – Android erlaubt ohnehin kein Downgrade mit Datenerhalt.
 * - Vor der Installation: konsistenter Datenbank-Sicherungspunkt ([DatabaseSafety]).
 * - Als Device Owner ohne Rückfrage; die Integrität prüft zusätzlich Android selbst beim Installieren.
 */
class UpdateInstaller(private val context: Context, private val log: EventLog, private val strings: Strings) {

    private val pm = context.packageManager

    /** Kopiert die gewählte Datei in den Cache und prüft sie. */
    suspend fun inspect(uri: Uri): UpdateCandidate = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw UpdateRejectedException(UpdateRejectedException.Reason.UNREADABLE)
        val archive: PackageInfo = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateRejectedException(UpdateRejectedException.Reason.NOT_AN_APK)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

        if (archive.packageName != context.packageName) {
            throw UpdateRejectedException(UpdateRejectedException.Reason.WRONG_PACKAGE, archive.packageName)
        }
        if (fingerprints(archive) != fingerprints(installed) || fingerprints(archive).isEmpty()) {
            throw UpdateRejectedException(UpdateRejectedException.Reason.WRONG_SIGNATURE)
        }
        if (archive.longVersionCode <= installed.longVersionCode) {
            throw UpdateRejectedException(UpdateRejectedException.Reason.NOT_NEWER, "${archive.versionName} <= ${installed.versionName}")
        }
        UpdateCandidate(file, archive.versionName ?: "?", archive.longVersionCode, installed.versionName ?: "?")
    }

    /** Installiert ein geprüftes Update. Der Prozess wird danach vom System neu gestartet. */
    suspend fun install(candidate: UpdateCandidate) = withContext(Dispatchers.IO) {
        DatabaseSafety.snapshot(context, label = "vor-update-${candidate.currentVersionName}")
        log.info(LogCategory.SYSTEM, strings.get(R.string.log_update_installing, candidate.currentVersionName, candidate.versionName))
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Ab Android 12 explizit ohne Rückfrage (als Device Owner ohnehin still)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("raum.apk", 0, candidate.file.length()).use { out ->
                candidate.file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    /** SHA-256 der aktuellen Signaturzertifikate. Schlüsselrotation wird bewusst nicht unterstützt. */
    private fun fingerprints(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
}

/** Ergebnis der Installation ins Protokoll (bei Erfolg startet raum. über MY_PACKAGE_REPLACED neu). */
class UpdateStatusReceiver : BroadcastReceiver(), KoinComponent {
    private val log: EventLog by inject()
    private val strings: Strings by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> log.info(LogCategory.SYSTEM, strings.get(R.string.log_update_success))
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Ohne Device Owner: Android fragt den Nutzer
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            else -> log.record(LogCategory.SYSTEM, LogLevel.ERROR, strings.get(R.string.log_update_failed, message ?: status.toString()))
        }
    }
}
