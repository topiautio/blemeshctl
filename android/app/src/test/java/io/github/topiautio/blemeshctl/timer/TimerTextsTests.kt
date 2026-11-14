package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.R
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerTextsTests {
    private val text = FakeTextSource()
    private val texts = TimerTexts(text, ZoneId.of("UTC"))

    private fun record(
        state: TimerState,
        steps: TimerSteps = TimerSteps(),
        started: Long? = null,
        first: String? = null,
        last: String? = null,
        outcome: String? = null,
        fireAt: Long = 0,
    ) = TimerRecord(
        id = "abc123", description = "Päälle", target = "Olohuone", state = state, createdAtMillis = 0,
        fireAtMillis = fireAt, startedAtMillis = started, steps = steps, firstError = first, lastError = last,
        outcome = outcome,
    )

    @Test
    fun describesEveryAction() {
        assertEquals(text.expect(R.string.timer_desc_on), texts.describe(TimerSummary(TimerAction.ON)))
        assertEquals(text.expect(R.string.timer_desc_off), texts.describe(TimerSummary(TimerAction.OFF)))
        assertEquals(
            text.expect(R.string.timer_desc_scene, "Elokuva"),
            texts.describe(TimerSummary(TimerAction.SCENE, sceneName = "Elokuva")),
        )
        val colour = TimerSummary(TimerAction.FADE, colourHex = "ff8800", from = 100, to = null, fadeSeconds = 1200)
        assertEquals(
            text.expect(R.string.timer_desc_fade_colour, "ff8800", 100, text.expect(R.string.timer_fade_to_off), "20m"),
            texts.describe(colour),
        )
        val white = TimerSummary(TimerAction.FADE, whiteKelvin = 4600, from = 80, to = 10, fadeSeconds = 5400)
        assertEquals(
            text.expect(R.string.timer_desc_fade_white, 4600, 80, text.expect(R.string.timer_percent, 10), "1h30m"),
            texts.describe(white),
        )
    }

    @Test
    fun clockIsHoursMinutesSecondsInTheGivenZone() {
        assertEquals(text.expect(R.string.timer_clock, "01:02:03"), texts.clock((3600 + 120 + 3) * 1000L))
        val helsinki = TimerTexts(text, ZoneId.of("Europe/Helsinki"))
        assertEquals(text.expect(R.string.timer_clock, "02:00:00"), helsinki.clock(0))
    }

    @Test
    fun rowShowsProgressWhileActiveAndOutcomeAfterwards() {
        val running = record(TimerState.RUNNING, TimerSteps(total = 8, executed = 2), started = 1)
        val row = texts.row(running)
        assertEquals("Päälle", row.title)
        assertEquals(
            text.expect(
                R.string.timer_row_meta, "Olohuone", text.expect(R.string.timer_state_running), text.expect(R.string.timer_clock, "00:00:00"),
            ),
            row.meta,
        )
        assertEquals(text.expect(R.string.timer_progress_total, 2, 8, 25), row.detail)
        assertNull(texts.row(record(TimerState.SCHEDULED)).detail)
        assertEquals(text.expect(R.string.timer_progress_open, 3), texts.progress(record(TimerState.RUNNING, TimerSteps(executed = 3), started = 1)))
        assertNull(texts.outcome(running))
        assertTrue(texts.row(record(TimerState.DONE, TimerSteps(1, 1, 1))).detail!!.isNotEmpty())
    }

    @Test
    fun doneOutcomeCountsAcknowledgedStepsWithoutClaimingPhysicalState() {
        val outcome = texts.outcome(record(TimerState.DONE, TimerSteps(total = 4, executed = 4, ok = 4), started = 0))
        assertEquals(
            text.expect(R.string.timer_outcome_done, 4, 4) + " " + text.expect(R.string.timer_outcome_unverified),
            outcome,
        )
    }

    @Test
    fun partialOutcomeListsEachKindOfShortfallAndTheErrors() {
        val outcome = texts.outcome(
            record(
                TimerState.PARTIAL,
                TimerSteps(total = 5, executed = 5, ok = 2, partial = 2, failed = 1),
                started = 0,
                first = "step 2 (color ff0000 100): timeout",
                last = "step 5 (off): lopputulos epävarma: drop",
            ),
        )!!
        assertTrue(outcome.contains(text.expect(R.string.timer_outcome_acked, 2, 5)))
        assertTrue(outcome.contains(text.expect(R.string.timer_outcome_some_lights, 2)))
        assertTrue(outcome.contains(text.expect(R.string.timer_outcome_failed_steps, 1)))
        assertTrue(outcome.contains(text.expect(R.string.timer_outcome_first_error, text.expect(R.string.timer_step_error, 2, "timeout"))))
        assertTrue(outcome.contains(text.expect(R.string.timer_outcome_last_error, text.expect(R.string.timer_step_error, 5, "lopputulos epävarma: drop"))))
    }

    @Test
    fun failedOutcomeDistinguishesRunnerCrashFromUndeliveredSteps() {
        val undelivered = texts.outcome(
            record(TimerState.FAILED, TimerSteps(total = 2, executed = 2, failed = 2), started = 0, first = "raw one", last = "raw one"),
        )!!
        assertTrue(undelivered.startsWith(text.expect(R.string.timer_outcome_none, 2, 2)))
        // The same message twice is shown once, and an unrecognised message is kept raw.
        assertEquals(1, undelivered.split(text.expect(R.string.timer_outcome_first_error, "raw one")).size - 1)

        val crashed = texts.outcome(record(TimerState.FAILED, started = 0, outcome = "runner error: kaboom"))
        assertEquals(text.expect(R.string.timer_outcome_runner, "kaboom"), crashed)
    }

    @Test
    fun cancelledOutcomeDependsOnWhetherItFired() {
        assertEquals(
            text.expect(R.string.timer_outcome_cancelled_before),
            texts.outcome(record(TimerState.CANCELLED)),
        )
        assertEquals(
            text.expect(R.string.timer_outcome_cancelled_running, 3),
            texts.outcome(record(TimerState.CANCELLED, TimerSteps(total = 9, executed = 3), started = 5)),
        )
    }

    @Test
    fun aLateStartIsMentioned() {
        val late = texts.outcome(record(TimerState.DONE, TimerSteps(1, 1, 1), started = 125_000, fireAt = 0))!!
        assertTrue(late.contains(text.expect(R.string.timer_outcome_late, "2m5s")))
        val onTime = texts.outcome(record(TimerState.DONE, TimerSteps(1, 1, 1), started = 59_000, fireAt = 0))!!
        assertTrue(!onTime.contains(text.expect(R.string.timer_outcome_late, "59s")))
    }

    @Test
    fun finishedNotificationCarriesTitleTargetAndOutcome() {
        val done = record(TimerState.DONE, TimerSteps(1, 1, 1), started = 0)
        assertEquals(
            text.expect(R.string.timer_notification_finished_title, "Päälle", text.expect(R.string.timer_state_done)),
            texts.finishedTitle(done),
        )
        assertEquals("Olohuone · " + texts.outcome(done), texts.finishedBody(done))
    }

    @Test
    fun activeCountUsesThePlural() {
        assertEquals("plural ${R.plurals.timer_notification_active}:3 q3", texts.activeCount(3))
    }

    @Test
    fun problemMessagesPassRawDetailsOnlyWhereTheyHaveOne() {
        assertEquals(text.expect(R.string.timer_error_too_long), texts.problem(ScheduleProblem.TOO_LONG, "ignored"))
        assertEquals(
            text.expect(R.string.timer_error_scene_invalid, "line 3"),
            texts.problem(ScheduleProblem.SCENE_INVALID, "line 3"),
        )
    }
}
