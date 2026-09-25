package app.raum.data.reset

import app.raum.matter.controller.AdminFabric
import app.raum.matter.controller.MatterController
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Ergebnis des Rechteentzugs für ein Gerät. */
sealed interface RevocationOutcome {
    val ok: Boolean get() = false

    /** Keine fremden Apps (mehr) – [removed] sind die jetzt entfernten. */
    data class Revoked(val removed: List<AdminFabric>) : RevocationOutcome { override val ok get() = true }

    /** Gerät nicht erreichbar oder Liste nicht lesbar – ob fremde Apps Zugriff haben, ist unbekannt. */
    data object Unreachable : RevocationOutcome

    /** Entzug abgelehnt oder nicht bestätigt – diese Apps haben weiterhin Zugriff (oder es ist unklar). */
    data class Failed(val remaining: List<AdminFabric>, val unconfirmed: Boolean = false) : RevocationOutcome
}

data class DeviceRevocation(val nodeId: ULong, val outcome: RevocationOutcome)

/** Ergebnis der Übergabe-Vorbereitung je Gerät (RST-005). */
data class HandoverReport(val devices: List<DeviceRevocation>) {
    val complete: Boolean get() = devices.all { it.outcome.ok }
    val problems: List<DeviceRevocation> get() = devices.filterNot { it.outcome.ok }
    val revokedCount: Int get() = devices.sumOf { (it.outcome as? RevocationOutcome.Revoked)?.removed?.size ?: 0 }
}

/**
 * Entzieht allen anderen Apps (Apple Home, Google Home …) den Zugriff auf die Geräte – solange raum. selbst noch
 * Administrator ist. Arbeitet mit einer frisch gelesenen Liste je Gerät (nicht dem zwischengespeicherten Stand)
 * und bestätigt den Entzug durch erneutes Lesen: RemoveFabric meldet Fehler teils nur in der Antwort.
 */
class AdminRevocation(private val controller: MatterController) {

    suspend fun run(nodes: Collection<ULong>): HandoverReport = coroutineScope {
        // Parallel: nicht erreichbare Geräte brauchen jeweils bis zum Verbindungs-Timeout
        HandoverReport(nodes.map { node -> async { DeviceRevocation(node, revoke(node)) } }.awaitAll())
    }

    private suspend fun revoke(node: ULong): RevocationOutcome {
        val before = controller.readAdmins(node) ?: return RevocationOutcome.Unreachable
        // Ohne erkennbare eigene Fabric keine Entscheidung – sonst entfernt raum. womöglich sich selbst
        if (before.none { it.own }) return RevocationOutcome.Unreachable
        val foreign = before.filterNot { it.own }
        if (foreign.isEmpty()) return RevocationOutcome.Revoked(emptyList())

        foreign.forEach { controller.removeAdmin(node, it.fabricIndex) }

        val after = controller.readAdmins(node) ?: return RevocationOutcome.Failed(foreign, unconfirmed = true)
        val remaining = after.filterNot { it.own }
        return if (remaining.isEmpty()) RevocationOutcome.Revoked(foreign) else RevocationOutcome.Failed(remaining)
    }
}
