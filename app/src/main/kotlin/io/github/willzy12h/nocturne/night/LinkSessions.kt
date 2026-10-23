package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shares the one BedJet connection between the screen and background work. The BedJet takes one phone at a time,
 * so the connection is let go a few seconds after nobody needs it, and a connect still under way when the last
 * holder lets go is called off. Every hold is undone exactly once, so one holder letting go can't drop a
 * connection another is still writing over.
 */
class LinkSessions(
    private val scope: CoroutineScope,
    private val currentLink: () -> BedJetLink,
    private val now: () -> Long,
    private val graceMs: Long = GRACE_MS,
) {
    constructor(graph: Graph) : this(graph.scope, { graph.link }, { graph.clock.millis() })

    private val lock = Mutex()
    private var holders = 0
    private var release: Job? = null
    private var held: BedJetLink? = null
    /** Connects under way, so the last release can call them off. */
    private val connects = mutableSetOf<Job>()
    @Volatile private var screenHold = false

    private val _released = MutableStateFlow(false)
    /** True while the BedJet is released for another phone; see [suspendAutoConnect]. */
    val released: StateFlow<Boolean> = _released.asStateFlow()
    @Volatile private var releasedUntil: Long? = null
    private var releaseEnds: Job? = null

    suspend fun <T> withConnection(work: suspend (BedJetLink) -> T): T {
        val link = acquire()
        try {
            return work(link)
        } finally {
            releaseLater()
        }
    }

    /**
     * Connects (if needed) and keeps the connection until a matching [releaseLater]. On failure or cancellation
     * nothing is held. While the BedJet is released for another phone, this throws [BedJetError.NotConnected].
     */
    suspend fun acquire(): BedJetLink {
        val link = hold()
        try {
            if (isReleased()) throw BedJetError.NotConnected(RELEASED)
            connect(link)
        } catch (e: Throwable) {
            releaseLater()
            throw e
        }
        return link
    }

    fun releaseLater() {
        scope.launch {
            lock.withLock {
                holders = maxOf(0, holders - 1)
                if (holders > 0) return@withLock
                // Nobody needs the connection any more, so a connect still under way is called off.
                connects.forEach { it.cancel() }
                release?.cancel()
                release = scope.launch {
                    delay(graceMs)
                    lock.withLock { if (holders == 0) { held?.disconnect(); held = null } }
                }
            }
        }
    }

    /**
     * Holds the connection while the app is on screen, so the controls are live. Saying the same thing twice does
     * nothing. The hold stays even if connecting fails, or the BedJet is released, so [reconnect] can try again.
     */
    fun holdForScreen(hold: Boolean) {
        if (hold == screenHold) return
        screenHold = hold
        if (hold) {
            scope.launch {
                val link = hold()
                if (!isReleased()) runCatching { connect(link) }
            }
        } else {
            releaseLater()
        }
    }

    /** Tries the connection again while the screen holds it, or takes the screen's hold if it doesn't. */
    fun reconnect() {
        if (!screenHold) return holdForScreen(true)
        scope.launch { runCatching { withConnection { } } }
    }

    /**
     * Lets go of the BedJet now so another phone can connect, and refuses [acquire] until [resumeAutoConnect] or,
     * when [untilMs] is given, until that time.
     */
    fun suspendAutoConnect(untilMs: Long?): Job {
        releasedUntil = untilMs ?: Long.MAX_VALUE
        _released.value = true
        releaseEnds?.cancel()
        // Checking once the time has come ends the release. The timer runs on monotonic time and the deadline is
        // wall-clock time, so it checks again until the wall clock has really passed it: a clock moved back during
        // the release would otherwise leave it released for good.
        releaseEnds = untilMs?.let { until ->
            scope.launch { while (isReleased()) delay((until - now()).coerceIn(RECHECK_MIN_MS, RECHECK_MAX_MS)) }
        }
        return scope.launch {
            lock.withLock {
                connects.forEach { it.cancel() }
                (held ?: currentLink()).disconnect()
            }
        }
    }

    fun resumeAutoConnect() {
        releaseEnds?.cancel()
        endRelease()
    }

    private fun endRelease() {
        releasedUntil = null
        if (!_released.value) return
        _released.value = false
        if (screenHold) reconnect()
    }

    /** Whether the BedJet is released for another phone; a timed release that has run out ends here. */
    private fun isReleased(): Boolean {
        val until = releasedUntil ?: return false
        if (now() < until) return true
        endRelease()
        return false
    }

    private suspend fun hold(): BedJetLink = lock.withLock {
        holders++
        release?.cancel()
        val current = currentLink()
        // Demo mode was switched; let go of the other link.
        if (held != null && held !== current) held?.disconnect()
        held = current
        current
    }

    /**
     * Connects [link] in a job of its own, which the last release can cancel without cancelling the caller.
     * If the connect finishes after everyone has let go, the connection is dropped at once.
     */
    private suspend fun connect(link: BedJetLink) = coroutineScope {
        val attempt = async(start = CoroutineStart.LAZY) { link.connect() }
        val wanted = lock.withLock { (holders > 0).also { if (it) connects += attempt } }
        if (!wanted) {
            attempt.cancel()
            throw BedJetError.NotConnected()
        }
        try {
            attempt.await()
        } catch (e: CancellationException) {
            ensureActive()
            // Called off by the last release or by releasing the BedJet, not by our caller.
            throw if (isReleased()) BedJetError.NotConnected(RELEASED) else BedJetError.NotConnected()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    connects -= attempt
                    if (holders == 0 || isReleased()) link.disconnect()
                }
            }
        }
    }

    private companion object {
        const val GRACE_MS = 5_000L
        /** The shortest wait between checks of a timed release, so a deadline a moment away is no busy loop. */
        const val RECHECK_MIN_MS = 250L
        /**
         * The longest, so a clock moved far back and then corrected (network time) is noticed within a minute rather
         * than after the whole stale remainder.
         */
        const val RECHECK_MAX_MS = 60_000L
        const val RELEASED = "Released for another phone"
    }
}
