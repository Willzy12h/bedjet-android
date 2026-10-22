package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeOwnershipTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var beforeSend: suspend (ByteArray) -> Unit = {}
    private var beforeConnect: suspend () -> Unit = {}
    private var failBackups = false
    private var failClear = false
    private val sent = mutableListOf<ByteArray>()
    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { beforeSend(command); sent += command; demo.send(command) }
            override suspend fun connect() { beforeConnect(); demo.connect() }
        }
        graph = Graph(context, link) { scope, now -> Repository(context, scope, writeFile = { file, bytes ->
            if (failClear && file.name == "active-night.json" && bytes.decodeToString() == "null") throw IOException("Clear disk failure")
            if (failBackups && file.name == "slot-backups.json") throw IOException("Backup disk failure")
            Repository.writeDurably(file, bytes)
        }, now = now) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }
    private suspend fun start(): ActiveNight {
        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        return graph.repository.activeNight.value!!
    }
    private fun advance(minutes: Long) { graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(minutes)); context.graph.clock = graph.clock }
    @Test fun interruptedBoostPendingUploadCannotReplayDisplacedNightAfterRestart() = runBlocking {
        assertInterruptedBoostCannotReplay(Confidence.PENDING)
    }
    @Test fun interruptedBoostConfirmedSlotCannotReplayDisplacedNightAfterRestart() = runBlocking {
        assertInterruptedBoostCannotReplay(Confidence.CONFIRMED)
    }
    private suspend fun assertInterruptedBoostCannotReplay(confidence: Confidence) {
        val n = start()
        graph.repository.updateRuntime { it.copy(
            slotWrite = SlotWrite("demo", n.slot, StoredProgram("Boost", emptyList()), confidence, "Boost", true, n.identity),
            alarmInbox = AlarmObservation("alarm", n.recordId, "demo", graph.now().toString())) }
        val restarted = Graph(context, graph.link)
        restarted.clock = graph.clock
        val count = sent.size
        restarted.actions.reconcile()
        restarted.actions.retryPendingRetime()
        assertEquals(count, sent.size)
        assertEquals(Confidence.UNKNOWN, restarted.repository.activeNight.value?.confidence)
        assertNull(restarted.repository.runtime.value.alarmInbox)
        assertNull(restarted.repository.runtime.value.retime)
    }
    @Test fun endAlarmQueuedBehindExtendCannotExpireItsNewRevision() = runBlocking {
        val original = start()
        graph.clock = Clock.fixed(ZonedDateTime.parse(original.endsAt).toInstant(), ZoneOffset.UTC)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val extend = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.execute(graph.actions.capture(NightActions.Command.EXTEND_WARM)) }
        val oldEnd = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.expire(original.identity) }
        release.complete(Unit); holder.await()
        assertEquals(OutcomeKind.ACCEPTED, extend.await().kind)
        assertFalse(oldEnd.await())
        val extended = graph.repository.activeNight.value!!
        assertEquals(original.revision + 1, extended.revision)
        assertEquals(graph.now().plusMinutes(graph.repository.settings.value.extendWarm.minutes.toLong()).toInstant(), ZonedDateTime.parse(extended.endsAt).toInstant())
        val count = sent.size
        graph.clock = Clock.fixed(ZonedDateTime.parse(extended.endsAt).toInstant(), ZoneOffset.UTC)
        assertTrue(graph.actions.expire(extended.identity))
        assertNull(graph.repository.activeNight.value); assertEquals(count, sent.size)
    }
    @Test fun restoreRefusesTrackedNightEvenWhenFreshDeviceHasStopped() = runBlocking {
        val n = start()
        graph.controller().stop()
        val before = sent.size
        val result = graph.actions.restoreOriginal(n.slot)
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(before, sent.size)
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
    }
    @Test fun startPersistsCompleteIntentionAndOriginalBeforeFirstWrite() = runBlocking {
        var inspected = false
        beforeSend = { bytes -> if (bytes[0] == 0x40.toByte()) {
            val pending = graph.repository.activeNight.value!!
            assertEquals(Confidence.PENDING, pending.confidence)
            val json = File(context.filesDir, "active-night.json").readText()
            assertTrue("intended snapshot missing", json.contains("intended"))
            assertNotNull(graph.repository.originalSlot("demo", 2)); inspected = true
        } }
        start(); assertTrue(inspected)
    }
    @Test fun backupFailureAbortsStartAndRecordsFailed() = runBlocking {
        failBackups = true
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        assertEquals(OutcomeKind.FAILED, result.kind)
        assertNull(demo.slots[2]); assertEquals(Confidence.FAILED, graph.repository.activeNight.value!!.confidence)
    }
    @Test fun partialUploadCancellationIsDurablyUnknownAndNeverRetried() = runBlocking {
        beforeSend = { if (it[0] == 0x40.toByte()) throw CancellationException("cancel upload") }
        try { start(); fail("cancel expected") } catch (_: CancellationException) {}
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertTrue(File(context.filesDir, "active-night.json").readText().contains("UNKNOWN"))
        beforeSend = {}; graph.actions.retryPendingRetime(); assertNull(demo.slots[2])
    }
    @Test fun oldNotificationOffIsRejectedButFreshOffQueuedBehindStartRuns() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeConnect = { entered.complete(Unit); release.await() }
        val start = async { graph.actions.perform(NightActions.Command.START_NIGHT) }
        entered.await()
        val staleOff = graph.actions.capture(NightActions.Command.OFF, requireIdentity = true)
        val off = async { graph.actions.perform(NightActions.Command.OFF) }
        yield(); assertEquals(1, graph.actions.busy.value.queuedCount)
        release.complete(Unit); start.await(); off.await()
        assertNull(graph.repository.activeNight.value); assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        start()
        assertEquals(OutcomeKind.STALE, graph.actions.execute(staleOff).kind)
        assertNotNull(graph.repository.activeNight.value)
    }
    @Test fun targetAndDemoChangesAreRefusedDuringNight() = runBlocking {
        start()
        assertEquals(OutcomeKind.REFUSED, graph.actions.setDemoMode(false).kind)
        assertEquals(OutcomeKind.REFUSED, graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:FF").kind)
        assertEquals(OutcomeKind.REFUSED, graph.actions.forgetTarget().kind)
        assertTrue(graph.repository.settings.value.demoMode)
        graph.actions.perform(NightActions.Command.STOP_TRACKING)
        assertEquals(OutcomeKind.LOCAL, graph.actions.setDemoMode(false).kind)
    }
    @Test fun pinRefusesWorkingSlotAndRecordsOriginalBeforeWritingOtherSlot() = runBlocking {
        val profile = graph.repository.profiles.value.first()
        assertEquals(OutcomeKind.REFUSED, graph.actions.pin(profile.id, 2).kind)
        beforeSend = { if (it[0] == 0x40.toByte()) assertNotNull(graph.repository.originalSlot("demo", 0)) }
        val result = graph.actions.pin(profile.id, 0)
        assertEquals(OutcomeKind.CONFIRMED, result.kind)
        assertTrue(result.message.contains("duration"))
        assertNotNull(demo.slots[0]); assertFalse(demo.slots[0]!!.steps.any { it.endsAtClockTime })
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertNull(demo.slots[0])
    }
    @Test fun pinAndBoostAbortWhenOriginalBackupFails() = runBlocking {
        failBackups = true
        assertEquals(OutcomeKind.FAILED, graph.actions.pin(graph.repository.profiles.value.first().id, 0).kind)
        assertNull(demo.slots[0])
        graph.repository.updateSettings { it.copy(boostMinutes = 15) }
        graph.repository.saveProfile(graph.repository.profiles.value.first().copy(limits = io.github.willzy12h.nocturne.core.plan.ProfileLimits(maxTemp = 86)))
        assertEquals(OutcomeKind.FAILED, graph.actions.execute(graph.actions.capture(NightActions.Command.BOOST)).kind)
        assertNull(demo.slots[2])
    }
    @Test fun stopTrackingIsLocalAndResumeRestoresRemainingNight() = runBlocking {
        start(); advance(30); sent.clear()
        graph.actions.perform(NightActions.Command.STOP_TRACKING)
        assertTrue(sent.isEmpty()); assertNull(graph.repository.activeNight.value)
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.RESUME)).kind)
        assertNotNull(graph.repository.activeNight.value)
    }
    @Test fun newerCommandInvalidatesResumeOfferAcrossReload() = runBlocking {
        start(); graph.actions.perform(NightActions.Command.OFF)
        val version = graph.repository.runtime.value.generation
        graph.actions.perform(NightActions.Command.TURBO)
        assertTrue(graph.repository.runtime.value.generation > version)
        assertNull(graph.repository.runtime.value.resumeOffer)
        val loaded = Repository(context, graph.scope, now = { graph.now() })
        assertEquals(graph.repository.runtime.value.generation, loaded.runtime.value.generation)
    }
    @Test fun remoteOffEndsNightAndLaterAlarmCannotRestartIt() = runBlocking {
        start()
        graph.actions.remote("Off", endsNight = true) { it.stop() }
        sent.clear(); graph.actions.perform(NightActions.Command.RETIME)
        assertNull(graph.repository.activeNight.value); assertTrue(sent.isEmpty())
    }
    @Test fun lightsBeepsAndAcknowledgementDoNotEndNightOrLeaveItPending() = runBlocking {
        val n = start()
        graph.actions.remote("Lights") { it.setLed(false) }
        graph.actions.remote("Beeps") { it.setMuted(true) }
        graph.actions.remote("Filter") { it.acknowledge() }
        assertEquals(n.identity, graph.repository.activeNight.value!!.identity)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
    }
    @Test fun normalProgressionIsNotTakeoverButFreshManualStatusIsUnknown() = runBlocking {
        val n = start(); advance(60); demo.tick()
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        demo.send(Codec.setMode(Mode.COOL)!!)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertEquals(EventKind.REMOTE_CHANGE, graph.repository.history.value.first { it.id == n.recordId }.events.last().kind)
        graph.actions.perform(NightActions.Command.KEEP_TRACKING)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
    }
    @Test fun diagnosticsExcludeTokenButIncludeBoundedEventsAndLink() = runBlocking {
        graph.repository.updateToken = "TOP_SECRET_TOKEN"
        start()
        val diagnostics = graph.actions.diagnostics()
        assertFalse(diagnostics.contains("TOP_SECRET_TOKEN")); assertFalse(diagnostics.contains("updateToken"))
        assertTrue(diagnostics.contains("READY")); assertTrue(diagnostics.contains("activeNight"))
    }
    @Test fun handoverWaitsForWholeTransactionAndPeerDeclinesBusy() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeSend = { if (it[0] == 0x40.toByte()) { entered.complete(Unit); release.await() } }
        val start = async { graph.actions.perform(NightActions.Command.START_NIGHT) }; entered.await()
        assertEquals(OutcomeKind.REFUSED, graph.actions.releaseForOtherPhone(declineIfBusy = true).kind)
        val handover = async { graph.actions.releaseForOtherPhone() }; yield()
        val waited = !handover.isCompleted
        val notReleasedEarly = !graph.sessions.released.value
        release.complete(Unit); start.await();
        assertTrue(waited); assertTrue(notReleasedEarly); assertEquals(OutcomeKind.LOCAL, handover.await().kind)
        assertTrue(graph.sessions.released.value)
    }
    @Test fun jobEligibilityIsCheckedAfterConnectionBeforeMutation() = runBlocking {
        var eligible = true
        beforeConnect = { eligible = false }
        val result = graph.actions.performJobOwned(graph.actions.capture(NightActions.Command.START_NIGHT)) { eligible && !it.isRunning }
        assertEquals(OutcomeKind.REFUSED, result.kind); assertTrue(sent.isEmpty())
    }
    @Test fun fallbackSubmissionsNeverWaitForUploadAndNewestObservationSurvivesCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeSend = { if (it[0] == 0x40.toByte()) { entered.complete(Unit); release.await() } }
        val operation = async { graph.actions.perform(NightActions.Command.START_NIGHT) }; entered.await()
        var first: String? = null
        var second: String? = null
        try {
            assertNotNull(withTimeoutOrNull(100) { graph.actions.submitAlarmChange() })
            first = graph.repository.runtime.value.alarmInbox?.id
            assertNotNull(first)
            assertNotNull(withTimeoutOrNull(100) { graph.actions.submitAlarmChange() })
            second = graph.repository.runtime.value.alarmInbox?.id
            assertNotEquals(first, second)
            assertTrue(File(context.filesDir, "runtime.json").readText().contains(second!!))
        } finally { release.complete(Unit); operation.await() }
        assertEquals(second, graph.repository.runtime.value.alarmInbox?.id)
        advance(600)
        graph.actions.retryPendingRetime()
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertFalse(graph.actions.retimePending)
    }
    @Test fun manualLimitsAndBoostUseOwnedRuntimeAndPreserveOriginal() = runBlocking {
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(limits = io.github.willzy12h.nocturne.core.plan.ProfileLimits(maxFan = 50, maxTemp = 64)))
        graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(extendWarm = ActionPreset(Mode.HEAT, 80, 100, 10)) }
        graph.actions.perform(NightActions.Command.WARM)
        assertEquals(Mode.HEAT, demo.status.value!!.mode); assertEquals(64, demo.status.value!!.targetTemperature)
        assertEquals(50, demo.status.value!!.fanPercent); assertEquals(600, demo.status.value!!.remainingSeconds)
        graph.repository.saveProfile(profile.copy(limits = io.github.willzy12h.nocturne.core.plan.ProfileLimits(maxTemp = 86)))
        graph.actions.perform(NightActions.Command.BOOST)
        assertEquals(listOf(Mode.TURBO to 10, Mode.HEAT to 5), demo.slots[2]!!.steps.map { it.mode to it.durationMinutes })
        assertEquals(80, demo.slots[2]!!.steps[1].temperature); assertEquals(Mode.TURBO, demo.status.value!!.mode)
        assertNotNull(graph.repository.originalSlot("demo", 2)); assertNull(graph.repository.originalSlot("demo", 2)!!.program)
    }
    @Test fun rejectedPreparationDoesNotInvalidateResumeGeneration() = runBlocking {
        start(); graph.actions.perform(NightActions.Command.OFF)
        val generation = graph.repository.runtime.value.generation
        val offer = graph.repository.runtime.value.resumeOffer
        graph.actions.perform(NightActions.Command.START_NIGHT, "missing")
        assertEquals(generation, graph.repository.runtime.value.generation); assertEquals(offer, graph.repository.runtime.value.resumeOffer)
    }
    @Test fun restartKeepsOriginalIntendedSnapshotAndMarksInterruptedStateUnknown() = runBlocking {
        beforeSend = { if (it[0] == 0x40.toByte()) throw CancellationException("interrupted") }
        try { start() } catch (_: CancellationException) {}
        val saved = graph.repository.activeNight.value!!
        val intended = saved.pending!!.intended!!
        graph.repository.saveProfile(intended.profile!!.copy(name = "Edited after interruption"))
        graph.repository.setActiveNight(saved.copy(confidence = Confidence.PENDING))
        graph.actions.reconcile()
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertEquals(intended.profile, graph.repository.activeNight.value!!.pending!!.intended!!.profile)
        assertEquals(intended.applied, graph.repository.activeNight.value!!.pending!!.intended!!.applied)
    }
    @Test fun replacingNightRequiresExplicitPolicyConfirmationAndOffNeverDoes() = runBlocking {
        val original = start()
        val request = graph.actions.capture(NightActions.Command.START_NIGHT)
        val outcome = graph.actions.execute(request)
        assertEquals(OutcomeKind.CONFIRMATION_REQUIRED, outcome.kind)
        assertEquals(original.identity, graph.repository.activeNight.value!!.identity)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(request.copy(replacementConfirmed = true)).kind)
        assertEquals(OutcomeKind.ACCEPTED, graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
    }
    @Test fun reapplyHonoursNewTonightOverrideAndFinishClearsIt() = runBlocking {
        start()
        val wake = graph.now().plusHours(6)
        graph.repository.updateSettings { it.setWakeOverride(wake, graph.now()) }
        graph.actions.perform(NightActions.Command.REAPPLY)
        assertEquals(wake.toInstant(), ZonedDateTime.parse(graph.repository.activeNight.value!!.wakeAt).toInstant())
        assertEquals(WakeOrigin.OVERRIDE, graph.repository.activeNight.value!!.wakeOrigin)
        graph.actions.perform(NightActions.Command.OFF)
        assertNull(graph.repository.settings.value.tonightOverride)
    }
    @Test fun interruptedLocalFinishKeepsDurableResumeOfferAndReconcilesWithoutHardware() = runBlocking {
        val night = start()
        failClear = true
        graph.actions.perform(NightActions.Command.OFF)
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        assertNotNull(graph.repository.runtime.value.ending)
        failClear = false; sent.clear()
        graph.actions.reconcile()
        assertTrue(sent.isEmpty()); assertNull(graph.repository.activeNight.value)
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        assertNull(graph.repository.runtime.value.ending)
        assertEquals(1, graph.repository.history.value.first { it.id == night.recordId }.events.count { it.kind == EventKind.STOPPED })
    }
    @Test fun setupCheckOnlyTouchesFanAndRestoresItAndRejectsRunningUnit() = runBlocking {
        demo.connect()
        val original = demo.status.value!!.fanStep
        assertEquals(OutcomeKind.ACCEPTED, graph.actions.setupFanCheck().kind)
        assertEquals(original, demo.status.value!!.fanStep)
        assertEquals(2, sent.size); assertTrue(sent.all { it[0] == 0x07.toByte() })
        demo.send(Codec.setMode(Mode.COOL)!!); sent.clear()
        assertEquals(OutcomeKind.REFUSED, graph.actions.setupFanCheck().kind); assertTrue(sent.isEmpty())
    }
    @Test fun snoozeStoresTheAcceptedManualProgrammeInsteadOfTheOldNightCurve() = runBlocking {
        val original = start()
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        val n = graph.repository.activeNight.value!!
        assertEquals(1, n.applied.programSteps.size)
        assertEquals(Mode.HEAT, n.applied.programSteps.single().mode)
        assertEquals(10, n.applied.steps.single().minutes)
        assertEquals(original.revision + 1, n.applied.revision)
        assertEquals(n.applied, graph.repository.history.value.first { it.id == n.recordId }.revisions.last())
    }
    @Test fun restoreAbortsIfMigratedOriginalCannotBeDurablyBoundToTarget() = runBlocking {
        val original = StoredProgram("Original", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))
        graph.repository.recordOriginalSlot("unknown", 0, original)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        failBackups = true
        assertEquals(OutcomeKind.FAILED, graph.actions.restoreOriginal(0).kind)
        assertTrue(sent.isEmpty()); assertNull(demo.slots[0])
    }
    @Test fun timeoutWaitingForLockLeavesCoordinatorUsable() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val hold = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }; entered.await()
        assertNull(withTimeoutOrNull(1) { graph.actions.perform(NightActions.Command.OFF) })
        release.complete(Unit); hold.await()
        assertEquals("BedJet off.", graph.actions.perform(NightActions.Command.OFF)); assertEquals(0, graph.actions.busy.value.queuedCount)
    }
}
