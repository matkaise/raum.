package app.raum.matter

import app.raum.matter.commissioning.SetupCodeGenerator
import app.raum.matter.commissioning.SetupCodeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Kopplungscodes für Multi-Admin – geprüft gegen die Testwerte des Matter-SDK (Diskriminator 3840, PIN 20202021). */
class SetupCodeGeneratorTest {

    @Test fun `manueller Code entspricht dem Referenzgerät`() {
        assertEquals("34970112332", SetupCodeGenerator.manualCode(3840, 20202021))
        assertEquals("3497-011-2332", SetupCodeGenerator.formatManual("34970112332"))
    }

    @Test fun `QR-Inhalt entspricht dem Referenzgerät`() {
        // Beispielgerät des Matter-SDK: VID 0xFFF1, PID 0x8001, BLE
        assertEquals(
            "MT:-24J042C00KA0648G00",
            SetupCodeGenerator.qrPayload(0xFFF1, 0x8001, 3840, 20202021, discovery = SetupCodeGenerator.DISCOVERY_BLE),
        )
    }

    @Test fun `erzeugte Codes sind gültig und lassen sich zurücklesen`() {
        val r = Random(42)
        repeat(500) {
            val d = SetupCodeGenerator.randomDiscriminator(r)
            val p = SetupCodeGenerator.randomPasscode(r)
            val parsed = SetupCodeParser.parse(SetupCodeGenerator.manualCode(d, p))
            assertTrue(parsed.toString(), parsed is SetupCodeParser.ParseResult.Valid)
            val code = (parsed as SetupCodeParser.ParseResult.Valid).code
            assertEquals(d shr 8, code.shortDiscriminator)
            assertEquals(p, code.passcode)
        }
    }
}
