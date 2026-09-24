package app.raum

import app.raum.automation.TestClock
import app.raum.platform.display.BrightnessCurve
import app.raum.platform.display.DisplayController
import app.raum.platform.display.DisplayMode
import app.raum.platform.display.DisplaySettings
import app.raum.platform.display.SleepAction
import app.raum.security.AdminPinStore
import app.raum.security.AdminPinStore.VerifyResult
import app.raum.security.InMemoryKeyValueStore
import app.raum.security.MaintenanceSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformLogicTest {

    // --- PIN ------------------------------------------------------------------

    private val clock = TestClock(ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC))

    @Test
    fun `pin is stored as hash and verifies`() {
        val kv = InMemoryKeyValueStore()
        val pins = AdminPinStore(kv, clock)
        assertEquals(VerifyResult.NotSet, pins.verify("1234"))
        assertEquals(VerifyResult.Ok, pins.setPin("482915"))
        assertTrue(pins.isSet)
        assertEquals(VerifyResult.Ok, pins.verify("482915"))
        assertEquals(VerifyResult.Wrong(4), pins.verify("000000"))
        // PIN darf nirgends im Klartext liegen
        listOf("admin_pin_hash", "admin_pin_salt").forEach { assertFalse(kv.getString(it)!!.contains("482915")) }
    }

    @Test
    fun `changing pin requires current pin`() {
        val pins = AdminPinStore(InMemoryKeyValueStore(), clock)
        pins.setPin("1111")
        assertTrue(pins.setPin("2222", current = "9999") is VerifyResult.Wrong)
        assertEquals(VerifyResult.Ok, pins.verify("1111"))
        assertEquals(VerifyResult.Ok, pins.setPin("2222", current = "1111"))
        assertEquals(VerifyResult.Ok, pins.verify("2222"))
    }

    @Test
    fun `lockout after repeated failures, doubling, survives restart`() {
        val kv = InMemoryKeyValueStore()
        var pins = AdminPinStore(kv, clock)
        pins.setPin("1234")
        repeat(4) { pins.verify("0000") }
        val locked = pins.verify("0000") as VerifyResult.LockedOut
        assertEquals(clock.instant().plusSeconds(30), locked.until)
        // Auch die richtige PIN wird während der Sperre abgelehnt
        assertTrue(pins.verify("1234") is VerifyResult.LockedOut)
        // „Neustart“: neue Instanz, gleicher Speicher
        pins = AdminPinStore(kv, clock)
        assertTrue(pins.verify("1234") is VerifyResult.LockedOut)
        clock.now = clock.now.plusSeconds(31)
        val second = pins.verify("0000") as VerifyResult.LockedOut
        assertEquals(clock.instant().plusSeconds(60), second.until)
        clock.now = clock.now.plusSeconds(61)
        assertEquals(VerifyResult.Ok, pins.verify("1234"))
        assertEquals(VerifyResult.Wrong(4), pins.verify("0000")) // Zähler zurückgesetzt
    }

    @Test
    fun `pin format`() {
        val pins = AdminPinStore(InMemoryKeyValueStore(), clock)
        assertFalse(pins.isValidFormat("123"))
        assertFalse(pins.isValidFormat("12a4"))
        assertTrue(pins.isValidFormat("1234"))
        assertFalse(pins.isValidFormat("123456789"))
    }

    @Test
    fun `maintenance session locks after inactivity`() {
        val s = MaintenanceSession(clock, Duration.ofMinutes(5))
        s.unlock()
        clock.now = clock.now.plusMinutes(4); s.touch()
        clock.now = clock.now.plusMinutes(4)
        assertTrue(s.checkTimeout())
        clock.now = clock.now.plusMinutes(2)
        assertFalse(s.checkTimeout())
    }

    // --- Display -------------------------------------------------------------

    @Test
    fun `brightness curve is monotonic and bounded`() {
        assertEquals(0.1f, BrightnessCurve.target(0f, 0.1f, 1f), 0.001f)
        assertEquals(1f, BrightnessCurve.target(5000f, 0.1f, 1f), 0.001f)
        val values = listOf(0f, 5f, 50f, 200f, 800f).map { BrightnessCurve.target(it, 0.1f, 1f) }
        assertEquals(values.sorted(), values)
    }

    private class DisplayFixture(scope: TestScope, s: DisplaySettings, canTurnOff: Boolean = false) {
        val settings = MutableStateFlow(s)
        val lux = MutableStateFlow<Float?>(null)
        val near = MutableStateFlow<Boolean?>(false)
        val controller = DisplayController(
            settings, lux, near, scope.backgroundScope,
            nowMs = { scope.testScheduler.currentTime }, canTurnOff = { canTurnOff },
        ).also { it.start() }
    }

    @Test
    fun `display sleeps after timeout and wakes on proximity`() = runTest {
        val f = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = 60))
        runCurrent()
        advanceTimeBy(59_000); runCurrent()
        assertEquals(DisplayMode.ACTIVE, f.controller.mode.value)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(DisplayMode.SCREENSAVER, f.controller.mode.value)

        f.near.value = true; runCurrent()
        assertEquals(DisplayMode.ACTIVE, f.controller.mode.value)
    }

    @Test
    fun `touch resets idle timer`() = runTest {
        val f = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = 60))
        runCurrent()
        advanceTimeBy(50_000); f.controller.userActivity()
        advanceTimeBy(50_000); runCurrent()
        assertEquals(DisplayMode.ACTIVE, f.controller.mode.value)
    }

    @Test
    fun `display off only when allowed, otherwise screensaver`() = runTest {
        val off = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = 10, sleepAction = SleepAction.DISPLAY_OFF), canTurnOff = true)
        val fallback = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = 10, sleepAction = SleepAction.DISPLAY_OFF), canTurnOff = false)
        advanceTimeBy(11_000); runCurrent()
        assertEquals(DisplayMode.OFF, off.controller.mode.value)
        assertEquals(DisplayMode.SCREENSAVER, fallback.controller.mode.value)
    }

    @Test
    fun `never sleeps when disabled and proximity wake can be turned off`() = runTest {
        val never = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = null))
        val noProx = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = 10, proximityWake = false))
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(DisplayMode.ACTIVE, never.controller.mode.value)
        assertEquals(DisplayMode.SCREENSAVER, noProx.controller.mode.value)
        noProx.near.value = true; runCurrent()
        assertEquals(DisplayMode.SCREENSAVER, noProx.controller.mode.value)
    }

    @Test
    fun `auto brightness follows ambient light smoothly`() = runTest {
        val f = DisplayFixture(this, DisplaySettings(sleepAfterSeconds = null, minBrightness = 0.1f, maxBrightness = 1f))
        f.lux.value = 1000f
        runCurrent()
        advanceTimeBy(500); runCurrent()
        val first = f.controller.brightness.value
        advanceTimeBy(10_000); runCurrent()
        val settled = f.controller.brightness.value
        assertNotEquals(first, settled) // nicht sprunghaft
        assertEquals(1f, settled, 0.02f)
        f.lux.value = 0f
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0.1f, f.controller.brightness.value, 0.02f)

        f.settings.value = f.settings.value.copy(autoBrightness = false, manualBrightness = 0.5f)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0.5f, f.controller.brightness.value, 0.02f)
    }

    // --- Absturzprotokoll ---------------------------------------------------

    @Test
    fun `crash summary keeps root cause and survives restart once`() {
        val file = java.io.File.createTempFile("crash", ".txt").also { it.delete() }
        val rec = app.raum.platform.service.CrashRecorder(file)
        val error = RuntimeException("außen", IllegalStateException("Datenbank voll"))
        file.writeText(rec.summary("main", error))
        val text = rec.takeLastCrash()!!
        assertTrue(text, text.contains("IllegalStateException: Datenbank voll"))
        assertTrue(text.contains("Thread main"))
        assertEquals(null, rec.takeLastCrash()) // nur einmal gemeldet
    }
}
