package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

/** IR-01: automatic and programme-preserving rewrites read the BedJet first and never undo a physical-remote change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteTakeoverRewriteTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        // Only app-owned writes are counted. The physical remote below talks to the demo unit directly.
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
        }
        graph = Graph(context, link) { scope, now -> Repository(context, scope, now = now) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private suspend fun start(): ActiveNight {
        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        return graph.repository.activeNight.value!!
    }
    private fun advance(minutes: Long) {
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(minutes)); context.graph.clock = graph.clock
        demo.tick()
    }
    /** The physical remote: the unit changes and nothing in the app observes a status. */
    private suspend fun physicalRemote(vararg commands: ByteArray) {
        demo.connect()
        commands.forEach { demo.send(it) }
        demo.disconnect()
    }
    private fun moveAlarm(hours: Long): ZonedDateTime {
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = graph.now().plusHours(hours).withSecond(0).withNano(0)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), null), fire)
        return at
    }
    private fun retryScheduled() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .any { shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }
    private fun takeover() = context.getString(R.string.runtime_takeover)
    private fun assertTakenOver(n: ActiveNight, slotBefore: StoredProgram?, sentBefore: Int) {
        assertEquals("no slot write or start command", sentBefore, sent.size)
        assertEquals(slotBefore, demo.slots[n.slot])
        val now = graph.repository.activeNight.value!!
        assertEquals(n.recordId, now.recordId)
        assertEquals(Confidence.UNKNOWN, now.confidence)
        assertEquals(takeover(), graph.repository.runtime.value.reason)
        assertNull(graph.repository.runtime.value.retime)
        assertFalse(graph.actions.retimePending)
        assertFalse("retry alarm cancelled", retryScheduled())
        assertTrue(graph.repository.history.value.first { it.id == n.recordId }.events.any { it.kind == EventKind.REMOTE_CHANGE })
    }
    private suspend fun queueAlarmChange() {
        moveAlarm(5)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        assertTrue(retryScheduled())
    }

    @Test fun retimeRetryAfterUnobservedRemoteOffDoesNotRestartTheBedJet() = runBlocking {
        val n = start(); advance(30)
        physicalRemote(Codec.setMode(Mode.STANDBY)!!)
        queueAlarmChange()
        val slot = demo.slots[n.slot]; val count = sent.size
        graph.actions.retryPendingRetime()
        assertTakenOver(n, slot, count)
        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
    }

    @Test fun retimeRetryAfterUnobservedManualHeatRunDoesNotOverrideIt() = runBlocking {
        val n = start(); advance(30)
        physicalRemote(Codec.setMode(Mode.HEAT)!!, Codec.setTemperature(70), Codec.setRuntime(60))
        queueAlarmChange()
        val slot = demo.slots[n.slot]; val count = sent.size
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(takeover(), outcome.message)
        assertTrue("the takeover notice is already posted, so the service adds no generic failure", outcome.notified)
        assertTakenOver(n, slot, count)
        // The existing takeover copy is posted; notices are logged by the application graph.
        withTimeout(5_000) { context.graph.repository.notices.first { list -> list.any { it.kind == NoticeKind.INFO && it.title == context.getString(R.string.runtime_takeover_title) } } }
        assertEquals(Mode.HEAT, demo.status.value!!.mode)
        assertEquals(0, demo.status.value!!.extra!!.programStep)
    }

    @Test fun immediateRetimeAfterUnobservedRemoteOffDoesNotRestartTheBedJet() = runBlocking {
        val n = start(); advance(30)
        physicalRemote(Codec.setMode(Mode.STANDBY)!!)
        moveAlarm(5)
        val slot = demo.slots[n.slot]; val count = sent.size
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.RETIME)).kind)
        assertTakenOver(n, slot, count)
    }

    @Test fun reapplyAfterProfileEditIsRefusedWhileTakenOver() = runBlocking {
        val n = start(); advance(30)
        physicalRemote(Codec.setMode(Mode.STANDBY)!!)
        val request = graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)
        graph.repository.saveProfile(graph.repository.profile(n.profileId)!!.copy(name = "Edited"))
        val slot = demo.slots[n.slot]; val count = sent.size
        val outcome = graph.actions.execute(request)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertFalse(outcome.succeeded)
        assertTakenOver(n, slot, count)
    }

    @Test fun wakeOverrideIsRefusedWhileTakenOver() = runBlocking {
        val n = start(); advance(30)
        physicalRemote(Codec.setMode(Mode.STANDBY)!!)
        val slot = demo.slots[n.slot]; val count = sent.size
        val outcome = graph.actions.setWakeOverride(graph.now().plusHours(6))
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertTakenOver(n, slot, count)
        assertNull("the override was not saved for a night that was not rewritten", graph.repository.settings.value.tonightOverride)
    }

    @Test fun matchingStatusStillRetimes() = runBlocking {
        val n = start(); advance(30)
        val at = moveAlarm(5)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val count = sent.size
        graph.actions.retryPendingRetime()
        val after = graph.repository.activeNight.value!!
        assertEquals(Confidence.CONFIRMED, after.confidence)
        assertEquals(at.toInstant(), ZonedDateTime.parse(after.wakeAt).toInstant())
        assertTrue(after.revision > n.revision)
        assertTrue("the re-time wrote the slot", sent.size > count)
        assertFalse(graph.actions.retimePending)
        assertTrue(graph.repository.history.value.first { it.id == n.recordId }.events.none { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun matchingStatusStillReappliesAndOverrides() = runBlocking {
        val n = start(); advance(30)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)).kind)
        advance(1)
        val wake = graph.now().plusHours(6)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.setWakeOverride(wake).kind)
        assertEquals(wake.toInstant(), ZonedDateTime.parse(graph.repository.activeNight.value!!.wakeAt).toInstant())
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        assertTrue(graph.repository.history.value.first { it.id == n.recordId }.events.none { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun aReportLaggingAStepBoundaryIsNotATakeoverButAStaleStepLaterIs() = runBlocking {
        val n = start()
        val second = n.activePlan(graph.repository.profile(n.profileId))!!.steps[1].startsAt
        // The clock moves without the demo unit ticking, so it still reports the first step.
        fun at(time: ZonedDateTime) { graph.clock = Clock.fixed(time.toInstant(), ZoneOffset.UTC); context.graph.clock = graph.clock }
        at(second.plusSeconds(5))
        val request = graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(request).kind)
        val reapplied = graph.repository.activeNight.value!!
        val next = reapplied.activePlan(graph.repository.profile(reapplied.profileId))!!.steps[1].startsAt
        at(next.plusSeconds(60))
        val count = sent.size
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)).kind)
        assertEquals(count, sent.size)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
    }
}
