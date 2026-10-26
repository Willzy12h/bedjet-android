package io.github.willzy12h.nocturne.data

/**
 * Admission is synchronous, before any queue or suspension. A command lease covers waiting as well as execution;
 * import takes exclusive admission before joining the repository writer. Neither side waits for the other.
 */
class OperationAdmission {
    private var commands = 0
    private var importing = false

    @Synchronized fun tryCommand(): Lease? {
        if (importing) return null
        commands++
        return Lease(false)
    }

    @Synchronized fun tryImport(): Lease? {
        if (importing || commands != 0) return null
        importing = true
        return Lease(true)
    }

    @Synchronized fun isIdle(): Boolean = !importing && commands == 0

    /** Cancellation and service completion may both close the same admission; release happens exactly once. */
    inner class Lease internal constructor(private val restore: Boolean) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(this@OperationAdmission) {
            if (!closed) {
                closed = true
                if (restore) importing = false else commands--
            }
        }
    }
}
