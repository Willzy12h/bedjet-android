package io.github.willzy12h.nocturne.ble

import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.latestStatusFrom
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.link.BedJetController
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [GattLink] driven through a fake adapter, with short timings so failures show quickly. */
class GattLinkTest {
    private val fake = FakeRadio()
    private var target: String? = DeviceId.ble(ADDRESS)
    @Volatile private var name: String? = null
    private val link = GattLink(fake.radio, { target }, TIMINGS, onName = { _, value -> name = value })

    @After
    fun close() = link.shutdown()

    // Connecting

    @Test
    fun connectsToTheTargetAndAnswersQueries() = runBlocking {
        link.connect()
        assertEquals(LinkState.READY, link.state.value)
        assertEquals(listOf(ADDRESS), fake.connects)
        assertEquals("BEDJET 3", name)
        assertEquals("BEDJET 3", Codec.parseText(link.query(Codec.Query.DEVICE_NAME)))
        link.send(Codec.setFan(5))
        assertTrue(fake.ports.single().writes.any { it.contentEquals(Codec.setFan(5)) })
    }

    @Test
    fun withNoTargetConnectAsksForSetup() = runBlocking {
        target = null
        val error = assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals("No BedJet chosen yet. Choose yours in Setup.", error.message)
        assertTrue(fake.connects.isEmpty())
    }

    @Test
    fun theDemoTargetIsNoBluetoothTarget() = runBlocking {
        target = DeviceId.DEMO
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertTrue(fake.connects.isEmpty())
    }

    @Test
    fun status133RetriesTheSameAddressThenFailsWithoutTryingAnotherBedJet() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(connectStatus = 133)
        fake.advertisements = mapOf(ScanSpec.Service to listOf(ScanHit(OTHER, "BEDJET", -40, true)))
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(listOf(ADDRESS, ADDRESS, ADDRESS), fake.connects)
        assertTrue(fake.ports.all { it.closed })
        assertEquals(LinkState.SEARCHING, link.state.value)
    }

    @Test
    fun aLateConnectionTimeoutIsRetriedOnTheSameAddress() = runBlocking {
        fake.upcoming += FakeRadio.Behaviour(connectStatus = null)
        link.connect()
        assertEquals(listOf(ADDRESS, ADDRESS), fake.connects)
        assertTrue(fake.ports[0].closed)
        assertEquals(LinkState.READY, link.state.value)
    }

    @Test
    fun aReadySessionForTheOldTargetCannotCarryTheNewTargetsCommand() = runBlocking {
        link.connect()
        val oldPort = fake.ports.single()
        target = DeviceId.ble(OTHER)

        link.send(Codec.setFan(4))

        assertEquals(listOf(ADDRESS, OTHER), fake.connects)
        assertTrue(oldPort.closed)
        assertFalse(oldPort.writes.any { it.contentEquals(Codec.setFan(4)) })
        assertTrue(fake.ports.last().writes.any { it.contentEquals(Codec.setFan(4)) })
    }

    @Test
    fun aStartQueuedBehindAnotherGattWriteIsRejectedAtTheWriteBoundary() = runBlocking {
        link.connect()
        val port = fake.ports.single()
        // A start goes out only on the session prepared for it, as BedJetController.startProgram does.
        link.prepareForStart()
        port.holdWrites = true
        val first = async { link.send(Codec.setFan(4)) }
        eventually { port.writes.any { it.contentEquals(Codec.setFan(4)) } }
        var stillBeforeDeadline = true
        val start = async {
            runCatching {
                link.sendGuarded(Codec.startProgram(2)) {
                    if (!stillBeforeDeadline) throw IllegalStateException("expired before GATT write")
                }
            }
        }
        // The command has been admitted to the transport, but cannot pass the occupied GATT queue yet.
        delay(20)
        stillBeforeDeadline = false
        port.ackWrite()
        first.await()
        assertEquals("expired before GATT write", start.await().exceptionOrNull()?.message)
        assertFalse(port.writes.any { it.contentEquals(Codec.startProgram(2)) })
    }

    @Test
    fun eachStatusCarriesTheBedJetThatReportedIt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val old = fake.ports.single()
        val report = link.latestSourcedStatus()!!
        assertEquals(DeviceId.ble(ADDRESS), report.source)
        assertEquals(link.status.value, report.status)
        assertEquals(report, link.sourcedStatus.first())
        assertTrue(report.isFrom(DeviceId.ble(ADDRESS)))
        assertFalse(report.isFrom(DeviceId.ble(OTHER)))
        assertEquals(1, link.latestStatusFrom(DeviceId.ble(ADDRESS))?.extra?.programStep)
        assertEquals(null, link.latestStatusFrom(DeviceId.ble(OTHER)))
        // Once the target changes, nothing the old unit reports stands for the new one.
        target = DeviceId.ble(OTHER)
        old.notify(Status.full(programStep = 1))
        fake.drain()
        assertEquals(null, link.latestStatusFrom(DeviceId.ble(OTHER)))
    }

    @Test
    fun preparingWithNoSessionLeavesNoUnattributedStatus() = runBlocking {
        link.prepareForStart()
        assertEquals(null, link.status.value)
        assertEquals(null, link.latestSourcedStatus())
        link.connect()
        link.prepareForStart()
        assertEquals(DeviceId.ble(ADDRESS), link.latestSourcedStatus()?.source)
        assertEquals(link.status.value, link.latestSourcedStatus()?.status)
    }

    @Test
    fun changingTargetDuringRetryCannotReviveTheOldSession() = runBlocking {
        fake.upcoming += FakeRadio.Behaviour(connectStatus = 133)
        val retryingLink = GattLink(fake.radio, { target }, TIMINGS.copy(retryBackoffMs = listOf(500)))
        val connecting = async { runCatching { retryingLink.connect() } }
        eventually { fake.connects.size == 1 && fake.ports.first().closed }
        target = DeviceId.ble(OTHER)

        assertTrue(connecting.await().isFailure)
        assertEquals(listOf(ADDRESS), fake.connects)
        retryingLink.connect()
        assertEquals(listOf(ADDRESS, OTHER), fake.connects)
        assertEquals(LinkState.READY, retryingLink.state.value)
        retryingLink.shutdown()
    }

    @Test
    fun aConnectionCallbackBeforeConnectReturnsStillCarriesOn() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(connectsInline = true)
        link.connect()
        assertEquals(listOf(ADDRESS), fake.connects)
        assertEquals(LinkState.READY, link.state.value)
    }

    @Test
    fun anAttachWhoseSessionDiesBeforeItIsReadyIsRetried() = runBlocking {
        // The first connection never answers the name query, so its session is retired mid-attach.
        fake.upcoming += FakeRadio.Behaviour(answersReplies = false)
        link.connect()
        assertEquals(listOf(ADDRESS, ADDRESS), fake.connects)
        assertTrue(fake.ports[0].closed)
        assertEquals(LinkState.READY, link.state.value)
        link.send(Codec.setFan(4))
        assertTrue(fake.ports[1].writes.any { it.contentEquals(Codec.setFan(4)) })
    }

    @Test
    fun anAttachThatNeverGetsReadyFailsRatherThanReturning() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(answersReplies = false)
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(3, fake.connects.size)
        assertEquals(LinkState.SEARCHING, link.state.value)
    }

    @Test
    fun permissionsAndBluetoothOffAreReported() = runBlocking {
        fake.availability = Radio.UNAUTHORIZED
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(LinkState.UNAUTHORIZED, link.state.value)
        fake.availability = Radio.OFF
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(LinkState.BLUETOOTH_OFF, link.state.value)
        assertTrue(fake.connects.isEmpty())
    }

    // Sessions (R01, R02, R04)

    @Test
    fun aLateWriteCallbackFromAPreviousSessionDoesNotCompleteTheCurrentWrite() = runBlocking {
        link.connect()
        val first = fake.ports.single()
        first.drop()
        eventually { link.state.value == LinkState.SEARCHING }
        assertTrue(first.closed)
        link.send(Codec.setFan(3))
        val second = fake.ports[1]
        second.holdWrites = true
        val next = async { runCatching { link.send(Codec.setFan(4)) } }
        eventually { second.writes.any { it.contentEquals(Codec.setFan(4)) } }
        // The first session's late answer must not complete the write outstanding on the second.
        first.ackWrite()
        fake.drain()
        delay(50)
        assertFalse(next.isCompleted)
        second.ackWrite()
        next.await().getOrThrow()
    }

    @Test
    fun aRetiredSessionsLateConnectionCallbackIsIgnored() = runBlocking {
        link.connect()
        val first = fake.ports.single()
        val asked = first.mtuRequests
        link.disconnect()
        assertTrue(first.closed)
        first.connected()
        fake.drain()
        assertEquals(asked, first.mtuRequests)
        assertEquals(LinkState.SEARCHING, link.state.value)
    }

    @Test
    fun aTimeoutRetiresTheSessionAndTheNextOperationReconnects() = runBlocking {
        link.connect()
        val first = fake.ports.single()
        first.holdWrites = true
        assertThrows<BedJetError.Timeout> { link.send(Codec.setFan(3)) }
        assertTrue(first.closed)
        assertEquals(LinkState.SEARCHING, link.state.value)
        // The unit's answer turns up late; it belongs to nothing now.
        first.ackWrite()
        link.send(Codec.setFan(4))
        assertEquals(2, fake.connects.size)
        assertTrue(fake.ports[1].writes.any { it.contentEquals(Codec.setFan(4)) })
        assertEquals(LinkState.READY, link.state.value)
    }

    @Test
    fun cancellingAnOperationInFlightRetiresTheSession() = runBlocking {
        link.connect()
        val first = fake.ports.single()
        first.holdWrites = true
        val sending = launch { link.send(Codec.setFan(3)) }
        eventually { first.writes.any { it.contentEquals(Codec.setFan(3)) } }
        sending.cancelAndJoin()
        assertTrue(first.closed)
        first.ackWrite()
        link.send(Codec.setFan(4))
        assertEquals(2, fake.connects.size)
    }

    @Test
    fun aCancelledAttachClosesItsGatt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(connectStatus = null)
        val connecting = launch { link.connect() }
        eventually { fake.ports.isNotEmpty() }
        connecting.cancelAndJoin()
        assertTrue(fake.ports.single().closed)
        assertEquals(LinkState.SEARCHING, link.state.value)
    }

    @Test
    fun aCallbackForTheWrongCharacteristicFailsTheOperation() = runBlocking {
        link.connect()
        val port = fake.ports.single()
        port.holdWrites = true
        val sending = async { runCatching { link.send(Codec.setFan(3)) } }
        eventually { port.writes.any { it.contentEquals(Codec.setFan(3)) } }
        port.ackWrite(Attr.REPLY)
        val error = withTimeout(200) { sending.await() }.exceptionOrNull()
        assertTrue("$error", error is BedJetError.BadResponse)
        // The real answer may still be on its way, so the session can't be trusted.
        assertTrue(port.closed)
    }

    @Test
    fun failedServiceDiscoveryFailsTheConnection() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(servicesStatus = 129)
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(3, fake.connects.size)
        assertTrue(fake.ports.all { it.closed })
    }

    // Split status (R06)

    @Test
    fun anOldProgramStepDoesNotConfirmANewStartButAFreshOneDoes() = runBlocking {
        // The unit was already running a program when we connected.
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        val start = async { runCatching { controller.startProgram(2) } }
        eventually { port.writes.any { it.contentEquals(Codec.startProgram(2)) } }
        fake.drain()
        delay(50)
        port.notify(Status.head())
        eventually { port.reads.contains(Attr.STATUS) }
        delay(100)
        assertFalse("A stale program step confirmed the start", start.isCompleted)
        assertEquals(null, link.status.value?.extra)
        port.answerStatusRead(Status.remainder(programStep = 1))
        withTimeout(4_000) { start.await() }.getOrThrow()
    }

    @Test
    fun preparingForAStartForgetsTheProgramStepUntilAFreshReport() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        link.prepareForStart()
        assertEquals(null, link.status.value?.extra)
        // Until the start is written, a head-only report neither brings the step back nor asks for the remainder:
        // the read would only be dropped, and would hold up the start.
        port.notify(Status.head())
        fake.drain()
        delay(100)
        assertFalse(port.reads.contains(Attr.STATUS))
        assertEquals(null, link.status.value?.extra)
        link.cancelPreparedStart()
        port.notify(Status.full(programStep = 1))
        eventually { link.status.value?.extra?.programStep == 1 }
    }

    @Test
    fun endingAPreparationWithoutAStartAsksForTheRemainderAgainAtOnce() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        link.prepareForStart()
        port.notify(Status.head())
        fake.drain()
        delay(100)
        // Whatever read the pending start allowed has finished before the preparation ends.
        if (Attr.STATUS in port.reads) port.answerStatusRead(Status.remainder(programStep = 1))
        fake.drain()
        delay(50)
        val readsBefore = port.reads.count { it == Attr.STATUS }
        link.cancelPreparedStart()
        port.notify(Status.full(programStep = 1))
        eventually { link.status.value?.extra?.programStep == 1 }
        // Well inside remainderEveryMs: progress must not wait for the next periodic read to come back.
        port.notify(Status.head())
        eventually(timeoutMs = 1_000) { port.reads.count { it == Attr.STATUS } > readsBefore }
    }

    @Test
    fun aStartRefusedByItsFinalCheckLeavesProgressReportedAgain() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        val result = runCatching { controller.startProgram(2) { throw IllegalStateException("too late") } }
        assertTrue("$result", result.exceptionOrNull() is IllegalStateException)
        assertFalse(port.writes.any { it.contentEquals(Codec.startProgram(2)) })
        // The session lives on; its reports must carry the program again, or takeovers would go unseen.
        port.notify(Status.full(programStep = 1))
        eventually { link.status.value?.extra?.programStep == 1 }
    }

    @Test
    fun aFullStatusWhileTheStartIsBeingWrittenDoesNotConfirmIt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        port.holdWrites = true
        val start = async { runCatching { controller.startProgram(2) } }
        eventually { port.writes.any { it[0].toInt() == 0x08 } }
        port.ackWrite()
        eventually { port.writes.any { it.contentEquals(Codec.startProgram(2)) } }
        // Sent before the unit acknowledged the start, so it may describe the unit from before it.
        port.notify(Status.full(programStep = 1))
        fake.drain()
        port.ackWrite()
        delay(1_800)
        assertFalse("A full status from before the unit took the start confirmed it", start.isCompleted)
        port.notify(Status.full(programStep = 1))
        withTimeout(4_000) { start.await() }.getOrThrow()
    }

    @Test
    fun aHeadAndRemainderReadBetweenPrepareAndStartDoNotConfirmIt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        var reported = false
        val start = async {
            runCatching {
                controller.startProgram(2) {
                    if (!reported) {
                        reported = true
                        // A head-only report after the prepare; any remainder read it starts is answered with the old step.
                        port.notify(Status.head())
                        fake.drain()
                        Thread.sleep(100)
                        port.answerStatusRead(Status.remainder(programStep = 1))
                        fake.drain()
                    }
                }
            }
        }
        eventually { port.writes.any { it.contentEquals(Codec.startProgram(2)) } }
        fake.drain()
        delay(1_800)
        assertFalse("A remainder read from before the start confirmed it", start.isCompleted)
        // After the start, the next head asks for the remainder at once, and a fresh step confirms.
        val readsBefore = port.reads.count { it == Attr.STATUS }
        port.notify(Status.head())
        eventually { port.reads.count { it == Attr.STATUS } > readsBefore }
        port.answerStatusRead(Status.remainder(programStep = 1))
        withTimeout(4_000) { start.await() }.getOrThrow()
    }

    @Test
    fun aReportBetweenTheClockAndTheStartDoesNotConfirmIt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        port.holdWrites = true
        val controller = BedJetController(link) { ZonedDateTime.now() }
        val start = async { runCatching { controller.startProgram(2) } }
        eventually { port.writes.any { it[0].toInt() == 0x08 } }
        // A head-only report while the clock is being set still carries the old program step.
        port.notify(Status.head())
        fake.drain()
        port.ackWrite()
        eventually { port.writes.any { it.contentEquals(Codec.startProgram(2)) } }
        port.notify(Status.head())
        fake.drain()
        port.ackWrite()
        // Longer than the controller's refusal grace, so a confirmation would have finished the start by now.
        delay(1_800)
        assertFalse("A stale program step confirmed the start", start.isCompleted)
        port.notify(Status.head())
        eventually { port.reads.contains(Attr.STATUS) }
        port.answerStatusRead(Status.remainder(programStep = 1))
        withTimeout(4_000) { start.await() }.getOrThrow()
    }

    @Test
    fun aFullOldStatusBeforeTheStartWriteCannotConfirmIt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        val start = async {
            runCatching {
                controller.startProgram(2) {
                    port.notify(Status.full(programStep = 1))
                    fake.drain()
                }
            }
        }
        eventually { port.writes.any { it.contentEquals(Codec.startProgram(2)) } }
        delay(1_800)
        assertFalse("The old full status confirmed the new start", start.isCompleted)
        port.notify(Status.full(programStep = 1))
        withTimeout(4_000) { start.await() }.getOrThrow()
    }

    @Test
    fun anOldRemainderCompletedBeforeTheStartWriteCannotConfirmIt() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val port = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        val start = async {
            runCatching {
                controller.startProgram(2) {
                    port.notify(Status.head())
                    fake.drain()
                    Thread.sleep(100)
                    // No remainder read starts while the start is pending; a stray old remainder changes nothing.
                    assertFalse("A remainder read started while the start was pending", Attr.STATUS in port.reads)
                    port.answerStatusRead(Status.remainder(programStep = 1))
                    fake.drain()
                }
            }
        }
        eventually { port.writes.any { it.contentEquals(Codec.startProgram(2)) } }
        delay(1_800)
        assertFalse("The old remainder confirmed the new start", start.isCompleted)
        port.notify(Status.full(programStep = 1))
        withTimeout(4_000) { start.await() }.getOrThrow()
    }

    @Test
    fun aReconnectBetweenPrepareAndStartNeverConfirmsFromTheNewSession() = runBlocking {
        fake.behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1))
        link.connect()
        val first = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        var dropped = false
        val result = withTimeout(5_000) {
            runCatching {
                controller.startProgram(2) {
                    if (!dropped) {
                        dropped = true
                        // The unit drops after the prepare and another holder reconnects before the start goes out.
                        first.drop()
                        fake.drain()
                        runBlocking { eventually { link.state.value == LinkState.SEARCHING }; link.connect() }
                    }
                }
            }
        }
        assertEquals(2, fake.connects.size)
        assertTrue("$result", result.exceptionOrNull() is BedJetError.NotConnected)
        assertFalse(fake.ports.any { p -> p.writes.any { it.contentEquals(Codec.startProgram(2)) } })
    }

    // A start is sent without reconnecting (IR-11)

    @Test
    fun aStartWithNoLiveSessionFailsRatherThanReconnecting() = runBlocking {
        link.connect()
        val first = fake.ports.single()
        val controller = BedJetController(link) { ZonedDateTime.now() }
        var dropped = false
        val result = withTimeout(5_000) {
            runCatching {
                controller.startProgram(2) {
                    if (!dropped) {
                        dropped = true
                        first.drop()
                        fake.drain()
                        runBlocking { eventually { link.state.value == LinkState.SEARCHING } }
                    }
                }
            }
        }
        assertTrue("$result", result.exceptionOrNull() is BedJetError.NotConnected)
        assertEquals(listOf(ADDRESS), fake.connects)
        assertFalse(first.writes.any { it.contentEquals(Codec.startProgram(2)) })
        // Other commands still reconnect as before.
        link.send(Codec.setFan(4))
        assertEquals(2, fake.connects.size)
    }

    @Test
    fun aStartThatWasNeverPreparedIsNotWrittenAndItsGuardDoesNotRun() = runBlocking {
        link.connect()
        val port = fake.ports.single()
        var guarded = false
        assertThrows<BedJetError.NotConnected> { link.sendGuarded(Codec.startProgram(2)) { guarded = true } }
        assertFalse("The final check ran for a start that could not go out", guarded)
        assertFalse(port.writes.any { it.contentEquals(Codec.startProgram(2)) })
    }

    // Platform exceptions (R07)

    @Test
    fun aSecurityExceptionFromAWriteBecomesNotConnectedAndLeavesNothingPending() = runBlocking {
        link.connect()
        val first = fake.ports.single()
        first.writeThrows = SecurityException("BLUETOOTH_CONNECT revoked")
        assertThrows<BedJetError.NotConnected> { link.send(Codec.setFan(3)) }
        assertEquals(LinkState.UNAUTHORIZED, link.state.value)
        assertTrue(first.closed)
        // Nothing is left waiting: the queue is free and a stray answer changes nothing.
        first.ackWrite()
        link.send(Codec.setFan(4))
        assertEquals(LinkState.READY, link.state.value)
        assertEquals(2, fake.connects.size)
    }

    @Test
    fun aSecurityExceptionFromConnectingOrScanningBecomesNotConnected() = runBlocking {
        fake.connectThrows = SecurityException("revoked")
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(LinkState.UNAUTHORIZED, link.state.value)
        fake.scanThrows = SecurityException("revoked")
        assertThrows<BedJetError.NotConnected> { link.discover(100) }
        assertEquals(LinkState.UNAUTHORIZED, link.state.value)
    }

    @Test
    fun theBackgroundRemainderReadSwallowsPlatformExceptions() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(uncaught) { uncaught += e } }
        try {
            link.connect()
            val port = fake.ports.single()
            port.statusReadThrows = IllegalStateException("GATT gone")
            port.notify(Status.head())
            fake.drain()
            delay(100)
            link.send(Codec.setFan(4))
            assertEquals(emptyList<Throwable>(), synchronized(uncaught) { uncaught.toList() })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    // Discovery

    @Test
    fun discoverListsBedJetsWithoutConnecting() = runBlocking {
        fake.advertisements = mapOf(
            ScanSpec.Service to listOf(ScanHit(ADDRESS, "BEDJET_V3", -60, true), ScanHit(ADDRESS, "BEDJET_V3", -50, true)),
            ScanSpec.Unfiltered to listOf(
                ScanHit(OTHER, "BedJet 3", -70, false),
                ScanHit("11:22:33:44:55:66", "Kettle", -30, false),
                ScanHit(ADDRESS, "BEDJET_V3", -55, true),
            ),
        )
        val found = link.discover(timeoutMs = 200)
        assertEquals(
            listOf(FoundBedJet(ADDRESS, "BEDJET_V3", -50), FoundBedJet(OTHER, "BedJet 3", -70)),
            found,
        )
        assertEquals(listOf(ScanSpec.Service, ScanSpec.Unfiltered), fake.scans)
        assertTrue(fake.connects.isEmpty())
    }

    companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER = "AA:BB:CC:DD:EE:02"
        val TIMINGS = GattLink.Timings(
            operationMs = 300, connectMs = 500, mtuFallbackMs = 50, firstStatusMs = 300,
            retryBackoffMs = listOf(20, 40), queryRetryMs = 10, remainderEveryMs = 2_000,
        )
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
    }
    fail("Expected ${T::class.simpleName}, nothing was thrown")
    throw IllegalStateException()
}

/** Polls until [condition] holds, for things that happen on the fake's callback thread. */
suspend fun eventually(timeoutMs: Long = 2_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > end) fail("Condition not met in $timeoutMs ms")
        delay(5)
    }
}
