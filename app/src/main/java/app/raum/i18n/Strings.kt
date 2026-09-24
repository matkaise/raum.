package app.raum.i18n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Zugriff auf übersetzte Texte außerhalb von Compose (Meldungen, Protokoll, Automations-Sätze,
 * Berichte, Benachrichtigungen). Die Oberfläche nutzt direkt `stringResource`.
 *
 * App: [AndroidStrings] (folgt der gewählten App-Sprache). Tests: liest die echten XML-Ressourcen.
 */
interface Strings {
    fun get(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}
