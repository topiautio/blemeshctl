package io.github.topiautio.blemeshctl.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationTests {
    @Test
    fun timeoutIsAGattFailureNotACancellation() = runTest {
        val error = runCatching {
            awaitGattOperation(CompletableDeferred<Unit>(), 20_000, "characteristic write")
        }.exceptionOrNull()

        // A cancellation would bypass the "outcome unknown" and retry handling.
        assertTrue(error is GattException)
        assertFalse(error is CancellationException)
        assertEquals("characteristic write timed out after 20000 ms", error?.message)
    }

    @Test
    fun callbackFailureIsPropagated() = runTest {
        val operation = CompletableDeferred<ByteArray>()
        operation.completeExceptionally(GattException("characteristic read failed with status 133"))

        val error = runCatching { awaitGattOperation(operation, 20_000, "characteristic read") }.exceptionOrNull()

        assertEquals("characteristic read failed with status 133", error?.message)
    }

    @Test
    fun completedOperationReturnsItsValue() = runTest {
        val operation = CompletableDeferred(byteArrayOf(0x0D))

        assertEquals(0x0D.toByte(), awaitGattOperation(operation, 20_000, "characteristic read").single())
    }
}
