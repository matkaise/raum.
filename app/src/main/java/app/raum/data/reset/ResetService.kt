package app.raum.data.reset

import app.raum.matter.bridge.MatterBridge
import app.raum.data.weather.WeatherService
import android.content.Context
import app.raum.data.database.DatabaseSafety
import app.raum.data.database.RoomHomeRepository
import app.raum.data.preferences.SettingsStore
import app.raum.diagnostics.EventLog
import app.raum.matter.controller.MatterController
import app.raum.security.AdminPinStore
import app.raum.thread.NetworkCredentialStore
import java.io.File

enum class ResetMode {
    /** RST-005: Zuhause, Räume und Grundeinstellungen bleiben; persönliche Daten und Integrationen gehen. */
    HANDOVER,
    /** RST-001/002: alles wird gelöscht, raum. startet wie neu. */
    FULL,
}

/** Die Übergabe wurde nicht abgeschlossen: Nicht alle fremden Zugriffe sind bestätigt entzogen. */
class HandoverIncompleteException(val report: HandoverReport) : IllegalStateException("handover incomplete")

/**
 * Werksreset und Wohnungsübergabe (Spez. 7.11). Aufrufer müssen vorher die Administrator-PIN
 * geprüft haben (RST-003). Nach dem Reset ist ein Neustart des Prozesses nötig.
 *
 * Übergabe in zwei Schritten: [revokeForeignAdmins] entzieht anderen Apps den Zugriff und meldet das Ergebnis je
 * Gerät; erst danach löscht [reset] die eigenen Schlüssel. Ohne sie ließe sich ein verbliebener fremder Zugriff
 * später nicht mehr entziehen – deshalb nur bei vollständigem Ergebnis oder ausdrücklich unvollständig.
 */
class ResetService(
    private val context: Context,
    private val repository: RoomHomeRepository,
    private val controller: MatterController,
    private val settings: SettingsStore,
    private val pins: AdminPinStore,
    @Suppress("unused") private val log: EventLog,
    private val weather: WeatherService,
    private val bridge: MatterBridge,
    private val credentials: NetworkCredentialStore,
) {
    /**
     * Übergabe, Schritt 1: allen anderen Apps (Apple Home, Google Home …) den Zugriff auf alle Geräte entziehen –
     * sonst könnten Vormieter die Geräte weiter steuern. Geht nur, solange raum. noch Admin ist. Ändert sonst nichts.
     */
    suspend fun revokeForeignAdmins(): HandoverReport = AdminRevocation(controller).run(controller.devices.value.keys)

    /**
     * @param handover Ergebnis von [revokeForeignAdmins] (Pflicht bei [ResetMode.HANDOVER]).
     * @param acceptIncomplete Nutzer hat ausdrücklich bestätigt, trotz verbliebener/ungeprüfter Zugriffe abzuschließen.
     * @throws HandoverIncompleteException Übergabe ohne vollständiges Ergebnis und ohne Bestätigung – nichts gelöscht.
     */
    suspend fun reset(mode: ResetMode, handover: HandoverReport? = null, acceptIncomplete: Boolean = false) {
        // Bewusst kein Protokolleintrag: er würde asynchron nach dem Löschen geschrieben.
        if (mode == ResetMode.HANDOVER) {
            val report = handover ?: throw HandoverIncompleteException(HandoverReport(emptyList()))
            if (!report.complete && !acceptIncomplete) throw HandoverIncompleteException(report)
        }
        // Bridge: alle Kopplungen anderer Apps lösen, Bridge aus (neue Bewohner entscheiden selbst). Zuerst die
        // Einstellung, damit sie nicht wieder startet. Schlägt der Reset fehl, bricht alles ab – noch ist nichts gelöscht.
        settings.setBridgeEnabled(false)
        bridge.reset()
        settings.setBridgeExcluded(emptySet())
        // 1. Eigene Matter-Fabric auflösen (RST-002) – vor dem Löschen der Daten.
        controller.resetFabric()

        // 2. Konfiguration und Protokoll
        repository.erase(keepRooms = mode == ResetMode.HANDOVER)

        // 3. Zugangsdaten und Einstellungen
        pins.clear()
        // Thread-Dataset und WLAN für neue Geräte gehören den bisherigen Bewohnern
        credentials.clearAll()
        // Vorhersage enthält den (gerundeten) Standort – bei beiden Varianten entfernen
        weather.clearCache()
        if (mode == ResetMode.FULL) {
            settings.clearAll()
            listOf(File(context.filesDir, "last_crash.txt")).forEach { it.delete() }
            DatabaseSafety.recoveryDir(context).deleteRecursively()
            context.cacheDir.deleteRecursively()
        } else {
            // Übergabe: neue Bewohner durchlaufen die Einrichtung (Sprache, Name, PIN, Fabric) –
            // Zuhause-Name und Räume sind dabei schon vorhanden.
            settings.restartOnboarding()
        }
    }
}
