package io.github.willzy12h.nocturne.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** An expiry notice must describe the result of the command that actually owns the re-time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetimeExpiryBoundaryTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    private var connects = 0
    private var beforeConnect: suspend () -> Unit = {}
    private var beforeQuery: suspend (Int) -> Unit = {}
    private var afterSend: suspend (ByteArray) -> Unit = {}
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun connect() { connects++; beforeConnect(); demo.connect() }
            override suspend fun send(command: ByteArray) {
                sent += command.copyOf()
                demo.send(command)
                afterSend(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                send(command)
            }
            override suspend fun query(request: Int): ByteArray { beforeQuery(request); return demo.query(request) }
        }
    }

    @Before fun setup() = runBlocking {
        load(Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC))
        graph.repository.updateSettings {
            it.copy(demoMode = true, acceptUnknownAlarmSource = true, alarmPackages = setOf("approved.clock"))
        }
        BedJetService.lastResult = null
    }

    private fun load(clock: Clock) {
        graph = graphs.fresh { Graph(app, link) }
        graph.clock = clock
        graph.actions.confirmMs = 1
        graph.actions.freshStatusMs = 100
        ReflectionHelpers.setField(app, "graph", graph)
    }

    private fun at(time: ZonedDateTime) {
        graph.clock = Clock.fixed(time.toInstant(), ZoneOffset.UTC)
        demo.tick()
    }

    private fun alarm(time: ZonedDateTime) {
        val fire = PendingIntent.getBroadcast(app, 117, Intent("test.retime.boundary"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(fire).setCreatorPackage("approved.clock")
        app.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(time.toInstant().toEpochMilli(), fire), fire)
    }

    private suspend fun start(): ActiveNight {
        alarm(graph.now().plusHours(6))
        assertEquals(OutcomeKind.CONFIRMED,
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT, profileId = "preset-summer")).kind)
        return graph.repository.activeNight.value!!
    }

    private suspend fun requestLaterWake(night: ActiveNight): ZonedDateTime {
        val later = ZonedDateTime.parse(night.wakeAt).plusHours(1)
        alarm(later)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        return later
    }

    /** Pump Android delivery while real repository writes finish; do not advance the app's fixed clock. */
    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Re-time boundary fixture did not reach its controlled suspension", check())
    }

    private suspend fun retryThroughService(during: suspend () -> Unit = {}): CommandOutcome {
        val delivery = ServiceStartContext(app)
        assertTrue(BedJetService.start(delivery, NightActions.Command.RETRY_RETIME, notifyFailures = true))
        val outcome = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(delivery.delivered, 0, 1)
            during()
            until { outcome.isCompleted && shadowOf(service).isStoppedBySelf }
            return outcome.await()
        } finally { controller.destroy(); outcome.cancel() }
    }

    private fun errorNotification() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3)

    private fun assertOneExpiryNotice() {
        val title = app.getString(R.string.runtime_retime_expired)
        assertEquals("The final notice must not be replaced by a generic service failure", title,
            errorNotification()?.extras?.getString("android.title"))
        assertTrue(errorNotification()!!.extras.getString("android.text")!!.contains("not restarted"))
        until { graph.repository.notices.value.any { it.title == title } }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        assertFalse(graph.actions.retimePending)
        assertFalse(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY
        })
    }

    @Test fun anAlarmAtTheBoundaryCannotDeclareExpiryWhileAStartAcknowledgementIsPending() = runBlocking {
        val night = start()
        val boundary = ZonedDateTime.parse(night.wakeUpStart)
        at(boundary.minusSeconds(1))
        val laterWake = requestLaterWake(night)
        val startWritten = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        val startsBefore = sent.count { it.contentEquals(Codec.startProgram(night.slot)) }
        afterSend = { command ->
            if (command.contentEquals(Codec.startProgram(night.slot))) {
                startWritten.complete(Unit)
                acknowledge.await()
            }
        }
        val operation = async { graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)) }
        val outcome = try {
            withTimeout(5_000) { startWritten.await() }
            assertEquals(Confidence.PENDING, graph.repository.activeNight.value?.confidence)
            assertNotNull(graph.repository.runtime.value.retime)
            assertEquals(startsBefore + 1, sent.count { it.contentEquals(Codec.startProgram(night.slot)) })
            at(boundary)
            assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
            acknowledge.complete(Unit)
            operation.await()
        } finally { acknowledge.complete(Unit); afterSend = {}; operation.cancel() }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        assertEquals(laterWake.toInstant(), ZonedDateTime.parse(graph.repository.activeNight.value!!.wakeAt).toInstant())
        assertFalse(graph.actions.retimePending)
        assertNull("A START already sent and then confirmed must never be described as not restarted", errorNotification())
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }

    @Test fun expiryDuringTheFreshReadIsLocalWithOneFinalNoticeAndNoWrite() = runBlocking {
        val night = start()
        val boundary = ZonedDateTime.parse(night.wakeUpStart)
        at(boundary.minusSeconds(1))
        requestLaterWake(night)
        val reading = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        // withConnection connects once, then freshStatus reconnects for the target's current report.
        val freshConnect = connects + 2
        beforeConnect = {
            if (connects == freshConnect) { reading.complete(Unit); reply.await() }
        }
        val writes = sent.size
        val outcome = try {
            retryThroughService {
                until { reading.isCompleted }
                at(boundary)
                reply.complete(Unit)
            }
        } finally { reply.complete(Unit); beforeConnect = {} }
        assertEquals("An elapsed re-time deadline is a quiet local outcome", OutcomeKind.LOCAL, outcome.kind)
        assertEquals("No packet may be written after the pre-write read crossed the deadline", writes, sent.size)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneExpiryNotice()
        assertEquals(OutcomeKind.LOCAL, retryThroughService().kind)
        assertOneExpiryNotice()
    }

    @Test fun expiryDuringTheOriginalBackupReadIsLocalWithOneFinalNoticeAndNoWrite() = runBlocking {
        val night = start()
        val clock = graph.clock
        // As in the existing original-loss fixtures: no saved original forces a read before any slot write.
        graphs.finish()
        assertTrue(File(app.filesDir, "slot-backups.json").delete())
        load(clock)
        assertNull(graph.repository.originalSlot("demo", night.slot))
        val boundary = ZonedDateTime.parse(night.wakeUpStart)
        at(boundary.minusSeconds(1))
        requestLaterWake(night)
        val reading = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        beforeQuery = { request ->
            if (request == Codec.Query.PROGRAM_NAMES) { reading.complete(Unit); reply.await() }
        }
        val writes = sent.size
        val outcome = try {
            retryThroughService {
                until { reading.isCompleted }
                assertEquals(Confidence.PENDING, graph.repository.activeNight.value?.confidence)
                at(boundary)
                reply.complete(Unit)
            }
        } finally { reply.complete(Unit); beforeQuery = {} }
        assertEquals("A backup that outlives the re-time deadline is a quiet local outcome", OutcomeKind.LOCAL, outcome.kind)
        assertEquals("The final pre-write guard must leave the BedJet untouched", writes, sent.size)
        assertEquals(night, graph.repository.activeNight.value)
        assertOneExpiryNotice()
        assertEquals(OutcomeKind.LOCAL, retryThroughService().kind)
        assertOneExpiryNotice()
    }

    @Test fun anExpiredRetimeForAnEarlierRevisionCannotReportTheNewRevisionAsNotRestarted() = runBlocking {
        val previous = start()
        val published = previous.copy(revision = previous.revision + 1,
            applied = previous.applied.copy(revision = previous.revision + 1))
        // A new confirmed revision was published, then the process died before old retry metadata was cleared.
        // This is also the state an alarm submission can see between those two durable writes.
        graph.repository.updateRuntime { it.copy(retime = io.github.willzy12h.nocturne.night.PendingRetime(
            "superseded-re-time", previous.identity, previous.deviceId,
            ZonedDateTime.parse(previous.wakeAt).plusHours(1).toString(), "approved.clock", previous.wakeUpStart),
            reason = app.getString(R.string.runtime_retime_pending)) }
        graph.repository.setActiveNight(published)
        load(graph.clock)
        assertEquals(published, graph.repository.activeNight.value)
        assertEquals(previous.identity, graph.repository.runtime.value.retime?.expected)
        at(ZonedDateTime.parse(previous.wakeUpStart))
        val writes = sent.size
        val connections = connects
        assertEquals(OutcomeKind.LOCAL, retryThroughService().kind)
        assertEquals("Only superseded retry metadata may be removed", published, graph.repository.activeNight.value)
        assertFalse(graph.actions.retimePending)
        assertEquals(writes, sent.size)
        assertEquals(connections, connects)
        assertNull("An old request cannot claim the published revision was not restarted", errorNotification())
        assertNull("The superseded request must not retain a pending or expiry reason", graph.repository.runtime.value.reason)
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
        assertEquals(OutcomeKind.LOCAL, retryThroughService().kind)
        assertNull(errorNotification())
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }

    @Test fun anExpiredRetimeCannotDescribeAnUnknownStartAsNotRestarted() = runBlocking {
        val previous = start()
        val unknown = previous.copy(confidence = Confidence.UNKNOWN)
        val unknownReason = "The start acknowledgement was lost."
        // A partial start can publish UNKNOWN before the old retry metadata is cleared; expiry is no hardware proof.
        graph.repository.updateRuntime { it.copy(retime = io.github.willzy12h.nocturne.night.PendingRetime(
            "uncertain-re-time", previous.identity, previous.deviceId,
            ZonedDateTime.parse(previous.wakeAt).plusHours(1).toString(), "approved.clock", previous.wakeUpStart),
            reason = unknownReason) }
        graph.repository.setActiveNight(unknown)
        load(graph.clock)
        assertEquals(previous.identity, graph.repository.runtime.value.retime?.expected)
        at(ZonedDateTime.parse(previous.wakeUpStart))
        val writes = sent.size
        val connections = connects
        assertEquals(OutcomeKind.LOCAL, retryThroughService().kind)
        assertEquals("Expiry cannot settle the uncertain hardware state", unknown, graph.repository.activeNight.value)
        assertFalse(graph.actions.retimePending)
        assertEquals(writes, sent.size)
        assertEquals(connections, connects)
        assertNull("An UNKNOWN start must not be described as not restarted", errorNotification())
        assertEquals("Keep the genuine uncertainty reason", unknownReason, graph.repository.runtime.value.reason)
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
        // With no expired request left, a fresh explicit retry still refuses unsafe UNKNOWN state.
        assertEquals(OutcomeKind.REFUSED, graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)).kind)
        assertEquals(writes, sent.size)
        assertEquals(connections, connects)
        assertEquals(unknownReason, graph.repository.runtime.value.reason)
        assertNull(errorNotification())
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }
}
