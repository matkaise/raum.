package app.raum.matter.bridge

import java.io.ByteArrayOutputStream
import java.security.PublicKey
import java.util.Base64
import javax.security.auth.x500.X500Principal

/**
 * Zertifikatsanforderung (PKCS#10, RFC 2986) für das Gerätezertifikat (DAC) der Bridge – ohne Fremdbibliothek.
 * Signiert wird von außen ([sign]), damit der private Schlüssel im Android Keystore bleibt.
 */
object CsrBuilder {

    /** Matter-Attribute im Namen: Hersteller- und Produkt-ID als Hex-Text (UTF8String), wie es PKI-Anbieter erwarten. */
    fun dacSubject(commonName: String, vendorId: Int, productId: Int): X500Principal {
        fun utf8(text: String): String {
            val b = text.toByteArray(Charsets.UTF_8)
            return "#0C%02X".format(b.size) + b.joinToString("") { "%02X".format(it) }
        }
        val cn = commonName.replace("\\", "\\\\").replace(",", "\\,").replace("+", "\\+").replace("=", "\\=")
        return X500Principal(
            "CN=$cn, 1.3.6.1.4.1.37244.2.1=${utf8("%04X".format(vendorId))}, 1.3.6.1.4.1.37244.2.2=${utf8("%04X".format(productId))}",
        )
    }

    /**
     * @param publicKey öffentlicher Schlüssel (X.509-SubjectPublicKeyInfo, z. B. aus dem Keystore)
     * @param sign ECDSA mit SHA-256 über die übergebenen Bytes, Ergebnis DER-kodiert
     * @return CSR als DER
     */
    fun build(subject: X500Principal, publicKey: PublicKey, sign: (ByteArray) -> ByteArray): ByteArray {
        val info = seq(
            byteArrayOf(0x02, 0x01, 0x00),         // version 0
            subject.encoded,                        // Name
            publicKey.encoded,                      // SubjectPublicKeyInfo
            byteArrayOf(0xA0.toByte(), 0x00),       // attributes [0] – leer
        )
        val signature = sign(info)
        return seq(info, ECDSA_WITH_SHA256, tlv(0x03, byteArrayOf(0x00) + signature))
    }

    fun pem(der: ByteArray): String =
        "-----BEGIN CERTIFICATE REQUEST-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END CERTIFICATE REQUEST-----\n"

    /** AlgorithmIdentifier ecdsa-with-SHA256 (1.2.840.10045.4.3.2), ohne Parameter */
    private val ECDSA_WITH_SHA256 = byteArrayOf(0x30, 0x0A, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    internal fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 0x80 -> out.write(value.size)
            value.size < 0x100 -> { out.write(0x81); out.write(value.size) }
            else -> { out.write(0x82); out.write(value.size shr 8); out.write(value.size and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
