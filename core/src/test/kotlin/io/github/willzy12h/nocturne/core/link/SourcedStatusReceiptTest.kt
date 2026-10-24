package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class SourcedStatusReceiptTest {
    private fun demo() = DemoBedJet(now = { ZonedDateTime.parse("2026-10-21T22:30Z") })

    @Test fun readingAndRecollectingTheSameStatusCannotCreateANewerReceipt() = runBlocking {
        val link = demo()
        link.connect()
        val first = link.sourcedStatus.first()!!
        val requestOrder = StatusReceiptOrder.next()
        repeat(3) {
            val latest = link.latestSourcedStatus()!!
            assertSame(first.status, latest.status)
            assertEquals(first.receiptOrder, latest.receiptOrder)
            assertTrue(latest.receiptOrder < requestOrder)
        }
        val anotherCollector = link.sourcedStatus.first()!!
        assertEquals(first.receiptOrder, anotherCollector.receiptOrder)
        assertEquals(first.receiptOrder, anotherCollector.copy(source = "demo").receiptOrder)
    }

    @Test fun anEqualValuedNewStatusGetsItsOwnLaterReceipt() = runBlocking {
        val link = demo()
        link.connect()
        link.send(Codec.setMode(Mode.STANDBY)!!)
        val old = link.sourcedStatus.first()!!
        val requestOrder = StatusReceiptOrder.next()
        link.send(Codec.setMode(Mode.HEAT)!!)
        link.send(Codec.setMode(Mode.STANDBY)!!)
        val fresh = link.sourcedStatus.first()!!
        assertEquals(old.status, fresh.status)
        assertNotSame(old.status, fresh.status)
        assertTrue(old.receiptOrder < requestOrder)
        assertTrue(fresh.receiptOrder > requestOrder)
        assertEquals(fresh.receiptOrder, link.latestSourcedStatus()!!.receiptOrder)
    }

    @Test fun derivedStatusAndSourceCopiesRetainTheOriginalReceipt() = runBlocking {
        val link = demo()
        link.connect()
        val original = link.sourcedStatus.first()!!
        val requestOrder = StatusReceiptOrder.next()
        val derived = original.copy(source = "demo", status = original.status.copy(extra = null))
        assertEquals(original.receiptOrder, derived.receiptOrder)
        assertTrue(derived.receiptOrder < requestOrder)
    }
}
