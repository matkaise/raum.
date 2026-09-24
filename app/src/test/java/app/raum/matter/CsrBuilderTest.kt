package app.raum.matter

import app.raum.matter.bridge.AttestationStore
import app.raum.matter.bridge.CsrBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Zertifikatsanforderung (PKCS#10) für das Gerätezertifikat der Bridge. */
class CsrBuilderTest {

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun csr(subject: javax.security.auth.x500.X500Principal = CsrBuilder.dacSubject("raum. Bridge 0011aabb", 0xFFF2, 0x8001)) =
        CsrBuilder.build(subject, keys.public) { data ->
            Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(data); sign() }
        }

    /** Einfacher DER-Leser: (Tag, Inhalt, Gesamtlänge) ab Position */
    private fun read(b: ByteArray, at: Int): Triple<Int, ByteArray, Int> {
        var i = at + 1
        var len = b[i].toInt() and 0xFF; i++
        if (len and 0x80 != 0) { val n = len and 0x7F; len = 0; repeat(n) { len = (len shl 8) or (b[i].toInt() and 0xFF); i++ } }
        return Triple(b[at].toInt() and 0xFF, b.copyOfRange(i, i + len), i + len - at)
    }

    @Test fun `Aufbau und Signatur`() {
        val der = csr()
        val (tag, body, total) = read(der, 0)
        assertEquals(0x30, tag)
        assertEquals(der.size, total)
        val (infoTag, _, infoLen) = read(body, 0)
        assertEquals(0x30, infoTag)
        val info = body.copyOfRange(0, infoLen)
        val (algTag, _, algLen) = read(body, infoLen)
        assertEquals(0x30, algTag)
        val (sigTag, sigBits, _) = read(body, infoLen + algLen)
        assertEquals(0x03, sigTag)
        assertEquals(0, sigBits[0].toInt()) // keine ungenutzten Bits
        val signature = sigBits.copyOfRange(1, sigBits.size)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(keys.public); update(info); verify(signature) })
        // öffentlicher Schlüssel steckt unverändert drin
        assertTrue(info.toList().windowed(keys.public.encoded.size).any { it == keys.public.encoded.toList() })
    }

    @Test fun `Matter-IDs im Namen`() {
        val subject = CsrBuilder.dacSubject("raum. Bridge, mit Komma", 0x1234, 0x0042)
        assertEquals(0x1234 to 0x0042, AttestationStore.matterIds(subject))
        assertTrue(subject.getName(javax.security.auth.x500.X500Principal.RFC1779).contains("raum. Bridge"))
    }

    @Test fun `OpenSSL akzeptiert die Anforderung`() {
        val openssl = listOf("/usr/bin/openssl", "/opt/homebrew/bin/openssl").firstOrNull { File(it).canExecute() } ?: return
        val f = File.createTempFile("raum", ".csr").apply { writeText(CsrBuilder.pem(csr())); deleteOnExit() }
        val p = ProcessBuilder(openssl, "req", "-in", f.path, "-verify", "-noout").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        assertTrue(out, out.contains("verify OK", ignoreCase = true) || out.isBlank())
    }
}
