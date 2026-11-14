package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.R
import io.github.topiautio.blemeshctl.ble.CommandOutcomeUnknownException
import io.github.topiautio.blemeshctl.ble.InitialConnectionException
import io.github.topiautio.blemeshctl.script.LightCommand
import kotlinx.coroutines.CancellationException

/**
 * Writes commands to one light, connecting on demand. A write that is acknowledged returns
 * normally; any failure throws. [keepAliveMillis] says how long to hold the connection afterwards.
 */
fun interface LightChannel {
    suspend fun send(command: LightCommand, keepAliveMillis: Long)
}

/** Finnish description of a failed write, keeping the lower layer's message as a raw technical detail. */
fun describeFailure(exception: Exception, text: TextSource): String {
    val detail = exception.message ?: exception.javaClass.simpleName
    return when (exception) {
        is InitialConnectionException -> text.get(R.string.timer_fail_unreachable, detail)
        is CommandOutcomeUnknownException -> text.get(R.string.timer_fail_uncertain, detail)
        else -> text.get(R.string.timer_fail_error, detail)
    }
}

internal fun keepAliveMillis(keepaliveSeconds: Double): Long = (keepaliveSeconds * 1000).toLong().coerceAtLeast(0)

/** Sends each timer step to a single light. A failed step is reported, never retried here. */
class SingleLightSender(
    private val channel: LightChannel,
    private val text: TextSource,
) : StepSender {
    override suspend fun send(command: LightCommand, keepaliveSeconds: Double): StepResult = try {
        channel.send(command, keepAliveMillis(keepaliveSeconds))
        StepResult(StepStatus.ACKNOWLEDGED)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        StepResult(StepStatus.FAILED, describeFailure(exception, text))
    }
}

/**
 * Sends each timer step to every member of a group, one after another.
 *
 * A step is ACKNOWLEDGED only when every member accepted it, PARTIAL when some did, and FAILED
 * when none did. A member that fails is skipped for the rest of that step and tried again at the
 * next one, like the command-line tool.
 */
class GroupSender(
    private val members: List<LightRef>,
    private val channelFor: (LightRef) -> LightChannel,
    private val text: TextSource,
) : StepSender {
    init {
        require(members.isNotEmpty()) { "a group timer needs at least one light" }
    }

    override suspend fun send(command: LightCommand, keepaliveSeconds: Double): StepResult {
        val holdMillis = keepAliveMillis(keepaliveSeconds)
        val failures = mutableListOf<String>()
        var acknowledged = 0
        for (member in members) {
            try {
                channelFor(member).send(command, holdMillis)
                acknowledged += 1
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failures += text.get(R.string.timer_fail_member, member.name, describeFailure(exception, text))
            }
        }
        val message = failures.joinToString("; ").ifEmpty { null }
        return when (acknowledged) {
            members.size -> StepResult(StepStatus.ACKNOWLEDGED)
            0 -> StepResult(StepStatus.FAILED, message)
            else -> StepResult(StepStatus.PARTIAL, message)
        }
    }
}
