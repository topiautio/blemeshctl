package io.github.topiautio.blemeshctl.timer

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One keyframe of the ramp: [fraction] of the way through, the RGB colour, and the brightness. */
private class Keyframe(val fraction: Double, val rgb: IntArray, val level: Int)

/** When a sunrise ramp starts and ends, in epoch milliseconds. */
data class SunrisePlan(val startMillis: Long, val wakeMillis: Long, val overSeconds: Int, val compressed: Boolean)

/**
 * Generates a sunrise ramp as `.blemesh` text using only existing instructions.
 *
 * This is a line-for-line port of `sunrise_program_text` and the planning helpers in `timers.py`;
 * both must stay identical. Only RGB `color` commands are used: a `color` turns a light that is
 * off on by itself, and the white channel's temperature is not trustworthy. The ramp ends on the
 * warm white `ffb060` and there is no final `off`.
 */
object SunriseProgram {
    const val DEFAULT_SECONDS = 30 * 60
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 6 * 60 * 60
    private const val MIN_STEP_SECONDS = 5.0
    private const val MAX_INTERVALS = 300

    private val KEYFRAMES = listOf(
        Keyframe(0.00, intArrayOf(0xFF, 0x00, 0x00), 1),
        Keyframe(0.30, intArrayOf(0xFF, 0x3C, 0x00), 20),
        Keyframe(0.60, intArrayOf(0xFF, 0x7A, 0x14), 55),
        Keyframe(0.85, intArrayOf(0xFF, 0xA6, 0x48), 85),
        Keyframe(1.00, intArrayOf(0xFF, 0xB0, 0x60), 100),
    )

    /** The interpolated RGB colour and brightness at [fraction] of the ramp. */
    fun frame(fraction: Double): Pair<IntArray, Int> {
        val clamped = min(1.0, max(0.0, fraction))
        for (index in 0 until KEYFRAMES.size - 1) {
            val a = KEYFRAMES[index]
            val b = KEYFRAMES[index + 1]
            if (clamped <= b.fraction) {
                val weight = (clamped - a.fraction) / (b.fraction - a.fraction)
                val rgb = IntArray(3) { channel ->
                    min(255, max(0, (a.rgb[channel] + (b.rgb[channel] - a.rgb[channel]) * weight + 0.5).toInt()))
                }
                val level = min(100, max(1, (a.level + (b.level - a.level) * weight + 0.5).toInt()))
                return rgb to level
            }
        }
        throw IllegalStateException("keyframes cover the whole ramp")
    }

    /** The short description used in the program's comment line, such as `sunrise 30m`. */
    fun comment(overSeconds: Int): String = "sunrise ${TimerDuration.format(overSeconds.toLong())}"

    fun text(overSeconds: Int): String {
        if (overSeconds !in MIN_SECONDS..MAX_SECONDS) throw TimerException("a sunrise lasts from 1m to 6h")
        val over = overSeconds.toDouble()
        // Python's float `//` floors the exact quotient of the two doubles, which can differ from
        // floor(a / b) when the rounded quotient lands on an integer.
        val step = max(MIN_STEP_SECONDS, over / MAX_INTERVALS)
        val intervals = max(1, BigDecimal(over).divideToIntegralValue(BigDecimal(step)).toInt())
        val spacing = over / intervals
        val commands = mutableListOf<String>()
        val waits = mutableListOf<Double>()
        for (index in 0..intervals) {
            val (rgb, level) = frame(index.toDouble() / intervals)
            val command = "color %02x%02x%02x %d".format(Locale.ROOT, rgb[0], rgb[1], rgb[2], level)
            if (commands.isNotEmpty() && commands.last() == command) {
                waits[waits.size - 1] = waits.last() + spacing
            } else {
                commands += command
                waits += spacing
            }
        }
        val lines = mutableListOf("# ${comment(overSeconds)}")
        commands.forEachIndexed { index, command ->
            lines += command
            if (index < commands.size - 1) lines += "wait ${formatWait(waits[index])}"
        }
        return lines.joinToString("\n") + "\n"
    }

    /**
     * Plan a ramp that ends at the next [hour]:[minute] on [zone]'s clock, compressing it when it
     * would have had to start in the past. Throws [TimerException] when under a minute remains.
     */
    fun plan(hour: Int, minute: Int, overSeconds: Int, nowMillis: Long, zone: ZoneId): SunrisePlan {
        val wake = nextLocalTime(hour, minute, nowMillis, zone)
        val start = wake - overSeconds * 1000L
        if (start >= nowMillis) return SunrisePlan(start, wake, overSeconds, false)
        val remaining = ((wake - nowMillis) / 1000).toInt()
        if (remaining < MIN_SECONDS) {
            throw TimerException("too close to the wake time for a ramp; use a shorter duration or a later time")
        }
        return SunrisePlan(nowMillis, wake, remaining, true)
    }

    /**
     * The first instant after [nowMillis] at which the clock reads [hour]:[minute]. A repeated time
     * (autumn) resolves to the first occurrence still ahead; a skipped time (spring) shifts forward.
     */
    fun nextLocalTime(hour: Int, minute: Int, nowMillis: Long, zone: ZoneId): Long {
        val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        for (offset in 0L..2L) {
            val first = ZonedDateTime.of(today.plusDays(offset), LocalTime.of(hour, minute), zone)
            val future = listOf(first, first.withLaterOffsetAtOverlap())
                .map { it.toInstant().toEpochMilli() }
                .filter { it > nowMillis }
            if (future.isNotEmpty()) return future.min()
        }
        throw TimerException("could not find the next %02d:%02d".format(Locale.ROOT, hour, minute))
    }

    /** Python's `f"{seconds:.3f}".rstrip("0").rstrip(".") + "s"`, with round-half-even on the exact value. */
    private fun formatWait(seconds: Double): String {
        val text = BigDecimal(seconds).setScale(3, RoundingMode.HALF_EVEN).toPlainString()
        return text.trimEnd('0').trimEnd('.') + "s"
    }
}
