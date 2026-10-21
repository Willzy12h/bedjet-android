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
 * so the connection is let go a few seconds after nobody needs it. Every hold is undone exactly once, so one
 * holder letting go can't drop a connection another is still writing over.
 */
class LinkSessions(private val graph: Graph) {
    private val lock = Mutex()
    private var holders = 0
    private var release: Job? = null
    private var held: BedJetLink? = null
    private var screenHold = false

    suspend fun <T> withConnection(work: suspend (BedJetLink) -> T): T {
        val link = acquire()
        try {
            return work(link)
        } finally {
            releaseLater()
        }
    }

    /** Connects (if needed) and keeps the connection until a matching [releaseLater]. On failure nothing is held. */
    suspend fun acquire(): BedJetLink {
        val link = hold()
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

    /**
     * Holds the connection while the app is on screen, so the controls are live. Saying the same thing twice does
     * nothing. The hold stays even if connecting fails, so [reconnect] can try again.
     */
    fun holdForScreen(hold: Boolean) {
        if (hold == screenHold) return
        screenHold = hold
        if (hold) {
            graph.scope.launch {
                val link = hold()
                runCatching { link.connect() }
            }
        } else {
            releaseLater()
        }
    }

    /** Tries the connection again while the screen holds it, or takes the screen's hold if it doesn't. */
    fun reconnect() {
        if (!screenHold) return holdForScreen(true)
        graph.scope.launch { runCatching { withConnection { } } }
    }

    private suspend fun hold(): BedJetLink = lock.withLock {
        holders++
        release?.cancel()
        val current = graph.link
        // Demo mode was switched; let go of the other link.
        if (held != null && held !== current) held?.disconnect()
        held = current
        current
    }

    private companion object {
        const val GRACE_MS = 5_000L
    }
}
