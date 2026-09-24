package app.raum.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Beide Sprachen müssen dieselben Schlüssel und dieselben Platzhalter haben. */
class TranslationCompletenessTest {

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxX%]""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test
    fun `german and english have the same keys`() {
        val en = XmlStrings.load("en")
        val de = XmlStrings.load("de")
        val missingInDe = en.strings.keys - de.strings.keys - setOf("app_name")
        val missingInEn = de.strings.keys - en.strings.keys
        assertTrue("Fehlt in values-de: $missingInDe", missingInDe.isEmpty())
        assertTrue("Fehlt in values (en): $missingInEn", missingInEn.isEmpty())
        assertEquals("Plurals unterschiedlich", en.plurals.keys, de.plurals.keys)
    }

    @Test
    fun `placeholders match between languages`() {
        val en = XmlStrings.load("en")
        val de = XmlStrings.load("de")
        val mismatches = en.strings.keys.intersect(de.strings.keys).filter { k ->
            placeholders(en.strings.getValue(k)) != placeholders(de.strings.getValue(k))
        }
        assertTrue("Platzhalter unterschiedlich: $mismatches", mismatches.isEmpty())
        val pluralMismatches = en.plurals.keys.intersect(de.plurals.keys).filter { k ->
            placeholders(en.plurals.getValue(k).getValue("other")) != placeholders(de.plurals.getValue(k).getValue("other"))
        }
        assertTrue("Plural-Platzhalter unterschiedlich: $pluralMismatches", pluralMismatches.isEmpty())
    }

    @Test
    fun `no empty translations`() {
        listOf("en", "de").forEach { lang ->
            val empty = XmlStrings.load(lang).strings.filterValues { it.isBlank() }.keys
            assertTrue("Leere Texte in $lang: $empty", empty.isEmpty())
        }
    }

    /**
     * Texte mit einem einzelnen „%“ ohne Platzhalter dürfen nur ohne Argumente abgerufen werden –
     * `Strings.get` formatiert dann nicht. Hier wird sichergestellt, dass sie als nicht-formatiert markiert sind.
     */
    @Test
    fun `texts with bare percent sign are not formatted`() {
        listOf("en", "de").forEach { lang ->
            val raw = XmlStrings.file(lang).readText()
            val offenders = Regex("""<string name="([^"]+)"(?![^>]*formatted="false")[^>]*>([^<]*)</string>""").findAll(raw)
                .filter { m -> val t = m.groupValues[2]; "%" in t && !Regex("""%(\d+\$)?[sdf]""").containsMatchIn(t) && "%%" !in t }
                .map { it.groupValues[1] }.toList()
            assertTrue("Unformatierte %-Texte ohne formatted=false in $lang: $offenders", offenders.isEmpty())
        }
    }
}
