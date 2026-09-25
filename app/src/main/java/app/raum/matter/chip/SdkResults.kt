package app.raum.matter.chip

import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import chip.devicecontroller.model.Status
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Status eines Attribut-Schreibvorgangs. Eine Antwort heißt nicht Erfolg: Das Gerät kann den Wert ablehnen
 * (z. B. Sollwert außerhalb der Grenzen) – das meldet das SDK über denselben Rückruf.
 */
internal fun writeResult(status: Status): CommandResult = when (status.status) {
    Status.Code.Success -> CommandResult.Success
    Status.Code.Timeout -> CommandResult.Failure(CommandFailure.TIMEOUT, "rejected: $status")
    Status.Code.UnsupportedAttribute, Status.Code.UnsupportedWrite, Status.Code.UnsupportedCluster,
    Status.Code.UnsupportedEndPoint, Status.Code.UnsupportedAccess ->
        CommandResult.Failure(CommandFailure.UNSUPPORTED, "rejected: $status")
    else -> CommandResult.Failure(CommandFailure.DEVICE_ERROR, "rejected: $status")
}

/**
 * Nativer Gerätezeiger des SDK (OperationalDeviceProxy), nur innerhalb von [block] gültig. Jeder erhaltene Zeiger
 * wird genau einmal über [release] freigegeben – nach Gebrauch, bei Fehler oder Abbruch und auch, wenn die
 * Verbindung erst nach dem Timeout zustande kommt. null = Gerät nicht erreichbar.
 */
internal suspend fun <T : Any> withDevicePointer(
    timeoutMs: Long,
    connect: (onConnected: (Long) -> Unit, onFailure: () -> Unit) -> Unit,
    release: (Long) -> Unit,
    block: suspend (Long) -> T,
): T? {
    val ptr = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Long?> { cont ->
            connect(
                // Bereits abgebrochen (Timeout) oder zwischen Rückruf und Fortsetzung abgebrochen: sofort freigeben
                { p -> cont.resume(p) { _, value, _ -> value?.let(release) } },
                { if (cont.isActive) cont.resume(null) },
            )
        }
    } ?: return null
    try {
        return block(ptr)
    } finally {
        release(ptr)
    }
}
