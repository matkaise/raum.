package app.raum.matter

import app.raum.matter.commissioning.SetupCodeParser
import app.raum.matter.commissioning.SetupCodeParser.ParseResult
import app.raum.matter.commissioning.Verhoeff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCodeParserTest {

    private fun valid(code: String) = (SetupCodeParser.parse(code) as ParseResult.Valid).code

    @Test
    fun `decodes Matter default test code`() {
        // Passcode 20202021, Discriminator 3840 (kurz: 15) – Standard der Matter-Beispielgeräte
        val code = valid("34970112332")
        assertEquals(20202021L, code.passcode)
        assertEquals(15, code.shortDiscriminator)
        assertNull(code.vendorId)
    }

    @Test
    fun `accepts label formatting with dashes and spaces`() {
        assertEquals(20202021L, valid("3497-011-2332").passcode)
        assertEquals(20202021L, valid(" 3497 011 2332 ").passcode)
    }

    @Test
    fun `decodes other generated codes`() {
        assertEquals(12344321L, valid("12355307536").passcode)
        assertEquals(5, valid("12355307536").shortDiscriminator)
        assertEquals(24681357L, valid("23982115066").passcode)
        assertEquals(10, valid("23982115066").shortDiscriminator)
    }

    @Test
    fun `rejects wrong check digit`() {
        assertTrue(SetupCodeParser.parse("34970112333") is ParseResult.Invalid)
    }

    @Test
    fun `rejects wrong length`() {
        assertTrue(SetupCodeParser.parse("3497011233") is ParseResult.Invalid)
        assertTrue(SetupCodeParser.parse("") is ParseResult.Invalid)
    }

    @Test
    fun `rejects QR payload for now`() {
        assertTrue(SetupCodeParser.parse("MT:Y.K9042C00KA0648G00") is ParseResult.Invalid)
    }

    @Test
    fun `short code with VID flag set is inconsistent`() {
        // Chunk1 = 4 (VID/PID-Flag), aber nur 11 Stellen
        val body = "4970112330"
        val code = body + Verhoeff.checkDigit(body)
        assertTrue(SetupCodeParser.parse(code) is ParseResult.Invalid)
    }
}
