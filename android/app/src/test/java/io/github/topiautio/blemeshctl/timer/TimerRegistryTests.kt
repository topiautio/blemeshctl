@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.topiautio.blemeshctl.timer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimerRegistryTests {
    private val hour = 3_600_000L
    private var now = 10 * 24 * hour
    private var counter = 0
    private val registry = TimerRegistry(Clock { now }) { "id%04d".format(++counter) }

    private fun record(id: String, fireAt: Long, state: TimerState = TimerState.SCHEDULED, finished: Long? = null) = TimerRecord(
        id = id, description = "d", target = "t", state = state, createdAtMillis = now, fireAtMillis = fireAt,
        finishedAtMillis = finished,
    )

    @Test
    fun listIsOrderedByFireTimeThenId() {
        registry.add(record("b", 200))
        registry.add(record("a", 200))
        registry.add(record("c", 100))
        assertEquals(listOf("c", "a", "b"), registry.list().map { it.id })
    }

    @Test
    fun cancelScheduledAndRunningTimersInvokesTheHandle() {
        var cancelled = 0
        registry.add(record("a", 1)) { cancelled++ }
        registry.add(record("b", 2, TimerState.RUNNING)) { cancelled++ }
        assertNull(registry.cancel("a"))
        assertNull(registry.cancel("b"))
        assertEquals(2, cancelled)
        assertEquals("cancelled before it fired", registry.get("a")!!.outcome)
        assertEquals("cancelled while running; the light keeps its last state", registry.get("b")!!.outcome)
        assertEquals(TimerState.CANCELLED, registry.get("b")!!.state)
        assertEquals("timer a already finished (cancelled)", registry.cancel("a"))
        assertEquals("no timer 'zzz'", registry.cancel("zzz"))
        assertEquals(2, cancelled)
    }

    @Test
    fun finishedRecordsIgnoreLateRunnerUpdatesExceptCancellationDetail() {
        registry.add(record("a", 1))
        registry.cancel("a")
        registry.update(record("a", 1, TimerState.DONE, now))
        assertEquals(TimerState.CANCELLED, registry.get("a")!!.state)
        registry.update(record("a", 1, TimerState.CANCELLED, now).copy(outcome = "cancelled after 3 steps; the light keeps its last state"))
        assertEquals("cancelled after 3 steps; the light keeps its last state", registry.get("a")!!.outcome)
    }

    @Test
    fun cancelAllOnlyTouchesActiveTimers() {
        registry.add(record("a", 1))
        registry.add(record("b", 2, TimerState.RUNNING))
        registry.add(record("c", 3, TimerState.DONE, now))
        assertEquals(2, registry.cancelAll())
        assertEquals(TimerState.DONE, registry.get("c")!!.state)
    }

    @Test
    fun clearRemovesOnlyFinishedRecords() {
        registry.add(record("a", 1))
        registry.add(record("b", 2, TimerState.DONE, now))
        registry.add(record("c", 3, TimerState.FAILED, now))
        registry.add(record("d", 4, TimerState.RUNNING))
        assertEquals(2, registry.clear())
        assertEquals(listOf("a", "d"), registry.list().map { it.id })
        assertEquals(0, registry.clear())
    }

    @Test
    fun finishedRecordsAreKeptForTwentyFourHours() {
        registry.add(record("old", 1, TimerState.DONE, now))
        registry.add(record("active", 2, TimerState.SCHEDULED))
        now += 24 * hour
        assertEquals(listOf("old", "active"), registry.list().map { it.id })
        now += 1
        assertEquals(listOf("active"), registry.list().map { it.id })
        now += 1000 * hour
        assertEquals(listOf("active"), registry.list().map { it.id })
    }

    @Test
    fun listenersSeeEveryChange() {
        val seen = mutableListOf<Int>()
        registry.addListener { seen += it.size }
        registry.add(record("a", 1))
        registry.add(record("b", 2))
        registry.cancel("a")
        registry.clear()
        assertEquals(listOf(1, 2, 2, 1), seen)
    }

    @Test
    fun scheduleParsesFirstAndRunsThroughTheRunner() = runTest {
        val time = FakeTime(now)
        val sender = FakeSender()
        val runner = TimerRunner(sender, time.clock, time.sleeper)
        try {
            registry.schedule(this, runner, TimerRequest("bad", "t", now, "bogus\n"))
            fail("expected a script error")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("unknown instruction"))
        }
        assertTrue(registry.list().isEmpty())

        val scheduled = registry.schedule(this, runner, TimerRequest("off", "desk", now + 1000, "off\n"))
        assertEquals("id0001", scheduled.id)
        assertEquals(1, scheduled.steps.total)
        advanceUntilIdle()
        assertEquals(TimerState.DONE, registry.get("id0001")!!.state)
        assertEquals(listOf("off"), sender.descriptions)
    }

    @Test
    fun cancellingAScheduledTimerStopsItsCoroutineBeforeAnythingIsSent() = runTest {
        val time = FakeTime(now)
        val sender = FakeSender()
        val blocking = Sleeper { kotlinx.coroutines.awaitCancellation() }
        val runner = TimerRunner(sender, time.clock, blocking)
        registry.schedule(this, runner, TimerRequest("off", "desk", now + hour, "off\n"))
        runCurrent()
        assertNull(registry.cancel("id0001"))
        advanceUntilIdle()
        assertTrue(sender.calls.isEmpty())
        assertEquals(TimerState.CANCELLED, registry.get("id0001")!!.state)
        assertEquals("cancelled before it fired", registry.get("id0001")!!.outcome)
    }
}
