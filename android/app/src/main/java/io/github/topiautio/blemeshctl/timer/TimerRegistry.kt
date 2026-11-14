package io.github.topiautio.blemeshctl.timer

import io.github.topiautio.blemeshctl.script.BleMeshScript
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What to run: the `.blemesh` [programText] at [fireAtMillis] against [target]. */
data class TimerRequest(
    val description: String,
    val target: String,
    val fireAtMillis: Long,
    val programText: String,
)

/**
 * In-memory timer list for a service plus the UI thread. Nothing is persisted: timers do not
 * survive process death, matching the command-line tool, which loses them on reboot.
 *
 * Every method is synchronized; listeners are called after the lock is released, on whichever
 * thread made the change.
 */
class TimerRegistry(
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val idGenerator: () -> String = ::randomId,
) {
    private val lock = Any()
    private val records = LinkedHashMap<String, TimerRecord>()
    private val handles = HashMap<String, () -> Unit>()
    private val listeners = CopyOnWriteArrayList<(List<TimerRecord>) -> Unit>()

    fun addListener(listener: (List<TimerRecord>) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (List<TimerRecord>) -> Unit) {
        listeners -= listener
    }

    /** Records ordered by fire time, then id, after dropping finished ones older than 24 h. */
    fun list(): List<TimerRecord> {
        val changed = synchronized(lock) { prune() }
        if (changed) notifyListeners()
        return snapshot()
    }

    fun get(id: String): TimerRecord? = synchronized(lock) { records[id] }

    /**
     * Validate [request] (a bad program throws before anything is stored), register it, and launch
     * the runner in [scope]. Cancelling the registered timer cancels that coroutine.
     */
    fun schedule(scope: CoroutineScope, runner: TimerRunner, request: TimerRequest): TimerRecord {
        val program = BleMeshScript.parse(request.programText, "timer")
        val now = clock.nowMillis()
        var created: TimerRecord? = null
        var job: Job? = null
        synchronized(lock) {
            var id = idGenerator()
            while (id in records) id = idGenerator()
            val record = TimerRecord(
                id = id,
                description = request.description,
                target = request.target,
                state = TimerState.SCHEDULED,
                createdAtMillis = now,
                fireAtMillis = request.fireAtMillis,
                steps = TimerSteps(total = TimerRunner.countCommands(program)),
            )
            records[id] = record
            created = record
            handles[id] = { job?.cancel() }
            job = scope.launch {
                try {
                    runner.run(record, program) { update(it) }
                } finally {
                    synchronized(lock) { handles.remove(id) }
                }
            }
        }
        notifyListeners()
        return checkNotNull(created)
    }

    /**
     * Register an externally run timer. [cancel] is invoked by [cancel]; progress is reported
     * through [update].
     */
    fun add(record: TimerRecord, cancel: () -> Unit = {}) {
        synchronized(lock) {
            records[record.id] = record
            handles[record.id] = cancel
        }
        notifyListeners()
    }

    /** Apply a runner's progress report. A finished record only accepts a CANCELLED refinement. */
    fun update(record: TimerRecord) {
        val accepted = synchronized(lock) {
            val existing = records[record.id] ?: return@synchronized false
            if (!existing.state.isActive &&
                !(existing.state == TimerState.CANCELLED && record.state == TimerState.CANCELLED)
            ) {
                return@synchronized false
            }
            records[record.id] = record
            if (!record.state.isActive) handles.remove(record.id)
            true
        }
        if (accepted) notifyListeners()
    }

    /** Cancel one timer. Returns null when cancelled, else why it was not. */
    fun cancel(id: String): String? {
        val handle: (() -> Unit)?
        synchronized(lock) {
            val record = records[id] ?: return "no timer '$id'"
            if (!record.state.isActive) {
                return "timer $id already finished (${record.state.name.lowercase()})"
            }
            records[id] = markCancelled(record)
            handle = handles.remove(id)
        }
        handle?.invoke()
        notifyListeners()
        return null
    }

    /** Cancel every scheduled or running timer; returns how many. */
    fun cancelAll(): Int {
        val toCancel = synchronized(lock) { records.values.filter { it.state.isActive }.map { it.id } }
        return toCancel.count { cancel(it) == null }
    }

    /** Remove finished, failed, and cancelled records; returns how many. */
    fun clear(): Int {
        val removed = synchronized(lock) {
            val finished = records.values.filter { !it.state.isActive }.map { it.id }
            finished.forEach { records.remove(it) }
            finished.size
        }
        if (removed > 0) notifyListeners()
        return removed
    }

    private fun markCancelled(record: TimerRecord): TimerRecord = record.copy(
        state = TimerState.CANCELLED,
        finishedAtMillis = clock.nowMillis(),
        outcome = if (record.state == TimerState.SCHEDULED) {
            "cancelled before it fired"
        } else {
            "cancelled while running; the light keeps its last state"
        },
    )

    /** Drop finished records past retention; the caller must hold the lock. */
    private fun prune(): Boolean {
        val now = clock.nowMillis()
        val expired = records.values.filter { record ->
            val finished = record.finishedAtMillis
            !record.state.isActive && finished != null && now - finished > FINISHED_RETENTION_MILLIS
        }
        expired.forEach { records.remove(it.id) }
        return expired.isNotEmpty()
    }

    private fun snapshot(): List<TimerRecord> = synchronized(lock) {
        records.values.sortedWith(compareBy({ it.fireAtMillis }, { it.id }))
    }

    private fun notifyListeners() {
        val current = snapshot()
        for (listener in listeners) listener(current)
    }

    companion object {
        const val FINISHED_RETENTION_MILLIS = 24L * 60 * 60 * 1000

        private val random = SecureRandom()

        /** Six lowercase hexadecimal digits, like the command-line tool's timer ids. */
        fun randomId(): String = "%06x".format(random.nextInt(0x1000000))
    }
}
