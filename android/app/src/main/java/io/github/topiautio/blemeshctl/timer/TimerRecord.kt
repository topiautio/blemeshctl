package io.github.topiautio.blemeshctl.timer

enum class TimerState {
    SCHEDULED,
    RUNNING,
    DONE,
    PARTIAL,
    FAILED,
    CANCELLED,
    ;

    /** True while the timer still has something to do. */
    val isActive: Boolean get() = this == SCHEDULED || this == RUNNING
}

/** Step counters; [total] is null for a program that never ends. */
data class TimerSteps(
    val total: Int? = null,
    val executed: Int = 0,
    val ok: Int = 0,
    val partial: Int = 0,
    val failed: Int = 0,
)

/**
 * Immutable snapshot of one timer. Times are wall-clock epoch milliseconds.
 *
 * Messages describe what was sent and acknowledged, never a physical light state.
 */
data class TimerRecord(
    val id: String,
    val description: String,
    val target: String,
    val state: TimerState,
    val createdAtMillis: Long,
    val fireAtMillis: Long,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val steps: TimerSteps = TimerSteps(),
    val firstError: String? = null,
    val lastError: String? = null,
    val outcome: String? = null,
)
