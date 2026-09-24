package app.raum.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import app.raum.R
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Unterstützte App-Sprachen (ONB-003). Weitere Sprachen: Eintrag + `values-xx/strings.xml`. */
enum class AppLanguage(val tag: String?, @StringRes val labelRes: Int) {
    SYSTEM(null, R.string.language_system),
    DE("de", R.string.language_de),
    EN("en", R.string.language_en),
}

/**
 * App-eigene Sprache unabhängig von der Systemsprache des Panels (die Firmware ist oft nur
 * Englisch/Chinesisch). Nicht unterstützte Systemsprachen fallen auf Englisch (`values/`) zurück.
 */
class LocaleController(private val language: StateFlow<AppLanguage>) {

    val current: AppLanguage get() = language.value
    val languageFlow: StateFlow<AppLanguage> get() = language

    fun locale(lang: AppLanguage = language.value): Locale =
        lang.tag?.let(Locale::forLanguageTag) ?: Resources.getSystem().configuration.locales[0]

    /** Kontext mit der gewählten Sprache (für Activity und Texte außerhalb der Oberfläche). */
    fun wrap(base: Context, lang: AppLanguage = language.value): Context {
        val locale = locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }
}
