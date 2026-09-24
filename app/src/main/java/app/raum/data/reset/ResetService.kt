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

/**
 * Werksreset und Wohnungsübergabe (Spez. 7.11). Aufrufer müssen vorher die Administrator-PIN
 * geprüft haben (RST-003). Nach dem Reset ist ein Neustart des Prozesses nötig.
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
    suspend fun reset(mode: ResetMode) {
        // Bewusst kein Protokolleintrag: er würde asynchron nach dem Löschen geschrieben.
        // 0. Übergabe: andere Apps (Apple Home, Google Home …) verlieren den Zugriff – sonst könnten
        //    Vormieter die Geräte weiter steuern. Geht nur, solange raum. noch Admin ist.
        if (mode == ResetMode.HANDOVER) {
            controller.adminFabrics.value.forEach { (node, admins) ->
                admins.filterNot { it.own }.forEach { runCatching { controller.removeAdmin(node, it.fabricIndex) } }
            }
        }
        // Bridge: alle Kopplungen anderer Apps lösen, Bridge aus (neue Bewohner entscheiden selbst)
        runCatching { bridge.reset(); bridge.stop() }
        settings.setBridgeEnabled(false)
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
