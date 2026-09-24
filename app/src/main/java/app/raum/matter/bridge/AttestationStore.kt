package app.raum.matter.bridge

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipInputStream
import javax.security.auth.x500.X500Principal

/** Wie die Bridge ihre Echtheit nachweist. */
enum class AttestationStatus {
    /** Testzertifikate des SDK – nur mit Test-ID 0xFFF1/0x8000, nicht für die Auslieferung */
    TEST,
    /** Eigene Zertifikate passend zur Hersteller-/Produkt-ID, Schlüssel im Keystore */
    CUSTOM,
    /** Eigene Hersteller-ID, aber keine passenden Zertifikate – andere Apps lehnen die Bridge ab */
    MISSING,
}

/**
 * Echtheitszertifikate der Bridge (Matter: DAC, PAI, Certification Declaration) – je Panel eigene (docs/VENDOR.md).
 * Sie gehören zur Hardware und überstehen den Werksreset der App.
 *
 * Zwei Wege:
 *  1. **Empfohlen:** [createRequest] erzeugt das Schlüsselpaar im Android Keystore (nicht exportierbar) und eine
 *     Zertifikatsanforderung (CSR). Der PKI-Anbieter liefert ein Paket **ohne** Schlüssel zurück (dac.der, pai.der, cd.der).
 *  2. Paket **mit** Schlüssel (zusätzlich dac-key.der, PKCS#8) – der Schlüssel wird in den Keystore übernommen;
 *     das Paket danach löschen.
 * Ein bisher aktives Zertifikat bleibt in Betrieb, bis ein neues geprüft übernommen ist.
 */
class AttestationStore(context: Context, private val vendorId: Int, private val productId: Int) {

    private val dir = File(context.filesDir, "attestation").apply { mkdirs() }
    private val dacFile = File(dir, "dac.der")
    private val paiFile = File(dir, "pai.der")
    private val cdFile = File(dir, "cd.der")
    /** Keystore-Alias des aktiven und des auf ein Zertifikat wartenden Schlüssels */
    private val activeAliasFile = File(dir, "key.alias")
    private val pendingAliasFile = File(dir, "pending.alias")
    private val pendingSinceFile = File(dir, "pending.since")

    private val activeAlias: String get() = activeAliasFile.takeIf { it.exists() }?.readText()?.trim() ?: ALIAS
    private val pendingAlias: String? get() = pendingAliasFile.takeIf { it.exists() }?.readText()?.trim()

    /** Wann eine Zertifikatsanforderung erstellt wurde, für die noch kein Zertifikat vorliegt (null = keine). */
    val pendingSince: java.time.Instant?
        get() = pendingAlias?.takeIf { keystore().containsAlias(it) }
            ?.let { pendingSinceFile.takeIf { f -> f.exists() }?.readText()?.trim()?.toLongOrNull() }
            ?.let(java.time.Instant::ofEpochMilli)

    val status: AttestationStatus
        get() = when {
            load() != null -> AttestationStatus.CUSTOM
            vendorId == TEST_VENDOR && productId == TEST_PRODUCT -> AttestationStatus.TEST
            else -> AttestationStatus.MISSING
        }

    class Credentials(val dac: ByteArray, val pai: ByteArray, val cd: ByteArray, private val key: PrivateKey) {
        /** ECDSA P-256 mit SHA-256, Ergebnis roh (r ‖ s, 64 Byte) wie von Matter verlangt */
        fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); derToRaw(sign()) }
    }

    /** Gespeicherte Zertifikate, nur wenn sie vollständig sind, zur Konfiguration passen und der Schlüssel im Keystore liegt. */
    fun load(): Credentials? {
        if (!dacFile.exists() || !paiFile.exists() || !cdFile.exists()) return null
        val key = (keystore().getEntry(activeAlias, null) as? KeyStore.PrivateKeyEntry)?.privateKey ?: return null
        val dac = dacFile.readBytes()
        val ids = matterIds(cert(dac).subjectX500Principal)
        if (ids.first != vendorId || ids.second != productId) return null
        return Credentials(dac, paiFile.readBytes(), cdFile.readBytes(), key)
    }

    sealed interface ImportResult {
        data object Ok : ImportResult
        data class Invalid(val reason: Reason) : ImportResult
    }

    enum class Reason { INCOMPLETE, BAD_CERTIFICATE, CHAIN, WRONG_IDS, KEY_MISMATCH, NO_REQUEST }

    /**
     * Neues Schlüsselpaar im Keystore (P-256, nur Signieren, StrongBox wenn vorhanden) und Zertifikatsanforderung dazu.
     * Eine frühere, unbeantwortete Anforderung wird ersetzt; das aktive Zertifikat bleibt unberührt.
     * @return CSR als PEM – geht an den PKI-Anbieter, enthält nichts Geheimes
     */
    fun createRequest(): String {
        pendingAlias?.let { old -> runCatching { keystore().deleteEntry(old) } }
        val alias = "${ALIAS}_${System.currentTimeMillis()}"
        val pair = generateKey(alias, strongBox = true) ?: generateKey(alias, strongBox = false)
            ?: error("Schlüssel konnte nicht erzeugt werden")
        pendingAliasFile.writeText(alias)
        pendingSinceFile.writeText(System.currentTimeMillis().toString())
        val serial = (1..8).map { "%02x".format(java.security.SecureRandom().nextInt(256)) }.joinToString("")
        val subject = CsrBuilder.dacSubject("raum. Bridge $serial", vendorId, productId)
        val der = CsrBuilder.build(subject, pair.public) { data ->
            Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
        }
        return CsrBuilder.pem(der)
    }

    private fun generateKey(alias: String, strongBox: Boolean): java.security.KeyPair? = runCatching {
        java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build(),
            )
        }.generateKeyPair()
    }.getOrNull()

    /** Paket prüfen und übernehmen. Bei jedem Fehler bleibt der bisherige Stand unverändert. */
    fun import(zip: InputStream): ImportResult {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && files.size < 8) files[e.name.substringAfterLast('/')] = z.readBytes()
            }
        }
        val dacBytes = files["dac.der"]; val paiBytes = files["pai.der"]; val cdBytes = files["cd.der"]; val keyBytes = files["dac-key.der"]
        if (dacBytes == null || paiBytes == null || cdBytes == null || cdBytes.isEmpty()) {
            return ImportResult.Invalid(Reason.INCOMPLETE)
        }
        val (dac, pai) = runCatching { cert(dacBytes) to cert(paiBytes) }.getOrElse { return ImportResult.Invalid(Reason.BAD_CERTIFICATE) }
        // Kette: DAC muss vom PAI signiert sein
        if (dac.issuerX500Principal != pai.subjectX500Principal || runCatching { dac.verify(pai.publicKey) }.isFailure) {
            return ImportResult.Invalid(Reason.CHAIN)
        }
        val ids = matterIds(dac.subjectX500Principal)
        val paiVendor = matterIds(pai.subjectX500Principal).first
        if (ids.first != vendorId || ids.second != productId || (paiVendor != null && paiVendor != vendorId)) {
            return ImportResult.Invalid(Reason.WRONG_IDS)
        }
        val alias: String
        if (keyBytes != null) {
            // Weg 2: Schlüssel liegt bei – prüfen und in den Keystore übernehmen
            val key = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes)) }
                .getOrElse { return ImportResult.Invalid(Reason.KEY_MISMATCH) }
            if (!signsFor(key, dac)) return ImportResult.Invalid(Reason.KEY_MISMATCH)
            alias = "${ALIAS}_${System.currentTimeMillis()}"
            keystore().setEntry(
                alias,
                KeyStore.PrivateKeyEntry(key, arrayOf(dac)),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build(),
            )
            keyBytes.fill(0)
        } else {
            // Weg 1: Zertifikat zur Anforderung – muss zum wartenden Schlüssel im Keystore passen
            alias = pendingAlias ?: return ImportResult.Invalid(Reason.NO_REQUEST)
            val pending = keystore().getCertificate(alias)?.publicKey ?: return ImportResult.Invalid(Reason.NO_REQUEST)
            if (!pending.encoded.contentEquals(dac.publicKey.encoded)) return ImportResult.Invalid(Reason.KEY_MISMATCH)
        }
        activate(alias, dacBytes, paiBytes, cdBytes)
        return ImportResult.Ok
    }

    private fun signsFor(key: PrivateKey, dac: X509Certificate): Boolean {
        val probe = "raum-attestation-probe".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(probe); sign() }
        return Signature.getInstance("SHA256withECDSA").run { initVerify(dac.publicKey); update(probe); verify(signature) }
    }

    /** Neues Zertifikat scharf schalten, alten Schlüssel entfernen. */
    private fun activate(alias: String, dac: ByteArray, pai: ByteArray, cd: ByteArray) {
        val previous = activeAlias.takeIf { activeAliasFile.exists() || keystore().containsAlias(ALIAS) }
        dacFile.writeBytes(dac); paiFile.writeBytes(pai); cdFile.writeBytes(cd)
        activeAliasFile.writeText(alias)
        if (alias == pendingAlias) { pendingAliasFile.delete(); pendingSinceFile.delete() }
        if (previous != null && previous != alias) runCatching { keystore().deleteEntry(previous) }
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        const val ALIAS = "raum_bridge_dac"
        const val TEST_VENDOR = 0xFFF1
        const val TEST_PRODUCT = 0x8000

        private fun cert(der: ByteArray) =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        /** Matter-Attribute im Zertifikatsnamen: 1.3.6.1.4.1.37244.2.1 (VID) und .2.2 (PID), je als Hex-Text. */
        fun matterIds(name: X500Principal): Pair<Int?, Int?> {
            val rdn = name.getName(X500Principal.RFC2253)
            fun find(oid: String): Int? = Regex("""$oid=#([0-9A-Fa-f]+)""").find(rdn)?.groupValues?.get(1)?.let(::derString)?.toIntOrNull(16)
            return find("1\\.3\\.6\\.1\\.4\\.1\\.37244\\.2\\.1") to find("1\\.3\\.6\\.1\\.4\\.1\\.37244\\.2\\.2")
        }

        /** DER-kodierter String (UTF8String/PrintableString) aus Hex → Text */
        private fun derString(hex: String): String? {
            val b = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (b.size < 2) return null
            val len = b[1].toInt() and 0xFF
            if (len >= 0x80 || 2 + len > b.size) return null
            return String(b, 2, len, Charsets.UTF_8)
        }

        /** ECDSA-Signatur: DER (SEQUENCE { r, s }) → roh r ‖ s mit je 32 Byte */
        fun derToRaw(der: ByteArray): ByteArray {
            var i = 2 // 0x30, Länge (P-256: immer < 128)
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F
            fun int(): ByteArray {
                require(der[i] == 0x02.toByte())
                val len = der[i + 1].toInt() and 0xFF
                val value = der.copyOfRange(i + 2, i + 2 + len)
                i += 2 + len
                val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
                require(trimmed.size <= 32)
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return int() + int()
        }
    }
}
