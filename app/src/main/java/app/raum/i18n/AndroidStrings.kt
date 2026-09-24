package app.raum.i18n

import android.content.Context

/** [Strings] aus den App-Ressourcen, immer in der aktuell gewählten App-Sprache. */
class AndroidStrings(private val app: Context, private val locales: LocaleController) : Strings {

    @Volatile private var cached: Pair<AppLanguage, Context>? = null

    private fun context(): Context {
        val lang = locales.current
        cached?.let { (l, ctx) -> if (l == lang) return ctx }
        return locales.wrap(app, lang).also { cached = lang to it }
    }

    /**
     * Ohne Argumente wird bewusst NICHT formatiert: `getString(id, *emptyArray())` ruft trotzdem
     * `String.format` auf und stürzt bei Texten mit „%“ ab (z. B. „über 70 % – bitte lüften“).
     */
    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context().getString(id) else context().getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        context().resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else args))
}
