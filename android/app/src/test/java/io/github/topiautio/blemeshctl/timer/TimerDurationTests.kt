package io.github.topiautio.blemeshctl.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimerDurationTests {
    @Test
    fun parsesValidDurations() {
        for ((text, seconds) in listOf(
            "30s" to 30L, "90s" to 90L, "20m" to 1200L, "2h" to 7200L, "1h30m" to 5400L,
            "1m30s" to 90L, " 24h " to 86400L, "1S" to 1L, "1H2M3S" to 3723L, "86400s" to 86400L,
        )) {
            assertEquals(text, seconds, TimerDuration.parse(text))
        }
    }

    @Test
    fun rejectsInvalidAndOutOfRangeDurations() {
        for (text in listOf(
            "", "  ", "0s", "0", "90", "1.5h", "-5m", "5x", "1m1h", "h", "24h1s", "25h", "1d",
            "99999999999999999999h", "86401s",
        )) {
            try {
                TimerDuration.parse(text)
                fail("expected rejection of '$text'")
            } catch (expected: TimerException) {
                assertTrue(expected.message, expected.message!!.contains("duration"))
            }
        }
    }

    @Test
    fun errorMessagesMatchThePythonTool() {
        assertEquals(
            "invalid duration 'soon': use a whole number with units such as 30s, 20m, 2h, or 1h30m",
            messageOf("soon"),
        )
        assertEquals("duration '0s' is too short; the minimum is 1s", messageOf("0s"))
        assertEquals("duration '25h' is too long; the maximum is 24h", messageOf("25h"))
        assertEquals("duration '99999999999h' is too long; the maximum is 24h", messageOf("99999999999h"))
    }

    @Test
    fun formatsDurations() {
        assertEquals("1h30m", TimerDuration.format(5400L))
        assertEquals("20m", TimerDuration.format(1200L))
        assertEquals("45s", TimerDuration.format(45L))
        assertEquals("1m30s", TimerDuration.format(89.2))
        assertEquals("0s", TimerDuration.format(0.0))
        assertEquals("0s", TimerDuration.format(-5.0))
        assertEquals("1h", TimerDuration.format(3600L))
    }

    private fun messageOf(text: String): String? = try {
        TimerDuration.parse(text)
        null
    } catch (exception: TimerException) {
        exception.message
    }
}
