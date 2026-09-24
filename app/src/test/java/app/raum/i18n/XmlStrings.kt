package app.raum.i18n

import app.raum.R
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Strings] für JVM-Tests: liest die echten `strings.xml` (inkl. Plurals) und formatiert wie Android.
 * So prüfen Unit-Tests die tatsächlichen Übersetzungen statt Kopien davon.
 */
class XmlStrings(val language: String) : Strings {

    data class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val res = load(language)
    private val locale = if (language == "en") Locale.ENGLISH else Locale.forLanguageTag(language)
    private val stringNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }

    override fun get(id: Int, vararg args: Any): String {
        val name = stringNames[id] ?: error("Unbekannte String-ID $id")
        val template = res.strings[name] ?: error("„$name“ fehlt in values-$language")
        return if (args.isEmpty()) template else String.format(locale, template, *args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val name = pluralNames[id] ?: error("Unbekannte Plural-ID $id")
        val forms = res.plurals[name] ?: error("Plural „$name“ fehlt in values-$language")
        val template = (if (count == 1) forms["one"] else forms["other"]) ?: forms.getValue("other")
        return String.format(locale, template, *(if (args.isEmpty()) arrayOf<Any>(count) else args))
    }

    companion object {
        private fun resDir(): File {
            // Gradle startet Tests im Modulverzeichnis (app/)
            val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
            return candidates.firstOrNull { it.isDirectory } ?: error("res-Verzeichnis nicht gefunden")
        }

        fun file(language: String): File = File(resDir(), if (language == "en") "values/strings.xml" else "values-$language/strings.xml")

        fun load(language: String): Resources {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(language))
            val strings = mutableMapOf<String, String>()
            val plurals = mutableMapOf<String, Map<String, String>>()
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                            val item = items.item(j) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                    }
                }
            }
            return Resources(strings, plurals)
        }

        /** Android-Escapes in Ressourcen: \' \" \n \t \\ und umschließende Anführungszeichen. */
        fun unescape(raw: String): String {
            var s = raw
            if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); else -> out.append(n)
                    }
                    i += 2
                } else { out.append(c); i++ }
            }
            return out.toString()
        }
    }
}
