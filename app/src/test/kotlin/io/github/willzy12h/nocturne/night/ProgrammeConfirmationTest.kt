package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.ble.FakeRadio
import io.github.willzy12h.nocturne.ble.GattLink
import io.github.willzy12h.nocturne.ble.Status
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.EventKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.ZonedDateTime

/** Reports keep their original provenance and ordering across the complete command owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgrammeConfirmationTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun postAcknowledgementGattReportMustMatchTheStoredFirstStep() = runBlocking {
        graphs.finish()
        val target = DeviceId.ble("AA:BB:CC:DD:EE:FF")
        val radio = FakeRadio().also { it.readableProgramme = true }
        val gatt = GattLink(radio.radio, { target })
        val acknowledged = CompletableDeferred<Unit>()
        val link = object : BedJetLink by gatt {
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                gatt.sendGuarded(command, beforeWrite)
                if (command.contentEquals(Codec.startProgram(2))) acknowledged.complete(Unit)
            }
        }
        try {
            link.connect()
            val controller = BedJetController(link) { ZonedDateTime.parse("2026-10-29T22:00:00Z") }
            assertEquals(Mode.COOL, controller.programSteps(2).first().mode)
            val pending = async { runCatching { controller.startProgram(2) } }
            withTimeout(4_000) { acknowledged.await() }
            val packet = Status.full(programStep = 1).also {
                it[4] = 0; it[5] = 10; it[6] = 0
                it[8] = 66; it[9] = Mode.HEAT.code.toByte(); it[10] = 7
            }
            radio.ports.single().notify(packet)
            val premature = withTimeoutOrNull(2_000) { pending.await() }
            assertEquals(target, link.latestSourcedStatus()!!.source)
            assertEquals(Mode.HEAT, link.latestSourcedStatus()!!.status.mode)
            assertFalse("A current-session report for another first step cannot confirm the stored COOL programme",
                premature?.isSuccess == true)
            if (premature == null) {
                // FakeRadio's stored first step is COOL, fan step 5, temperature 50, ten minutes.
                radio.ports.single().notify(packet.copyOf().also {
                    it[8] = 50; it[9] = Mode.COOL.code.toByte(); it[10] = 5
                })
                assertTrue(withTimeout(4_000) { pending.await() }.isSuccess)
            }
        } finally { gatt.shutdown() }
    }

    @Test fun standbyDuringStartConfirmationCannotRemainDurablyConfirmed() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        val observed = CompletableDeferred<Unit>()
        f.afterSend = { command ->
            if (command.contentEquals(Codec.startProgram(2))) launch {
                delay(250)
                assertNotNull(f.graph.actions.busy.value.runningLabel)
                assertEquals(Confidence.PENDING, f.graph.repository.activeNight.value!!.confidence)
                f.remoteOff()
                observed.complete(Unit)
            }
        }
        val outcome = f.start()
        withTimeout(2_000) { observed.await() }
        assertUnknownWithoutAnotherCommand(f)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
    }

    @Test fun standbyDuringTheConfirmedSaveCannotBeOverwrittenByPublication() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var observed = false
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.CONFIRMED) {
                f.beforeFileWrite = null
                assertNotNull(f.graph.actions.busy.value.runningLabel)
                assertEquals(Confidence.PENDING, f.graph.repository.activeNight.value!!.confidence)
                runBlocking { withTimeout(2_000) { f.remoteOff() } }
                observed = true
            }
        }
        val outcome = f.start()
        assertTrue("The conflicting report arrived inside the durable CONFIRMED write", observed)
        assertUnknownWithoutAnotherCommand(f)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
    }

    @Test fun standbyAtOwnershipReleaseIsHandedToTheObserver() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        val observed = CompletableDeferred<Unit>()
        var owned = false
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            f.graph.actions.busy.collect { busy ->
                if (busy.runningLabel != null) owned = true
                else if (owned && !observed.isCompleted) {
                    // This public busy-state transition currently precedes the owner's final unlock.
                    assertTrue(ReflectionHelpers.getField<Mutex>(f.graph.actions, "lock").isLocked)
                    f.remoteOff()
                    observed.complete(Unit)
                }
            }
        }
        try {
            f.start()
            withTimeout(2_000) { observed.await() }
            assertUnknownWithoutAnotherCommand(f)
        } finally { watcher.cancelAndJoin() }
    }

    @Test fun standbyHeadDuringTheConfirmedSaveDoesNotNeedAProgressTail() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var observed = false
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.CONFIRMED) {
                f.beforeFileWrite = null
                runBlocking { withTimeout(2_000) {
                    f.device.send(Codec.setMode(Mode.STANDBY)!!)
                    val report = f.link.latestSourcedStatus()!!
                    f.observe(report.copy(status = report.status.copy(extra = null)))
                } }
                observed = true
            }
        }
        val outcome = f.start()
        assertTrue(observed)
        assertUnknownWithoutAnotherCommand(f)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
    }

    @Test fun cancellationAfterConfirmedPublicationStillReconcilesTheObservedOff() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        val owner = Job(currentCoroutineContext()[Job])
        var observed = false
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" &&
                BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence == Confidence.CONFIRMED) {
                f.beforeFileWrite = null
                runBlocking { withTimeout(2_000) { f.remoteOff() } }
                observed = true
                owner.cancel(CancellationException("Owner cancelled while saving confirmed state"))
            }
        }
        val failure = try { runCatching { withContext(owner) { f.start() } }.exceptionOrNull() }
            finally { f.beforeFileWrite = null; owner.complete() }
        assertTrue(observed)
        assertTrue("Owner cancellation must still propagate", failure is CancellationException)
        assertUnknownWithoutAnotherCommand(f)
    }

    @Test fun bufferedReportsFromAnotherTargetCannotInvalidateTheProgramme() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        val observed = CompletableDeferred<Unit>()
        f.afterSend = { command ->
            if (command.contentEquals(Codec.startProgram(2))) launch {
                delay(250)
                val running = f.link.latestSourcedStatus()!!
                val foreign = DeviceId.ble("AA:BB:CC:DD:EE:FF")
                val off = running.status.copy(mode = Mode.STANDBY, extra = running.status.extra!!.copy(programStep = 0))
                f.observe(SourcedStatus(foreign, off))
                f.observe(SourcedStatus(f.target, off), addressedTarget = foreign)
                observed.complete(Unit)
            }
        }
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        withTimeout(2_000) { observed.await() }
        assertConfirmedWithoutTakeover(f)
    }

    @Test fun aBufferedPreStartReceiptCannotUndoTheNewConfirmation() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        f.link.connect()
        val staleOff = f.link.latestSourcedStatus()!!
        val observed = CompletableDeferred<Unit>()
        f.afterSend = { command ->
            if (command.contentEquals(Codec.startProgram(2))) launch {
                delay(250)
                assertTrue(staleOff.receiptOrder < f.link.latestSourcedStatus()!!.receiptOrder)
                f.observe(staleOff)
                observed.complete(Unit)
            }
        }
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        withTimeout(2_000) { observed.await() }
        assertConfirmedWithoutTakeover(f)
    }

    @Test fun cancellationAfterAnObservedOffCannotResurrectConfirmation() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        val observed = CompletableDeferred<Unit>()
        f.afterSend = { command ->
            if (command.contentEquals(Codec.startProgram(2))) launch {
                delay(250)
                f.remoteOff()
                observed.complete(Unit)
            }
        }
        val owner = async { f.start() }
        withTimeout(4_000) { observed.await() }
        owner.cancelAndJoin()
        assertTrue(owner.isCancelled)
        assertUnknownWithoutAnotherCommand(f)
    }

    private fun assertUnknownWithoutAnotherCommand(f: ProgrammeOwnerFixture) {
        assertEquals(f.target, f.link.latestSourcedStatus()!!.source)
        assertEquals(Mode.STANDBY, f.link.latestSourcedStatus()!!.status.mode)
        val disk = f.diskNight()
        assertEquals(f.target, disk.deviceId)
        assertEquals(f.graph.repository.activeNight.value, disk)
        assertEquals("The already-received current-target Off must survive command ownership", Confidence.UNKNOWN, disk.confidence)
        assertEquals("Resolving a contradictory report must not send another start", 1, f.starts())
        assertNull(f.graph.actions.busy.value.runningLabel)
    }

    private fun assertConfirmedWithoutTakeover(f: ProgrammeOwnerFixture) {
        assertEquals(Confidence.CONFIRMED, f.diskNight().confidence)
        assertEquals(1, f.starts())
        assertTrue(f.graph.repository.history.value.flatMap { it.events }.none { it.kind == EventKind.REMOTE_CHANGE })
    }
}
