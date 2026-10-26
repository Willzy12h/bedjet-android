package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.nullable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A sent manual request must settle before its owner exits, without a later reconcile repairing it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualCancellationSettlementTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var frozen: SourcedStatus? = null
    private var reconnecting = false
    private var reconnected = false
    private var blockReconnect = false
    private val reconnectEntered = CompletableDeferred<Unit>()
    private val freshReportEntered = CompletableDeferred<Unit>()
    private var writes = 0
    private var workReturned = false
    @Volatile private var beforeFileWrite: ((File, ByteArray) -> Unit)? = null

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            override suspend fun disconnect() { reconnecting = true; demo.disconnect() }
            override suspend fun connect() {
                if (reconnecting) {
                    reconnectEntered.complete(Unit)
                    if (blockReconnect) awaitCancellation()
                }
                demo.connect()
                if (reconnecting) reconnected = true
            }
            override val sourcedStatus: Flow<SourcedStatus?>
                get() = frozen?.let { cached -> flow {
                    if (reconnected) freshReportEntered.complete(Unit)
                    emit(cached)
                    awaitCancellation()
                } } ?: demo.sourcedStatus
            override fun latestSourcedStatus(): SourcedStatus? = frozen ?: demo.latestSourcedStatus()
        }
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 60_000
        graph.actions.freshStatusMs = 60_000
    }

    private suspend fun start(): ActiveNight {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        return graph.repository.activeNight.value!!
    }

    private fun diskNight(): ActiveNight? = BackupCodec.json.decodeFromString(
        ActiveNight.serializer().nullable, File(app.filesDir, "active-night.json").readText())

    private fun diskRuntime(): RuntimeState = BackupCodec.json.decodeFromString(
        RuntimeState.serializer(), File(app.filesDir, "runtime.json").readText())

    private fun assertSettled(night: ActiveNight) {
        val current = graph.repository.activeNight.value
        assertEquals(night.identity, current?.identity)
        assertEquals("An interrupted sent request must already be UNKNOWN before reconciliation", Confidence.UNKNOWN, current?.confidence)
        assertNull("The interrupted request is no longer pending", current?.pending)
        assertEquals("Settlement must be durable before cancellation returns", current, diskNight())
        assertEquals(graph.repository.runtime.value, diskRuntime())
    }

    private suspend fun temperature(written: CompletableDeferred<Unit>): CommandOutcome =
        graph.actions.remote("Temperature set", affectsProgramme = true, confirms = { it.targetTemperature == 72 }) {
            it.setTemperature(72)
            workReturned = true
            written.complete(Unit)
        }

    @Test fun cancellingAfterWorkReturnsDuringReportWaitSettlesTheRequest() = runBlocking {
        val night = start()
        frozen = demo.latestSourcedStatus()
        val before = writes
        val written = CompletableDeferred<Unit>()
        val request = async { temperature(written) }
        try {
            withTimeout(5_000) { written.await() }
            yield() // The callback has returned; the owner is awaiting a matching report.
            assertTrue(workReturned)
            assertFalse(request.isCompleted)
            request.cancelAndJoin()
            assertEquals("Exercise one accepted control packet", before + 1, writes)
            assertSettled(night)
        } finally { request.cancelAndJoin() }
    }

    @Test fun aCallerTimeoutDuringReportWaitPersistsAReadableReason() = runBlocking {
        val night = start()
        frozen = demo.latestSourcedStatus()
        val written = CompletableDeferred<Unit>()
        val failure = runCatching { withTimeout(1_000) { temperature(written) } }.exceptionOrNull()

        assertTrue("The write callback must finish before the timeout", workReturned)
        assertTrue(failure is TimeoutCancellationException)
        assertSettled(night)
        assertEquals(app.getString(R.string.runtime_operation_timeout), graph.repository.runtime.value.reason)
    }

    @Test fun cancellingAFallbackReconnectSettlesTheSentRequest() = runBlocking {
        val night = start()
        frozen = demo.latestSourcedStatus()
        graph.actions.confirmMs = 1
        blockReconnect = true
        val request = async { temperature(CompletableDeferred()) }
        try {
            withTimeout(5_000) { reconnectEntered.await() }
            assertTrue("Fallback begins only after work returns", workReturned)
            request.cancelAndJoin()
            assertSettled(night)
        } finally { request.cancelAndJoin() }
    }

    @Test fun cancellingAFallbackReportWaitSettlesTheSentRequest() = runBlocking {
        val night = start()
        frozen = demo.latestSourcedStatus()
        graph.actions.confirmMs = 1
        val request = async { temperature(CompletableDeferred()) }
        try {
            withTimeout(5_000) { freshReportEntered.await() }
            assertTrue(workReturned)
            assertTrue("A reconnect completed before the fresh report wait", reconnected)
            request.cancelAndJoin()
            assertSettled(night)
        } finally { request.cancelAndJoin() }
    }

    @Test fun cancelledOffKeepsItsDurableIntentionForALaterStandby() = runBlocking {
        val night = start()
        frozen = demo.latestSourcedStatus()
        val written = CompletableDeferred<Unit>()
        val request = async {
            graph.actions.remote("Off", endsNight = true, confirms = { it.mode == Mode.STANDBY }) {
                it.stop()
                workReturned = true
                written.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { written.await() }
            yield()
            request.cancelAndJoin()
            assertTrue(workReturned)
            assertSettled(night)
            val intention = graph.repository.runtime.value
            assertEquals(night.identity, intention.endRequested)
            assertEquals("Off", intention.endRequestedLabel)
            assertNotNull(intention.endRequestedAt)
            assertNotNull(intention.endRequestedSequence)
            assertNotNull(intention.endRequestedEpoch)
            assertTrue(graph.repository.history.value.single { it.id == night.recordId }.events.none { it.kind == EventKind.STOPPED })

            frozen = null
            graph.actions.observeStatus(demo.status.value!!, "demo", receipt = graph.actions.statusReceipt())
            assertNull("A later target Standby can still finish that Off", graph.repository.activeNight.value)
            assertNull(graph.repository.runtime.value.endRequested)
            assertEquals("Off", graph.repository.history.value.single { it.id == night.recordId }
                .events.single { it.kind == EventKind.STOPPED }.detail)
        } finally { request.cancelAndJoin() }
    }

    @Test fun anIntentSaveFailureRestoresThePriorTrackingWithoutSending() = runBlocking {
        val night = start().copy(confidence = Confidence.UNKNOWN, trackingManual = true)
        graph.repository.setActiveNight(night)
        graph.repository.updateRuntime { it.copy(endRequested = night.identity, endRequestedLabel = "Earlier Off",
            endRequestedAt = graph.now().toString(), endRequestedEpoch = "earlier", endRequestedSequence = 3) }
        val before = writes
        beforeFileWrite = { file, bytes ->
            if (file.name == "runtime.json" && BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.decodeToString()).endRequestedLabel == "Off") {
                beforeFileWrite = null
                throw IOException("test intention save failure")
            }
        }

        val outcome = graph.actions.remote("Off", endsNight = true, confirms = { it.mode == Mode.STANDBY }) { it.stop() }

        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals("No packet may be sent before the intention is durable", before, writes)
        assertEquals("A known pre-write failure must restore the existing tracking", night, graph.repository.activeNight.value)
        assertEquals(night, diskNight())
        assertEquals("Earlier Off", graph.repository.runtime.value.endRequestedLabel)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
    }

    @Test fun cancellationAsPendingPublishesBeforeWritingRestoresThePriorNight() = runBlocking {
        val night = start()
        val before = writes
        val owner = Job(currentCoroutineContext()[Job])
        beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" && BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable,
                    bytes.decodeToString())?.confidence == Confidence.PENDING) {
                beforeFileWrite = null
                owner.cancel(CancellationException("test cancellation as pending publishes"))
            }
        }
        val failure = try { runCatching { withContext(owner) { temperature(CompletableDeferred()) } }.exceptionOrNull() }
        finally { beforeFileWrite = null; owner.complete() }

        assertTrue(failure is CancellationException)
        assertEquals(before, writes)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(night, diskNight())
    }

    @Test fun cancellationAfterConfirmationPublishesDoesNotDowngradeTheNight() = runBlocking {
        val night = start()
        val owner = Job(currentCoroutineContext()[Job])
        var confirmedPublication = false
        beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json" && workReturned && BackupCodec.json.decodeFromString(
                    ActiveNight.serializer().nullable, bytes.decodeToString())?.confidence == Confidence.CONFIRMED) {
                confirmedPublication = true
                beforeFileWrite = null
                owner.cancel(CancellationException("test cancellation after confirmation publishes"))
            }
        }
        val failure = try {
            runCatching { withContext(owner) {
                graph.actions.remote("Lights on", confirms = { it.extra?.ledEnabled == true }) {
                    it.setLed(true)
                    workReturned = true
                }
            } }.exceptionOrNull()
        } finally { beforeFileWrite = null; owner.complete() }

        assertTrue(confirmedPublication)
        assertTrue(failure is CancellationException)
        assertEquals("A durably published confirmation must survive cleanup", night, graph.repository.activeNight.value)
        assertEquals(night, diskNight())
    }

    @Test fun cancellationDoesNotReplaceAnotherPublishedRequest() = runBlocking {
        val night = start()
        frozen = demo.latestSourcedStatus()
        val written = CompletableDeferred<Unit>()
        val request = async { temperature(written) }
        try {
            withTimeout(5_000) { written.await() }
            yield()
            val another = night.copy(confidence = Confidence.PENDING, pending = PendingChange("another-request", ChangeKind.MANUAL,
                null, graph.now().toString(), graph.now().plusMinutes(1).toString(), night.identity))
            graph.repository.setActiveNight(another)
            request.cancelAndJoin()
            assertEquals(another, graph.repository.activeNight.value)
            assertEquals(another, diskNight())
        } finally { request.cancelAndJoin() }
    }

    @Test fun cancellingAsConfirmedOffEndingPublishesCompletesItBeforeReconciliation() = runBlocking {
        val night = start()
        val before = writes
        val owner = Job(currentCoroutineContext()[Job])
        var cancelledAtEnding = false
        var sawDurableConfirmedEnd = false
        beforeFileWrite = { file, bytes ->
            if (file.name == "runtime.json" && !cancelledAtEnding) {
                val ending = BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.decodeToString()).ending
                if (ending?.identity == night.identity && ending.event.kind == EventKind.STOPPED) {
                    cancelledAtEnding = true
                    // Let the journal write and publication finish, then propagate prompt cancellation to its caller.
                    owner.cancel(CancellationException("test cancellation as confirmed ending publishes"))
                }
            }
            if (file.name == "active-night.json" && cancelledAtEnding) {
                val ending = diskRuntime().ending
                sawDurableConfirmedEnd = ending?.identity == night.identity && ending.event.kind == EventKind.STOPPED
                beforeFileWrite = null
            }
        }
        val failure = try { runCatching {
            withContext(owner) { graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)) }
        }.exceptionOrNull() } finally { beforeFileWrite = null; owner.complete() }

        assertTrue(cancelledAtEnding)
        assertTrue("The matching confirmed STOPPED journal must exist on disk before cleanup", sawDurableConfirmedEnd)
        assertTrue(failure is CancellationException)
        assertEquals("Repair is local and must not send Off again", before + 1, writes)
        assertNull("A durably confirmed Off must finish locally before reconciliation", graph.repository.activeNight.value)
        assertNull(diskNight())
        assertNull(graph.repository.runtime.value.ending)
        assertNull(graph.repository.runtime.value.endRequested)
        assertEquals(graph.repository.runtime.value, diskRuntime())
        assertEquals(app.getString(R.string.runtime_off), graph.repository.history.value.single { it.id == night.recordId }
            .events.single { it.kind == EventKind.STOPPED }.detail)
    }

    private suspend fun cancelTimedPlanPublication(command: NightActions.Command) {
        val night = start()
        val before = writes
        val owner = Job(currentCoroutineContext()[Job])
        var interruptedPublication = false
        beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                val next = BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.decodeToString())
                if (next?.trackingManual == true && next.revision == night.revision + 1) {
                    interruptedPublication = true
                    beforeFileWrite = null
                    val cancellation = CancellationException("test cancellation before timed manual plan publication")
                    owner.cancel(cancellation)
                    throw cancellation
                }
            }
        }
        val failure = try { runCatching {
            withContext(owner) { graph.actions.execute(graph.actions.capture(command).copy(replacementConfirmed = true)) }
        }.exceptionOrNull() } finally { beforeFileWrite = null; owner.complete() }

        assertTrue("Exercise the later manual-plan save after command confirmation", interruptedPublication)
        assertTrue("The timed command must have reached the BedJet", writes > before)
        assertTrue(failure is CancellationException)
        assertSettled(night)
        assertTrue("The displaced programme must not still describe this manual run", graph.repository.activeNight.value!!.trackingManual)
        assertEquals("The original programme must retain its Resume offer", night, graph.repository.runtime.value.resumeOffer?.night)
    }

    @Test fun cancellingSnoozeBeforeItsPlanPublishesCannotRestoreAConfirmedProgramme() = runBlocking {
        cancelTimedPlanPublication(NightActions.Command.SNOOZE_WARM)
    }

    @Test fun cancellingExtendBeforeItsPlanPublishesCannotRestoreAConfirmedProgramme() = runBlocking {
        cancelTimedPlanPublication(NightActions.Command.EXTEND_COOL)
    }

    @Test fun confirmedManualControlsStillSettleNormally() = runBlocking {
        val night = start()
        assertEquals(OutcomeKind.CONFIRMED, temperature(CompletableDeferred()).kind)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        assertNull(graph.repository.activeNight.value?.pending)

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
        assertNull(graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.endRequested)
        assertEquals(1, graph.repository.history.value.single { it.id == night.recordId }.events.count { it.kind == EventKind.STOPPED })
    }
}
