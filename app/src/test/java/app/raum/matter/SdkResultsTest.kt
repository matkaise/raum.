package app.raum.matter

import app.raum.matter.chip.withDevicePointer
import app.raum.matter.chip.writeResult
import app.raum.matter.controller.CommandFailure
import app.raum.matter.controller.CommandResult
import chip.devicecontroller.model.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkResultsTest {

    // --- Schreibstatus -------------------------------------------------------------------------

    @Test
    fun `write success status maps to success`() {
        assertEquals(CommandResult.Success, writeResult(Status.newInstance(Status.Code.Success.id)))
    }

    @Test
    fun `rejected write is a failure`() {
        val r = writeResult(Status.newInstance(Status.Code.ConstraintError.id))
        assertEquals(CommandFailure.DEVICE_ERROR, (r as CommandResult.Failure).reason)
        assertTrue(r.detail!!.contains("ConstraintError"))
    }

    @Test
    fun `unsupported write is reported as unsupported`() {
        val r = writeResult(Status.newInstance(Status.Code.UnsupportedWrite.id))
        assertEquals(CommandFailure.UNSUPPORTED, (r as CommandResult.Failure).reason)
    }

    // --- Gerätezeiger --------------------------------------------------------------------------

    /** Simuliertes SDK: merkt sich den Rückruf, damit der Test Verbindung/Fehler selbst auslöst. */
    private class FakeSdk {
        var nextPtr = 100L
        val released = mutableListOf<Long>()
        var onConnected: ((Long) -> Unit)? = null
        var onFailure: (() -> Unit)? = null
        val connect: ((Long) -> Unit, () -> Unit) -> Unit = { ok, err -> onConnected = ok; onFailure = err }
        val release: (Long) -> Unit = { released += it }
        fun connectNow(): Long = nextPtr++.also { onConnected!!(it) }
    }

    @Test
    fun `pointer is released after use`() = runTest {
        val sdk = FakeSdk()
        val r = async { withDevicePointer(1_000, sdk.connect, sdk.release) { p -> "used $p" } }
        runCurrent()
        val p = sdk.connectNow()
        assertEquals("used $p", r.await())
        assertEquals(listOf(p), sdk.released)
    }

    @Test
    fun `pointer is released when block throws`() = runTest {
        val sdk = FakeSdk()
        val r = async { runCatching { withDevicePointer(1_000, sdk.connect, sdk.release) { error("boom") } } }
        runCurrent()
        val p = sdk.connectNow()
        assertTrue(r.await().isFailure)
        assertEquals(listOf(p), sdk.released)
    }

    @Test
    fun `pointer is released when caller is cancelled during use`() = runTest {
        val sdk = FakeSdk()
        val entered = CompletableDeferred<Unit>()
        val job = launch { withDevicePointer(1_000, sdk.connect, sdk.release) { entered.complete(Unit); awaitCancellation() } }
        runCurrent()
        val p = sdk.connectNow()
        runCurrent()
        assertTrue(entered.isCompleted)
        job.cancel()
        runCurrent()
        assertEquals(listOf(p), sdk.released)
    }

    @Test
    fun `late connection after timeout is released`() = runTest {
        val sdk = FakeSdk()
        val r = async { withDevicePointer(1_000, sdk.connect, sdk.release) { fail("block must not run"); "" } }
        runCurrent()
        advanceTimeBy(1_001)
        assertNull(r.await())
        val p = sdk.connectNow() // SDK meldet die Verbindung erst jetzt
        assertEquals(listOf(p), sdk.released)
    }

    @Test
    fun `connection failure yields null without release`() = runTest {
        val sdk = FakeSdk()
        val r = async { withDevicePointer(1_000, sdk.connect, sdk.release) { "x" } }
        runCurrent()
        sdk.onFailure!!()
        assertNull(r.await())
        assertTrue(sdk.released.isEmpty())
    }
}
