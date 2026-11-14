package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.script.BleMeshScript
import io.github.topiautio.blemeshctl.script.ScriptException
import java.math.BigInteger
import java.util.Locale

enum class TimerAction { ON, OFF, SCENE, FADE }

enum class FadeColour { WHEEL, WHITE }

/** The input field a problem belongs to. */
enum class ScheduleField { DELAY, SCENE, FADE_COLOUR, FADE_DURATION, FADE_FROM, FADE_TO }

enum class ScheduleProblem { EMPTY, INVALID_DURATION, TOO_SHORT, TOO_LONG, NO_SCENE, SCENE_INVALID, BAD_PERCENT, BAD_COLOUR }

/** What the user typed and chose in the timer section. */
data class ScheduleInput(
    val action: TimerAction,
    val delayText: String,
    val sceneName: String? = null,
    val sceneScript: String? = null,
    val fadeColour: FadeColour = FadeColour.WHEEL,
    /** The colour wheel's selection as `rrggbb`. */
    val wheelColour: String? = null,
    val whiteKelvin: Int = TelinkProtocol.WHITE_NEUTRAL_KELVIN,
    val fadeDurationText: String = "",
    val fromText: String = "",
    val toText: String = "",
)

/** Structured description of a timer; [TimerTexts.describe] turns it into Finnish text. */
data class TimerSummary(
    val action: TimerAction,
    val sceneName: String? = null,
    val colourHex: String? = null,
    val whiteKelvin: Int? = null,
    val from: Int = 100,
    /** Null means the fade ends with the light switched off. */
    val to: Int? = null,
    val fadeSeconds: Long? = null,
)

/** A validated timer: the program to run [delaySeconds] from the moment it was built. */
data class TimerPlan(
    val summary: TimerSummary,
    val programText: String,
    val delaySeconds: Long,
    val fireAtMillis: Long,
)

sealed interface ScheduleResult {
    data class Ready(val plan: TimerPlan) : ScheduleResult

    /** [detail] carries a raw message from a lower layer when there is one. */
    data class Invalid(val field: ScheduleField, val problem: ScheduleProblem, val detail: String? = null) : ScheduleResult
}

/** Outcome of checking a duration such as `30m`. */
sealed interface DurationCheck {
    data class Valid(val seconds: Long) : DurationCheck

    data class Problem(val problem: ScheduleProblem) : DurationCheck
}

/** Validates user input for a timer before any service is started or anything is sent. */
object ScheduleBuilder {
    private val DURATION = Regex("(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9]+)s)?")
    private const val DEFAULT_FROM = 100

    /** Classify [text] the way [TimerDuration.parse] would, without its English messages. */
    fun checkDuration(text: String): DurationCheck {
        val cleaned = text.trim().lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return DurationCheck.Problem(ScheduleProblem.EMPTY)
        val match = DURATION.matchEntire(cleaned) ?: return DurationCheck.Problem(ScheduleProblem.INVALID_DURATION)
        val (hours, minutes, seconds) = match.destructured
        val total = BigInteger.valueOf(3600) * part(hours) + BigInteger.valueOf(60) * part(minutes) + part(seconds)
        return when {
            total < BigInteger.valueOf(TimerDuration.MIN_SECONDS) -> DurationCheck.Problem(ScheduleProblem.TOO_SHORT)
            total > BigInteger.valueOf(TimerDuration.MAX_SECONDS) -> DurationCheck.Problem(ScheduleProblem.TOO_LONG)
            else -> DurationCheck.Valid(total.toLong())
        }
    }

    fun build(input: ScheduleInput, nowMillis: Long): ScheduleResult {
        val delay = when (val check = checkDuration(input.delayText)) {
            is DurationCheck.Valid -> check.seconds
            is DurationCheck.Problem -> return ScheduleResult.Invalid(ScheduleField.DELAY, check.problem)
        }
        val fireAt = nowMillis + delay * 1000
        return when (input.action) {
            TimerAction.ON -> ready(TimerSummary(TimerAction.ON), "on\n", delay, fireAt)
            TimerAction.OFF -> ready(TimerSummary(TimerAction.OFF), "off\n", delay, fireAt)
            TimerAction.SCENE -> buildScene(input, delay, fireAt)
            TimerAction.FADE -> buildFade(input, delay, fireAt)
        }
    }

    private fun buildScene(input: ScheduleInput, delay: Long, fireAt: Long): ScheduleResult {
        val name = input.sceneName
        val script = input.sceneScript
        if (name == null || script == null) {
            return ScheduleResult.Invalid(ScheduleField.SCENE, ScheduleProblem.NO_SCENE)
        }
        try {
            BleMeshScript.parse(script, "scene")
        } catch (exception: ScriptException) {
            return ScheduleResult.Invalid(ScheduleField.SCENE, ScheduleProblem.SCENE_INVALID, exception.message)
        }
        return ready(TimerSummary(TimerAction.SCENE, sceneName = name), script, delay, fireAt)
    }

    private fun buildFade(input: ScheduleInput, delay: Long, fireAt: Long): ScheduleResult {
        val fadeSeconds = when (val check = checkDuration(input.fadeDurationText)) {
            is DurationCheck.Valid -> check.seconds
            is DurationCheck.Problem -> return ScheduleResult.Invalid(ScheduleField.FADE_DURATION, check.problem)
        }
        val from = if (input.fromText.isBlank()) DEFAULT_FROM else percent(input.fromText)
            ?: return ScheduleResult.Invalid(ScheduleField.FADE_FROM, ScheduleProblem.BAD_PERCENT)
        val to = if (input.toText.isBlank()) {
            null
        } else {
            percent(input.toText) ?: return ScheduleResult.Invalid(ScheduleField.FADE_TO, ScheduleProblem.BAD_PERCENT)
        }
        val colour = if (input.fadeColour == FadeColour.WHEEL) input.wheelColour else null
        val kelvin = if (input.fadeColour == FadeColour.WHITE) input.whiteKelvin else null
        if (input.fadeColour == FadeColour.WHEEL && colour == null) {
            return ScheduleResult.Invalid(ScheduleField.FADE_COLOUR, ScheduleProblem.BAD_COLOUR)
        }
        val text = try {
            FadeProgram.text(colour, kelvin, from, to, fadeSeconds.toInt())
        } catch (exception: TimerException) {
            return ScheduleResult.Invalid(ScheduleField.FADE_COLOUR, ScheduleProblem.BAD_COLOUR, exception.message)
        }
        val summary = TimerSummary(
            action = TimerAction.FADE,
            colourHex = colour?.let { hexOf(it) },
            whiteKelvin = kelvin,
            from = from,
            to = to,
            fadeSeconds = fadeSeconds,
        )
        return ready(summary, text, delay, fireAt)
    }

    /** Normalise to six lowercase hex digits, like the fade program's own label. */
    private fun hexOf(colour: String): String {
        val rgb = TelinkProtocol.parseRgb(colour)
        return "%02x%02x%02x".format(Locale.ROOT, rgb.red, rgb.green, rgb.blue)
    }

    private fun ready(summary: TimerSummary, program: String, delay: Long, fireAt: Long): ScheduleResult =
        ScheduleResult.Ready(TimerPlan(summary, program, delay, fireAt))

    private fun percent(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..100 }

    private fun part(digits: String): BigInteger = if (digits.isEmpty()) BigInteger.ZERO else BigInteger(digits)
}
