@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.ble.MeshCredentials
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerCoordinatorTests {
    private var counter = 0
    private val registry = TimerRegistry(Clock { 1_000L }) { "id%04d".format(++counter) }
    private val coordinator = TimerCoordinator(registry)
    private val credentials = MeshCredentials("mesh", "secret")

    private fun spec(program: String, vararg addresses: String, group: Boolean = false) = TimerSpec(
        // A fire time in the past makes the runner start at once, whatever the test clock does.
        TimerRequest("d", "t", 0L, program),
        TimerTarget("t", addresses.map { LightRef(it, it) }, group),
        credentials,
    )

    @Test
    fun queuedSpecsLockTheirLightsBeforeTheServiceStarts() {
        coordinator.enqueue(spec("off\n", "aa:bb", "cc:dd", group = true))
        assertTrue(coordinator.hasPending())
        assertEquals(setOf("AA:BB", "CC:DD"), coordinator.lockedAddresses())
        assertEquals(1, coordinator.discardPending())
        assertFalse(coordinator.hasPending())
        assertTrue(coordinator.lockedAddresses().isEmpty())
    }

    @Test
    fun drainSchedulesAndLocksUntilTheTimerFinishes() = runTest {
        val sender = FakeSender()
        coordinator.enqueue(spec("off\n", "AA:BB"))
        assertTrue(coordinator.drain(this) { sender }.isEmpty())
        assertFalse(coordinator.hasPending())
        assertEquals(setOf("AA:BB"), coordinator.lockedAddresses(registry.list().map { it.copy(state = TimerState.SCHEDULED) }))
        advanceUntilIdle()
        assertEquals(listOf("off"), sender.descriptions)
        assertEquals(TimerState.DONE, registry.get("id0001")!!.state)
        assertTrue(coordinator.lockedAddresses().isEmpty())
    }

    @Test
    fun aSpecWhoseProgramNoLongerParsesIsRejectedWithoutBlockingOthers() = runTest {
        val sender = FakeSender()
        coordinator.enqueue(spec("bogus\n", "AA:BB"))
        coordinator.enqueue(spec("off\n", "CC:DD"))
        val rejected = coordinator.drain(this) { sender }
        assertEquals(1, rejected.size)
        assertEquals("bogus\n", rejected.single().spec.request.programText)
        advanceUntilIdle()
        assertEquals(1, registry.list().size)
        assertTrue(coordinator.lockedAddresses().isEmpty())
    }

    @Test
    fun finishedTimersAreReportedOnce() {
        val done = TimerRecord("a", "d", "t", TimerState.DONE, 0, 0, finishedAtMillis = 1)
        val active = TimerRecord("b", "d", "t", TimerState.RUNNING, 0, 0)
        assertEquals(listOf("a"), coordinator.takeFinished(listOf(done, active)).map { it.id })
        assertTrue(coordinator.takeFinished(listOf(done, active)).isEmpty())
        val cancelled = active.copy(state = TimerState.CANCELLED)
        assertEquals(listOf("b"), coordinator.takeFinished(listOf(done, cancelled)).map { it.id })
    }
}
