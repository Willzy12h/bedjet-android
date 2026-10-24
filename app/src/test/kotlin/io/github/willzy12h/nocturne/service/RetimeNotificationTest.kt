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
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.NightAlarmReceiver
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private suspend fun deliveredResult(context: ServiceStartContext): CommandOutcome {
        val outcome = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(context.delivered, 0, 1)
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
                // Persistence crosses the new plan's wake boundary, but not the old retry deadline.
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
        } finally { controller.destroy() }
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
