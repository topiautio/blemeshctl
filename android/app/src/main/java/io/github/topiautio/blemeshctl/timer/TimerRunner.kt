package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.LightCommand
import io.github.topiautio.blemeshctl.script.LoopStep
import io.github.topiautio.blemeshctl.script.RainbowStep
import io.github.topiautio.blemeshctl.script.ScriptStep
import io.github.topiautio.blemeshctl.script.WaitStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** Wall clock in epoch milliseconds. */
fun interface Clock {
    fun nowMillis(): Long
}

/** Suspends for the given time; replaceable so tests (and suspend/resume) are deterministic. */
fun interface Sleeper {
    suspend fun sleep(millis: Long)
}

enum class StepStatus { ACKNOWLEDGED, PARTIAL, FAILED }

/**
 * Result of delivering one command. For a group, ACKNOWLEDGED means the gateway acknowledged and
 * no member failed; PARTIAL means only some members received it. Only the gateway connection is
 * acknowledged, so this is never evidence of a physical state change.
 */
data class StepResult(val status: StepStatus, val message: String? = null)

/** Delivers one command to the timer's target (a single light or a group). */
fun interface StepSender {
    /**
     * Deliver [command], holding the connection for [keepaliveSeconds] afterwards. Return FAILED
     * instead of throwing where possible; any non-cancellation exception is treated as FAILED.
     */
    suspend fun send(command: LightCommand, keepaliveSeconds: Double): StepResult
}

/**
 * Runs one compiled program unattended at a wall-clock time. Port of `run_timer` in `timers.py`.
 *
 * A step whose write fails has an unknown outcome: it is recorded as failed and skipped, never
 * retried at its own position, and later steps are still attempted. Only the program's final
 * top-level `off` is retried ([OFF_RETRIES] extra attempts, [OFF_RETRY_SPACING_SECONDS] apart).
 */
class TimerRunner(
    private val sender: StepSender,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val sleeper: Sleeper = Sleeper { delay(it) },
) {
    /**
     * Wait for the fire time, run [program], and report each state change through [onUpdate].
     * Returns the final record. Cancelling the calling coroutine reports CANCELLED and rethrows.
     */
    suspend fun run(
        record: TimerRecord,
        program: List<ScriptStep>,
        onUpdate: (TimerRecord) -> Unit = {},
    ): TimerRecord {
        var current = record
        var fired = false
        var first: String? = null
        var last: String? = null
        var steps = TimerSteps(total = countCommands(program))

        fun publish(next: TimerRecord) {
            current = next
            onUpdate(next)
        }

        fun progress() {
            publish(current.copy(steps = steps, firstError = first, lastError = last))
        }

        var lateMillis = 0L
        try {
            val fireAt = record.fireAtMillis
            publish(current.copy(state = TimerState.SCHEDULED, steps = steps))
            while (true) {
                val remaining = fireAt - clock.nowMillis()
                if (remaining <= 0) break
                sleeper.sleep(min(remaining, WAKE_CHUNK_MILLIS))
            }
            lateMillis = clock.nowMillis() - fireAt
            fired = true
            publish(current.copy(state = TimerState.RUNNING, startedAtMillis = clock.nowMillis(), steps = steps))

            val total = steps.total
            val widest = longestWaitSeconds(program)
            val keepaliveMiddle = if (widest > MAX_HOLD_WAIT_SECONDS) {
                MIN_KEEPALIVE_SECONDS
            } else {
                min(MAX_KEEPALIVE_SECONDS, max(MIN_KEEPALIVE_SECONDS, 2 * widest))
            }
            val offLine = finalOffLine(program)

            BleMeshScript.execute(
                program,
                sendCommand = { command ->
                    val index = steps.executed + 1
                    steps = steps.copy(executed = index)
                    val keepalive = if (total != null && index == total) FINAL_KEEPALIVE_SECONDS else keepaliveMiddle
                    val attempts = 1 + if (command.line == offLine) OFF_RETRIES else 0
                    var status = StepStatus.FAILED
                    for (attempt in 0 until attempts) {
                        val result = try {
                            sender.send(command, keepalive)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            StepResult(StepStatus.FAILED, exception.message ?: exception.javaClass.simpleName)
                        }
                        status = result.status
                        if (status == StepStatus.ACKNOWLEDGED) break
                        val detail = result.message?.takeIf { it.isNotEmpty() } ?: "not acknowledged"
                        val message = "step $index (${command.description}): $detail"
                        if (first == null) first = message
                        last = message
                        if (attempt + 1 < attempts) {
                            sleeper.sleep((OFF_RETRY_SPACING_SECONDS * 1000).toLong())
                        }
                    }
                    steps = when (status) {
                        StepStatus.ACKNOWLEDGED -> steps.copy(ok = steps.ok + 1)
                        StepStatus.PARTIAL -> steps.copy(partial = steps.partial + 1)
                        StepStatus.FAILED -> steps.copy(failed = steps.failed + 1)
                    }
                    progress()
                },
                wait = { step -> sleeper.sleep(step.milliseconds) },
            )
        } catch (exception: CancellationException) {
            val outcome = if (fired) {
                "cancelled after ${steps.executed} steps; the light keeps its last state"
            } else {
                "cancelled before it fired"
            }
            publish(
                current.copy(
                    state = TimerState.CANCELLED,
                    finishedAtMillis = clock.nowMillis(),
                    steps = steps,
                    outcome = outcome,
                ),
            )
            throw exception
        } catch (exception: Exception) {
            publish(
                current.copy(
                    state = TimerState.FAILED,
                    finishedAtMillis = clock.nowMillis(),
                    steps = steps,
                    firstError = first,
                    lastError = last,
                    outcome = "runner error: ${exception.message ?: exception.javaClass.simpleName}",
                ),
            )
            return current
        }

        val (state, summary) = summarize(steps, first, last)
        val outcome = if (lateMillis >= LATE_NOTE_MILLIS) {
            "$summary; fired ${TimerDuration.format(lateMillis / 1000.0)} after the scheduled time"
        } else {
            summary
        }
        publish(
            current.copy(
                state = state,
                finishedAtMillis = clock.nowMillis(),
                steps = steps,
                firstError = first,
                lastError = last,
                outcome = outcome,
            ),
        )
        return current
    }

    companion object {
        const val WAKE_CHUNK_SECONDS = 30.0
        const val FINAL_KEEPALIVE_SECONDS = 5.0
        const val MIN_KEEPALIVE_SECONDS = 10.0

        /** Longest time to hold a light idle between steps; further apart, release it. */
        const val MAX_HOLD_WAIT_SECONDS = 120.0
        const val MAX_KEEPALIVE_SECONDS = 3600.0
        const val OFF_RETRIES = 3
        const val OFF_RETRY_SPACING_SECONDS = 5.0
        const val LATE_NOTE_SECONDS = 60.0

        private const val WAKE_CHUNK_MILLIS = 30_000L
        private const val LATE_NOTE_MILLIS = 60_000L

        /** How many commands a program sends, or null when it never ends. */
        fun countCommands(program: List<ScriptStep>): Int? {
            var total = 0
            for (step in program) {
                when (step) {
                    is LightCommand -> total += 1
                    is RainbowStep -> total += BleMeshScript.rainbowColors.size
                    is LoopStep -> {
                        val count = step.count ?: return null
                        total += count * (countCommands(step.body) ?: return null)
                    }
                    is WaitStep -> Unit
                }
            }
            return total
        }

        /** Longest single wait in seconds, including rainbow dwell times and loop bodies. */
        fun longestWaitSeconds(program: List<ScriptStep>): Double {
            var longest = 0.0
            for (step in program) {
                when (step) {
                    is WaitStep -> longest = max(longest, step.milliseconds / 1000.0)
                    is RainbowStep -> longest = max(longest, step.milliseconds / 1000.0)
                    is LoopStep -> longest = max(longest, longestWaitSeconds(step.body))
                    is LightCommand -> Unit
                }
            }
            return longest
        }

        /** Source line of a top-level `off` that ends the program (ignoring trailing waits), else null. */
        fun finalOffLine(program: List<ScriptStep>): Int? {
            for (step in program.asReversed()) {
                if (step is WaitStep) continue
                return if (step is LightCommand && step.opcode == TelinkProtocol.COMMAND_ON_OFF &&
                    step.parameters.contentEquals(TelinkProtocol.offParameters())
                ) {
                    step.line
                } else {
                    null
                }
            }
            return null
        }

        /** Final state and outcome message for a finished run. */
        fun summarize(steps: TimerSteps, firstError: String?, lastError: String?): Pair<TimerState, String> {
            val (_, executed, ok, partial, failed) = steps
            if (ok == executed && executed > 0) {
                return TimerState.DONE to "$ok of $executed steps acknowledged"
            }
            var detail = "; first error: ${firstError ?: "unknown"}"
            if (lastError != firstError) detail += "; last error: ${lastError ?: "unknown"}"
            if (ok == 0 && partial == 0) {
                return TimerState.FAILED to "nothing was delivered ($failed of $executed steps failed)$detail"
            }
            val parts = mutableListOf("$ok of $executed steps acknowledged")
            if (partial > 0) parts += "$partial reached only some lights"
            if (failed > 0) parts += "$failed failed"
            return TimerState.PARTIAL to parts.joinToString(", ") + detail
        }
    }
}
