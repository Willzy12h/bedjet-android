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
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.AlarmChangedReceiver
import io.github.willzy12h.nocturne.night.AlarmObservation
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.NightAlarmReceiver
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
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
    private suspend fun deliveredResult(context: ServiceStartContext, afterDelivery: () -> Unit = {}): CommandOutcome {
        val outcome = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(context.delivered, 0, 1)
            afterDelivery()
            until { outcome.isCompleted && shadowOf(service).isStoppedBySelf }
            return outcome.await()
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

    private suspend fun pendingForReceiver(): ActiveNight = pendingAfterConnectionFailure().also { unreachable = false }

    @Test fun receiverPartialWriteLeavesUnknownAndPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        val count = writes
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        assertEquals(OutcomeKind.UNKNOWN, retryReceiver().kind)
        assertTrue("The upload happened before its START failed", writes > count)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertOneTerminalNotice(unknown = true)
    }

    @Test fun receiverPermanentPreWriteRefusalPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        val count = writes
        var delayed = false
        beforeFileWrite = { file, bytes ->
            if (!delayed && file.name == "active-night.json" && bytes.toString(Charsets.UTF_8).contains("PENDING")) {
                delayed = true
                // Exercise NightRunner's final pre-write wake-boundary guard after pending persistence;
                // the new plan expires here, while the old retry deadline remains in the future.
                graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).minusHours(1).toInstant(), ZoneOffset.UTC)
                demo.tick()
            }
        }
        assertEquals(OutcomeKind.REFUSED, retryReceiver().kind)
        assertTrue("Exercise the final pre-write PlanException guard", delayed)
        assertEquals(count, writes)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneTerminalNotice(unknown = false)
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
        val count = writes
        beforeConnect = {
            graph.repository.setActiveNight(night.copy(confidence = Confidence.FAILED))
            throw BedJetError.NotConnected()
        }
        assertEquals(OutcomeKind.FAILED, retryReceiver().kind)
        assertEquals(Confidence.FAILED, graph.repository.activeNight.value?.confidence)
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
        alarm(5)
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).minusHours(1).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        demo.tick()
        graph.actions.submitAlarmChange()
        assertNull(graph.repository.runtime.value.retime)
        assertNotNull(graph.repository.runtime.value.alarmInbox)
        val count = writes
        retryReceiver()
        assertEquals(count, writes)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneTerminalNotice(unknown = false)
        assertEquals(error()!!.extras.getString("android.text"), graph.repository.runtime.value.reason)
    }

    @Test fun receiverTimeoutAfterWritePostsOneTruthfulNotice() = runBlocking {
        pendingForReceiver()
        var wrote = false
        afterSend = {
            if (it[0] == 0x40.toByte()) {
                wrote = true
                // Inject a real TimeoutCancellationException after upload to reach the service timeout
                // catch and core NonCancellable UNKNOWN publication without waiting for its outer budget.
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
            until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM } }
            graph.repository.updateRuntime { it }
            assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
            assertFalse("A possibly accepted write must disable its automatic retry", graph.actions.retimePending)
            assertFalse(retryScheduled())
            val count = writes
            val repeated = ServiceStartContext(app)
            NightAlarmReceiver().onReceive(repeated, Intent(NightAlarms.ACTION_RETRY))
            assertTrue(repeated.requests.isEmpty())
            assertEquals(count, writes)
        } finally { controller.destroy() }
    }

    @Test fun receiverTimeoutBeforeConnectKeepsSafeRetryQuiet() = runBlocking {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime
        val count = writes
        var entered = false
        beforeConnect = { entered = true; awaitCancellation() }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { entered }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(pending, graph.repository.runtime.value.retime)
            assertEquals(count, writes)
            assertTrue(retryScheduled())
            graph.repository.updateRuntime { it }
            assertQuiet()
        } finally { controller.destroy() }
    }

    @Test fun receiverPreWriteTimeoutAtDeadlineLeavesOneExpiryNotice() = runBlocking {
        preWriteTimeoutAtDeadline(expireBeforeTimeout = false)
    }

    @Test fun receiverPreWriteTimeoutAfterExpiryDoesNotPostAgain() = runBlocking {
        preWriteTimeoutAtDeadline(expireBeforeTimeout = true)
    }

    private suspend fun preWriteTimeoutAtDeadline(expireBeforeTimeout: Boolean) {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        val count = writes
        var entered = false
        beforeConnect = { entered = true; awaitCancellation() }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { entered }
            // The connection is already stalled; move wall time exactly to expiry before its owner timer fires.
            graph.clock = Clock.fixed(ZonedDateTime.parse(pending.deadline).toInstant(), ZoneOffset.UTC)
            if (expireBeforeTimeout) {
                graph.actions.submitAlarmChange()
                assertOneExpiryNotice()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(count, writes)
            graph.repository.updateRuntime { it }
            if (expireBeforeTimeout) {
                assertEquals("A consumed expiry must remain the only terminal notice", 1,
                    graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
                assertOneExpiryNotice()
                assertEquals("The wake-up change expired.", BedJetService.lastResult)
                assertFalse(retryScheduled())
            } else {
                assertQuiet()
                assertEquals(pending, graph.repository.runtime.value.retime)
                assertTrue("A due deadline still needs a future delivery to its sole expiry owner",
                    shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
                        shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY &&
                            it.triggerAtTime > graph.clock.millis()
                    })
            }
        } finally { controller.destroy(); beforeConnect = {} }
        if (!expireBeforeTimeout) {
            val connectsBeforeExpiry = connects
            assertEquals(OutcomeKind.LOCAL, retryReceiver().kind)
            assertOneExpiryNotice()
            assertOneTerminalNotice(unknown = false)
            assertEquals(count, writes)
            assertEquals(connectsBeforeExpiry, connects)
        }
    }

    @Test fun neverAdmittedManualRetryTimeoutLeavesItsObservationForOneRefusal() = runBlocking {
        neverAdmittedIneligibleRetryTimeout(manual = true)
    }

    @Test fun neverAdmittedUnknownRetryTimeoutLeavesItsObservationForOneRefusal() = runBlocking {
        neverAdmittedIneligibleRetryTimeout(manual = false)
    }

    private suspend fun neverAdmittedIneligibleRetryTimeout(manual: Boolean) {
        val night = start()
        alarm(5)
        graph.actions.submitAlarmChange()
        val observed = graph.repository.runtime.value.alarmInbox
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        } }
        until { entered.isCompleted }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        val current = night.copy(trackingManual = manual,
            confidence = if (manual) Confidence.CONFIRMED else Confidence.UNKNOWN)
        val count = writes
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { graph.actions.busy.value.queuedCount == 1 }
            graph.repository.setActiveNight(current)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(current, graph.repository.activeNight.value)
            assertEquals(observed, graph.repository.runtime.value.alarmInbox)
            assertEquals(count, writes)
            assertTrue(retryScheduled())
            graph.repository.updateRuntime { it }
            assertQuiet()
        } finally {
            release.complete(Unit)
            until { held.isCompleted }
            controller.destroy()
        }
        val connectsBeforeRefusal = connects
        assertEquals(OutcomeKind.REFUSED, retryReceiver().kind)
        assertOneTerminalNotice(unknown = !manual)
        assertEquals(current, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertEquals(connectsBeforeRefusal, connects)
    }

    @Test fun receiverTimeoutInOwnerQueueKeepsSafeRetryQuiet() = runBlocking {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime
        val count = writes
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive {
            entered.complete(Unit)
            // Keep the owner in its finalisation until the queued request's own deadline has fired.
            withContext(NonCancellable) { release.await() }
        } }
        until { entered.isCompleted }
        val connectsBefore = connects
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { graph.actions.busy.value.queuedCount == 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(night, graph.repository.activeNight.value)
            assertEquals(pending, graph.repository.runtime.value.retime)
            assertEquals(count, writes)
            assertEquals(connectsBefore, connects)
            assertTrue(retryScheduled())
            graph.repository.updateRuntime { it }
            assertQuiet()
        } finally {
            release.complete(Unit)
            until { held.isCompleted }
            controller.destroy()
        }
    }

    @Test fun receiverSaveFailureAfterConfirmedRetimeReportsTheConfirmedWake() = runBlocking {
        val night = pendingForReceiver()
        var failed = false
        var started = false
        afterSend = { if (it.contentEquals(Codec.startProgram(night.slot))) started = true }
        beforeFileWrite = { file, _ ->
            val current = graph.repository.activeNight.value
            if (!failed && file.name == "history.json" && current?.confidence == Confidence.CONFIRMED &&
                current.revision == night.revision + 1) {
                failed = true
                throw java.io.IOException("Could not save the changed history")
            }
        }
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, NightActions.Command.RETRY_RETIME, notifyFailures = true))
        val result = deliveredResult(delivery) {
            until { started }
            // A confirmed start waits through the controller's refusal-grace timer on the service's looper.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        }
        assertTrue("The injected storage failure must happen after confirmed publication", failed)
        val current = graph.repository.activeNight.value!!
        assertEquals(night.revision + 1, current.revision)
        assertEquals(Confidence.CONFIRMED, current.confidence)
        assertNotEquals(night.wakeAt, current.wakeAt)
        assertEquals("Confirmed device state must survive a later local save failure", OutcomeKind.CONFIRMED, result.kind)
        assertNotNull(error())
        assertTrue(error()!!.extras.getString("android.text")!!.contains("confirmed"))
        assertFalse(error()!!.extras.getString("android.title")!!.contains("did not finish"))
        assertFalse(error()!!.extras.getString("android.text")!!.contains("unknown"))
        assertOneTerminalNotice(unknown = false)
        val scheduled = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue("Local recovery must schedule the newly confirmed wake", scheduled.any {
            shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_WAKE_UP &&
                it.triggerAtTime == ZonedDateTime.parse(current.wakeUpStart).toInstant().toEpochMilli()
        })
    }

    @Test fun receiverSaveTimeoutAfterConfirmedRetimePreservesConfirmationAndPropagatesCancellation() = runBlocking {
        val night = pendingForReceiver()
        var timedOut = false
        var started = false
        afterSend = { if (it.contentEquals(Codec.startProgram(night.slot))) started = true }
        beforeFileWrite = { file, _ ->
            val current = graph.repository.activeNight.value
            if (!timedOut && file.name == "history.json" && current?.confidence == Confidence.CONFIRMED &&
                current.revision == night.revision + 1) {
                timedOut = true
                // Reach cancellation after durable CONFIRMED publication, distinct from the upload timeout
                // guard: local finalisation must still run while cancellation propagates to the service.
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val outcome = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, NightActions.Command.RETRY_RETIME, notifyFailures = true))
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { started }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            until { shadowOf(service).isStoppedBySelf }
            assertTrue(timedOut)
            assertFalse("Cancellation must propagate without emitting a completed command outcome", outcome.isCompleted)
            val current = graph.repository.activeNight.value!!
            assertEquals(night.revision + 1, current.revision)
            assertEquals(Confidence.CONFIRMED, current.confidence)
            assertNotNull(error())
            assertTrue("A confirmed wake must not be described as an unconfirmed timeout",
                error()!!.extras.getString("android.text")!!.contains("confirmed"))
            assertFalse(error()!!.extras.getString("android.title")!!.contains("timed out"))
            assertFalse(error()!!.extras.getString("android.text")!!.contains("unknown"))
            assertOneTerminalNotice(unknown = false)
            assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
                shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_WAKE_UP &&
                    it.triggerAtTime == ZonedDateTime.parse(current.wakeUpStart).toInstant().toEpochMilli()
            })
        } finally { controller.destroy(); outcome.cancel() }
    }

    @Test fun receiverQueuedTimeoutPreservesAReplacementObservationWithoutANotice() = runBlocking {
        val night = pendingForReceiver()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        } }
        until { entered.isCompleted }
        val delivery = receiverDelivery()
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { graph.actions.busy.value.queuedCount == 1 }
            val replacement = night.copy(recordId = "replacement-night", revision = 1)
            val observed = AlarmObservation("replacement-observation", replacement.recordId, replacement.deviceId, graph.now().toString())
            graph.repository.setActiveNight(replacement)
            graph.repository.updateRuntime { it.copy(retime = null, alarmInbox = observed) }
            NightAlarms.scheduleObservation(app, graph.now())
            val count = writes
            val connectsBefore = connects
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NightActions.OPERATION_MS + 1))
            until { shadowOf(service).isStoppedBySelf }
            assertEquals(replacement, graph.repository.activeNight.value)
            assertEquals(observed, graph.repository.runtime.value.alarmInbox)
            assertNull(graph.repository.runtime.value.retime)
            assertEquals(count, writes)
            assertEquals(connectsBefore, connects)
            assertTrue(retryScheduled())
            graph.repository.updateRuntime { it }
            assertQuiet()
        } finally {
            release.complete(Unit)
            until { held.isCompleted }
            controller.destroy()
        }
    }

    @Test fun receiverTimeoutAfterCoalescingAReplacementPostsOneNotice() = runBlocking {
        val night = pendingForReceiver()
        val pending = graph.repository.runtime.value.retime!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }
        val delivery = receiverDelivery()
        var wrote = false
        afterSend = {
            if (it[0] == 0x40.toByte()) {
                wrote = true
                // The real timeout follows the coalesced replacement's upload, so the service must use
                // the identity actually owned by this retry, rather than its earlier captured identity.
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { graph.actions.busy.value.queuedCount == 1 }
            val replacement = night.copy(recordId = "coalesced-night", revision = 1)
            graph.repository.setActiveNight(replacement)
            graph.repository.updateRuntime { it.copy(retime = pending.copy(id = "coalesced-retry", expected = replacement.identity)) }
            release.complete(Unit)
            until { held.isCompleted && shadowOf(service).isStoppedBySelf }
            assertTrue("The old delivery must retain its coalesced-latest-work behavior", wrote)
            assertEquals(replacement.recordId, graph.repository.activeNight.value?.recordId)
            assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
            assertNotNull("A timeout after writing the replacement must not be silenced as stale", error())
            assertTrue(error()!!.extras.getString("android.title")!!.contains("timed out"))
            assertFalse(error()!!.extras.getString("android.text")!!.contains("not restarted"))
            assertOneTerminalNotice(unknown = false)
        } finally {
            release.complete(Unit)
            until { held.isCompleted }
            controller.destroy()
        }
    }

    @Test fun receiverManualObservationRefusalPostsOneTerminalNotice() = runBlocking {
        val night = start()
        alarm(5)
        graph.actions.submitAlarmChange()
        val manual = night.copy(trackingManual = true)
        graph.repository.setActiveNight(manual)
        val count = writes
        assertEquals(OutcomeKind.REFUSED, retryService(notifyFailures = true).kind)
        assertEquals(manual, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = false)
        assertEquals(error()!!.extras.getString("android.text"), graph.repository.runtime.value.reason)
    }

    @Test fun receiverUncertainObservationRefusalPostsOneTerminalNotice() = runBlocking {
        val night = start()
        alarm(5)
        graph.actions.submitAlarmChange()
        val uncertain = night.copy(confidence = Confidence.UNKNOWN)
        graph.repository.setActiveNight(uncertain)
        val count = writes
        assertEquals(OutcomeKind.REFUSED, retryReceiver().kind)
        assertEquals(uncertain, graph.repository.activeNight.value)
        assertEquals(count, writes)
        assertOneTerminalNotice(unknown = true)
    }

    @Test fun terminalManualRefusalIsNotRepeatedWhenInboxClearTimesOut() = runBlocking {
        manualRefusalWithFailedInboxClear(timeout = true)
    }

    @Test fun terminalManualRefusalIsNotRepeatedWhenInboxClearFails() = runBlocking {
        manualRefusalWithFailedInboxClear(timeout = false)
    }

    private suspend fun manualRefusalWithFailedInboxClear(timeout: Boolean) {
        val night = start()
        alarm(5)
        graph.actions.submitAlarmChange()
        val observed = graph.repository.runtime.value.alarmInbox!!
        val manual = night.copy(trackingManual = true)
        graph.repository.setActiveNight(manual)
        var failed = false
        beforeFileWrite = { file, bytes ->
            if (!failed && file.name == "runtime.json" &&
                graph.repository.runtime.value.alarmInbox?.id == observed.id &&
                !bytes.toString(Charsets.UTF_8).contains(observed.id)) {
                failed = true
                // The terminal refusal has already posted. Fail its later inbox-clear write to exercise
                // service deduplication for both a propagated timeout and a returned FAILED outcome.
                if (timeout) runBlocking { withTimeout(1) { awaitCancellation() } }
                throw java.io.IOException("Could not clear the refused observation")
            }
        }
        val writesBefore = writes
        val outcome = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, NightActions.Command.RETRY_RETIME, notifyFailures = true))
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            until { shadowOf(service).isStoppedBySelf }
            assertTrue("The failure must occur only while clearing the already-refused observation", failed)
            if (timeout) assertFalse("The local clear timeout must still propagate cancellation", outcome.isCompleted)
            else {
                assertTrue(outcome.isCompleted)
                assertEquals(OutcomeKind.FAILED, outcome.await().kind)
            }
            until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM } }
            graph.repository.updateRuntime { it }
            val problems = graph.repository.notices.value.filter { it.kind == NoticeKind.PROBLEM }
            assertEquals("The later local save failure must not repeat the terminal notice: ${problems.map { it.title to it.text }}", 1,
                problems.size)
            assertTrue(error()!!.extras.getString("android.text")!!.contains("manual run"))
            assertEquals(manual, graph.repository.activeNight.value)
            assertEquals(writesBefore, writes)
        } finally { controller.destroy(); outcome.cancel() }
    }

    @Test fun terminalRetimeNoticeIsNotRepeatedByAnExplicitServiceRequest() = runBlocking {
        val night = pendingForReceiver()
        beforeSend = { if (it.contentEquals(Codec.startProgram(night.slot))) throw BedJetError.NotConnected() }
        assertEquals(OutcomeKind.UNKNOWN, retryService(notifyFailures = true).kind)
        assertOneTerminalNotice(unknown = true)
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
        atBoundary(night)
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertOneExpiryNotice()
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertOneExpiryNotice()
        assertEquals(night.applied, graph.repository.activeNight.value!!.applied)
        assertEquals(count, writes)
    }

    @Test fun aNewAlarmObservationCannotSwallowThePendingRetimeExpiryNotice() = runBlocking {
        val night = pendingAfterConnectionFailure()
        graph.actions.submitAlarmChange()
        assertNotNull(graph.repository.runtime.value.alarmInbox)
        atBoundary(night)
        assertEquals(OutcomeKind.LOCAL, retryService(notifyFailures = true).kind)
        assertOneExpiryNotice()
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
            // Exercise the receiver fallback's TimeoutCancellationException branch before any durable
            // replacement; its existing pending request and quiet retry must survive this local timeout.
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
