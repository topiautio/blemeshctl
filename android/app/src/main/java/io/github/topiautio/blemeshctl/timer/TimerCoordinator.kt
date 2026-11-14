package io.github.topiautio.blemeshctl.timer

import kotlinx.coroutines.CoroutineScope

/** A spec the registry refused, such as a program that no longer parses. */
data class RejectedTimer(val spec: TimerSpec, val error: Exception)

/**
 * The state shared by the activity and the timer service, which live in one process.
 *
 * The activity [enqueue]s a [TimerSpec] and starts the service, which [drain]s the queue into the
 * [registry]. Specs hold mesh credentials, so they stay in memory and never travel in an intent.
 */
class TimerCoordinator(val registry: TimerRegistry = TimerRegistry()) {
    private val lock = Any()
    private val pending = ArrayList<TimerSpec>()
    private val addressesById = HashMap<String, Set<String>>()
    private val notified = HashSet<String>()

    fun enqueue(spec: TimerSpec) {
        synchronized(lock) { pending += spec }
    }

    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    /** Forget queued specs, for when the service could not be started. */
    fun discardPending(): Int = synchronized(lock) { pending.size.also { pending.clear() } }

    /**
     * Schedule every queued spec in [scope]. [senderFor] builds the sender that delivers a spec's
     * steps. Returns the specs that were rejected.
     */
    fun drain(scope: CoroutineScope, senderFor: (TimerSpec) -> StepSender): List<RejectedTimer> {
        val rejected = mutableListOf<RejectedTimer>()
        // The lock is held while scheduling so lockedAddresses() never misses a spec in transit.
        synchronized(lock) {
            val specs = pending.toList()
            pending.clear()
            for (spec in specs) {
                try {
                    val record = registry.schedule(scope, TimerRunner(senderFor(spec)), spec.request)
                    addressesById[record.id] = spec.target.addresses
                } catch (exception: Exception) {
                    rejected += RejectedTimer(spec, exception)
                }
            }
        }
        return rejected
    }

    /** Addresses of lights that a queued or active timer will use, upper-cased. */
    fun lockedAddresses(records: List<TimerRecord> = registry.list()): Set<String> = synchronized(lock) {
        val locked = HashSet<String>()
        pending.forEach { locked += it.target.addresses }
        records.filter { it.state.isActive }.forEach { record -> addressesById[record.id]?.let { locked += it } }
        locked
    }

    /** Finished records that have not been returned before; each timer is reported once. */
    fun takeFinished(records: List<TimerRecord>): List<TimerRecord> = synchronized(lock) {
        records.filter { !it.state.isActive && notified.add(it.id) }
    }
}

/** The process-wide coordinator shared by the activity and [TimerService]. */
object TimerHub {
    val coordinator = TimerCoordinator()
}
