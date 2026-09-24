package app.raum.data

import app.raum.i18n.XmlStrings
import app.raum.i18n.ErrorTexts
import app.raum.data.backup.RestoreWarning
import app.raum.data.backup.BackupCodec
import app.raum.data.backup.BackupDocument
import app.raum.data.backup.BackupMapper
import app.raum.data.backup.IncompatibleBackupException
import app.raum.data.backup.SettingsDto
import app.raum.domain.models.Home
import app.raum.matter.controller.mock.MockHomeSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BackupTest {

    private val home = Home(UUID.randomUUID(), "Wohnung Müller", Instant.parse("2026-01-01T00:00:00Z"))
    private val pw = "sicheres-Passwort"
    private val fastIterations = 10_000 // Tests schnell halten; Produktion nutzt 310 000

    private fun document(dbSchema: Int = 2) = BackupMapper.toDocument(
        home, MockHomeSeed.rooms, MockHomeSeed.deviceMetadata, MockHomeSeed.scenes, MockHomeSeed.automations,
        SettingsDto(themeMode = "DARK", latitude = 52.5, longitude = 13.4, sleepAfterSeconds = 120),
        appVersion = "0.5.0", dbSchema = dbSchema,
    )

    @Test
    fun `backup round-trips completely`() {
        val bytes = BackupCodec.encode(document(), pw, fastIterations)
        val doc = BackupCodec.decode(bytes, pw)
        val plan = BackupMapper.plan(doc, currentDbSchema = 2, knownNodeIds = MockHomeSeed.devices.map { it.nodeId }.toSet())
        assertEquals(home, plan.home)
        assertEquals(MockHomeSeed.rooms, plan.rooms)
        assertEquals(MockHomeSeed.deviceMetadata, plan.devices)
        assertEquals(MockHomeSeed.scenes, plan.scenes)
        assertEquals(MockHomeSeed.automations, plan.automations)
        assertEquals("DARK", plan.settings.themeMode)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `content is encrypted`() {
        val bytes = BackupCodec.encode(document(), pw, fastIterations)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Müller"))
        assertFalse(text.contains("Deckenleuchte"))
        assertTrue(text.startsWith("RAUMBAK"))
    }

    @Test
    fun `wrong password and tampering are detected`() {
        val bytes = BackupCodec.encode(document(), pw, fastIterations)
        assertThrows(BackupCodec.DecodeError.WrongPasswordOrCorrupt::class.java) { BackupCodec.decode(bytes, "falsches-Passwort") }
        val tampered = bytes.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertThrows(BackupCodec.DecodeError.WrongPasswordOrCorrupt::class.java) { BackupCodec.decode(tampered, pw) }
        // Kopf manipuliert (Iterationen) → ebenfalls erkannt, da authentisiert
        val header = bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertThrows(BackupCodec.DecodeError::class.java) { BackupCodec.decode(header, pw) }
    }

    @Test
    fun `non backup files are rejected`() {
        assertThrows(BackupCodec.DecodeError.NotABackup::class.java) { BackupCodec.decode("hallo welt, das ist keine Sicherung".toByteArray(), pw) }
    }

    @Test
    fun `short passwords are refused`() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(document(), "1234567", fastIterations) }
    }

    @Test
    fun `backups from newer versions are rejected`() {
        assertThrows(IncompatibleBackupException::class.java) { BackupMapper.plan(document(dbSchema = 3), 2, emptySet()) }
        assertThrows(IncompatibleBackupException::class.java) {
            BackupMapper.plan(document().copy(format = BackupDocument.CURRENT_FORMAT + 1), 2, emptySet())
        }
    }

    @Test
    fun `missing fabric devices produce a clear warning`() {
        val plan = BackupMapper.plan(document(), 2, knownNodeIds = emptySet())
        assertEquals(RestoreWarning.MissingFromFabric(MockHomeSeed.devices.size), plan.warnings.single())
        // Nutzertext in beiden Sprachen
        assertTrue(ErrorTexts.restoreWarning(plan.warnings.single(), XmlStrings("de")).contains("keine Fabric-Schlüssel"))
        assertTrue(ErrorTexts.restoreWarning(plan.warnings.single(), XmlStrings("en")).contains("no fabric keys"))
    }

    @Test
    fun `broken references are repaired with warnings`() {
        val doc = document()
        val removed = doc.devices.first { it.displayName == "Stehlampe" }
        val broken = doc.copy(devices = doc.devices - removed)
        val plan = BackupMapper.plan(broken, 2, MockHomeSeed.devices.map { it.nodeId }.toSet())
        assertTrue(plan.scenes.flatMap { it.actions }.none { it.deviceId.toString() == removed.id })
        assertTrue(plan.warnings.any { it is RestoreWarning.DroppedSceneActions })
    }
}
