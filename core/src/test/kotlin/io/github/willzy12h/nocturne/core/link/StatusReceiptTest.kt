package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** A default link keeps one receipt for its current snapshot across reads and collectors. */
class StatusReceiptTest {
    private val link = DemoBedJet(now = { ZonedDateTime.parse("2026-10-21T22:30Z") })

    @Test fun cachedReadsKeepTheOriginalReport() = runBlocking {
        link.connect()
        val received = link.latestSourcedStatus()!!

        assertSame(received, link.latestSourcedStatus(), "reading cached state does not receive another report")
        assertSame(received, link.latestSourcedStatus())
    }

    @Test fun collectorsShareTheOriginalReport() = runBlocking {
        link.connect()
        val received = link.sourcedStatus.first()!!

        assertSame(received, link.sourcedStatus.first(), "recollecting cached state does not receive another report")
        assertSame(received, link.latestSourcedStatus(), "latest and flow expose the same receipt")
    }

    @Test fun anEqualValueSuppressedByStateFlowCannotBecomeANewReceiptOnRead() = runBlocking {
        link.connect()
        val received = link.latestSourcedStatus()!!
        link.connect()

        assertSame(received, link.latestSourcedStatus(), "an equal cached status cannot acquire a fresh receipt")
    }

    @Test fun aChangedUnitReportIsDistinctAndThenRemainsStable() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!
        link.send(Codec.setMode(Mode.COOL)!!)
        val after = link.sourcedStatus.first()!!

        assertNotSame(before, after)
        assertEquals(Mode.COOL, after.status.mode)
        assertSame(after, link.latestSourcedStatus(), "the next lookup retains this new receipt")
    }
}
