package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.R
import io.github.topiautio.blemeshctl.ble.CommandOutcomeUnknownException
import io.github.topiautio.blemeshctl.ble.InitialConnectionException
import io.github.topiautio.blemeshctl.script.LightCommand
import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StepSendersTests {
    private val text = FakeTextSource()
    private val command = LightCommand(1, "on", TelinkProtocol.COMMAND_ON_OFF, TelinkProtocol.onParameters())

    /** Channel that throws the queued failure for its address, else records the write. */
    private class FakeChannels {
        val sent = mutableListOf<Triple<String, String, Long>>()
        val failures = mutableMapOf<String, Exception>()

        fun channel(light: LightRef) = LightChannel { command, keepAlive ->
            failures[light.address]?.let { throw it }
            sent += Triple(light.address, command.description, keepAlive)
        }
    }

    private val a = LightRef("AA:AA", "Olohuone")
    private val b = LightRef("BB:BB", "Keittiö")
    private val c = LightRef("CC:CC", "Eteinen")

    @Test
    fun singleLightAcknowledgesAndPassesKeepaliveInMilliseconds() = runBlocking {
        val channels = FakeChannels()
        val result = SingleLightSender(channels.channel(a), text).send(command, 12.5)
        assertEquals(StepResult(StepStatus.ACKNOWLEDGED), result)
        assertEquals(listOf(Triple("AA:AA", "on", 12_500L)), channels.sent)
    }

    @Test
    fun singleLightFailureKindsGetFinnishPrefixesAndKeepTheRawDetail() = runBlocking {
        val cases = listOf(
            InitialConnectionException("no light") to R.string.timer_fail_unreachable,
            CommandOutcomeUnknownException("dropped") to R.string.timer_fail_uncertain,
            IllegalStateException("boom") to R.string.timer_fail_error,
        )
        for ((exception, id) in cases) {
            val channels = FakeChannels().apply { failures["AA:AA"] = exception }
            val result = SingleLightSender(channels.channel(a), text).send(command, 5.0)
            assertEquals(StepStatus.FAILED, result.status)
            assertEquals(text.expect(id, exception.message!!), result.message)
        }
    }

    @Test
    fun singleLightNeverRetriesAFailedStep() = runBlocking {
        var attempts = 0
        val sender = SingleLightSender({ _, _ -> attempts++; throw InitialConnectionException("x") }, text)
        sender.send(command, 5.0)
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAFailedStep() {
        val single = SingleLightSender({ _, _ -> throw CancellationException("stop") }, text)
        val group = GroupSender(listOf(a), { LightChannel { _, _ -> throw CancellationException("stop") } }, text)
        for (sender in listOf<StepSender>(single, group)) {
            try {
                runBlocking { sender.send(command, 5.0) }
                fail("expected cancellation")
            } catch (expected: CancellationException) {
                // The runner reports CANCELLED itself.
            }
        }
    }

    @Test
    fun groupIsAcknowledgedOnlyWhenEveryMemberAcknowledged() = runBlocking {
        val channels = FakeChannels()
        val result = GroupSender(listOf(a, b, c), channels::channel, text).send(command, 10.0)
        assertEquals(StepResult(StepStatus.ACKNOWLEDGED), result)
        assertEquals(listOf("AA:AA", "BB:BB", "CC:CC"), channels.sent.map { it.first })
        assertTrue(channels.sent.all { it.third == 10_000L })
    }

    @Test
    fun groupWithSomeFailuresIsPartialAndNamesTheFailedLight() = runBlocking {
        val channels = FakeChannels().apply { failures["BB:BB"] = InitialConnectionException("gone") }
        val result = GroupSender(listOf(a, b, c), channels::channel, text).send(command, 5.0)
        assertEquals(StepStatus.PARTIAL, result.status)
        val failure = text.expect(R.string.timer_fail_unreachable, "gone")
        assertEquals(text.expect(R.string.timer_fail_member, "Keittiö", failure), result.message)
        // The members after the failed one were still written in the same step.
        assertEquals(listOf("AA:AA", "CC:CC"), channels.sent.map { it.first })
    }

    @Test
    fun groupWhereNoMemberAcknowledgedFailsWithEveryReason() = runBlocking {
        val channels = FakeChannels().apply {
            failures["AA:AA"] = InitialConnectionException("one")
            failures["BB:BB"] = CommandOutcomeUnknownException("two")
        }
        val result = GroupSender(listOf(a, b), channels::channel, text).send(command, 5.0)
        assertEquals(StepStatus.FAILED, result.status)
        assertTrue(result.message!!.contains("; "))
        assertTrue(result.message!!.contains("Olohuone") && result.message!!.contains("Keittiö"))
    }

    @Test
    fun aFailedMemberIsTriedAgainAtTheNextStep() = runBlocking {
        val channels = FakeChannels().apply { failures["BB:BB"] = InitialConnectionException("gone") }
        val sender = GroupSender(listOf(a, b), channels::channel, text)
        assertEquals(StepStatus.PARTIAL, sender.send(command, 5.0).status)
        channels.failures.clear()
        val second = sender.send(command, 5.0)
        assertEquals(StepStatus.ACKNOWLEDGED, second.status)
        assertNull(second.message)
        assertEquals(listOf("AA:AA", "AA:AA", "BB:BB"), channels.sent.map { it.first })
    }

    @Test
    fun aGroupNeedsAtLeastOneLight() {
        try {
            GroupSender(emptyList(), { LightChannel { _, _ -> } }, text)
            fail("expected an error")
        } catch (expected: IllegalArgumentException) {
            // Validated when the timer is scheduled, so this is a programming error.
        }
    }
}
