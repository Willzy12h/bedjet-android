package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shares the one BedJet connection between the screen and background work. The BedJet takes one phone at a time,
 * so the connection is let go a few seconds after nobody needs it.
 */
class LinkSessions(private val graph: Graph) {
    private val lock = Mutex()
    private var holders = 0
    private var release: Job? = null
    private var held: BedJetLink? = null

    suspend fun <T> withConnection(work: suspend (BedJetLink) -> T): T {
        val link = acquire()
        try {
            return work(link)
        } finally {
            releaseLater()
        }
    }

    /** Connects (if needed) and keeps the connection until a matching [releaseLater]. */
    suspend fun acquire(): BedJetLink {
        val link = lock.withLock {
            holders++
            release?.cancel()
            val current = graph.link
            // Demo mode was switched; let go of the other link.
            if (held != null && held !== current) held?.disconnect()
            held = current
            current
        }
        try {
            link.connect()
        } catch (e: Exception) {
            releaseLater()
            throw e
        }
        return link
    }

    fun releaseLater() {
        graph.scope.launch {
            lock.withLock {
                holders = maxOf(0, holders - 1)
                if (holders > 0) return@withLock
                release?.cancel()
                release = graph.scope.launch {
                    delay(GRACE_MS)
                    lock.withLock { if (holders == 0) { held?.disconnect(); held = null } }
                }
            }
        }
    }

    private companion object {
        const val GRACE_MS = 5_000L
    }
}
