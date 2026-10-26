package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The real session holder, with virtual time only replacing coroutine scheduling. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundConnectionTest {
    @Test fun hidingKeepsTheReadyConnectionUntilThirtySecondsThenDisconnects() = runTest {
        val link = ReadyLink()
        val sessions = LinkSessions(backgroundScope, { link }, { testScheduler.currentTime })
        sessions.holdForScreen(true)
        runCurrent()
        assertEquals(LinkState.READY, link.state.value)
        sessions.holdForScreen(false)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals("Foreground grace must last 30 seconds", LinkState.READY, link.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(LinkState.SEARCHING, link.state.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("An idle app must not reconnect", 1, link.connects)
    }

    @Test fun returningToTheForegroundCancelsThePendingDisconnect() = runTest {
        val link = ReadyLink()
        val sessions = LinkSessions(backgroundScope, { link }, { testScheduler.currentTime })
        sessions.holdForScreen(true)
        runCurrent()
        sessions.holdForScreen(false)
        runCurrent()
        advanceTimeBy(20_000)
        sessions.holdForScreen(true)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(LinkState.READY, link.state.value)
        assertEquals("Return during grace should reuse the connection", 1, link.connects)
        assertEquals(0, link.disconnects)
    }

    @Test fun aBackgroundOwnerRetainsItsConnectionUntilItsWorkFinishes() = runTest {
        val link = ReadyLink()
        val sessions = LinkSessions(backgroundScope, { link }, { testScheduler.currentTime })
        sessions.holdForScreen(true)
        runCurrent()
        val finish = CompletableDeferred<Unit>()
        val work = backgroundScope.launch { sessions.withConnection { finish.await() } }
        runCurrent()
        sessions.holdForScreen(false)
        runCurrent()
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals("Screen release must not cut off a command", LinkState.READY, link.state.value)
        assertEquals(0, link.disconnects)
        finish.complete(Unit)
        runCurrent()
        work.join()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(LinkState.SEARCHING, link.state.value)
    }

    @Test fun backgroundWorkConnectsOnDemandWithoutAnIdleReconnectLoop() = runTest {
        val link = ReadyLink()
        val sessions = LinkSessions(backgroundScope, { link }, { testScheduler.currentTime })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, link.connects)
        sessions.withConnection { assertEquals(LinkState.READY, it.state.value) }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(LinkState.SEARCHING, link.state.value)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, link.connects)
    }

    private class ReadyLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.SEARCHING)
        override val status = MutableStateFlow<DeviceStatus?>(null)
        var connects = 0
        var disconnects = 0
        override suspend fun connect() {
            if (state.value != LinkState.READY) { connects++; state.value = LinkState.READY }
        }
        override suspend fun disconnect() { disconnects++; state.value = LinkState.SEARCHING }
        override suspend fun send(command: ByteArray) = Unit
        override suspend fun query(request: Int) = byteArrayOf()
    }
}
