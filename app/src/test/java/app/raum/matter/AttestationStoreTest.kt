package app.raum.matter

import app.raum.matter.bridge.AttestationStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal

/** Hilfsfunktionen des Echtheitsnachweises: Matter-Kennungen im Zertifikat, Signaturformat. */
class AttestationStoreTest {

    @Test fun `Hersteller- und Produkt-ID aus dem Zertifikatsnamen`() {
        // So legt chip-cert die IDs ab: UTF8String mit Hex-Text
        val name = X500Principal("CN=raum. Bridge 1, 1.3.6.1.4.1.37244.2.1=#0C0446464632, 1.3.6.1.4.1.37244.2.2=#0C0438303031")
        assertEquals(0xFFF2 to 0x8001, AttestationStore.matterIds(name))
        assertEquals(null to null, AttestationStore.matterIds(X500Principal("CN=ohne")))
    }

    @Test fun `ECDSA-Signatur von DER in roh (r und s je 32 Byte)`() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(20) { i ->
            val msg = "Nachricht $i".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(msg); sign() }
            val raw = AttestationStore.derToRaw(der)
            assertEquals(64, raw.size)
            // zurück nach DER und mit dem öffentlichen Schlüssel prüfen
            fun derInt(b: ByteArray) = BigInteger(1, b).toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
            val body = derInt(raw.copyOfRange(0, 32)) + derInt(raw.copyOfRange(32, 64))
            val reDer = byteArrayOf(0x30, body.size.toByte()) + body
            assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(keys.public); update(msg); verify(reDer) })
        }
        // Kurze Zahlen werden links mit Nullen aufgefüllt
        val short = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x05, 0x02, 0x01, 0x07)
        assertArrayEquals(ByteArray(31) + 0x05 + ByteArray(31) + 0x07, AttestationStore.derToRaw(short))
    }
}
