package app.raum.matter.chip

import android.content.Context
import chip.devicecontroller.AttestationTrustStoreDelegate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Stammzertifikate der Hersteller-Zertifizierungsstellen (PAA) für die Echtheitsprüfung beim Koppeln.
 * Lokal aus `assets/paa/` (Spiegel der CSA-DCL im Matter-SDK) – kein Internetzugriff (Spez. 11.1).
 */
class PaaTrustStore(context: Context) : AttestationTrustStoreDelegate {

    /** Subject Key Identifier (hex) → DER */
    private val bySkid: Map<String, ByteArray> = run {
        val assets = context.assets
        val factory = CertificateFactory.getInstance("X.509")
        assets.list("paa").orEmpty().filter { it.endsWith(".der") }.mapNotNull { name ->
            runCatching {
                val der = assets.open("paa/$name").use { it.readBytes() }
                val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
                skid(cert)?.let { it to der }
            }.getOrNull()
        }.toMap()
    }

    val size: Int get() = bySkid.size

    override fun getProductAttestationAuthorityCert(skid: ByteArray): ByteArray? = bySkid[skid.hex()]

    private fun skid(cert: X509Certificate): String? {
        // Erweiterung 2.5.29.14: OCTET STRING, darin OCTET STRING mit der Schlüsselkennung
        val ext = cert.getExtensionValue("2.5.29.14") ?: return null
        val inner = ext.copyOfRange(2, ext.size)
        return inner.copyOfRange(2, inner.size).hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
