package app.raum.i18n

import androidx.annotation.StringRes
import app.raum.R
import app.raum.data.backup.BackupCodec
import app.raum.data.backup.IncompatibleBackupException
import app.raum.data.backup.RestoreWarning
import app.raum.matter.commissioning.SetupCodeParser.InvalidReason
import app.raum.platform.update.UpdateRejectedException
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommissioningFailure
import app.raum.matter.bridge.BridgeResetException

/** Fehlergründe → übersetzte Texte (für Oberfläche, Meldungen und Protokoll). */
object ErrorTexts {
    @StringRes
    fun command(reason: CommandFailure): Int = when (reason) {
        CommandFailure.OFFLINE -> R.string.error_command_offline
        CommandFailure.TIMEOUT -> R.string.error_command_timeout
        CommandFailure.UNSUPPORTED -> R.string.error_command_unsupported
        CommandFailure.DEVICE_ERROR -> R.string.error_command_device
    }

    @StringRes
    fun commissioning(reason: CommissioningFailure): Int = when (reason) {
        CommissioningFailure.INVALID_CODE -> R.string.setup_code_invalid
        CommissioningFailure.DEVICE_NOT_FOUND -> R.string.error_commissioning_not_found
        CommissioningFailure.PASE_FAILED -> R.string.error_commissioning_pase
        CommissioningFailure.NETWORK_FAILED -> R.string.error_commissioning_network
        CommissioningFailure.TIMEOUT -> R.string.error_commissioning_timeout
        CommissioningFailure.ATTESTATION -> R.string.error_commissioning_attestation
        CommissioningFailure.ALREADY_PAIRED -> R.string.error_commissioning_already_paired
        CommissioningFailure.BLUETOOTH_UNAVAILABLE -> R.string.error_commissioning_bluetooth
        CommissioningFailure.NO_THREAD_NETWORK -> R.string.error_commissioning_no_thread
        CommissioningFailure.NO_WIFI_CREDENTIALS -> R.string.error_commissioning_no_wifi
        CommissioningFailure.THREAD_JOIN_FAILED -> R.string.error_commissioning_thread_join
        CommissioningFailure.WIFI_JOIN_FAILED -> R.string.error_commissioning_wifi_join
        CommissioningFailure.NOT_REACHABLE -> R.string.error_commissioning_not_reachable
    }

    @StringRes
    fun setupCode(reason: InvalidReason): Int = when (reason) {
        InvalidReason.QR_NOT_SUPPORTED -> R.string.setup_code_qr_unsupported
        InvalidReason.WRONG_LENGTH -> R.string.setup_code_wrong_length
        InvalidReason.CHECK_DIGIT -> R.string.setup_code_check_digit
        InvalidReason.INCONSISTENT -> R.string.setup_code_inconsistent
        InvalidReason.INVALID -> R.string.setup_code_invalid
        InvalidReason.FORBIDDEN_PASSCODE -> R.string.setup_code_forbidden_passcode
    }

    /** Nutzertext für Fehler bei Sicherung, Wiederherstellung und Update; null = unbekannter Fehler. */
    fun maintenance(error: Throwable, strings: Strings): String? = when (error) {
        is BackupCodec.DecodeError.NotABackup -> strings.get(R.string.backup_error_not_backup)
        is BackupCodec.DecodeError.UnsupportedVersion -> strings.get(R.string.backup_error_unsupported, error.version)
        is BackupCodec.DecodeError.WrongPasswordOrCorrupt -> strings.get(R.string.backup_error_password)
        is BackupCodec.DecodeError.InvalidContent -> strings.get(R.string.backup_error_content, error.detail)
        is IncompatibleBackupException -> when (error.reason) {
            IncompatibleBackupException.Reason.NEWER_FORMAT, IncompatibleBackupException.Reason.NEWER_SCHEMA ->
                strings.get(R.string.backup_error_newer)
            IncompatibleBackupException.Reason.INVALID -> strings.get(R.string.backup_error_content, error.message ?: "")
        }
        is UpdateRejectedException -> when (error.reason) {
            UpdateRejectedException.Reason.UNREADABLE -> strings.get(R.string.update_error_unreadable)
            UpdateRejectedException.Reason.NOT_AN_APK -> strings.get(R.string.update_error_not_apk)
            UpdateRejectedException.Reason.WRONG_PACKAGE -> strings.get(R.string.update_error_package, error.message ?: "")
            UpdateRejectedException.Reason.WRONG_SIGNATURE -> strings.get(R.string.update_error_signature)
            UpdateRejectedException.Reason.NOT_NEWER -> strings.get(R.string.update_error_not_newer, error.message ?: "")
        }
        is BridgeResetException -> strings.get(R.string.reset_error_bridge)
        else -> null
    }

    fun restoreWarning(w: RestoreWarning, strings: Strings): String = when (w) {
        is RestoreWarning.InvalidDevices -> strings.plural(R.plurals.restore_warning_invalid_devices, w.count)
        is RestoreWarning.LostRooms -> strings.plural(R.plurals.restore_warning_lost_rooms, w.count)
        is RestoreWarning.DroppedSceneActions -> strings.plural(R.plurals.restore_warning_scene_actions, w.count)
        is RestoreWarning.DisabledAutomations -> strings.plural(R.plurals.restore_warning_automations, w.count)
        is RestoreWarning.MissingFromFabric -> strings.plural(R.plurals.restore_warning_fabric, w.count)
    }
}
