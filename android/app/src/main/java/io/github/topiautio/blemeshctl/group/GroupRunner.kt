package io.github.topiautio.blemeshctl.group

import io.github.topiautio.blemeshctl.ble.CommandOutcomeUnknownException
import io.github.topiautio.blemeshctl.ble.InitialConnectionException
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
import io.github.topiautio.blemeshctl.script.ScriptStep
import io.github.topiautio.blemeshctl.script.WaitStep
import kotlinx.coroutines.CancellationException

/** Why a group member stopped receiving commands. */
enum class FailureKind {
    /** The connection could not be opened, so nothing was written. */
    UNREACHABLE,

    /** A write was interrupted; the light may or may not have applied it. */
    UNCERTAIN,

    /** Any other error, such as a protocol or permission failure. */
    ERROR,
}

/** What happened to one member during a group run. */
data class MemberResult(
    val address: String,
    /** Commands the light acknowledged at the GATT level; not a confirmed physical state. */
    val commandsWritten: Int,
    val failure: FailureKind? = null,
    /** The script line being sent when the member failed, or null if it failed while connecting. */
    val failedLine: Int? = null,
    val error: Throwable? = null,
) {
    val failed: Boolean
        get() = failure != null
}

/**
 * Runs one program for several lights at once by fanning out each command.
 *
 * The program runs once. Each command goes to every member that is still
 * working before the next command or wait, so members stay in step. A member
 * that fails is reported and skipped for the rest of the run, without
 * stopping the others, and is never retried: a failed write has an unknown
 * outcome. The run ends early once no member is left.
 *
 * Members are handled one after another inside a step, because Android is
 * unreliable when several GATT operations start at the same moment.
 */
class GroupRunner(
    members: List<String>,
    private val connect: suspend (String) -> Unit,
    private val send: suspend (String, LightCommand) -> Unit,
) {
    private val members = members.distinct()
    private val results = LinkedHashMap<String, MemberResult>()

    init {
        require(this.members.isNotEmpty()) { "a group run needs at least one light" }
        this.members.forEach { results[it] = MemberResult(it, 0) }
    }

    /** The current result of every member, in member order. */
    val snapshot: List<MemberResult>
        get() = results.values.toList()

    private val activeMembers: List<String>
        get() = members.filter { results.getValue(it).failure == null }

    /**
     * Connect to every member, then run [program].
     *
     * [onConnecting] is called before each connection attempt, [onMemberFailed]
     * whenever a member drops out, and [onCommand] after each command has been
     * offered to every remaining member. Cancellation propagates unchanged and
     * is never recorded as a member failure.
     */
    suspend fun run(
        program: List<ScriptStep>,
        wait: suspend (WaitStep) -> Unit,
        onConnecting: (String) -> Unit = {},
        onMemberFailed: (MemberResult) -> Unit = {},
        onCommand: (LightCommand, List<MemberResult>) -> Unit = { _, _ -> },
    ): List<MemberResult> {
        for (member in members) {
            onConnecting(member)
            try {
                connect(member)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                fail(member, null, exception, onMemberFailed)
            }
        }
        if (activeMembers.isEmpty()) return snapshot

        try {
            BleMeshScript.execute(
                program,
                sendCommand = { command ->
                    for (member in activeMembers) {
                        try {
                            send(member, command)
                            val current = results.getValue(member)
                            results[member] = current.copy(commandsWritten = current.commandsWritten + 1)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            fail(member, command.line, exception, onMemberFailed)
                        }
                    }
                    onCommand(command, snapshot)
                    if (activeMembers.isEmpty()) throw NoMembersLeft()
                },
                wait = wait,
            )
        } catch (exception: NoMembersLeft) {
            // Every member failed; an endless loop would otherwise wait forever.
        }
        return snapshot
    }

    private fun fail(member: String, line: Int?, exception: Exception, onMemberFailed: (MemberResult) -> Unit) {
        val kind = when (exception) {
            is InitialConnectionException -> FailureKind.UNREACHABLE
            is CommandOutcomeUnknownException -> FailureKind.UNCERTAIN
            else -> FailureKind.ERROR
        }
        val result = results.getValue(member).copy(failure = kind, failedLine = line, error = exception)
        results[member] = result
        onMemberFailed(result)
    }

    /** Ends the program once every member has failed; never escapes [run]. */
    private class NoMembersLeft : Exception()
}
