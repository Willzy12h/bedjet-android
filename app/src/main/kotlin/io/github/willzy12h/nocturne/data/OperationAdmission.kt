package io.github.willzy12h.nocturne.data

import kotlinx.coroutines.CompletableDeferred

/**
 * Admission happens before any queue or suspension. Ordinary commands refuse an active import; an import refuses
 * all pending/running commands. Already-delivered local receiver events retain their place: if import arrived
 * first, they wait on its one-shot completion inside the receiver's existing time budget. Their queued leases
 * prevent a later import from jumping ahead. No polling, extra service or hardware owner is introduced.
 */
class OperationAdmission {
    private var commands = 0
    private var importing = false
    private var importFinished: CompletableDeferred<Unit>? = null

    @Synchronized fun tryCommand(): Lease? {
        if (importing) return null
        commands++
        return Lease(false)
    }

    /** Capture an actual received local delivery synchronously; await readiness before its coroutine does work. */
    @Synchronized fun queueLocalCommand(): Lease {
        commands++
        return Lease(false, importFinished)
    }

    @Synchronized fun tryImport(): Lease? {
        if (importing || commands != 0) return null
        importing = true
        importFinished = CompletableDeferred()
        return Lease(true)
    }

    @Synchronized fun isIdle(): Boolean = !importing && commands == 0

    /** Cancellation and completion may both close an admission; release happens exactly once. */
    inner class Lease internal constructor(private val restore: Boolean,
        private val waitFor: CompletableDeferred<Unit>? = null) : AutoCloseable {
        private var closed = false
        suspend fun awaitReady() { waitFor?.await() }
        override fun close() {
            val wake = synchronized(this@OperationAdmission) {
                if (closed) return
                closed = true
                if (restore) {
                    importing = false
                    importFinished.also { importFinished = null }
                } else {
                    commands--
                    null
                }
            }
            // Resume receivers outside the monitor; their leases already block a subsequent import.
            wake?.complete(Unit)
        }
    }
}
