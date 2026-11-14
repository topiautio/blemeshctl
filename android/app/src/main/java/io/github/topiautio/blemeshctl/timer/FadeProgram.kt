package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.protocol.TelinkProtocol
import io.github.topiautio.blemeshctl.protocol.TelinkProtocolException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Generates a brightness fade as `.blemesh` text using only existing instructions.
 *
 * This is a line-for-line port of `fade_program_text` in `timers.py`; both must stay identical.
 * [to] of null dims to brightness 1 and then sends `off`. There is at most one step per
 * [MIN_FADE_STEP_SECONDS], one per brightness level, and at least one step. The colour is
 * required because lights cannot report their current state.
 */
object FadeProgram {
    const val MIN_FADE_STEP_SECONDS = 2

    /** Return the short description shown in the timer list, such as `fade ff8800 100->off over 20m`. */
    fun description(color: String?, whiteKelvin: Int?, from: Int, to: Int?, overSeconds: Int): String {
        val subject = if (color != null) rgbLabel(color) else "white ${whiteKelvin}K"
        val target = if (to == null) "off" else to.toString()
        return "fade $subject $from->$target over ${TimerDuration.format(overSeconds.toLong())}"
    }

    fun text(
        color: String?,
        whiteKelvin: Int?,
        from: Int = 100,
        to: Int?,
        overSeconds: Int,
    ): String {
        if (color == null && whiteKelvin == null) {
            throw TimerException(
                "fade needs --color RGB or --white [KELVIN]: lights cannot report their current " +
                    "state (issue #21), so blemeshctl cannot know which colour to dim",
            )
        }
        if (color != null && whiteKelvin != null) {
            throw TimerException("choose either --color or --white for a fade, not both")
        }
        checkBrightness("--from", from)
        if (to != null) checkBrightness("--to", to)
        if (overSeconds < 1) throw TimerException("--over must be at least 1s")

        val target = to ?: 1
        val steps = max(1, min(abs(from - target) + 1, overSeconds / MIN_FADE_STEP_SECONDS))
        val levels = if (steps == 1) {
            listOf(if (from == target) from else target)
        } else {
            List(steps) { index -> (from + (target - from) * index.toDouble() / (steps - 1) + 0.5).toInt() }
        }

        val subject = if (color != null) "color ${rgbLabel(color)}" else "white ${whiteKelvin}K"
        val lines = mutableListOf("# ${description(color, whiteKelvin, from, to, overSeconds)}")
        levels.forEachIndexed { index, level ->
            lines += "$subject $level"
            if (to == null) {
                lines += "wait ${formatWait(overSeconds.toDouble() / steps)}"
            } else if (index < steps - 1) {
                lines += "wait ${formatWait(overSeconds.toDouble() / (steps - 1))}"
            }
        }
        if (to == null) lines += "off"
        return lines.joinToString("\n") + "\n"
    }

    /** Python's `f"{seconds:.3f}".rstrip("0").rstrip(".") + "s"`, with round-half-even on the exact value. */
    private fun formatWait(seconds: Double): String {
        val text = BigDecimal(seconds).setScale(3, RoundingMode.HALF_EVEN).toPlainString()
        return text.trimEnd('0').trimEnd('.') + "s"
    }

    private fun checkBrightness(name: String, value: Int) {
        if (value !in 1..100) throw TimerException("$name brightness must be from 1 through 100")
    }

    private fun rgbLabel(color: String): String {
        val rgb = try {
            TelinkProtocol.parseRgb(color)
        } catch (exception: TelinkProtocolException) {
            throw TimerException(exception.message ?: "invalid RGB colour")
        }
        return "%02x%02x%02x".format(Locale.ROOT, rgb.red, rgb.green, rgb.blue)
    }
}
