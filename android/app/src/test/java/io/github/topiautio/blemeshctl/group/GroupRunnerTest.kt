package io.github.topiautio.blemeshctl.group

import io.github.topiautio.blemeshctl.ble.CommandOutcomeUnknownException
import io.github.topiautio.blemeshctl.ble.InitialConnectionException
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "02:00:00:00:00:0A"
private const val B = "02:00:00:00:00:0B"
private const val C = "02:00:00:00:00:0C"

/** Records every connect, send, and wait, and fails chosen members on chosen script lines. */
private class FakeLights(
    private val failConnect: Map<String, Exception> = emptyMap(),
    private val failSend: Map<Pair<String, Int>, Exception> = emptyMap(),
    private val sendDelayMillis: Map<String, Long> = emptyMap(),
) {
    val events = mutableListOf<String>()

    suspend fun connect(member: String) {
        events += "connect ${member.takeLast(1)}"
        failConnect[member]?.let { throw it }
    }

    suspend fun send(member: String, command: LightCommand) {
        sendDelayMillis[member]?.let { delay(it) }
        failSend[member to command.line]?.let {
            events += "fail ${member.takeLast(1)} ${command.line}"
            throw it
        }
        events += "${member.takeLast(1)} ${command.line}"
    }

    fun runner(members: List<String>) = GroupRunner(members, ::connect, ::send)
}

class GroupRunnerTests {
    @Test
    fun sendsEachCommandToEveryMemberBeforeTheNextStep() = runTest {
        // A slow member must not let a fast one run ahead.
        val lights = FakeLights(sendDelayMillis = mapOf(A to 500))
        val program = BleMeshScript.parse("on\ncolor ff0000\nwait 1s\nrepeat 2:\n  off\n  wait 2s", "group.blemesh")

        val results = lights.runner(listOf(A, B, C)).run(program, wait = { lights.events += "wait ${it.milliseconds}" })

        assertEquals(
            listOf(
                "connect A", "connect B", "connect C",
                "A 1", "B 1", "C 1",
                "A 2", "B 2", "C 2",
                "wait 1000",
                "A 5", "B 5", "C 5", "wait 2000",
                "A 5", "B 5", "C 5", "wait 2000",
            ),
            lights.events,
        )
        assertEquals(listOf(4, 4, 4), results.map { it.commandsWritten })
        assertTrue(results.none { it.failed })
    }

    @Test
    fun aFailedMemberIsReportedAndSkippedWithoutStoppingTheOthers() = runTest {
        val lights = FakeLights(
            failConnect = mapOf(C to InitialConnectionException("C was not advertising")),
            failSend = mapOf((A to 2) to CommandOutcomeUnknownException("connection dropped")),
        )
        val program = BleMeshScript.parse("on\ncolor 00ff00 50\nwait 1s\noff", "partial.blemesh")
        val failed = mutableListOf<MemberResult>()
        val reports = mutableListOf<Pair<Int, List<Int>>>()

        val results = lights.runner(listOf(A, B, C)).run(
            program,
            wait = { lights.events += "wait" },
            onMemberFailed = { failed += it },
            onCommand = { command, snapshot -> reports += command.line to snapshot.map { it.commandsWritten } },
        )

        assertEquals(
            listOf("connect A", "connect B", "connect C", "A 1", "B 1", "fail A 2", "B 2", "wait", "B 4"),
            lights.events,
        )
        val (a, b, c) = results
        assertEquals(MemberResult(B, 3), b)
        assertEquals(FailureKind.UNCERTAIN, a.failure)
        assertEquals(2, a.failedLine)
        assertEquals(1, a.commandsWritten)
        assertEquals(FailureKind.UNREACHABLE, c.failure)
        assertNull(c.failedLine)
        assertEquals(0, c.commandsWritten)
        assertEquals(listOf(C, A), failed.map { it.address })
        // A failed member's count never grows, so the UI cannot report it as written.
        assertEquals(listOf(1 to listOf(1, 1, 0), 2 to listOf(1, 2, 0), 4 to listOf(1, 3, 0)), reports)
    }

    @Test
    fun otherErrorsAreClassifiedSeparately() = runTest {
        val lights = FakeLights(failSend = mapOf((A to 1) to IllegalStateException("protocol error")))

        val results = lights.runner(listOf(A, B)).run(BleMeshScript.parse("on"), wait = {})

        assertEquals(FailureKind.ERROR, results[0].failure)
        assertEquals("protocol error", results[0].error?.message)
        assertFalse(results[1].failed)
    }

    @Test
    fun anEndlessLoopEndsWhenEveryMemberHasFailed() = runTest {
        val lights = FakeLights(
            failSend = mapOf(
                (A to 3) to CommandOutcomeUnknownException("A dropped"),
                (B to 3) to InitialConnectionException("B vanished"),
            ),
        )
        val program = BleMeshScript.parse("on\nloop:\n  color ff0000\n  wait 1s", "loop.blemesh")

        val results = lights.runner(listOf(A, B)).run(program, wait = { lights.events += "wait" })

        assertEquals(listOf("connect A", "connect B", "A 1", "B 1", "fail A 3", "fail B 3"), lights.events)
        assertEquals(listOf(FailureKind.UNCERTAIN, FailureKind.UNREACHABLE), results.map { it.failure })
    }

    @Test
    fun theProgramDoesNotRunWhenNoMemberConnects() = runTest {
        val lights = FakeLights(
            failConnect = mapOf(
                A to InitialConnectionException("A not found"),
                B to InitialConnectionException("B not found"),
            ),
        )

        val results = lights.runner(listOf(A, B, A)).run(BleMeshScript.parse("on\nwait 1s\noff"), wait = {
            lights.events += "wait"
        })

        assertEquals(listOf("connect A", "connect B"), lights.events)
        assertTrue(results.all { it.failure == FailureKind.UNREACHABLE })
    }

    @Test
    fun cancellationIsNotRecordedAsAMemberFailure() = runTest {
        val lights = FakeLights()
        val runner = lights.runner(listOf(A, B))
        val failed = mutableListOf<MemberResult>()
        var cancelled = false

        val job = launch {
            try {
                runner.run(
                    BleMeshScript.parse("loop:\n  on\n  wait 1s"),
                    wait = { delay(it.milliseconds) },
                    onMemberFailed = { failed += it },
                )
            } catch (exception: CancellationException) {
                cancelled = true
                throw exception
            }
        }
        // Virtual time: three loop iterations have started by 2.5 s.
        delay(2_500)
        job.cancel()
        job.join()

        assertTrue(cancelled)
        assertTrue(failed.isEmpty())
        assertTrue(runner.snapshot.none { it.failed })
        assertEquals(listOf(3, 3), runner.snapshot.map { it.commandsWritten })
    }

    @Test
    fun cancellationDuringASendPropagates() = runTest {
        val runner = GroupRunner(
            listOf(A, B),
            connect = {},
            send = { member, _ -> if (member == A) throw CancellationException("stopped") },
        )

        val error = runCatching { runner.run(BleMeshScript.parse("on"), wait = {}) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(runner.snapshot.none { it.failed })
    }
}
