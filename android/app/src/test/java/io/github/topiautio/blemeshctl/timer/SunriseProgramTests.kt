package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.script.BleMeshScript
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SunriseProgramTests {
    private val helsinki = ZoneId.of("Europe/Helsinki")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(helsinki).toInstant().toEpochMilli()

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun matchesPythonForShortRamps() {
        for ((seconds, expected) in SunriseGoldenVectors.texts) {
            assertEquals("$seconds s", expected, SunriseProgram.text(seconds))
        }
    }

    @Test
    fun matchesPythonForOtherDurationsByHash() {
        for ((seconds, expected) in SunriseGoldenVectors.hashes) {
            assertEquals("$seconds s", expected, sha256(SunriseProgram.text(seconds)))
        }
    }

    @Test
    fun framesStartDeepRedAndEndWarmWhite() {
        val (start, startLevel) = SunriseProgram.frame(0.0)
        assertEquals(listOf(255, 0, 0), start.toList())
        assertEquals(1, startLevel)
        val (end, endLevel) = SunriseProgram.frame(1.0)
        assertEquals(listOf(255, 0xB0, 0x60), end.toList())
        assertEquals(100, endLevel)
        val (middle, middleLevel) = SunriseProgram.frame(0.15)
        assertEquals(listOf(255, 30, 0), middle.toList())
        assertEquals(11, middleLevel)
    }

    @Test
    fun programsParseAndNeverTurnTheLightOff() {
        for (seconds in listOf(60, 120, 1800, 21600)) {
            val text = SunriseProgram.text(seconds)
            BleMeshScript.parse(text, "sunrise")
            assertFalse(text.lines().any { it == "off" })
            assertTrue(text.trimEnd().lines().last() == "color ffb060 100")
            assertTrue(text.lines().count { it.startsWith("color ") } <= 301)
        }
    }

    @Test
    fun rejectsDurationsOutOfRange() {
        assertThrows(TimerException::class.java) { SunriseProgram.text(59) }
        assertThrows(TimerException::class.java) { SunriseProgram.text(21601) }
        assertEquals("# sunrise 2m", SunriseProgram.text(120).lines().first())
    }

    @Test
    fun wakeTimeLaterTodayGetsAFullRamp() {
        val now = millis(2026, 11, 14, 6, 0)
        val plan = SunriseProgram.plan(7, 0, 1800, now, helsinki)
        assertEquals(millis(2026, 11, 14, 7, 0), plan.wakeMillis)
        assertEquals(millis(2026, 11, 14, 6, 30), plan.startMillis)
        assertEquals(1800 to false, plan.overSeconds to plan.compressed)
    }

    @Test
    fun wakeTimeAlreadyPassedMeansTomorrow() {
        val plan = SunriseProgram.plan(7, 0, 1800, millis(2026, 11, 14, 21, 20), helsinki)
        assertEquals(millis(2026, 11, 15, 7, 0), plan.wakeMillis)
        val same = SunriseProgram.plan(7, 0, 1800, millis(2026, 11, 14, 7, 0), helsinki)
        assertEquals(millis(2026, 11, 15, 7, 0), same.wakeMillis)
    }

    @Test
    fun lateStartIsCompressedAndExactlyEnoughTimeIsNot() {
        val now = millis(2026, 11, 14, 6, 48, 30) + 500
        val plan = SunriseProgram.plan(7, 0, 1800, now, helsinki)
        assertTrue(plan.compressed)
        assertEquals(now, plan.startMillis)
        assertEquals(689, plan.overSeconds)
        assertFalse(SunriseProgram.plan(7, 0, 1800, millis(2026, 11, 14, 6, 30), helsinki).compressed)
    }

    @Test
    fun refusesWhenLessThanAMinuteRemains() {
        for (now in listOf(millis(2026, 11, 14, 6, 59, 30), millis(2026, 11, 14, 6, 59, 1))) {
            assertThrows(TimerException::class.java) { SunriseProgram.plan(7, 0, 1800, now, helsinki) }
        }
        assertEquals(60, SunriseProgram.plan(7, 0, 1800, millis(2026, 11, 14, 6, 59), helsinki).overSeconds)
    }

    @Test
    fun clockChangesChangeTheRealLengthOfTheNight() {
        val spring = millis(2026, 3, 28, 23, 0)
        assertEquals(7 * 3600 * 1000L, SunriseProgram.plan(7, 0, 1800, spring, helsinki).wakeMillis - spring)
        val autumn = millis(2026, 10, 24, 23, 0)
        assertEquals(9 * 3600 * 1000L, SunriseProgram.plan(7, 0, 1800, autumn, helsinki).wakeMillis - autumn)
    }

    @Test
    fun skippedAndRepeatedWallTimesStillResolveAhead() {
        val skippedNow = millis(2026, 3, 29, 1, 0)
        val skipped = SunriseProgram.nextLocalTime(3, 30, skippedNow, helsinki)
        assertTrue(skipped > skippedNow && skipped - skippedNow < 3 * 3600 * 1000L)

        val first = java.time.ZonedDateTime.of(2026, 10, 25, 3, 30, 0, 0, helsinki).toInstant().toEpochMilli()
        val second = first + 3600 * 1000L
        assertEquals(first, SunriseProgram.nextLocalTime(3, 30, first - 60_000, helsinki))
        assertEquals(second, SunriseProgram.nextLocalTime(3, 30, first + 60_000, helsinki))
    }
}
