package app.raum.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Wo der Schlüssel liegt – für Anzeige und Sicherheitsprüfung. */
enum class KeyProtection {
    /** StrongBox (eigener Sicherheitschip) */
    STRONGBOX,
    /** Trusted Execution Environment des Prozessors */
    TEE,
    /** Nur Software (z. B. Emulator) – immer noch nicht exportierbar, aber ohne Hardwareschutz */
    SOFTWARE,
    UNKNOWN,
}

/**
 * AES-256-GCM mit einem nicht exportierbaren Schlüssel im Android Keystore (Spez. 10.3, 11.2).
 * Der Schlüssel verlässt den Keystore nie. Ergebnis: IV (12 Byte) + Chiffrat mit Tag.
 * Die AAD bindet ein Chiffrat an seinen Zweck (z. B. den Eintragsnamen) – vertauschen fällt auf.
 */
class KeystoreCipher(private val alias: String) {

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: run {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
                    init(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }.generateKey()
        }.also { cached = it }
    }

    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV erzeugt der Keystore
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    /** @throws javax.crypto.AEADBadTagException bei manipuliertem oder vertauschtem Chiffrat */
    fun decrypt(sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /** Schutzniveau des Schlüssels laut Keystore. */
    fun protection(): KeyProtection = runCatching {
        val k = key()
        val info = SecretKeyFactory.getInstance(k.algorithm, PROVIDER).getKeySpec(k, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyProtection.SOFTWARE
                else -> KeyProtection.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyProtection.TEE else KeyProtection.SOFTWARE
        }
    }.getOrDefault(KeyProtection.UNKNOWN)

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
