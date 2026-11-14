package io.github.topiautio.blemeshctl.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerWakeLockTests {
    private val events = mutableListOf<String>()
    private val lock = TimerWakeLock({ events += "acquire" }, { events += "release" })

    @Test
    fun heldOnlyWhileATimerRuns() {
        lock.update(false)
        lock.update(true)
        lock.update(true)
        lock.update(false)
        lock.update(false)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test
    fun closeAlwaysReleasesAndIsIdempotent() {
        lock.update(true)
        lock.close()
        lock.close()
        assertEquals(listOf("acquire", "release"), events)
    }
}
