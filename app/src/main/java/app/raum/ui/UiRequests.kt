package app.raum.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Einmalige Navigationswünsche über Bildschirmgrenzen hinweg (z. B. Einrichtung → „Gerät hinzufügen“). */
class UiRequests {
    val openCommissioning = MutableStateFlow(false)
    /** Einstellungen in einer bestimmten Kategorie öffnen (Name aus SettingsSection), z. B. nach einem Kopplungsfehler. */
    val openSettings = MutableStateFlow<String?>(null)
}
