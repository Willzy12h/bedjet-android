package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.ble.FakeRadio
import io.github.willzy12h.nocturne.ble.GattLink
import io.github.willzy12h.nocturne.ble.GattLinkTest
import io.github.willzy12h.nocturne.ble.assertThrows
import io.github.willzy12h.nocturne.ble.eventually
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
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
        // Wait for the release to let go, so its cancel can't land on the connect below.
        sessions.suspendAutoConnect(untilMs = now + 60_000).join()
        assertThrows<BedJetError.NotConnected> { sessions.acquire() }
        now += 60_000
        sessions.withConnection { }
        assertFalse(sessions.released.value)
    }

    @Test
    fun aTimedReleaseStillEndsAfterTheClockMovesBack() = runBlocking {
        link.gate.complete(Unit)
        val until = now + 1_000
        sessions.suspendAutoConnect(untilMs = until)
        // The wall clock is corrected backwards during the release, so when its timer runs out it is not yet over.
        now -= 1_500
        delay(1_300)
        assertTrue(sessions.released.value)
        // Once the wall clock does pass the deadline, the release ends by itself.
        now = until + 1
        eventually(timeoutMs = 4_000) { !sessions.released.value }
    }

    @Test
    fun aTimedReleaseThatEndsAfterATargetSwitchOnlyConnectsToTheNewBedJet() = runBlocking {
        val radio = FakeRadio()
        var target: String? = DeviceId.ble(GattLinkTest.ADDRESS)
        val gatt = GattLink(radio.radio, { target }, GattLinkTest.TIMINGS)
        val sessions = LinkSessions(scope, { gatt }, { now }, graceMs = 10_000)
        try {
            sessions.holdForScreen(true)
            eventually { gatt.state.value == LinkState.READY }
            val until = now + 1_000
            sessions.suspendAutoConnect(untilMs = until).join()
            assertEquals(LinkState.SEARCHING, gatt.state.value)
            target = DeviceId.ble(GattLinkTest.OTHER)
            val before = radio.connects.size
            now = until
            // The release runs out by itself and the screen's hold reconnects.
            eventually(timeoutMs = 4_000) { !sessions.released.value && gatt.state.value == LinkState.READY }
            assertEquals(listOf(GattLinkTest.OTHER), radio.connects.drop(before))
            assertTrue(radio.ports.filter { it.address == GattLinkTest.ADDRESS }.all { it.closed })
        } finally {
            gatt.shutdown()
        }
    }

    @Test
    fun aScreenReconnectDuringATargetSwitchOnlyConnectsToTheNewBedJet() = runBlocking {
        val radio = FakeRadio()
        var target: String? = DeviceId.ble(GattLinkTest.ADDRESS)
        val gatt = GattLink(radio.radio, { target }, GattLinkTest.TIMINGS)
        val sessions = LinkSessions(scope, { gatt }, { now }, graceMs = 10_000)
        try {
            sessions.holdForScreen(true)
            eventually { gatt.state.value == LinkState.READY }
            // The switch lets go of the old unit; the screen's reconnect lands before the new target is saved.
            gatt.disconnect()
            sessions.reconnect()
            eventually { radio.connects.size == 2 && gatt.state.value == LinkState.READY }
            target = DeviceId.ble(GattLinkTest.OTHER)
            val before = radio.connects.size
            sessions.reconnect()
            eventually { gatt.state.value == LinkState.READY && radio.connects.size > before }
            assertEquals(listOf(GattLinkTest.OTHER), radio.connects.drop(before))
            assertTrue(radio.ports.filter { it.address == GattLinkTest.ADDRESS }.all { it.closed })
        } finally {
            gatt.shutdown()
        }
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
