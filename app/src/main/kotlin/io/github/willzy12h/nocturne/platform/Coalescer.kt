package io.github.willzy12h.nocturne.platform

import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets a burst of the same system broadcast share runs instead of queueing one each. Some phones send several boot,
 * clock or alarm-change broadcasts in a row. At most one run is under way and one waits; a caller arriving while a
 * run waits joins that one. The waiting run starts after every caller that joined it, so the work it does (which
 * re-reads current state rather than the broadcast) still covers them all.
 *
 * Only the caller that started a run sees its failure; one that joined returns quietly, so a failure is reported
 * once. A caller's own cancellation (its receiver's timeout) still cancels that caller; callers that had joined a
 * cancelled run start another rather than go without.
 */
class Coalescer {
    private val running = Mutex()
    private val guard = Any()
    private var waiting: CompletableDeferred<Unit>? = null
    /** How many runs have started, for tests. */
    @Volatile var runsStarted = 0
        private set
    /** How many callers have arrived, for tests. */
    @Volatile var callers = 0
        private set

    /** Runs [work], or waits for the already-waiting run. Returns true if this caller ran it. */
    suspend fun run(work: suspend () -> Unit): Boolean {
        val joined: CompletableDeferred<Unit>?
        val mine: CompletableDeferred<Unit>
        synchronized(guard) {
            callers++
            joined = waiting
            mine = joined ?: CompletableDeferred<Unit>().also { waiting = it }
        }
        if (joined != null) {
            joined.join()
            // Its starter was cancelled (a receiver timeout), so that run may not have happened: run again.
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val cancelled = joined.isCancelled && joined.getCompletionExceptionOrNull() is CancellationException
            return if (cancelled) run(work) else false
        }
        try {
            running.withLock {
                // Under way now: a later broadcast must start the next run, since this one may already have read state.
                synchronized(guard) { if (waiting === mine) waiting = null }
                runsStarted++
                work()
            }
            mine.complete(Unit)
            return true
        } catch (e: Throwable) {
            synchronized(guard) { if (waiting === mine) waiting = null }
            mine.completeExceptionally(e)
            throw e
        }
    }
}
