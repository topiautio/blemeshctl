package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBuilderTests {
    private val now = 1_700_000_000_000L

    private fun build(input: ScheduleInput) = ScheduleBuilder.build(input, now)

    private fun ready(input: ScheduleInput) = (build(input) as ScheduleResult.Ready).plan

    private fun invalid(input: ScheduleInput) = build(input) as ScheduleResult.Invalid

    private fun fade(
        duration: String = "20m",
        from: String = "",
        to: String = "",
        colour: FadeColour = FadeColour.WHEEL,
        wheel: String? = "ff8800",
    ) = ScheduleInput(
        action = TimerAction.FADE,
        delayText = "5m",
        fadeColour = colour,
        wheelColour = wheel,
        fadeDurationText = duration,
        fromText = from,
        toText = to,
    )

    @Test
    fun durationCheckAgreesWithTheCoreParser() {
        for (text in listOf("30s", "20m", "2h", "1h30m", " 45S ", "24h", "1s", "86400s")) {
            assertEquals(text, DurationCheck.Valid(TimerDuration.parse(text)), ScheduleBuilder.checkDuration(text))
        }
    }

    @Test
    fun durationProblemsAreClassified() {
        val expected = mapOf(
            "" to ScheduleProblem.EMPTY,
            "   " to ScheduleProblem.EMPTY,
            "abc" to ScheduleProblem.INVALID_DURATION,
            "30" to ScheduleProblem.INVALID_DURATION,
            "-5m" to ScheduleProblem.INVALID_DURATION,
            "1.5h" to ScheduleProblem.INVALID_DURATION,
            "5m1h" to ScheduleProblem.INVALID_DURATION,
            "0s" to ScheduleProblem.TOO_SHORT,
            "0m0s" to ScheduleProblem.TOO_SHORT,
            "24h1s" to ScheduleProblem.TOO_LONG,
            "99999999999999999999h" to ScheduleProblem.TOO_LONG,
        )
        for ((text, problem) in expected) {
            assertEquals(text, DurationCheck.Problem(problem), ScheduleBuilder.checkDuration(text))
        }
    }

    @Test
    fun onAndOffBuildSingleCommandPrograms() {
        val on = ready(ScheduleInput(TimerAction.ON, "30m"))
        assertEquals("on\n", on.programText)
        assertEquals(1800L, on.delaySeconds)
        assertEquals(now + 1_800_000, on.fireAtMillis)
        assertEquals(TimerSummary(TimerAction.ON), on.summary)
        assertEquals("off\n", ready(ScheduleInput(TimerAction.OFF, "1h")).programText)
    }

    @Test
    fun aBadDelayIsReportedOnTheDelayField() {
        val result = invalid(ScheduleInput(TimerAction.ON, "soon"))
        assertEquals(ScheduleField.DELAY, result.field)
        assertEquals(ScheduleProblem.INVALID_DURATION, result.problem)
    }

    @Test
    fun sceneUsesItsScriptAndMustParse() {
        val script = "color ff0000 100\nwait 1s\noff\n"
        val plan = ready(ScheduleInput(TimerAction.SCENE, "10s", sceneName = "Elokuva", sceneScript = script))
        assertEquals(script, plan.programText)
        assertEquals(TimerSummary(TimerAction.SCENE, sceneName = "Elokuva"), plan.summary)

        val missing = invalid(ScheduleInput(TimerAction.SCENE, "10s"))
        assertEquals(ScheduleField.SCENE to ScheduleProblem.NO_SCENE, missing.field to missing.problem)

        val broken = invalid(ScheduleInput(TimerAction.SCENE, "10s", sceneName = "Rikki", sceneScript = "bogus\n"))
        assertEquals(ScheduleProblem.SCENE_INVALID, broken.problem)
        assertTrue(broken.detail!!.isNotEmpty())
    }

    @Test
    fun fadeFromTheWheelColourMatchesTheCoreProgram() {
        val plan = ready(fade(duration = "20m", from = "80", to = "10"))
        assertEquals(FadeProgram.text("ff8800", null, 80, 10, 1200), plan.programText)
        assertEquals(
            TimerSummary(TimerAction.FADE, colourHex = "ff8800", from = 80, to = 10, fadeSeconds = 1200),
            plan.summary,
        )
    }

    @Test
    fun fadeDefaultsToStartingAtFullBrightnessAndEndingOff() {
        val plan = ready(fade())
        assertEquals(FadeProgram.text("ff8800", null, 100, null, 1200), plan.programText)
        assertEquals(100, plan.summary.from)
        assertEquals(null, plan.summary.to)
        assertTrue(plan.programText.trimEnd().endsWith("off"))
    }

    @Test
    fun whiteFadeUsesTheNeutralWhiteAndIgnoresTheWheel() {
        val plan = ready(fade(colour = FadeColour.WHITE, wheel = null))
        assertEquals(FadeProgram.text(null, TelinkProtocol.WHITE_NEUTRAL_KELVIN, 100, null, 1200), plan.programText)
        assertEquals(TelinkProtocol.WHITE_NEUTRAL_KELVIN, plan.summary.whiteKelvin)
        assertEquals(null, plan.summary.colourHex)
    }

    @Test
    fun fadeInputsAreValidatedPerField() {
        assertEquals(ScheduleField.FADE_DURATION, invalid(fade(duration = "")).field)
        assertEquals(ScheduleProblem.TOO_LONG, invalid(fade(duration = "25h")).problem)
        for (bad in listOf("0", "101", "abc", "5.5", "-3")) {
            assertEquals(bad, ScheduleField.FADE_FROM, invalid(fade(from = bad)).field)
            assertEquals(bad, ScheduleProblem.BAD_PERCENT, invalid(fade(to = bad)).problem)
        }
        assertEquals(ScheduleField.FADE_TO, invalid(fade(to = "0")).field)
        assertEquals(ScheduleField.FADE_COLOUR, invalid(fade(wheel = null)).field)
        assertEquals(ScheduleProblem.BAD_COLOUR, invalid(fade(wheel = "zzzzzz")).problem)
        // The delay is checked before the fade fields.
        assertEquals(ScheduleField.DELAY, invalid(fade(duration = "bad").copy(delayText = "")).field)
    }
}
