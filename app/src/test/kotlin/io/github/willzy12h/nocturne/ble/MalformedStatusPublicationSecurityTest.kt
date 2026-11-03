package io.github.willzy12h.nocturne.ble

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MalformedStatusPublicationSecurityTest {
    private val radio = FakeRadio()
    private val link = GattLink(radio.radio, { DeviceId.ble(GattLinkTest.ADDRESS) }, GattLinkTest.TIMINGS)
    @After fun close() = link.shutdown()
    private fun malformed() = Status.full(0).copyOf(30).also {
        it[9] = Mode.HEAT.code.toByte(); it[13] = 100; it[14] = 40
    }
    @Test fun aMalformedSelectedDeviceReportCannotBecomeFreshConfirmationEvidence() = runBlocking {
        link.setStatusDemand(false)
        link.connect()
        val port = radio.ports.single()
        val before = link.latestSourcedStatus()!!
        val writes = port.writes.size
        port.notify(malformed())
        radio.drain()
        assertSame("An invalid report must not mint a receipt", before, link.latestSourcedStatus())
        assertEquals(writes, port.writes.size)
    }
    @Test fun anInvalidHeadAndItsTailCannotReviveOrEnrichAnEarlierHead() = runBlocking {
        link.setStatusDemand(false)
        link.connect()
        val port = radio.ports.single()
        port.notify(Status.head())
        radio.drain()
        val before = link.latestSourcedStatus()!!
        port.notify(malformed().copyOf(20))
        port.notify(Status.remainder(programStep = 1))
        radio.drain()
        assertSame("An invalid frame's tail cannot become programme confirmation", before, link.latestSourcedStatus())
    }
}
