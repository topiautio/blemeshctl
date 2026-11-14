package io.github.topiautio.blemeshctl.timer

import java.math.BigInteger
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Raised when a timer or fade request is invalid. */
class TimerException(message: String) : IllegalArgumentException(message)

/** Parses and formats whole-second timer durations such as `30s`, `20m`, `2h`, or `1h30m`. */
object TimerDuration {
    const val MIN_SECONDS = 1L
    const val MAX_SECONDS = 24L * 60 * 60

    private val PATTERN = Regex("(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9]+)s)?")

    /** Return whole seconds for `30s`, `20m`, `2h`, `1h30m` and similar, from 1 s to 24 h. */
    fun parse(text: String): Long {
        val cleaned = text.trim().lowercase(Locale.ROOT)
        val match = if (cleaned.isEmpty()) null else PATTERN.matchEntire(cleaned)
        if (match == null) {
            throw TimerException(
                "invalid duration ${repr(text)}: use a whole number with units such as 30s, 20m, 2h, or 1h30m",
            )
        }
        val (hours, minutes, seconds) = match.destructured
        val total = BigInteger.valueOf(3600) * part(hours) +
            BigInteger.valueOf(60) * part(minutes) + part(seconds)
        if (total < BigInteger.valueOf(MIN_SECONDS)) {
            throw TimerException("duration ${repr(text)} is too short; the minimum is 1s")
        }
        if (total > BigInteger.valueOf(MAX_SECONDS)) {
            throw TimerException("duration ${repr(text)} is too long; the maximum is 24h")
        }
        return total.toLong()
    }

    /** Format seconds (rounded up) as `1h30m`, `20m`, or `45s`; negative values become `0s`. */
    fun format(seconds: Double): String {
        var remaining = max(0.0, ceil(seconds)).toLong()
        val hours = remaining / 3600
        remaining %= 3600
        val minutes = remaining / 60
        val secs = remaining % 60
        val text = buildString {
            if (hours > 0) append(hours).append('h')
            if (minutes > 0) append(minutes).append('m')
            if (secs > 0) append(secs).append('s')
        }
        return text.ifEmpty { "0s" }
    }

    fun format(seconds: Long): String = format(seconds.toDouble())

    private fun part(digits: String): BigInteger = if (digits.isEmpty()) BigInteger.ZERO else BigInteger(digits)

    /** Quote like Python's repr() for the common cases, so messages match the command-line tool. */
    private fun repr(text: String): String {
        val quote = if ('\'' in text && '"' !in text) '"' else '\''
        val escaped = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
        return quote + (if (quote == '\'') escaped.replace("'", "\\'") else escaped) + quote
    }
}
