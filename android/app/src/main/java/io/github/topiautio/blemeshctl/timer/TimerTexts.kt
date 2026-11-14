package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The three text lines of one timer in the list. */
data class TimerRow(val title: String, val meta: String, val detail: String?)

/**
 * Finnish labels for timers.
 *
 * Everything is derived from a record's structured fields (state, step counters, times) rather
 * than from the English messages in the pure timer core. Only the first and last error are shown
 * as raw technical details. No text claims a physical light state: acknowledgement only means the
 * light accepted the write.
 */
class TimerTexts(
    private val text: TextSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    fun describe(summary: TimerSummary): String = when (summary.action) {
        TimerAction.ON -> text.get(R.string.timer_desc_on)
        TimerAction.OFF -> text.get(R.string.timer_desc_off)
        TimerAction.SCENE -> text.get(R.string.timer_desc_scene, summary.sceneName.orEmpty())
        TimerAction.FADE -> {
            val to = summary.to?.let { text.get(R.string.timer_percent, it) } ?: text.get(R.string.timer_fade_to_off)
            val over = TimerDuration.format(summary.fadeSeconds ?: 0L)
            if (summary.colourHex != null) {
                text.get(R.string.timer_desc_fade_colour, summary.colourHex, summary.from, to, over)
            } else {
                text.get(R.string.timer_desc_fade_white, summary.whiteKelvin ?: 0, summary.from, to, over)
            }
        }
    }

    /** The message shown on the input that [problem] belongs to. */
    fun problem(problem: ScheduleProblem, detail: String? = null): String = when (problem) {
        ScheduleProblem.EMPTY -> text.get(R.string.timer_error_empty)
        ScheduleProblem.INVALID_DURATION -> text.get(R.string.timer_error_invalid)
        ScheduleProblem.TOO_SHORT -> text.get(R.string.timer_error_too_short)
        ScheduleProblem.TOO_LONG -> text.get(R.string.timer_error_too_long)
        ScheduleProblem.NO_SCENE -> text.get(R.string.timer_error_no_scene)
        ScheduleProblem.SCENE_INVALID -> text.get(R.string.timer_error_scene_invalid, detail.orEmpty())
        ScheduleProblem.BAD_PERCENT -> text.get(R.string.timer_error_percent)
        ScheduleProblem.BAD_COLOUR -> text.get(R.string.timer_error_colour, detail.orEmpty())
    }

    fun state(state: TimerState): String = text.get(
        when (state) {
            TimerState.SCHEDULED -> R.string.timer_state_scheduled
            TimerState.RUNNING -> R.string.timer_state_running
            TimerState.DONE -> R.string.timer_state_done
            TimerState.PARTIAL -> R.string.timer_state_partial
            TimerState.FAILED -> R.string.timer_state_failed
            TimerState.CANCELLED -> R.string.timer_state_cancelled
        },
    )

    /** `kello HH:MM:SS` in the device's time zone. */
    fun clock(epochMillis: Long): String =
        text.get(R.string.timer_clock, timeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone)))

    fun row(record: TimerRecord): TimerRow = TimerRow(
        title = record.description,
        meta = text.get(R.string.timer_row_meta, record.target, state(record.state), clock(record.fireAtMillis)),
        detail = if (record.state.isActive) progress(record) else outcome(record),
    )

    /** Progress of a running timer; null while it waits for its fire time. */
    fun progress(record: TimerRecord): String? {
        if (record.state != TimerState.RUNNING) return null
        val steps = record.steps
        val total = steps.total
        return if (total != null && total > 0) {
            text.get(R.string.timer_progress_total, steps.executed, total, steps.executed * 100 / total)
        } else {
            text.get(R.string.timer_progress_open, steps.executed)
        }
    }

    /** One-line result of a finished timer; null while it is still active. */
    fun outcome(record: TimerRecord): String? {
        if (record.state.isActive) return null
        val steps = record.steps
        val parts = mutableListOf<String>()
        val total = steps.total ?: steps.executed
        when (record.state) {
            TimerState.DONE -> {
                parts += text.get(R.string.timer_outcome_done, steps.ok, total)
                parts += text.get(R.string.timer_outcome_unverified)
            }
            TimerState.PARTIAL -> {
                val head = StringBuilder(text.get(R.string.timer_outcome_acked, steps.ok, total))
                if (steps.partial > 0) head.append(text.get(R.string.timer_outcome_some_lights, steps.partial))
                if (steps.failed > 0) head.append(text.get(R.string.timer_outcome_failed_steps, steps.failed))
                parts += head.append('.').toString()
                parts += text.get(R.string.timer_outcome_unverified)
            }
            TimerState.FAILED ->
                if (steps.failed == 0 && steps.ok == 0 && steps.partial == 0) {
                    // The runner itself stopped, so only its raw message is available.
                    parts += text.get(R.string.timer_outcome_runner, record.outcome?.removePrefix(RUNNER_PREFIX).orEmpty())
                } else {
                    parts += text.get(R.string.timer_outcome_none, steps.failed, total)
                }
            TimerState.CANCELLED ->
                parts += if (record.startedAtMillis == null) {
                    text.get(R.string.timer_outcome_cancelled_before)
                } else {
                    text.get(R.string.timer_outcome_cancelled_running, steps.executed)
                }
            TimerState.SCHEDULED, TimerState.RUNNING -> Unit
        }
        val started = record.startedAtMillis
        if (started != null && started - record.fireAtMillis >= LATE_MILLIS) {
            parts += text.get(R.string.timer_outcome_late, TimerDuration.format((started - record.fireAtMillis) / 1000.0))
        }
        val first = record.firstError
        val last = record.lastError
        if (record.state != TimerState.DONE && first != null) {
            parts += text.get(R.string.timer_outcome_first_error, localiseStep(first))
            if (last != null && last != first) parts += text.get(R.string.timer_outcome_last_error, localiseStep(last))
        }
        return parts.joinToString(" ")
    }

    /** Title and body of the notification posted when [record] has finished. */
    fun finishedTitle(record: TimerRecord): String =
        text.get(R.string.timer_notification_finished_title, record.description, state(record.state))

    fun finishedBody(record: TimerRecord): String =
        listOfNotNull(record.target, outcome(record)).joinToString(" · ")

    fun activeCount(count: Int): String = text.plural(R.plurals.timer_notification_active, count, count)

    /** Turn the runner's `step N (command): detail` prefix into Finnish; other text is left raw. */
    private fun localiseStep(message: String): String {
        val match = STEP_PREFIX.matchEntire(message) ?: return message
        val (index, detail) = match.destructured
        return text.get(R.string.timer_step_error, index.toInt(), detail)
    }

    private companion object {
        const val RUNNER_PREFIX = "runner error: "
        const val LATE_MILLIS = 60_000L
        val STEP_PREFIX = Regex("step (\\d+) \\(.*?\\): (.*)", RegexOption.DOT_MATCHES_ALL)
    }
}
