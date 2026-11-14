@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wall clock whose sleeps advance it, optionally with a suspend/resume jump after chosen sleeps. */
internal class FakeTime(var now: Long = 1_000_000L) {
    val sleeps = mutableListOf<Long>()
    val jumpAfterSleep = mutableMapOf<Int, Long>()
    val clock = Clock { now }
    val sleeper = Sleeper { millis ->
        sleeps += millis
        now += millis + (jumpAfterSleep[sleeps.size] ?: 0L)
    }
}

/** Records deliveries (with the keepalive requested) and fails chosen 1-based call numbers. */
internal class FakeSender(private val results: Map<Int, StepResult> = emptyMap(), private val offFails: Boolean = false) : StepSender {
    val calls = mutableListOf<Pair<String, Double>>()
    override suspend fun send(command: LightCommand, keepaliveSeconds: Double): StepResult {
        calls += command.description to keepaliveSeconds
        if (offFails && command.description == "off") return StepResult(StepStatus.FAILED, "off failed")
        return results[calls.size] ?: StepResult(StepStatus.ACKNOWLEDGED)
    }
    val descriptions get() = calls.map { it.first }
    val keepalives get() = calls.map { it.second }
}

class TimerRunnerTests {
    private fun record(fireAt: Long, id: String = "abc123") = TimerRecord(
        id = id, description = "test", target = "desk", state = TimerState.SCHEDULED,
        createdAtMillis = 1_000_000L, fireAtMillis = fireAt,
    )

    private fun program(text: String) = BleMeshScript.parse(text, "test")

    private fun runner(time: FakeTime, sender: StepSender) = TimerRunner(sender, time.clock, time.sleeper)

    @Test
    fun allAcknowledgedIsDone() = runTest {
        val time = FakeTime()
        val sender = FakeSender()
        val updates = mutableListOf<TimerRecord>()
        val result = runner(time, sender).run(record(time.now + 100_000), program("on\nwait 1s\noff\n")) { updates += it }
        assertEquals(TimerState.DONE, result.state)
        assertEquals("2 of 2 steps acknowledged", result.outcome)
        assertEquals(TimerSteps(total = 2, executed = 2, ok = 2), result.steps)
        assertEquals(listOf("on", "off"), sender.descriptions)
        assertNotNull(result.startedAtMillis)
        assertNotNull(result.finishedAtMillis)
        assertEquals(TimerState.RUNNING, updates.first { it.state == TimerState.RUNNING }.state)
        assertEquals(result, updates.last())
    }

    @Test
    fun sleepsInChunksAndFiresOnTimeAfterASuspendJump() = runTest {
        val time = FakeTime()
        val fireAt = time.now + 3_600_000L
        // The machine suspends for 50 minutes during the first chunk and wakes.
        time.jumpAfterSleep[1] = 3_000_000L
        val sender = FakeSender()
        val result = runner(time, sender).run(record(fireAt), program("off\n"))
        assertEquals(TimerState.DONE, result.state)
        assertEquals(listOf(30_000L), time.sleeps.take(1))
        assertTrue(time.sleeps.all { it <= 30_000L })
        // 30 s + 3000 s jump, then 570 s remain, waking every 30 s: 1 + 19 = 20 sleeps, no late note.
        assertEquals(20, time.sleeps.size)
        assertEquals(fireAt, time.now)
        assertFalse(result.outcome!!.contains("fired"))
    }

    @Test
    fun lateFiringStillRunsAndSaysHowLate() = runTest {
        val time = FakeTime()
        val fireAt = time.now + 60_000L
        time.jumpAfterSleep[1] = 4_000_000L // a very long suspend
        val result = runner(time, FakeSender()).run(record(fireAt), program("off\n"))
        assertEquals(TimerState.DONE, result.state)
        assertTrue(result.outcome!!, result.outcome!!.endsWith("; fired 1h6m10s after the scheduled time"))
    }

    @Test
    fun shortLatenessIsNotMentioned() = runTest {
        val time = FakeTime()
        val fireAt = time.now - 59_000L
        val result = runner(time, FakeSender()).run(record(fireAt), program("off\n"))
        assertEquals("1 of 1 steps acknowledged", result.outcome)
        val late = runner(time, FakeSender()).run(record(time.now - 60_000L), program("off\n"))
        assertEquals("1 of 1 steps acknowledged; fired 1m after the scheduled time", late.outcome)
    }

    @Test
    fun failedStepIsSkippedAndNeverRetriedButLaterStepsAndFinalOffAreSent() = runTest {
        val time = FakeTime()
        val sender = FakeSender(mapOf(2 to StepResult(StepStatus.FAILED, "write failed")))
        val result = runner(time, sender).run(record(time.now), program("on\ncolor ff0000 50\nwait 1s\ncolor 00ff00 30\noff\n"))
        assertEquals(listOf("on", "colour rgb #ff0000, brightness 50", "colour rgb #00ff00, brightness 30", "off"), sender.descriptions)
        assertEquals(TimerState.PARTIAL, result.state)
        assertEquals(TimerSteps(total = 4, executed = 4, ok = 3, failed = 1), result.steps)
        assertEquals("step 2 (colour rgb #ff0000, brightness 50): write failed", result.firstError)
        assertEquals(result.firstError, result.lastError)
        assertEquals(
            "3 of 4 steps acknowledged, 1 failed; first error: step 2 (colour rgb #ff0000, brightness 50): write failed",
            result.outcome,
        )
        assertTrue(time.sleeps.none { it == 5_000L })
    }

    @Test
    fun partialGroupDeliveryIsCountedAndNotDone() = runTest {
        val time = FakeTime()
        val sender = FakeSender(mapOf(1 to StepResult(StepStatus.PARTIAL, "kitchen missed it")))
        val result = runner(time, sender).run(record(time.now), program("on\noff\n"))
        assertEquals(TimerState.PARTIAL, result.state)
        assertEquals(TimerSteps(total = 2, executed = 2, ok = 1, partial = 1), result.steps)
        assertTrue(result.outcome!!.contains("1 reached only some lights"))
    }

    @Test
    fun finalOffIsRetriedThreeTimesThenOutcomeIsPartial() = runTest {
        val time = FakeTime()
        val sender = FakeSender(offFails = true)
        val result = runner(time, sender).run(record(time.now), program("color ff0000 10\nwait 2s\noff\n"))
        assertEquals(listOf("colour rgb #ff0000, brightness 10", "off", "off", "off", "off"), sender.descriptions)
        // Waits: 2 s program wait, then 3 retry spacings of 5 s.
        assertEquals(listOf(2_000L, 5_000L, 5_000L, 5_000L), time.sleeps)
        assertEquals(TimerState.PARTIAL, result.state)
        assertEquals(TimerSteps(total = 2, executed = 2, ok = 1, failed = 1), result.steps)
        assertEquals("step 2 (off): off failed", result.lastError)
    }

    @Test
    fun finalOffSucceedingOnRetryCountsAsAcknowledged() = runTest {
        val time = FakeTime()
        val sender = FakeSender(mapOf(2 to StepResult(StepStatus.FAILED, "x"), 3 to StepResult(StepStatus.FAILED, "y")))
        val result = runner(time, sender).run(record(time.now), program("on\noff\n"))
        assertEquals(listOf("on", "off", "off", "off"), sender.descriptions)
        assertEquals(TimerState.DONE, result.state)
        assertEquals("step 2 (off): x", result.firstError)
        assertEquals("step 2 (off): y", result.lastError)
    }

    @Test
    fun offThatIsNotLastOrIsInsideALoopIsNotRetried() = runTest {
        val time = FakeTime()
        val sender = FakeSender(offFails = true)
        runner(time, sender).run(record(time.now), program("off\nwait 1s\non\n"))
        assertEquals(listOf("off", "on"), sender.descriptions)
        assertNull(TimerRunner.finalOffLine(program("off\non\n")))
        assertNull(TimerRunner.finalOffLine(program("repeat 2:\n  on\n  off\n")))
        assertEquals(3, TimerRunner.finalOffLine(program("on\nwait 1s\noff\nwait 5s\n")) ?: 0)
    }

    @Test
    fun nothingDeliveredIsFailed() = runTest {
        val time = FakeTime()
        val sender = FakeSender(
            mapOf(1 to StepResult(StepStatus.FAILED, "no connection"), 2 to StepResult(StepStatus.FAILED, "no connection")),
            offFails = true,
        )
        val result = runner(time, sender).run(record(time.now), program("on\noff\n"))
        assertEquals(TimerState.FAILED, result.state)
        assertTrue(result.outcome!!, result.outcome!!.startsWith("nothing was delivered (2 of 2 steps failed); first error: step 1 (on): no connection"))
    }

    @Test
    fun senderExceptionsAreRecordedAsFailedSteps() = runTest {
        val time = FakeTime()
        var calls = 0
        val sender = StepSender { _, _ ->
            calls++
            if (calls == 1) throw IllegalStateException("boom")
            StepResult(StepStatus.ACKNOWLEDGED)
        }
        val result = runner(time, sender).run(record(time.now), program("on\noff\n"))
        assertEquals(TimerState.PARTIAL, result.state)
        assertEquals("step 1 (on): boom", result.firstError)
    }

    @Test
    fun cancelDuringSleepSendsNothingAndIsCancelled() = runTest {
        val time = FakeTime()
        val sender = FakeSender()
        val updates = mutableListOf<TimerRecord>()
        val blocking = Sleeper { awaitCancellation() }
        var cancelled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                TimerRunner(sender, time.clock, blocking).run(record(time.now + 600_000L), program("off\n")) { updates += it }
            } catch (exception: CancellationException) {
                cancelled = true
                throw exception
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(cancelled)
        assertTrue(sender.calls.isEmpty())
        val last = updates.last()
        assertEquals(TimerState.CANCELLED, last.state)
        assertEquals("cancelled before it fired", last.outcome)
        assertTrue(updates.none { it.state == TimerState.DONE })
    }

    @Test
    fun cancelWhileRunningReportsStepsAndKeepsTheLightState() = runTest {
        val time = FakeTime()
        val sender = FakeSender()
        val updates = mutableListOf<TimerRecord>()
        val sleeper = Sleeper { millis -> if (millis == 7_000L) awaitCancellation() else time.sleeper.sleep(millis) }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            TimerRunner(sender, time.clock, sleeper).run(record(time.now), program("on\nwait 7s\noff\n")) { updates += it }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf("on"), sender.descriptions)
        assertEquals(TimerState.CANCELLED, updates.last().state)
        assertEquals("cancelled after 1 steps; the light keeps its last state", updates.last().outcome)
    }

    @Test
    fun keepaliveIsFiveSecondsForTheLastCommandAndShortWaitsScale() = runTest {
        val time = FakeTime()
        val sender = FakeSender()
        runner(time, sender).run(record(time.now), program("on\nwait 30s\ncolor ff0000 5\nwait 2s\noff\n"))
        // Longest wait 30 s: max(10, 60) = 60 for the middle steps; the last command gets 5 s.
        assertEquals(listOf(60.0, 60.0, 5.0), sender.keepalives)
    }

    @Test
    fun keepaliveIsTenSecondsWhenWaitsExceedTheHoldLimitAndIsCapped() = runTest {
        val time = FakeTime()
        val sender = FakeSender()
        runner(time, sender).run(record(time.now), program("on\nwait 121s\noff\n"))
        assertEquals(listOf(10.0, 5.0), sender.keepalives)
        val exact = FakeSender()
        runner(time, exact).run(record(time.now), program("on\nwait 120s\noff\n"))
        assertEquals(listOf(240.0, 5.0), exact.keepalives)
        val tiny = FakeSender()
        runner(time, tiny).run(record(time.now), program("on\nwait 2s\noff\n"))
        assertEquals(listOf(10.0, 5.0), tiny.keepalives)
    }

    @Test
    fun keepaliveIsCappedAtOneHourForEndlessProgramsWithoutALastCommand() {
        val endless = program("loop:\n  on\n  wait 100s\n")
        assertNull(TimerRunner.countCommands(endless))
        assertEquals(100.0, TimerRunner.longestWaitSeconds(endless), 0.0)
        assertEquals(3600.0, TimerRunner.MAX_KEEPALIVE_SECONDS, 0.0)
    }

    @Test
    fun programAnalysisCountsRepeatsAndRainbows() {
        assertEquals(6, TimerRunner.countCommands(program("repeat 3:\n  on\n  off\n")))
        assertEquals(1530, TimerRunner.countCommands(program("rainbow 1s\n")))
        assertEquals(2.0, TimerRunner.longestWaitSeconds(program("rainbow 2s\n")), 0.0)
    }
}
