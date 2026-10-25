package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** Demo receipts belong to simulated reports, never to the consumer that first happens to read them. */
class DemoStatusReceiptTest {
    private val link = DemoBedJet(now = { ZonedDateTime.parse("2026-10-21T22:30Z") })

    @Test fun initialConnectStampsItsReportBeforeTheFirstRead() = runBlocking {
        link.connect()
        val afterConnect = StatusReceiptOrder.next()

        val received = link.latestSourcedStatus()!!

        assertTrue(received.receiptOrder < afterConnect, "the initial report arrived before its first lookup")
        assertSame(received, link.sourcedStatus.first())
    }

    @Test fun changedSendStampsItsReportBeforeTheFirstRead() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!
        link.send(Codec.setMode(Mode.COOL)!!)
        val afterSend = StatusReceiptOrder.next()

        val received = link.sourcedStatus.first()!!

        assertEquals(Mode.COOL, received.status.mode)
        assertTrue(received.receiptOrder > before.receiptOrder)
        assertTrue(received.receiptOrder < afterSend, "the changed report arrived during send, before collection")
        assertSame(received, link.latestSourcedStatus())
    }

    @Test fun reconnectReceivesAnEqualStatusWithANewReceiptBeforeTheFirstRead() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!
        val requestedAfter = StatusReceiptOrder.next()
        link.disconnect()
        link.connect()
        val afterConnect = StatusReceiptOrder.next()

        val received = link.latestSourcedStatus()!!

        assertEquals(before.status, received.status)
        assertTrue(received.receiptOrder > requestedAfter, "a real reconnect receives the current state again")
        assertTrue(received.receiptOrder < afterConnect, "reconnect stamps its report before a consumer reads it")
        assertSame(received, link.sourcedStatus.first())
    }

    @Test fun anEqualOrdinarySendKeepsTheOriginalReceipt() = runBlocking {
        link.connect()
        link.send(Codec.setMode(Mode.HEAT)!!)
        link.send(Codec.setTemperature(70))
        val before = link.latestSourcedStatus()!!

        link.send(Codec.setTemperature(70))

        assertSame(before, link.latestSourcedStatus(), "an equal write alone is not a received report")
        assertSame(before, link.sourcedStatus.first())
    }

    @Test fun preparingForStartMasksProgressWithoutReceivingAReport() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!

        link.prepareForStart()

        val prepared = link.latestSourcedStatus()!!
        assertNull(link.status.value!!.extra)
        assertNull(prepared.status.extra)
        assertEquals(before.receiptOrder, prepared.receiptOrder, "local progress invalidation is not a unit report")
        assertSame(prepared, link.sourcedStatus.first())
    }

    @Test fun anEqualSendAfterPreparationKeepsTheOriginalReceipt() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!
        link.prepareForStart()

        link.send(Codec.setTemperature(before.status.targetTemperature))

        val restored = link.latestSourcedStatus()!!
        assertEquals(before.status, restored.status)
        assertEquals(before.receiptOrder, restored.receiptOrder, "restoring cached progress is not a new report")
    }

    @Test fun restartingTheSameFirstStepReceivesAFreshReportAfterPreparation() = runBlocking {
        link.slots[0] = StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 44, 0, 30)))
        link.connect()
        link.send(Codec.setClock(22, 30))
        link.send(Codec.startProgram(0))
        val before = link.latestSourcedStatus()!!
        link.prepareForStart()
        val requestedAfter = StatusReceiptOrder.next()

        link.send(Codec.startProgram(0))

        val restarted = link.latestSourcedStatus()!!
        assertEquals(before.status, restarted.status, "a restart at the same time reproduces the first step")
        assertEquals(1, restarted.status.extra!!.programStep)
        assertTrue(restarted.receiptOrder > requestedAfter, "a genuine programme restart receives a new report")
    }

    @Test fun aRedundantReadyConnectAfterPreparationKeepsTheOriginalReceipt() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!
        link.prepareForStart()
        link.latestSourcedStatus()

        link.connect()

        assertEquals(before.receiptOrder, link.latestSourcedStatus()!!.receiptOrder,
            "connect while READY does not receive the status again")
    }

    @Test fun ticksStampOnlyChangedReportsBeforeTheirFirstRead() = runBlocking {
        link.connect()
        val before = link.latestSourcedStatus()!!
        link.tick()
        val afterTick = StatusReceiptOrder.next()

        val changed = link.latestSourcedStatus()!!
        assertEquals(45, changed.status.actualTemperature)
        assertTrue(changed.receiptOrder > before.receiptOrder)
        assertTrue(changed.receiptOrder < afterTick, "the changed air temperature arrived before its first read")

        link.tick()

        assertSame(changed, link.latestSourcedStatus(), "a tick with unchanged status is not a fresh report")
    }
}
