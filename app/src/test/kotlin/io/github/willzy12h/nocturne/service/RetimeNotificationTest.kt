package io.github.willzy12h.nocturne.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.AlarmChangedReceiver
import io.github.willzy12h.nocturne.night.AlarmObservation
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.NightAlarmReceiver
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.PendingRetime
import io.github.willzy12h.nocturne.night.RuntimeState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/** Routine alarm retries stay quiet; terminal failures and expiry each have one truthful notice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetimeNotificationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var unreachable = false
    private var writes = 0
    private var connects = 0
    private var beforeConnect: suspend () -> Unit = {}
    private var beforeSend: suspend (ByteArray) -> Unit = {}
    private var afterSend: suspend (ByteArray) -> Unit = {}
    @Volatile private var beforeFileWrite: ((java.io.File, ByteArray) -> Unit)? = null
    @Volatile private var afterFileWrite: ((java.io.File, ByteArray) -> Unit)? = null
    @Volatile private var runtimeWriteFailure: (() -> Unit)? = null

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun connect() {
                connects++
                beforeConnect()
                if (unreachable) throw BedJetError.NotConnected()
                demo.connect()
            }
            override suspend fun send(command: ByteArray) { writes++; beforeSend(command); demo.send(command); afterSend(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                if (file.name == "runtime.json") runtimeWriteFailure?.invoke()
                Repository.writeDurably(file, bytes)
                afterFileWrite?.invoke(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 1
        graph.repository.updateSettings { it.copy(demoMode = true, alarmPackages = setOf("approved.clock"), acceptUnknownAlarmSource = false) }
        BedJetService.lastResult = null
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var advancedAt = System.nanoTime()
        while (!check() && System.nanoTime() < deadline) {
            // Successful START waits through BedJetController's refusal grace on Main. Advance only measured
            // elapsed time, so a tight polling loop cannot spend the operation's 90-second budget during file IO.
            // Graph.clock stays fixed: this advances coroutine delays, not the programme's wake boundaries.
            val elapsedMs = (System.nanoTime() - advancedAt) / 1_000_000
            if (elapsedMs > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsedMs))
                advancedAt += elapsedMs * 1_000_000
            } else shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Retime delivery did not finish", check())
    }
    private fun alarm(hours: Long, creator: String = "approved.clock") {
        val fire = PendingIntent.getBroadcast(app, 99, Intent("test.retime.alarm"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(fire).setCreatorPackage(creator)
        app.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(graph.clock.millis() + hours * 3_600_000, fire), fire)
    }
    private suspend fun start(): ActiveNight {
        alarm(6)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        return graph.repository.activeNight.value!!
    }
    private suspend fun retryService(notifyFailures: Boolean = false): CommandOutcome {
        val context = ServiceStartContext(app)
        assertTrue(BedJetService.start(context, NightActions.Command.RETRY_RETIME, notifyFailures = notifyFailures))
        return deliveredResult(context)
    }
    private fun receiverDelivery(): ServiceStartContext = ServiceStartContext(app).also {
        NightAlarmReceiver().onReceive(it, Intent(NightAlarms.ACTION_RETRY))
        assertNotNull("A pending receiver retry must be handed to the service", it.delivered)
    }
    private suspend fun retryReceiver(): CommandOutcome = deliveredResult(receiverDelivery())
    private suspend fun deliveredResult(context: ServiceStartContext): CommandOutcome = completedResult(context).also {
        assertNotNull("A completed re-time service must emit a coordinator outcome", it)
    }!!
    private suspend fun completedResult(context: ServiceStartContext): CommandOutcome? {
        val outcome = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(context.delivered, 0, 1)
            until { shadowOf(service).isStoppedBySelf }
            // Deliver any already-emitted outcome, then expose a missing result directly instead of waiting for it.
            shadowOf(Looper.getMainLooper()).idle()
            return if (outcome.isCompleted) outcome.await() else null
        } finally { controller.destroy(); outcome.cancel() }
    }
    private fun error() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3)
    private fun retryScheduled() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .any { shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }
    private fun assertQuiet() {
        assertNull("Routine alarm handling must not post a service error", error())
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }

    private suspend fun assertOneTerminalNotice(unknown: Boolean) {
        assertNotNull("A terminal background failure needs a visible problem notice", error())
        until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM } }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        if (unknown) {
            assertTrue("Possible writes must be described as uncertain", error()!!.extras.getString("android.text")!!.contains("unknown"))
            assertFalse(error()!!.extras.getString("android.text")!!.contains("not restarted"))
            assertFalse(error()!!.extras.getString("android.title")!!.contains("wasn't applied"))
        }
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
        val count = writes
        val repeated = ServiceStartContext(app)
        NightAlarmReceiver().onReceive(repeated, Intent(NightAlarms.ACTION_RETRY))
        assertTrue("A dropped retry must not start a second service", repeated.requests.isEmpty())
        assertEquals(count, writes)
        // A repository write serialises after the asynchronous notice writes, so duplicate logs cannot hide.
        graph.repository.updateRuntime { it }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
    }

    private fun errors(night: ActiveNight) = graph.repository.history.value.single { it.id == night.recordId }.events
        .filter { it.kind == EventKind.ERROR }

    private fun terminalErrors(night: ActiveNight) = errors(night).filter { failure ->
        OutcomeKind.entries.any { failure.detail.contains(" [${it.name}]: ") }
    }

    private suspend fun assertDurableTerminalHistory(night: ActiveNight, kind: OutcomeKind, reason: String,
        previousTerminalErrors: Int) {
        val record = graph.repository.history.value.single { it.id == night.recordId }
        val failures = terminalErrors(night)
        assertEquals("Each terminal retime must add exactly one typed durable failure", previousTerminalErrors + 1, failures.size)
        val failure = failures.last()
        assertTrue("History must retain the terminal $kind outcome independently of the notice", failure.detail.contains(" [${kind.name}]: "))
        assertTrue("History must retain the actual terminal reason", failure.detail.contains(reason))
        app.getSystemService(NotificationManager::class.java).cancelAll()
        graph.repository.clearNotices()
        beforeFileWrite = null
        runtimeWriteFailure = null
        val clock = graph.clock
        graph = graphs.fresh { Graph(app, demo) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = clock
        assertTrue("The notice has been dismissed independently", graph.repository.notices.value.isEmpty())
        assertEquals("The typed terminal reason must survive dismissal and process restart", record,
            graph.repository.history.value.single { it.id == night.recordId })
    }

    private suspend fun pendingForReceiver(): ActiveNight = pendingAfterConnectionFailure().also { unreachable = false }

    @Test fun receiverPartialWriteLeavesUnknownAndPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        val previousTerminalErrors = terminalErrors(night).size
        val count = writes
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        val outcome = retryReceiver()
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertTrue("The upload happened before its START failed", writes > count)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertOneTerminalNotice(unknown = true)
        assertDurableTerminalHistory(night, outcome.kind, outcome.message, previousTerminalErrors)
    }

    @Test fun receiverPermanentPreWriteRefusalPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        val previousTerminalErrors = terminalErrors(night).size
        val count = writes
        var delayed = false
        beforeFileWrite = { file, bytes ->
            if (!delayed && file.name == "active-night.json" && bytes.toString(Charsets.UTF_8).contains("PENDING")) {
                delayed = true
                // Move Graph's injected clock during PENDING persistence so NightRunner.checkExecutionTime's
                // final pre-write guard sees the new wake boundary crossed; the old retry deadline remains future.
                graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).minusHours(1).toInstant(), ZoneOffset.UTC)
                demo.tick()
            }
        }
        val outcome = retryReceiver()
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertTrue("Exercise the final pre-write PlanException guard", delayed)
        assertEquals(count, writes)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneTerminalNotice(unknown = false)
        assertDurableTerminalHistory(night, outcome.kind, outcome.message, previousTerminalErrors)
    }

    @Test fun receiverCatchPathDroppingUnknownPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        var failed = false
        runtimeWriteFailure = {
            if (!failed && graph.repository.activeNight.value?.confidence == Confidence.UNKNOWN) {
                failed = true
                throw java.io.IOException("Could not save the initial failure reason")
            }
        }
        assertEquals(OutcomeKind.UNKNOWN, retryReceiver().kind)
        assertTrue("The exception must reach the terminal catch branch", failed)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertOneTerminalNotice(unknown = true)
    }

    @Test fun receiverCatchPathDroppingFailedPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        val previousTerminalErrors = terminalErrors(night).size
        val count = writes
        beforeConnect = {
            graph.repository.setActiveNight(night.copy(confidence = Confidence.FAILED))
            throw BedJetError.NotConnected()
        }
        val outcome = retryReceiver()
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(Confidence.FAILED, graph.repository.activeNight.value?.confidence)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = false)
        assertDurableTerminalHistory(night, outcome.kind, outcome.message, previousTerminalErrors)
    }

    @Test fun receiverHistoryFailureAfterConfirmedRetimeKeepsTheConfirmedChange() = runBlocking {
        val night = pendingForReceiver()
        var failed = false
        beforeFileWrite = { file, _ ->
            val current = graph.repository.activeNight.value
            if (file.name == "history.json" && current?.identity != night.identity &&
                current?.confidence == Confidence.CONFIRMED) {
                failed = true
                throw java.io.IOException("Could not save the confirmed re-time history")
            }
        }
        val outcome = completedResult(receiverDelivery())
        assertTrue("Fail the history write only after the new confirmed identity was published", failed)
        assertEquals("A confirmed re-time must still emit CONFIRMED when its history cannot be saved", OutcomeKind.CONFIRMED, outcome?.kind)
        val confirmed = graph.repository.activeNight.value!!
        assertEquals(night.revision + 1, confirmed.revision)
        assertEquals(Confidence.CONFIRMED, confirmed.confidence)
        assertEquals(Instant.parse("2026-10-22T03:30:00Z"), ZonedDateTime.parse(confirmed.wakeAt).toInstant())
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
        assertQuiet()
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        val notice = graph.repository.notices.value.single { it.kind == NoticeKind.INFO }
        assertTrue("The notice must distinguish the local record from the confirmed BedJet change", notice.text.contains("record"))
        assertTrue("Keep the local persistence error available", notice.text.contains("Could not save the confirmed re-time history"))
        val wakeAlarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_WAKE_UP
        }
        assertEquals(confirmed.identity, NightAlarms.identity(shadowOf(wakeAlarm.operation).savedIntent))
        assertEquals(ZonedDateTime.parse(confirmed.wakeUpStart).toInstant().toEpochMilli(), wakeAlarm.triggerAtTime)
        val count = writes
        val repeated = ServiceStartContext(app)
        NightAlarmReceiver().onReceive(repeated, Intent(NightAlarms.ACTION_RETRY))
        assertTrue(repeated.requests.isEmpty())
        assertEquals(count, writes)
    }

    private suspend fun confirmedWithPersistentRuntimeFailure(): Pair<ActiveNight, PendingRetime> {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        var failedWrites = 0
        runtimeWriteFailure = {
            val current = graph.repository.activeNight.value
            if (current?.identity != night.identity && current?.confidence == Confidence.CONFIRMED) {
                failedWrites++
                throw java.io.IOException("Could not save the confirmed re-time runtime")
            }
        }
        val outcome = completedResult(receiverDelivery())
        assertTrue("The runtime write must fail after confirmation", failedWrites > 0)
        assertEquals("A confirmed re-time must still emit CONFIRMED when its runtime cannot be saved", OutcomeKind.CONFIRMED, outcome?.kind)
        return night to pending
    }

    @Test fun receiverPersistentRuntimeFailureAfterConfirmedRetimeKeepsTheConfirmedChange() = runBlocking {
        val (night, pending) = confirmedWithPersistentRuntimeFailure()
        val confirmed = graph.repository.activeNight.value!!
        assertEquals(night.revision + 1, confirmed.revision)
        assertEquals(Confidence.CONFIRMED, confirmed.confidence)
        assertEquals(Instant.parse("2026-10-22T03:30:00Z"), ZonedDateTime.parse(confirmed.wakeAt).toInstant())
        // Storage is still unavailable: the old journal remains, but it cannot undo proof of the new programme.
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertFalse("Do not keep scheduling a completed request whose journal cannot be cleared", retryScheduled())
        assertQuiet()
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        val notice = graph.repository.notices.value.single { it.kind == NoticeKind.INFO }
        assertTrue(notice.text.contains("Could not save the confirmed re-time runtime"))
        val wakeAlarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_WAKE_UP
        }
        assertEquals(confirmed.identity, NightAlarms.identity(shadowOf(wakeAlarm.operation).savedIntent))
        val count = writes
        runtimeWriteFailure = null
        assertEquals(OutcomeKind.STALE, retryReceiver().kind)
        assertEquals("An old durable request must never replay over the confirmed revision", count, writes)
        assertEquals(confirmed, graph.repository.activeNight.value)
        assertQuiet()
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.INFO })
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun persistentRetimeJournalDoesNotMakeTheConfirmedStatusPending() = runBlocking {
        val (_, pending) = confirmedWithPersistentRuntimeFailure()
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertTrue("The old journal remains available for later cleanup", graph.actions.retimePending)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("An older revision's journal must not make the confirmed programme pending",
            Confidence.CONFIRMED, graph.actions.status.value.confidence)
    }

    @Test fun persistentRetimeJournalDoesNotHideTheConfirmedQuietWake() = runBlocking {
        graph.repository.updateSettings { it.copy(quietNightNotification = true) }
        confirmedWithPersistentRuntimeFailure()
        val confirmed = graph.repository.activeNight.value!!
        graph.actions.refreshQuietNotification()
        val wake = ZonedDateTime.parse(confirmed.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        assertEquals("Refreshing the quiet status must keep the current confirmed wake visible",
            app.getString(R.string.runtime_quiet, wake), quietTitle())
    }

    @Test fun persistentRetimeJournalIsNotRearmedByReconciliation() = runBlocking {
        val (_, pending) = confirmedWithPersistentRuntimeFailure()
        val count = writes
        graph.actions.reconcile()
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertEquals(count, writes)
        assertFalse("Reconciliation must not schedule a request superseded by a confirmed revision", retryScheduled())
    }

    private suspend fun supersededRetryWhileStorageFails(fromReceiver: Boolean) {
        val (_, pending) = confirmedWithPersistentRuntimeFailure()
        val confirmed = graph.repository.activeNight.value
        val count = writes
        val outcome = if (fromReceiver) retryReceiver() else retryService(notifyFailures = true)
        assertEquals("A superseded journal save failure is local bookkeeping, not a failed BedJet change", OutcomeKind.LOCAL, outcome.kind)
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertEquals(confirmed, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertFalse("An uncleared obsolete request must not remain scheduled", retryScheduled())
        assertQuiet()
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.INFO })
    }

    @Test fun automaticSupersededRetimeRetryStaysLocalWhileStorageFails() = runBlocking {
        supersededRetryWhileStorageFails(fromReceiver = true)
    }

    @Test fun explicitSupersededRetimeRetryStaysLocalWhileStorageFails() = runBlocking {
        supersededRetryWhileStorageFails(fromReceiver = false)
    }

    @Test fun explicitRecoveredRetimeJournalCleanupStaysStaleWithoutAProblem() = runBlocking {
        confirmedWithPersistentRuntimeFailure()
        val confirmed = graph.repository.activeNight.value
        val count = writes
        runtimeWriteFailure = null
        assertEquals(OutcomeKind.STALE, retryService(notifyFailures = true).kind)
        assertNull(graph.repository.runtime.value.retime)
        assertEquals(confirmed, graph.repository.activeNight.value)
        assertEquals("Clearing a superseded journal must not replay its BedJet change", count, writes)
        assertFalse(retryScheduled())
        assertQuiet()
    }

    @Test fun ordinaryStaleServiceCommandStillShowsAProblem() = runBlocking {
        val night = start()
        val captured = graph.actions.capture(NightActions.Command.END_NIGHT, requireIdentity = true)
        graph.repository.setActiveNight(night.copy(revision = night.revision + 1))
        val count = writes
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, captured, notifyFailures = true))
        assertEquals(OutcomeKind.STALE, deliveredResult(delivery).kind)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = false)
    }

    @Test fun supersededJournalCleanupTimeoutDescribesTheSameCurrentConfirmedIdentity() = runBlocking {
        val (_, pending) = confirmedWithPersistentRuntimeFailure()
        val confirmed = graph.repository.activeNight.value!!
        val captured = graph.actions.capture(NightActions.Command.RETRY_RETIME)
        assertEquals("An app-open retry captures the already-confirmed current identity", confirmed.identity, captured.expected)
        val count = writes
        var timedOut = false
        runtimeWriteFailure = {
            timedOut = true
            // Exercise cancellation while clearing only an obsolete local journal, with no possible BedJet write.
            runBlocking { withTimeout(1) { awaitCancellation() } }
        }
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, captured, notifyFailures = true))
        assertNull("Cancellation must propagate without a coordinator outcome", completedResult(delivery))
        assertTrue("The timeout must come from the obsolete journal's persistence hook", timedOut)
        assertEquals(confirmed, graph.repository.activeNight.value)
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertEquals(count, writes)
        assertFalse(retryScheduled())
        assertQuiet()
        until { graph.repository.notices.value.any { it.text.contains("current programme is confirmed") } }
        val notice = graph.repository.notices.value.single { it.text.contains("current programme is confirmed") }
        val wake = ZonedDateTime.parse(confirmed.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        assertTrue("Describe the current confirmed wake without crediting the timed-out cleanup", notice.title.contains(wake))
        assertTrue(notice.text.contains("timed out"))
    }

    private fun quietTitle() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(6)
        ?.extras?.getString("android.title")

    @Test fun pendingPresentationBelongsToTheCurrentNightAndTarget() = runBlocking {
        graph.repository.updateSettings { it.copy(quietNightNotification = true) }
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        val wake = ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        fun presentation(confidence: Confidence, title: Int) {
            shadowOf(Looper.getMainLooper()).idle()
            graph.actions.refreshQuietNotification()
            assertEquals(confidence, graph.actions.status.value.confidence)
            assertEquals(app.getString(title, wake), quietTitle())
        }
        presentation(Confidence.PENDING, R.string.quiet_pending)
        graph.repository.updateRuntime { it.copy(retime = pending.copy(deviceId = "ble:00:11:22:33:44:55")) }
        presentation(Confidence.CONFIRMED, R.string.runtime_quiet)
        graph.repository.updateRuntime { it.copy(retime = null,
            alarmInbox = AlarmObservation("other", "other-night", night.deviceId, graph.now().toString())) }
        presentation(Confidence.CONFIRMED, R.string.runtime_quiet)
        graph.repository.updateRuntime { it.copy(alarmInbox = it.alarmInbox!!.copy(nightId = night.recordId,
            deviceId = "ble:00:11:22:33:44:55")) }
        presentation(Confidence.CONFIRMED, R.string.runtime_quiet)
        graph.repository.updateRuntime { it.copy(alarmInbox = it.alarmInbox!!.copy(deviceId = night.deviceId)) }
        presentation(Confidence.PENDING, R.string.quiet_pending)
    }

    @Test fun receiverCatchCannotCreditAnUnrelatedConfirmedRevisionAsThisRetime() = runBlocking {
        val night = pendingForReceiver()
        val unrelated = night.copy(revision = night.revision + 7)
        beforeConnect = {
            graph.repository.setActiveNight(unrelated)
            throw BedJetError.NotConnected()
        }
        val count = writes
        assertEquals(OutcomeKind.FAILED, retryReceiver().kind)
        assertEquals(unrelated, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = false)
    }

    @Test fun receiverRetryableFailureKeepsItsAlarmWithoutANotice() = runBlocking {
        val night = pendingAfterConnectionFailure()
        val count = writes
        repeat(2) {
            assertEquals(OutcomeKind.FAILED, retryReceiver().kind)
            assertTrue(graph.actions.retimePending)
            assertTrue(retryScheduled())
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(count, writes)
            graph.repository.updateRuntime { it }
            assertQuiet()
        }
    }

    @Test fun receiverExpiryKeepsItsExistingSingleNotice() = runBlocking {
        val night = pendingAfterConnectionFailure()
        atBoundary(night)
        assertEquals(OutcomeKind.LOCAL, retryReceiver().kind)
        assertOneExpiryNotice()
        assertOneTerminalNotice(unknown = false)
    }

    @Test fun receiverStaleRevisionDropsRetryWithoutANotice() = runBlocking {
        val night = pendingForReceiver()
        graph.repository.setActiveNight(night.copy(revision = night.revision + 1))
        val count = writes
        assertEquals(OutcomeKind.STALE, retryReceiver().kind)
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
        assertEquals(count, writes)
        graph.repository.updateRuntime { it }
        assertQuiet()
    }

    @Test fun receiverTakeoverDuringUploadKeepsItsSingleExistingNotice() = runBlocking {
        takeoverDuringUpload(fromReceiver = true)
    }

    @Test fun explicitRetryTakeoverDuringUploadKeepsItsSingleExistingNotice() = runBlocking {
        takeoverDuringUpload(fromReceiver = false)
    }

    private suspend fun takeoverDuringUpload(fromReceiver: Boolean) {
        pendingForReceiver()
        var changed = false
        afterSend = {
            if (!changed && it[0] == 0x40.toByte()) {
                changed = true
                demo.send(Codec.setMode(Mode.STANDBY)!!)
            }
        }
        val result = if (fromReceiver) retryReceiver() else retryService(notifyFailures = true)
        assertEquals(OutcomeKind.UNKNOWN, result.kind)
        assertTrue(changed)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        graph.repository.updateRuntime { it }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.INFO })
        assertEquals(0, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        assertNull(error())
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun takeoverNoticeSurvivesATimeoutClearingTheAdmittedRetry() = runBlocking {
        pendingForReceiver()
        var changed = false
        afterSend = {
            if (!changed && it[0] == 0x40.toByte()) {
                changed = true
                demo.send(Codec.setMode(Mode.STANDBY)!!)
            }
        }
        var timedOut = false
        var noticeWasPosted = false
        runtimeWriteFailure = {
            if (!timedOut && graph.repository.runtime.value.reason == app.getString(R.string.runtime_takeover)) {
                noticeWasPosted = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(5) != null
                timedOut = true
                // The takeover notice was posted by apply; cancellation of subsequent journal cleanup is
                // part of that same admitted delivery, not a second device outcome.
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, NightActions.Command.RETRY_RETIME, notifyFailures = true))
        assertNull("Cancellation still propagates without a coordinator outcome", completedResult(delivery))
        assertTrue("Exercise takeover during a real upload", changed)
        assertTrue("Cancel the cleanup only after its terminal notice exists", timedOut && noticeWasPosted)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        graph.repository.updateRuntime { it }
        assertEquals("The original takeover notice is the sole terminal notice", 1, graph.repository.notices.value.size)
        assertEquals(NoticeKind.INFO, graph.repository.notices.value.single().kind)
        assertNull("The service must not replace takeover with a second timeout warning", error())
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun explicitRetryTakeoverBeforeUploadKeepsItsSingleExistingNotice() = runBlocking {
        pendingForReceiver()
        demo.connect()
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val count = writes
        assertEquals(OutcomeKind.UNKNOWN, retryService(notifyFailures = true).kind)
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        graph.repository.updateRuntime { it }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.INFO })
        assertEquals(0, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        assertNull(error())
        assertEquals(count, writes)
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun receiverObservationCannotSilentlyDiscardAPendingRefusedRetime() = runBlocking {
        val night = pendingForReceiver()
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).minusHours(1).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        demo.tick()
        graph.actions.submitAlarmChange()
        val count = writes
        retryReceiver()
        assertEquals(count, writes)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneTerminalNotice(unknown = false)
        assertEquals("A dropped change must not keep promising an armed retry", error()!!.extras.getString("android.text"),
            graph.repository.runtime.value.reason)
    }

    @Test fun receiverRefusedAlarmObservationPostsOneNoticeBeforeARetimeWasPrepared() = runBlocking {
        val night = start()
        val previousTerminalErrors = terminalErrors(night).size
        alarm(5)
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).minusHours(1).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        demo.tick()
        graph.actions.submitAlarmChange()
        assertNull(graph.repository.runtime.value.retime)
        assertNotNull(graph.repository.runtime.value.alarmInbox)
        val count = writes
        val outcome = retryReceiver()
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(count, writes)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneTerminalNotice(unknown = false)
        assertEquals(error()!!.extras.getString("android.text"), graph.repository.runtime.value.reason)
        assertDurableTerminalHistory(night, OutcomeKind.REFUSED, outcome.message, previousTerminalErrors)
    }

    @Test fun terminalPreparationRefusalKeepsHistoryWhenTheNoticeLogCannotBeSaved() = runBlocking {
        val night = start()
        val previousTerminalErrors = terminalErrors(night).size
        alarm(5)
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).minusHours(1).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        demo.tick()
        graph.actions.submitAlarmChange()
        val noticeSaveFailed = AtomicBoolean()
        beforeFileWrite = { file, _ -> if (file.name == "notices.json") {
            noticeSaveFailed.set(true)
            throw java.io.IOException("The notification log is unavailable")
        } }
        val outcome = retryReceiver()
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals("The refused change leaves the old programme confirmed", night, graph.repository.activeNight.value)
        until { noticeSaveFailed.get() }
        assertTrue(graph.repository.notices.value.isEmpty())
        beforeFileWrite = null
        assertDurableTerminalHistory(night, outcome.kind, outcome.message, previousTerminalErrors)
    }

    @Test fun receiverReconciliationRefusalCannotSilentlyConsumeAnAlarmObservation() = runBlocking {
        val night = start().copy(confidence = Confidence.UNKNOWN)
        graph.repository.setActiveNight(night)
        alarm(5)
        graph.actions.submitAlarmChange()
        assertNull(graph.repository.runtime.value.retime)
        assertNotNull(graph.repository.runtime.value.alarmInbox)
        val count = writes
        assertEquals(OutcomeKind.REFUSED, retryReceiver().kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = true)
        assertNotNull(graph.repository.runtime.value.reason)
    }

    @Test fun explicitObservationReconciliationRefusalKeepsOneTruthfulNotice() = runBlocking {
        val night = start().copy(confidence = Confidence.UNKNOWN)
        graph.repository.setActiveNight(night)
        alarm(5)
        graph.actions.submitAlarmChange()
        val count = writes
        assertEquals(OutcomeKind.REFUSED, retryService(notifyFailures = true).kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = true)
    }

    @Test fun receiverManualRefusalCannotSilentlyDiscardAPendingRetime() = runBlocking {
        val night = pendingForReceiver().copy(trackingManual = true)
        graph.repository.setActiveNight(night)
        graph.actions.submitAlarmChange()
        val count = writes
        assertEquals(OutcomeKind.REFUSED, retryReceiver().kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = false)
        assertEquals(error()!!.extras.getString("android.text"), graph.repository.runtime.value.reason)
    }

    @Test fun receiverTimeoutBeforeWriteKeepsTheDurableRetryQuietUntilOneExpiryNotice() = runBlocking {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        val count = writes
        var connecting = false
        beforeConnect = {
            connecting = true
            awaitCancellation()
        }
        val delivery = receiverDelivery()
        NightAlarms.cancelRetry(app) // Require timeout handling itself to re-arm the durable work.
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { connecting }
            // Advance only the timeout dispatcher; the injected programme clock stays before its retry deadline.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(count, writes)
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(pending, graph.repository.runtime.value.retime)
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(pending.id))
            assertQuiet()
            assertTrue("A timed-out attempt must re-arm its durable retry", retryScheduled())
        } finally { controller.destroy(); beforeConnect = {} }
        atBoundary(night)
        assertEquals(OutcomeKind.LOCAL, retryReceiver().kind)
        assertOneExpiryNotice()
        assertOneTerminalNotice(unknown = false)
    }

    @Test fun admittedRetimeTimeoutPastDeadlineKeepsItsPendingExpiryQuiet() = runBlocking {
        timeoutPastDeadline(waitingForOwnership = false)
    }

    @Test fun queuedRetimeTimeoutPastDeadlineKeepsItsPendingExpiryQuiet() = runBlocking {
        timeoutPastDeadline(waitingForOwnership = true)
    }

    private suspend fun timeoutPastDeadline(waitingForOwnership: Boolean) = kotlinx.coroutines.coroutineScope {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = if (waitingForOwnership) async(start = CoroutineStart.UNDISPATCHED) {
            graph.actions.exclusive { entered.complete(Unit); release.await() }
        } else null
        if (holder != null) entered.await()
        else beforeConnect = { entered.complete(Unit); awaitCancellation() }
        val count = writes
        val delivery = receiverDelivery()
        NightAlarms.cancelRetry(app)
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { if (waitingForOwnership) graph.actions.busy.value.queuedCount == 1 else entered.isCompleted }
            atBoundary(night)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals("A cancelled read or queue wait cannot write to the BedJet", count, writes)
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals("Expiry still owns this durable no-write request", pending, graph.repository.runtime.value.retime)
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(pending.id))
            assertFalse("A confirmed unchanged night must not gain an unconfirmed-change reason",
                graph.repository.runtime.value.reason.orEmpty().contains("could not confirm"))
            graph.repository.updateRuntime { it }
            assertTrue("A no-write timeout stays quiet even after the deadline", graph.repository.notices.value.isEmpty())
            assertQuiet()
            val retry = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
                shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY
            }
            assertTrue("Schedule a future observation so the normal expiry owner can finish", retry.triggerAtTime > graph.clock.millis())
        } finally {
            release.complete(Unit)
            holder?.await()
            controller.destroy()
            beforeConnect = {}
        }
        assertEquals(OutcomeKind.LOCAL, retryReceiver().kind)
        assertEquals(count, writes)
        assertOneExpiryNotice()
        assertOneTerminalNotice(unknown = false)
    }

    @Test fun queuedRetimeTimeoutWithAnEmptyJournalStaysQuiet() = runBlocking {
        val night = start()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            graph.actions.exclusive { entered.complete(Unit); release.await() }
        }
        entered.await()
        val count = writes
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, NightActions.Command.RETRY_RETIME, notifyFailures = true))
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { graph.actions.busy.value.queuedCount == 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(count, writes)
            assertEquals(night, graph.repository.activeNight.value)
            assertFalse(graph.actions.retimePending)
            assertFalse(retryScheduled())
            graph.repository.updateRuntime { it }
            assertTrue("No pending work and no write means no extra information notice", graph.repository.notices.value.isEmpty())
            assertQuiet()
        } finally { release.complete(Unit); holder.await(); controller.destroy() }
    }

    @Test fun admittedRetimeTimeoutAfterLocalExpiryKeepsTheSingleExpiryNotice() = runBlocking {
        val night = pendingForReceiver()
        val entered = CompletableDeferred<Unit>()
        beforeConnect = { entered.complete(Unit); awaitCancellation() }
        val count = writes
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { entered.isCompleted }
            atBoundary(night)
            graph.actions.submitAlarmChange()
            assertOneExpiryNotice()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            graph.repository.updateRuntime { it }
            assertEquals("The completed expiry owns the only notice for this no-write request", 1,
                graph.repository.notices.value.size)
            assertEquals(app.getString(R.string.runtime_retime_expired), BedJetService.lastResult)
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(count, writes)
            assertOneExpiryNotice()
        } finally { controller.destroy(); beforeConnect = {} }
    }

    @Test fun receiverTimeoutWaitingForOwnershipKeepsItsDurableObservationQuiet() = runBlocking {
        val night = start()
        alarm(5)
        graph.actions.submitAlarmChange()
        val observed = graph.repository.runtime.value.alarmInbox!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            graph.actions.exclusive { entered.complete(Unit); release.await() }
        }
        entered.await()
        val count = writes
        val delivery = receiverDelivery()
        NightAlarms.cancelRetry(app)
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { graph.actions.busy.value.queuedCount == 1 }
            // Advance the service's timeout dispatcher, leaving Graph.now() and the running programme unchanged.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(count, writes)
            assertEquals(night, graph.repository.activeNight.value)
            assertNull(graph.repository.runtime.value.retime)
            assertEquals(observed, graph.repository.runtime.value.alarmInbox)
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(observed.id))
            assertQuiet()
            assertTrue("An observation not yet prepared still needs its retry alarm", retryScheduled())
        } finally { release.complete(Unit); holder.await(); controller.destroy() }
        assertEquals(OutcomeKind.CONFIRMED, retryReceiver().kind)
        until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
        graph.repository.updateRuntime { it }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.INFO })
        assertQuiet()
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun receiverTimeoutAfterWritePostsOneTruthfulNotice() = runBlocking {
        val night = pendingForReceiver()
        val previousTerminalErrors = terminalErrors(night).size
        var wrote = false
        afterSend = {
            if (it[0] == 0x40.toByte()) {
                wrote = true
                // Inject the timeout after a real upload packet to exercise NightRunner.apply's UNKNOWN
                // publication and BedJetService's timeout notice, without waiting for its operation deadline.
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { shadowOf(service).isStoppedBySelf }
            assertTrue(wrote)
            assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
            assertNotNull("A background operation timeout must not be suppressed", error())
            assertTrue(error()!!.extras.getString("android.title")!!.contains("timed out"))
            assertFalse(error()!!.extras.getString("android.text")!!.contains("not restarted"))
            assertOneTerminalNotice(unknown = true)
        } finally { controller.destroy() }
        assertDurableTerminalHistory(night, OutcomeKind.UNKNOWN, app.getString(R.string.runtime_retime_timeout_detail), previousTerminalErrors)
    }

    @Test fun receiverTimeoutAfterWritePreservesANewerAlarmObservation() = runBlocking {
        pendingForReceiver()
        var newerId: String? = null
        afterSend = {
            if (newerId == null && it[0] == 0x40.toByte()) {
                graph.actions.submitAlarmChange()
                newerId = graph.repository.runtime.value.alarmInbox!!.id
                // The timeout owns this upload, while the separately saved observation must survive its cleanup.
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { shadowOf(service).isStoppedBySelf }
            assertNotNull(newerId)
            assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
            assertNull("The timed-out upload must not remain an executable retry", graph.repository.runtime.value.retime)
            assertEquals(newerId, graph.repository.runtime.value.alarmInbox?.id)
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(newerId!!))
            assertTrue("The newer observation retains its own delivery", retryScheduled())
            assertTrue(error()!!.extras.getString("android.text")!!.contains("unknown"))
            until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM } }
            graph.repository.updateRuntime { it }
            assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        } finally { controller.destroy() }
    }

    @Test fun receiverTimeoutAfterConfirmationDescribesTheCurrentConfirmedWake() = runBlocking {
        val night = pendingForReceiver()
        var timedOut = false
        beforeFileWrite = { file, _ ->
            val current = graph.repository.activeNight.value
            if (!timedOut && file.name == "history.json" && current?.identity != night.identity &&
                current?.confidence == Confidence.CONFIRMED) {
                timedOut = true
                // Timeout is still cancellation after publication; only the local save has not completed.
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { shadowOf(service).isStoppedBySelf }
            assertTrue(timedOut)
            val confirmed = graph.repository.activeNight.value!!
            assertEquals(night.revision + 1, confirmed.revision)
            assertEquals(Confidence.CONFIRMED, confirmed.confidence)
            assertQuiet()
            until { graph.repository.notices.value.any { it.kind == NoticeKind.INFO } }
            val notice = graph.repository.notices.value.single { it.kind == NoticeKind.INFO }
            val wake = ZonedDateTime.parse(confirmed.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
            assertTrue("Show the current confirmed wake, without attributing it to the timed-out request", notice.title.contains(wake))
            assertTrue(notice.text.contains("timed out"))
            assertTrue(notice.text.contains("current programme is confirmed"))
            assertFalse("Runtime feedback must not expose the coroutine timeout implementation",
                graph.repository.runtime.value.reason.orEmpty().contains("Timed out waiting for"))
            assertFalse(graph.actions.retimePending)
            assertFalse(retryScheduled())
            val wakeAlarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
                shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_WAKE_UP
            }
            assertEquals(confirmed.identity, NightAlarms.identity(shadowOf(wakeAlarm.operation).savedIntent))
        } finally { controller.destroy() }
    }

    @Test fun terminalRetimeNoticeIsNotRepeatedByAnExplicitServiceRequest() = runBlocking {
        val night = pendingForReceiver()
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        assertEquals(OutcomeKind.UNKNOWN, retryService(notifyFailures = true).kind)
        assertOneTerminalNotice(unknown = true)
    }

    @Test fun directTerminalRetryKeepsOneFailureWhenItsJournalCleanupThrows() = runBlocking {
        val night = pendingForReceiver()
        val previousTerminalErrors = terminalErrors(night).size
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        var cleanupFailed = false
        beforeFileWrite = { file, bytes ->
            if (!cleanupFailed && file.name == "runtime.json" && graph.repository.activeNight.value?.confidence == Confidence.UNKNOWN &&
                BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.toString(Charsets.UTF_8)).retime == null) {
                cleanupFailed = true
                throw java.io.IOException("The terminal retry journal could not be cleared")
            }
        }
        // A direct coordinator caller supplies no service-owned RetimeExecution context.
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        assertTrue(cleanupFailed)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals("Cleanup belongs to the same failed hardware request", BedJetError.NotConnected().message, outcome.message)
        assertNull(graph.repository.runtime.value.retime)
        assertOneTerminalNotice(unknown = true)
        assertDurableTerminalHistory(night, outcome.kind, outcome.message, previousTerminalErrors)
    }

    @Test fun aNewObservationKeepsItsOwnRefusalAfterAnOlderTerminalCleanupFailed() = runBlocking {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        beforeFileWrite = { file, bytes ->
            if (file.name == "runtime.json" && graph.repository.activeNight.value?.confidence == Confidence.UNKNOWN &&
                BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.toString(Charsets.UTF_8)).retime == null)
                throw java.io.IOException("Keep the older terminal journal for recovery")
        }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)).kind)
        assertEquals("The old terminal result still owns its uncleared journal", pending, graph.repository.runtime.value.retime)
        until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM } }
        beforeFileWrite = null
        val previousTerminalErrors = terminalErrors(night).size
        val previousNotices = graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM }
        alarm(4)
        graph.actions.submitAlarmChange()
        val observation = graph.repository.runtime.value.alarmInbox!!
        assertNotEquals(pending.id, observation.id)
        val count = writes

        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))

        assertEquals("The fresh observation owns its reconciliation refusal", OutcomeKind.REFUSED, outcome.kind)
        assertEquals(app.getString(R.string.runtime_reconcile_needed), outcome.message)
        assertEquals("A reconciliation refusal sends no hardware command", count, writes)
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertNull(graph.repository.runtime.value.retime)
        until { graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM } > previousNotices }
        assertEquals("The new observation must retain its own notice", previousNotices + 1,
            graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        assertDurableTerminalHistory(night, outcome.kind, outcome.message, previousTerminalErrors)
    }
    private suspend fun unchangedAlarm(creator: String) {
        val night = start()
        graph.repository.updateSettings { it.copy(acceptUnknownAlarmSource = false, alarmPackages = setOf("approved.clock")) }
        alarm(4, creator)
        graph.actions.submitAlarmChange()
        val beforeWrites = writes; val beforeConnects = connects
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(beforeWrites, writes); assertEquals(beforeConnects, connects)
        assertFalse(graph.actions.retimePending)
        assertQuiet()
    }

    @Test fun aTimerAlarmThatDoesNotMoveWakeIsQuiet() = runBlocking { unchangedAlarm("timer.example") }
    @Test fun aCalendarAlarmThatDoesNotMoveWakeIsQuiet() = runBlocking { unchangedAlarm("calendar.example") }

    @Test fun anAlarmWithTheSameWakeTimeIsQuiet() = runBlocking {
        val night = start(); val count = writes
        // Match the actual resolved wake, so this exercises an unchanged observation rather than a rewrite.
        val wake = ZonedDateTime.parse(night.wakeAt).toInstant()
        val fire = PendingIntent.getBroadcast(app, 99, Intent("test.retime.alarm"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(fire).setCreatorPackage("approved.clock")
        app.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(wake.toEpochMilli(), fire), fire)
        assertEquals(wake, io.github.willzy12h.nocturne.night.AlarmSource.describe(app, graph.repository.settings.value)!!.time.toInstant())
        graph.actions.submitAlarmChange()
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertQuiet()
    }

    @Test fun alarmChangesDuringATonightOverrideAreQuiet() = runBlocking {
        graph.actions.setWakeOverride(graph.now().plusHours(6))
        val night = start(); val count = writes
        alarm(4); graph.actions.submitAlarmChange()
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertFalse(graph.actions.retimePending)
        assertQuiet()
    }

    private suspend fun pendingAfterConnectionFailure(): ActiveNight {
        val night = start()
        alarm(5); graph.actions.submitAlarmChange()
        graph.link.disconnect(); unreachable = true
        assertEquals(OutcomeKind.FAILED, graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)).kind)
        assertNotNull(graph.repository.runtime.value.retime)
        return night
    }

    @Test fun repeatedBackgroundConnectionFailuresKeepTheRetryWithoutErrorNotices() = runBlocking {
        val night = pendingAfterConnectionFailure(); val count = writes
        repeat(2) {
            graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(10))
            assertEquals(OutcomeKind.FAILED, retryService().kind)
            assertTrue(graph.actions.retimePending)
            assertTrue(retryScheduled())
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(count, writes)
            assertQuiet()
        }
    }

    private fun atBoundary(night: ActiveNight) {
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).toInstant(), ZoneOffset.UTC)
    }
    private fun assertOneExpiryNotice() {
        assertEquals("The wake-up change expired.", error()?.extras?.getString("android.title"))
        assertTrue(error()!!.extras.getString("android.text")!!.contains("not restarted"))
        until { graph.repository.notices.value.any { it.title == "The wake-up change expired." } }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun finalRetimeExpiryPostsOneNoticeWithoutAServiceFailure() = runBlocking {
        val night = pendingAfterConnectionFailure(); val count = writes
        val previousTerminalErrors = terminalErrors(night).size
        atBoundary(night)
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertOneExpiryNotice()
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertOneExpiryNotice()
        assertEquals(night.applied, graph.repository.activeNight.value!!.applied)
        assertEquals(count, writes)
        assertDurableTerminalHistory(night, OutcomeKind.LOCAL, app.getString(R.string.runtime_retime_expired_detail), previousTerminalErrors)
    }

    @Test fun expiredRetimeRemainsRecoverableWhenItsHistoryWriteFails() = runBlocking {
        val night = pendingAfterConnectionFailure()
        val pending = graph.repository.runtime.value.retime!!
        atBoundary(night)
        var historyFailed = false
        beforeFileWrite = { file, bytes ->
            if (!historyFailed && file.name == "history.json" && bytes.toString(Charsets.UTF_8).contains("[LOCAL]")) {
                historyFailed = true
                throw java.io.IOException("The expiry history could not be saved")
            }
        }
        graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        assertTrue(historyFailed)
        assertEquals("A failed history save must retain the exact expired request for local completion", pending,
            graph.repository.runtime.value.retime)
        assertTrue("The expired request still needs a local retry", retryScheduled())
        val previousTerminalErrors = terminalErrors(night).size
        val count = writes
        beforeFileWrite = null
        assertEquals(OutcomeKind.LOCAL, retryReceiver().kind)
        assertEquals("Completing expired history must never replay hardware", count, writes)
        assertOneExpiryNotice()
        assertDurableTerminalHistory(night, OutcomeKind.LOCAL, app.getString(R.string.runtime_retime_expired_detail), previousTerminalErrors)
    }

    private suspend fun cancelledExpiryRetainsRecovery(cancelAfterClear: Boolean) {
        val night = pendingAfterConnectionFailure()
        graph.actions.submitAlarmChange()
        val pending = graph.repository.runtime.value.retime!!
        val observation = graph.repository.runtime.value.alarmInbox!!
        val previousTerminalErrors = terminalErrors(night).size
        val previousErrors = errors(night).size
        val count = writes
        atBoundary(night)
        val cancellation = CancellationException("Cancel expiry before its history is saved")
        val owner = Job(currentCoroutineContext()[Job])
        var injected = false
        if (cancelAfterClear) {
            afterFileWrite = { file, bytes ->
                if (file.name == "runtime.json" &&
                    BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.toString(Charsets.UTF_8)).retime == null) {
                    injected = true
                    afterFileWrite = null
                    // The clear is on disk. Returning normally lets Repository publish it, then its
                    // withContext return observes this real owner cancellation before History begins.
                    owner.cancel(cancellation)
                }
            }
        } else {
            beforeFileWrite = { file, bytes ->
                if (file.name == "history.json" && bytes.toString(Charsets.UTF_8).contains("[LOCAL]")) {
                    injected = true
                    beforeFileWrite = null
                    owner.cancel(cancellation)
                    throw cancellation
                }
            }
        }
        var thrown: Throwable? = null
        val outcome = try {
            withContext(owner) { graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)) }
        } catch (e: Throwable) { thrown = e; null }
        finally { beforeFileWrite = null; afterFileWrite = null; owner.complete() }
        assertTrue("Cancel at the intended expiry persistence boundary", injected)
        assertTrue("Owner cancellation must propagate", thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
        // Coroutine stack-trace recovery can copy the exception while retaining the original as its cause.
        assertTrue("The original cancellation must survive coroutine recovery",
            generateSequence(thrown) { it.cause }.any { it === cancellation })
        assertNull(outcome)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals("Cancellation must retain the exact expired request for local completion", pending,
            graph.repository.runtime.value.retime)
        assertEquals("The consumed observation must also remain recoverable", observation, graph.repository.runtime.value.alarmInbox)
        val durable = BackupCodec.json.decodeFromString(RuntimeState.serializer(), java.io.File(app.filesDir, "runtime.json").readText())
        assertEquals(pending, durable.retime)
        assertEquals(observation, durable.alarmInbox)
        assertTrue("The restored expiry needs a local retry", retryScheduled())
        assertEquals("Expiry cancellation sends no hardware command", count, writes)
        assertEquals(previousErrors, errors(night).size)

        assertEquals(OutcomeKind.LOCAL, retryReceiver().kind)
        assertEquals("Finishing the restored expiry must not replay hardware", count, writes)
        assertOneExpiryNotice()
        assertDurableTerminalHistory(night, OutcomeKind.LOCAL, app.getString(R.string.runtime_retime_expired_detail), previousTerminalErrors)
    }

    @Test fun cancelledExpiryHistoryWriteRetainsItsExactRecoveryEvidence() = runBlocking {
        cancelledExpiryRetainsRecovery(cancelAfterClear = false)
    }

    @Test fun cancellationAfterExpiryClearPublicationRetainsItsExactRecoveryEvidence() = runBlocking {
        cancelledExpiryRetainsRecovery(cancelAfterClear = true)
    }

    @Test fun aNewAlarmObservationCannotSwallowThePendingRetimeExpiryNotice() = runBlocking {
        val night = pendingAfterConnectionFailure()
        graph.actions.submitAlarmChange()
        assertNotNull(graph.repository.runtime.value.alarmInbox)
        atBoundary(night)
        val outcome = retryService(notifyFailures = true)
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertEquals("Expiry feedback belongs to the observation consumed by this operation",
            app.getString(R.string.runtime_retime_expired), outcome.message)
        assertOneExpiryNotice()
    }

    @Test fun anExpiryMessageCannotHideAnUnknownNightReconciliationRefusal() = runBlocking {
        val night = start().copy(confidence = Confidence.UNKNOWN)
        graph.repository.setActiveNight(night)
        graph.repository.updateRuntime { it.copy(reason = app.getString(R.string.runtime_retime_expired)) }
        val count = writes
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        assertEquals("Display text cannot make UNKNOWN state eligible or claim a fresh expiry", OutcomeKind.REFUSED, outcome.kind)
        assertEquals(app.getString(R.string.runtime_reconcile_needed), outcome.message)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertFalse(graph.actions.retimePending)
    }

    /** Transfer the real broadcast's asynchronous lifetime to the receiver under test. */
    private fun deniedRetryBroadcast(silent: Boolean = false, receiver: BroadcastReceiver = NightAlarmReceiver(),
        action: String = NightAlarms.ACTION_RETRY, after: () -> Boolean) {
        val denied = ServiceStartContext(app).apply { if (silent) silentDenial = true else denyStart = true }
        var lifetime: BroadcastReceiver.PendingResult? = null
        val bridgeAction = "test.delivery.$action"
        val bridge = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                lifetime = goAsync()
                ReflectionHelpers.setField(receiver, "mPendingResult", lifetime)
                receiver.onReceive(denied, Intent(action))
            }
        }
        app.registerReceiver(bridge, IntentFilter(bridgeAction), Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendBroadcast(Intent(bridgeAction))
            until { lifetime?.let { Shadow.extract<ShadowBroadcastPendingResult>(it).future.isDone } == true }
            assertTrue("The completed receiver must preserve or expire the pending retry", after())
        }
        finally { app.unregisterReceiver(bridge) }
    }

    private suspend fun failedLocalRetryIsQuiet(receiver: BroadcastReceiver, action: String, timeout: Boolean) {
        val night = pendingAfterConnectionFailure(); val count = writes
        val pending = graph.repository.runtime.value.retime
        val durable = java.io.File(app.filesDir, "runtime.json").readText()
        var failedWrites = 0
        runtimeWriteFailure = {
            failedWrites++
            // Throw from the synchronous writer hook to exercise Receivers.local's quiet timeout fallback;
            // the nested timeout does not measure the receiver's real eight-second persistence budget.
            if (timeout) runBlocking { withTimeout(1) { awaitCancellation() } }
            throw java.io.IOException("Runtime storage unavailable")
        }
        try {
            repeat(2) { attempt ->
                deniedRetryBroadcast(receiver = receiver, action = action) { graph.repository.runtime.value.retime == pending }
                assertEquals(attempt + 1, failedWrites)
                assertEquals(durable, java.io.File(app.filesDir, "runtime.json").readText())
                assertTrue(graph.actions.retimePending)
                assertTrue(retryScheduled())
                assertEquals(night, graph.repository.activeNight.value)
                assertEquals(count, writes)
                assertQuiet()
            }
        } finally { runtimeWriteFailure = null }
    }

    @Test fun aDeniedRetryWithALocalPersistenceFailureStaysQuietAndDurable() = runBlocking {
        failedLocalRetryIsQuiet(NightAlarmReceiver(), NightAlarms.ACTION_RETRY, timeout = false)
    }

    @Test fun aDeniedRetryWithALocalPersistenceTimeoutStaysQuietAndDurable() = runBlocking {
        failedLocalRetryIsQuiet(NightAlarmReceiver(), NightAlarms.ACTION_RETRY, timeout = true)
    }

    @Test fun anAlarmChangeWithALocalPersistenceFailureStaysQuietAndDurable() = runBlocking {
        failedLocalRetryIsQuiet(AlarmChangedReceiver(), AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED, timeout = false)
    }

    @Test fun anAlarmChangeWithALocalPersistenceTimeoutStaysQuietAndDurable() = runBlocking {
        failedLocalRetryIsQuiet(AlarmChangedReceiver(), AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED, timeout = true)
    }

    @Test fun aDeniedBackgroundRetryRetainsDurableWorkWithoutNotificationSpam() = runBlocking {
        val night = pendingAfterConnectionFailure(); val count = writes
        repeat(2) {
            val old = graph.repository.runtime.value.alarmInbox?.id
            deniedRetryBroadcast { graph.repository.runtime.value.alarmInbox?.id?.let { id -> id != old } == true }
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(night.recordId))
            assertTrue(graph.actions.retimePending)
            assertTrue(retryScheduled())
            assertEquals(count, writes)
            assertQuiet()
        }
    }

    @Test fun aNullBackgroundRetryStartRetainsDurableWorkWithoutNotificationSpam() = runBlocking {
        val night = pendingAfterConnectionFailure(); val count = writes
        val old = graph.repository.runtime.value.alarmInbox?.id
        deniedRetryBroadcast(silent = true) { graph.repository.runtime.value.alarmInbox?.id?.let { id -> id != old } == true }
        assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(night.recordId))
        assertTrue(graph.actions.retimePending)
        assertTrue(retryScheduled())
        assertEquals(count, writes)
        assertQuiet()
    }

    @Test fun aDeniedBackgroundRetryStillReportsItsFinalExpiryExactlyOnce() = runBlocking {
        val night = pendingAfterConnectionFailure(); val count = writes
        atBoundary(night)
        deniedRetryBroadcast { !graph.actions.retimePending }
        assertOneExpiryNotice()
        graph.actions.submitAlarmChange()
        assertOneExpiryNotice()
        assertEquals(count, writes)
        assertEquals(night.applied, graph.repository.activeNight.value!!.applied)
    }
}
