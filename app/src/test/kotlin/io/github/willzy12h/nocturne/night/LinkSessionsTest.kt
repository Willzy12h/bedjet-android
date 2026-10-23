package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.ble.assertThrows
import io.github.willzy12h.nocturne.ble.eventually
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** [LinkSessions] over a link whose connection the test finishes by hand. */
class LinkSessionsTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val link = SlowLink()
    @Volatile private var now = 1_000_000L
    private val sessions = LinkSessions(scope, { link }, { now }, graceMs = 10_000)

    @After
    fun stop() {
        scope.cancel()
        executor.shutdown()
    }

    @Test
    fun aConnectThatFinishesAfterTheLastReleaseIsDisconnectedAtOnce() = runBlocking {
        link.ignoresCancellation = true
        sessions.holdForScreen(true)
        eventually { link.connects.get() == 1 }
        sessions.holdForScreen(false)
        delay(50)
        link.gate.complete(Unit)
        // Well inside the grace period: only an immediate disconnect passes.
        eventually(timeoutMs = 1_000) { link.state.value == LinkState.SEARCHING && link.disconnects.get() > 0 }
    }

    @Test
    fun theLastReleaseCancelsAConnectStillInProgress() = runBlocking {
        sessions.holdForScreen(true)
        eventually { link.connects.get() == 1 }
        sessions.holdForScreen(false)
        eventually(timeoutMs = 1_000) { link.cancelled.get() == 1 }
        assertEquals(LinkState.SEARCHING, link.state.value)
    }

    @Test
    fun anotherHolderKeepsTheConnectGoing() = runBlocking {
        val acquiring = scope.launch { sessions.acquire() }
        sessions.holdForScreen(true)
        eventually { link.connects.get() >= 1 }
        sessions.holdForScreen(false)
        delay(50)
        assertEquals(0, link.cancelled.get())
        link.gate.complete(Unit)
        acquiring.join()
        assertEquals(LinkState.READY, link.state.value)
    }

    @Test
    fun cancellingAnAcquireKeepsTheHoldersBalanced() = runBlocking {
        val sessions = LinkSessions(scope, { link }, { now }, graceMs = 50)
        val acquiring = scope.launch { sessions.acquire() }
        eventually { link.connects.get() == 1 }
        acquiring.cancelAndJoin()
        link.gate.complete(Unit)
        sessions.withConnection { }
        assertEquals(LinkState.READY, link.state.value)
        // Had the cancelled acquire kept its hold, nothing would let go of the connection.
        eventually { link.state.value == LinkState.SEARCHING }
    }

    @Test
    fun suspendingAutoConnectReleasesTheBedJetUntilResumed() = runBlocking {
        sessions.holdForScreen(true)
        link.gate.complete(Unit)
        eventually { link.state.value == LinkState.READY }
        sessions.suspendAutoConnect(untilMs = null)
        assertTrue(sessions.released.value)
        eventually { link.state.value == LinkState.SEARCHING }
        val error = assertThrows<BedJetError.NotConnected> { sessions.acquire() }
        assertEquals("Released for another phone", error.message)
        sessions.resumeAutoConnect()
        assertFalse(sessions.released.value)
        sessions.withConnection { }
        assertEquals(LinkState.READY, link.state.value)
    }

    @Test
    fun aTimedReleaseEndsByItself() = runBlocking {
        link.gate.complete(Unit)
        sessions.suspendAutoConnect(untilMs = now + 60_000)
        assertThrows<BedJetError.NotConnected> { sessions.acquire() }
        now += 60_000
        sessions.withConnection { }
        assertFalse(sessions.released.value)
    }

    @Test
    fun aBackwardClockChangeStillEndsTheReleaseWithoutAnAcquire() = runBlocking {
        link.gate.complete(Unit)
        sessions.holdForScreen(true)
        eventually { link.state.value == LinkState.READY }
        val deadline = now + 80
        sessions.suspendAutoConnect(untilMs = deadline)
        eventually { link.state.value == LinkState.SEARCHING }
        now -= 80
        delay(100) // The first scheduled check has passed while the wall clock is still before the deadline.
        assertTrue(sessions.released.value)

        now = deadline
        eventually(timeoutMs = 1_000) { !sessions.released.value && link.state.value == LinkState.READY }
    }

    /** A link whose connect waits for [gate]. */
    class SlowLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.SEARCHING)
        override val status = MutableStateFlow<DeviceStatus?>(null)
        val gate = CompletableDeferred<Unit>()
        @Volatile var ignoresCancellation = false
        val connects = AtomicInteger()
        val cancelled = AtomicInteger()
        val disconnects = AtomicInteger()

        override suspend fun connect() {
            if (state.value == LinkState.READY) return
            connects.incrementAndGet()
            try {
                if (ignoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
            state.value = LinkState.READY
        }

        override suspend fun disconnect() {
            disconnects.incrementAndGet()
            state.value = LinkState.SEARCHING
        }

        override suspend fun send(command: ByteArray) = Unit
        override suspend fun query(request: Int) = ByteArray(0)
    }
}
