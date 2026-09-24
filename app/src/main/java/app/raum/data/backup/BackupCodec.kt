package app.raum.data.backup

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschlüsseltes Sicherungsformat (BAK-002).
 *
 * Aufbau (.raumbak):
 * ```
 * "RAUMBAK" | Version (1 Byte) | PBKDF2-Iterationen (Int32) | Salt (16) | IV (12) | AES-256-GCM(gzip(JSON))
 * ```
 * Der Kopf ist als Associated Data authentisiert – Manipulationen an Kopf oder Inhalt fallen auf.
 * Der Schlüssel entsteht aus einem Sicherungspasswort (nicht aus dem Android Keystore), damit die
 * Sicherung auch auf einem Ersatzpanel wiederhergestellt werden kann.
 */
object BackupCodec {

    sealed class DecodeError(message: String) : Exception(message) {
        class NotABackup : DecodeError("not a raum. backup")
        class UnsupportedVersion(val version: Int) : DecodeError("unsupported backup format $version")
        class WrongPasswordOrCorrupt : DecodeError("wrong password or corrupt file")
        class InvalidContent(val detail: String) : DecodeError("invalid content: $detail")
    }

    const val MIN_PASSWORD_LENGTH = 8
    const val FILE_EXTENSION = "raumbak"

    private val MAGIC = "RAUMBAK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 310_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private val HEADER_LEN = MAGIC.size + 1 + 4 + SALT_LEN + IV_LEN

    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encode(document: BackupDocument, password: String, iterations: Int = ITERATIONS): ByteArray {
        require(password.length >= MIN_PASSWORD_LENGTH) { "password too short" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()

        val plain = gzip(json.encodeToString(BackupDocument.serializer(), document).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            updateAAD(header)
        }
        return header + cipher.doFinal(plain)
    }

    fun decode(bytes: ByteArray, password: String): BackupDocument {
        if (bytes.size < HEADER_LEN + 16 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw DecodeError.NotABackup()
        val buf = ByteBuffer.wrap(bytes)
        buf.position(MAGIC.size)
        val version = buf.get().toInt()
        if (version != VERSION.toInt()) throw DecodeError.UnsupportedVersion(version)
        val iterations = buf.int
        if (iterations !in 10_000..10_000_000) throw DecodeError.NotABackup()
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        val header = bytes.copyOfRange(0, HEADER_LEN)

        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
                updateAAD(header)
                doFinal(bytes, HEADER_LEN, bytes.size - HEADER_LEN)
            }
        } catch (e: AEADBadTagException) {
            throw DecodeError.WrongPasswordOrCorrupt()
        }
        return try {
            json.decodeFromString(BackupDocument.serializer(), gunzip(plain).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw DecodeError.InvalidContent(e.message?.take(120) ?: e.javaClass.simpleName)
        }
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
