package io.github.willzy12h.nocturne.ble

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.night.LinkSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/** Idle grace must not read split-status tails; commands still need them while hidden. */
class IdleStatusDemandTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val radio = FakeRadio()
    private val link = GattLink(radio.radio, { DeviceId.ble(GattLinkTest.ADDRESS) }, GattLinkTest.TIMINGS)
    private val sessions = LinkSessions(scope, { link }, System::currentTimeMillis, graceMs = 10_000)

    @After fun close() { scope.cancel(); executor.shutdown(); link.shutdown() }

    @Test fun anIdleConnectionDoesNotScheduleStatusRemainderReads() = runBlocking {
        // Await acquisition, not merely READY publication: hiding during an unfinished connect cancels it.
        sessions.acquire()
        val port = radio.ports.single()
        port.holdStatusReads = false
        sessions.releaseLater()
        // Queue a marker on the session's executor after its asynchronous release.
        val released = CompletableDeferred<Unit>()
        scope.launch { released.complete(Unit) }
        released.await()
        val before = port.reads.count { it == Attr.STATUS }
        port.notify(Status.head())
        radio.drain()
        delay(100)
        assertEquals("Idle background grace must not enrich full status", before, port.reads.count { it == Attr.STATUS })
        assertEquals("Grace preserves the ready connection", LinkState.READY, link.state.value)
    }

    @Test fun aHiddenCommandStillReceivesFreshStatusAndOriginalReceiptOrder() = runBlocking {
        sessions.acquire()
        val port = radio.ports.single()
        sessions.releaseLater()
        val released = CompletableDeferred<Unit>()
        scope.launch { released.complete(Unit) }
        released.await()
        sessions.withConnection {
            // Demand was disabled by hiding. Only this background owner restores the split-status read.
            port.holdStatusReads = true
            val readsBefore = port.reads.count { it == Attr.STATUS }
            val before = link.latestSourcedStatus()!!.receiptOrder
            port.notify(Status.head().apply { this[7] = 60 })
            eventually { port.reads.count { it == Attr.STATUS } > readsBefore && link.status.value?.actualTemperature == 60 }
            val head = link.latestSourcedStatus()!!
            assertTrue("The new head has a fresh receipt", head.receiptOrder > before)
            port.answerStatusRead(Status.remainder(programStep = 7))
            eventually { link.status.value?.extra?.programStep == 7 }
            val enriched = link.latestSourcedStatus()!!
            assertEquals("Enrichment retains the head's receipt", head.receiptOrder, enriched.receiptOrder)
            assertEquals(60, enriched.status.actualTemperature)
        }
    }

    @Test fun returningToTheForegroundResumesSplitStatusReads() = runBlocking {
        sessions.acquire()
        val port = radio.ports.single()
        port.holdStatusReads = false
        sessions.releaseLater()
        val released = CompletableDeferred<Unit>()
        scope.launch { released.complete(Unit) }
        released.await()
        sessions.holdForScreen(true)
        val resumed = CompletableDeferred<Unit>()
        scope.launch { resumed.complete(Unit) }
        resumed.await()
        port.notify(Status.head())
        eventually { port.reads.contains(Attr.STATUS) }
        assertEquals(LinkState.READY, link.state.value)
    }
}
