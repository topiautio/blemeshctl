package io.github.topiautio.blemeshctl.timer

import android.content.Context
import android.os.PowerManager

/**
 * Holds a partial wake lock only while some timer is RUNNING, never during the wait before a
 * timer fires. This is the only wake-lock code in the app: delete it, its calls in
 * [TimerService], and the WAKE_LOCK permission if a Doze test shows it is unnecessary.
 */
class TimerWakeLock(private val acquire: () -> Unit, private val release: () -> Unit) {
    private var held = false

    /** Acquire or release the lock to match whether any timer is running. */
    @Synchronized
    fun update(running: Boolean) {
        if (running == held) return
        if (running) {
            acquire()
            held = true
        } else {
            held = false
            release()
        }
    }

    /** Always release; safe to call repeatedly. */
    @Synchronized
    fun close() = update(false)

    companion object {
        /** A fade can run for 24 hours; the lock times out a little after that as a safety net. */
        private const val TIMEOUT_MILLIS = 25L * 60 * 60 * 1000

        fun create(context: Context): TimerWakeLock {
            val power = context.getSystemService(PowerManager::class.java)
            val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blemeshctl:timer")
                ?.apply { setReferenceCounted(false) }
            return TimerWakeLock(
                acquire = { lock?.acquire(TIMEOUT_MILLIS) },
                release = { if (lock?.isHeld == true) lock.release() },
            )
        }
    }
}
