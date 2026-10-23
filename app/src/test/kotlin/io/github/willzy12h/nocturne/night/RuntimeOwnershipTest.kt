package io.github.willzy12h.nocturne.night

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emitAll
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
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var beforeSend: suspend (ByteArray) -> Unit = {}
    private var beforeConnect: suspend () -> Unit = {}
    private var failBackups = false
    private var failSettings = false
    private var failClear = false
    private val sent = mutableListOf<ByteArray>()
    private enum class RefuseStart { NO, BEFORE_GUARD, AFTER_GUARD }
    private var refuseStart = RefuseStart.NO
    /** When set, the link's latest report came from another BedJet, as from a session still on the previous one. */
    private var foreign: SourcedStatus? = null
    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { beforeSend(command); sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                val start = command.contentEquals(Codec.startProgram(graph.repository.settings.value.workingSlot))
                // As GattLink does with no live, prepared session: refused before the final check runs.
                if (start && refuseStart == RefuseStart.BEFORE_GUARD) throw BedJetError.NotConnected()
                beforeWrite()
                if (start && refuseStart == RefuseStart.AFTER_GUARD) throw BedJetError.NotConnected()
                send(command)
            }
            override suspend fun connect() { beforeConnect(); demo.connect() }
            override val status: kotlinx.coroutines.flow.StateFlow<DeviceStatus?>
                get() = foreign?.let { kotlinx.coroutines.flow.MutableStateFlow<DeviceStatus?>(it.status) } ?: demo.status
            override val sourcedStatus: kotlinx.coroutines.flow.Flow<SourcedStatus?>
                get() = foreign?.let { f -> kotlinx.coroutines.flow.flow { emit(f); emitAll(demo.sourcedStatus) } } ?: demo.sourcedStatus
            override fun latestSourcedStatus(): SourcedStatus? = foreign ?: demo.latestSourcedStatus()
        }
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, writeFile = { file, bytes ->
            if (failClear && file.name == "active-night.json" && bytes.decodeToString() == "null") throw IOException("Clear disk failure")
            if (failBackups && file.name == "slot-backups.json") throw IOException("Backup disk failure")
            if (failSettings && file.name == "settings.json") throw IOException("Settings disk failure")
            Repository.writeDurably(file, bytes)
        }, now = now) } }
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
        val restarted = graphs.fresh { Graph(context, graph.link) }
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
        assertEquals(OutcomeKind.CONFIRMED, extend.await().kind)
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
        val failed = graph.repository.history.value.single { it.id == graph.repository.activeNight.value!!.recordId }
        assertTrue(failed.events.any { it.kind == EventKind.ERROR })
        val reloaded = graphs.afterFinishing { Repository(context, graph.scope, writer = Dispatchers.Unconfined) { graph.now() } }
        assertEquals(failed, reloaded.history.value.single { it.id == failed.id })
    }
    @Test fun corruptOriginalsCannotAuthoriseStartBoostOrPinAfterNoticeDismissal() = runBlocking {
        File(context.filesDir, "slot-backups.json").writeText("[{ broken")
        val restarted = graphs.fresh { Graph(context, graph.link) { scope, now ->
            Repository(context, scope, writer = Dispatchers.Unconfined, now = now)
        } }
        restarted.clock = graph.clock
        assertEquals(listOf("slot-backups.json"), restarted.repository.unreadable.value)
        restarted.repository.dismissUnreadable()
        assertTrue(restarted.repository.unreadable.value.isEmpty())
        sent.clear()

        // IR-03: refused with that reason before any work, rather than failed partway.
        assertEquals(OutcomeKind.REFUSED, restarted.actions.pin(restarted.repository.profiles.value.first().id, 0).kind)
        assertEquals(OutcomeKind.REFUSED, restarted.actions.execute(restarted.actions.capture(NightActions.Command.BOOST)).kind)
        assertEquals(OutcomeKind.REFUSED, restarted.actions.execute(restarted.actions.capture(NightActions.Command.START_NIGHT)).kind)
        assertTrue("A corrupt originals file allowed a hardware write", sent.isEmpty())
        assertTrue(demo.slots.all { it == null })
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
    @Test fun selectingRealTargetPersistsTargetAndDemoModeTogether() = runBlocking {
        val target = "ble:AA:BB:CC:DD:EE:FF"
        assertEquals(OutcomeKind.LOCAL, graph.actions.selectTarget(target, useRealDevice = true).kind)
        assertEquals(target, graph.repository.settings.value.deviceId())
        assertFalse(graph.repository.settings.value.demoMode)
        val reloaded = graphs.afterFinishing { Repository(context, graph.scope, writer = Dispatchers.Unconfined) { graph.now() } }
        assertEquals(target, reloaded.settings.value.deviceId())
        assertFalse(reloaded.settings.value.demoMode)
    }
    @Test fun activeWakeOverrideUsesOwnedReapplyAndRejectsStaleOrUnknownNight() = runBlocking {
        val initial = start()
        val wakeChoice = graph.now().plusHours(6)
        val applied = graph.actions.setWakeOverride(wakeChoice, initial.identity)
        assertEquals(OutcomeKind.CONFIRMED, applied.kind)
        val current = graph.repository.activeNight.value!!
        assertEquals(initial.revision + 1, current.revision)
        assertEquals(wakeChoice.toInstant(), ZonedDateTime.parse(current.wakeAt).toInstant())
        assertNotNull(graph.repository.settings.value.tonightOverride)

        val cleared = graph.actions.setWakeOverride(null, current.identity)
        assertTrue(cleared.kind == OutcomeKind.CONFIRMED || cleared.kind == OutcomeKind.LOCAL)
        assertNull(graph.repository.settings.value.tonightOverride)
        val afterClear = graph.repository.activeNight.value!!
        val before = graph.repository.settings.value
        assertEquals(OutcomeKind.STALE, graph.actions.setWakeOverride(graph.now().plusHours(5), initial.identity).kind)
        assertEquals(before, graph.repository.settings.value)

        graph.repository.setActiveNight(afterClear.copy(confidence = Confidence.UNKNOWN))
        assertEquals(OutcomeKind.REFUSED, graph.actions.setWakeOverride(null, afterClear.identity).kind)
        assertEquals(before, graph.repository.settings.value)
    }
    @Test fun selectingRealTargetRefusesAnActiveNightWithoutChangingSettings() = runBlocking {
        start()
        val before = graph.repository.settings.value
        assertEquals(OutcomeKind.REFUSED, graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:FF", useRealDevice = true).kind)
        assertEquals(before, graph.repository.settings.value)
    }
    @Test fun selectingRealTargetWriteFailureKeepsThePreviousPairAfterRestart() = runBlocking {
        val before = graph.repository.settings.value
        failSettings = true
        assertEquals(OutcomeKind.FAILED, graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:FF", useRealDevice = true).kind)
        assertEquals(before, graph.repository.settings.value)
        val reloaded = graphs.afterFinishing { Repository(context, graph.scope, writer = Dispatchers.Unconfined) { graph.now() } }
        assertEquals(before, reloaded.settings.value)
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
        assertNull(graph.repository.runtime.value.slotWrite)
        failBackups = false
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.pin(graph.repository.profiles.value.first().id, 0).kind)
        assertEquals(Confidence.CONFIRMED, graph.repository.runtime.value.slotWrite?.confidence)
        assertTrue(graph.actions.status.value.confidence != Confidence.FAILED)

        failBackups = true
        graph.repository.updateSettings { it.copy(boostMinutes = 15) }
        graph.repository.saveProfile(graph.repository.profiles.value.first().copy(limits = io.github.willzy12h.nocturne.core.plan.ProfileLimits(maxTemp = 86)))
        assertEquals(OutcomeKind.FAILED, graph.actions.execute(graph.actions.capture(NightActions.Command.BOOST)).kind)
        assertNull(demo.slots[2])
        assertNull(graph.repository.runtime.value.slotWrite)
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
        val loaded = graphs.afterFinishing { Repository(context, graph.scope, now = { graph.now() }) }
        assertEquals(graph.repository.runtime.value.generation, loaded.runtime.value.generation)
    }
    @Test fun remoteOffEndsNightAndLaterAlarmCannotRestartIt() = runBlocking {
        start()
        graph.actions.remote("Off", endsNight = true, confirms = { it.mode == Mode.STANDBY }) { it.stop() }
        sent.clear(); graph.actions.perform(NightActions.Command.RETIME)
        assertNull(graph.repository.activeNight.value); assertTrue(sent.isEmpty())
    }
    @Test fun unobservedPhysicalOffBlocksAutomaticRetimeAfterReconnect() = runBlocking {
        val night = start()
        // Simulate a physical remote while the app is disconnected: no observer delivers this status.
        graph.link.disconnect()
        demo.connect()
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        demo.disconnect()
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(graph.clock.millis() + 7 * 3_600_000, null),
            PendingIntent.getBroadcast(context, 188, Intent("retime-test"), PendingIntent.FLAG_IMMUTABLE))
        sent.clear()
        graph.actions.recordPendingRetime()
        graph.actions.retryPendingRetime()
        assertTrue("automatic retime wrote to a stopped unit", sent.isEmpty())
        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertFalse(graph.actions.retimePending)
        assertEquals(EventKind.REMOTE_CHANGE, graph.repository.history.value.first { it.id == night.recordId }.events.last().kind)
    }
    @Test fun unobservedPhysicalFanChangeBlocksAutomaticRetimeAfterReconnect() = runBlocking {
        val night = start()
        graph.link.disconnect()
        demo.connect()
        val oldFan = demo.status.value!!.fanStep
        demo.send(Codec.setFan(if (oldFan < 19) oldFan + 1 else oldFan - 1))
        demo.disconnect()
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(graph.clock.millis() + 7 * 3_600_000, null),
            PendingIntent.getBroadcast(context, 189, Intent("retime-fan-test"), PendingIntent.FLAG_IMMUTABLE))
        sent.clear()
        graph.actions.recordPendingRetime()
        graph.actions.retryPendingRetime()
        assertTrue("automatic retime overwrote a physical fan change", sent.isEmpty())
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertFalse(graph.actions.retimePending)
        assertEquals(EventKind.REMOTE_CHANGE, graph.repository.history.value.first { it.id == night.recordId }.events.last().kind)
    }
    @Test fun physicalOffDuringRetimeUploadBlocksStartAndLeavesWrittenSlotUncertain() = runBlocking {
        val night = start()
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(graph.clock.millis() + 7 * 3_600_000, null),
            PendingIntent.getBroadcast(context, 190, Intent("retime-upload-off-test"), PendingIntent.FLAG_IMMUTABLE))
        graph.actions.recordPendingRetime()
        sent.clear()
        var uploadFragments = 0
        beforeSend = { command ->
            if (command[0] == 0x40.toByte() && ++uploadFragments == 2) {
                // The remote acts after the first fragment, while this command owns the link.
                demo.send(Codec.setMode(Mode.STANDBY)!!)
            }
        }

        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))

        assertTrue("retime did not reach the upload", uploadFragments >= 2)
        assertEquals(OutcomeKind.UNKNOWN, result.kind)
        assertFalse("automatic retime restarted a physically stopped unit",
            sent.any { it.contentEquals(Codec.startProgram(night.slot)) })
        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertFalse(graph.actions.retimePending)
        assertEquals(EventKind.REMOTE_CHANGE,
            graph.repository.history.value.first { it.id == night.recordId }.events.last().kind)
    }
    @Test fun lightsBeepsAndAcknowledgementDoNotEndNightOrLeaveItPending() = runBlocking {
        val n = start()
        graph.actions.remote("Lights") { it.setLed(false) }
        graph.actions.remote("Beeps") { it.setMuted(true) }
        graph.actions.remote("Filter") { it.acknowledge() }
        assertEquals(n.identity, graph.repository.activeNight.value!!.identity)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
    }
    @Test fun aStatusFromAnotherBedJetIsNotObservedAsTheTargets() = runBlocking {
        start(); advance(60); demo.tick()
        demo.send(Codec.setMode(Mode.COOL)!!)
        // Reported by a session still on the previous unit: it says nothing about this one.
        graph.actions.observeStatus(demo.status.value!!, "demo", graph.now(), source = DeviceId.ble("AA:BB:CC:DD:EE:01"))
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        graph.actions.observeStatus(demo.status.value!!, "demo", graph.now(), source = "demo")
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
    }
    @Test fun keepTrackingReadsOnlyTheTargetsOwnFreshStatus() = runBlocking {
        start(); advance(60); demo.tick()
        demo.send(Codec.setMode(Mode.COOL)!!)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        val own = demo.status.value!!
        // A running report from another BedJet is the newest the link holds; it must not be tracked as this one's.
        foreign = SourcedStatus(DeviceId.ble("AA:BB:CC:DD:EE:01"), own.copy(mode = Mode.HEAT, remainingSeconds = own.remainingSeconds + 3_600))
        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.KEEP_TRACKING)).kind)
        foreign = null
        val tracked = graph.repository.activeNight.value!!
        assertEquals(graph.now().plusSeconds(own.remainingSeconds.toLong()).toInstant(), ZonedDateTime.parse(tracked.endsAt).toInstant())
    }
    @Test fun aFreshStartTheLinkRefusesBeforeItsFinalCheckEndsFailed() = runBlocking {
        refuseStart = RefuseStart.BEFORE_GUARD
        assertFalse(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        // No start can have reached the unit, so the night is known not to be running.
        assertEquals(Confidence.FAILED, graph.repository.activeNight.value?.confidence)
        assertFalse(sent.any { it.contentEquals(Codec.startProgram(graph.repository.settings.value.workingSlot)) })
    }
    @Test fun aFreshStartRefusedAfterItsFinalCheckIsUnknown() = runBlocking {
        // The contrast: once the final check has passed, the start may have been written, so nothing is known.
        refuseStart = RefuseStart.AFTER_GUARD
        assertFalse(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
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
    @Test fun aDifferentProgrammeDuringStepStartGraceIsTakeover() = runBlocking {
        val n = start()
        val changed = demo.status.value!!.copy(mode = Mode.HEAT, targetTemperature = 64)
        // The step number alone is insufficient: a physical remote can change mode before
        // the first twenty seconds of our planned step have elapsed.
        assertTrue(changed.extra!!.programStep > 0)
        graph.actions.observeStatus(changed, "demo")
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertEquals(EventKind.REMOTE_CHANGE,
            graph.repository.history.value.first { it.id == n.recordId }.events.last().kind)
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
    @Test fun handoverPreservesRecoveryReasonAndResumeOffer() = runBlocking {
        start()
        graph.actions.perform(NightActions.Command.OFF)
        val offer = graph.repository.runtime.value.resumeOffer
        assertNotNull(offer)
        graph.repository.updateRuntime { it.copy(reason = "Hardware state is uncertain.") }
        val generation = graph.repository.runtime.value.generation

        assertEquals(OutcomeKind.LOCAL, graph.actions.releaseForOtherPhone().kind)
        assertEquals(generation + 1, graph.repository.runtime.value.generation)
        assertEquals("Hardware state is uncertain.", graph.repository.runtime.value.reason)
        assertEquals(offer, graph.repository.runtime.value.resumeOffer)
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
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
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
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.setupFanCheck().kind)
        assertEquals(original, demo.status.value!!.fanStep)
        assertEquals(2, sent.size); assertTrue(sent.all { it[0] == 0x07.toByte() })
        demo.send(Codec.setMode(Mode.COOL)!!); sent.clear()
        assertEquals(OutcomeKind.REFUSED, graph.actions.setupFanCheck().kind); assertTrue(sent.isEmpty())
    }
    @Test fun forgettingAndReselectingAUnitRequiresANewSupervisedCheck() = runBlocking {
        val target = "ble:AA:BB:CC:DD:EE:FF"
        graph.repository.updateSettings { it.copy(targetDevice = target, setupFanCheckedDevice = target) }
        assertEquals(OutcomeKind.LOCAL, graph.actions.forgetTarget().kind)
        assertNull(graph.repository.settings.value.setupFanCheckedDevice)
        assertEquals(OutcomeKind.LOCAL, graph.actions.selectTarget(target, useRealDevice = true).kind)
        assertNull(graph.repository.settings.value.setupFanCheckedDevice)
    }
    @Test fun pinConfirmationRejectsChangedTargetProfileSlotAndActiveNight() = runBlocking {
        val profile = graph.repository.profiles.value.first()
        assertEquals(OutcomeKind.STALE, graph.actions.pin(profile.id, 0,
            expectedTarget = "ble:AA:BB:CC:DD:EE:FF", expectedProfile = profile, expectedWorkingSlot = 2).kind)
        assertEquals(OutcomeKind.STALE, graph.actions.pin(profile.id, 0,
            expectedTarget = "demo", expectedProfile = profile.copy(name = "Out of date"), expectedWorkingSlot = 2).kind)
        assertEquals(OutcomeKind.STALE, graph.actions.pin(profile.id, 0,
            expectedTarget = "demo", expectedProfile = profile, expectedWorkingSlot = 1).kind)
        start()
        assertEquals(OutcomeKind.REFUSED, graph.actions.pin(profile.id, 0,
            expectedTarget = "demo", expectedProfile = profile, expectedWorkingSlot = 2).kind)
    }
    @Test fun delayedPinPreviewCannotWriteAProgrammeThatChangedWhileOpen() = runBlocking {
        val profile = graph.repository.profiles.value.first()
        val at = graph.now()
        val plan = NightPreview.forStartNow(profile, at, null, settings = graph.repository.settings.value).plan!!
        val expected = StoredProgram(profile.name, plan.steps.map { step ->
            step.step.copy(hours = step.minutes / 60, minutes = step.minutes % 60, endsAtClockTime = false)
        })
        advance(2)
        assertEquals(OutcomeKind.STALE, graph.actions.pin(profile.id, 0, expectedTarget = "demo",
            expectedProfile = profile, expectedWorkingSlot = 2, previewedAt = at, expectedProgram = expected).kind)
        assertTrue(sent.isEmpty())
    }
    @Test fun snoozeStoresTheAcceptedManualProgrammeInsteadOfTheOldNightCurve() = runBlocking {
        val original = start()
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.SNOOZE_WARM)).kind)
        val n = graph.repository.activeNight.value!!
        assertEquals(Confidence.UNKNOWN, n.confidence)
        assertTrue(n.trackingManual)
        assertEquals(1, n.applied.programSteps.size)
        assertEquals(Mode.HEAT, n.applied.programSteps.single().mode)
        assertEquals(10, n.applied.steps.single().minutes)
        assertEquals(original.revision + 1, n.applied.revision)
        assertEquals(n.applied, graph.repository.history.value.first { it.id == n.recordId }.revisions.last())
        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.KEEP_TRACKING)).kind)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
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
