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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

/** IR-08 and IR-09: a manual run is tracked as pending until the BedJet reports it, and is watched like a programme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualTrackingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    /** Commands the unit "loses": accepted by the link, never applied. */
    private var drops: (ByteArray) -> Boolean = { false }

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; if (!drops(command)) demo.send(command) }
        }
        graph = Graph(context, link) { scope, now -> Repository(context, scope, now = now) }
        at(Instant.parse("2026-10-21T22:30:00Z"))
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }
    private fun at(instant: Instant) {
        graph.clock = Clock.fixed(instant, ZoneOffset.UTC); context.graph.clock = graph.clock
        if (::demo.isInitialized) demo.tick()
    }
    private fun advance(seconds: Long) = at(graph.now().plusSeconds(seconds).toInstant())
    private suspend fun start(): ActiveNight {
        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        return graph.repository.activeNight.value!!
    }
    private suspend fun observe() = graph.actions.observeStatus(demo.status.value!!, "demo")
    private fun events(n: ActiveNight) = graph.repository.history.value.first { it.id == n.recordId }.events
    private fun retryScheduled() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .any { shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }
    private fun moveAlarm(hours: Long) {
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = graph.now().plusHours(hours).withSecond(0).withNano(0)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), null), fire)
    }
    private fun isTimer(b: ByteArray) = b[0] == 0x02.toByte()

    // IR-08

    @Test fun aSnoozeTheBedJetDidNotTakeIsNotConfirmed() = runBlocking {
        val n = start()
        drops = ::isTimer
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.SNOOZE_WARM))
        val snoozed = graph.repository.activeNight.value!!
        assertTrue(snoozed.trackingManual)
        assertEquals(n.revision + 1, snoozed.revision)
        assertEquals("the timer was never reported, so the snooze is not a fact", Confidence.PENDING, snoozed.confidence)
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
        assertEquals(context.getString(R.string.runtime_manual_pending, context.getString(R.string.runtime_timed,
            context.getString(R.string.runtime_snooze_warm), graph.repository.settings.value.snoozeWarm.minutes)), outcome.message)
        // A restart or screen visit does not turn a run waiting for its report into an interrupted one.
        graph.actions.reconcile()
        assertEquals(Confidence.PENDING, graph.repository.activeNight.value!!.confidence)
        // The unit reports the run later: the snooze is confirmed then.
        drops = { false }
        demo.send(Codec.setRuntime(graph.repository.settings.value.snoozeWarm.minutes))
        observe()
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        assertTrue(events(n).none { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun aSnoozeTheBedJetReportsIsConfirmed() = runBlocking {
        start()
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.EXTEND_COOL))
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
        val n = graph.repository.activeNight.value!!
        assertTrue(n.trackingManual)
        assertEquals(Confidence.CONFIRMED, n.confidence)
    }

    @Test fun aManualRunChangedByTheRemoteIsATakeover() = runBlocking {
        start()
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        val n = graph.repository.activeNight.value!!
        assertEquals(Confidence.CONFIRMED, n.confidence)
        advance(120)
        // The physical remote switches to Cool; the app sees the next report.
        demo.send(Codec.setMode(Mode.COOL)!!)
        observe()
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertTrue(events(n).any { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun aManualRunEndingByItselfIsNotATakeover() = runBlocking {
        start()
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        val n = graph.repository.activeNight.value!!
        at(ZonedDateTime.parse(n.endsAt).minusSeconds(30).toInstant())
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        observe()
        assertEquals(n, graph.repository.activeNight.value)
        // Ordinary progress within the run is not a takeover either.
        assertTrue(events(n).none { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun takeBackKeepsAManualRunTheBedJetStillRuns() = runBlocking {
        start()
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        val n = graph.repository.activeNight.value!!
        advance(120)
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertEquals("compared with its own manual step, not a programme step", n, graph.repository.activeNight.value)
    }

    // IR-09

    @Test fun aRemoteTemperatureChangeDuringAProgrammedNightSwitchesToManualTracking() = runBlocking {
        val n = start(); advance(30 * 60)
        moveAlarm(5)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        assertTrue(retryScheduled())
        val running = demo.status.value!!
        val target = running.mode.temperatureRange.first + 4
        val outcome = graph.actions.adjust("Temperature set", ManualAdjust(temperature = target))
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
        val adjusted = graph.repository.activeNight.value!!
        assertNotEquals("not the old programmed night", n, adjusted)
        assertTrue(adjusted.trackingManual)
        assertEquals(n.revision + 1, adjusted.revision)
        assertEquals(target, adjusted.applied.programSteps.single().temperature)
        assertEquals(running.mode, adjusted.applied.programSteps.single().mode)
        assertEquals("the demo unit reports the change at once", Confidence.CONFIRMED, adjusted.confidence)
        assertFalse("the pending re-time is cancelled", graph.actions.retimePending)
        assertFalse(retryScheduled())
        // The user's own change is not taken for the physical remote, and nothing rewrites it.
        advance(60); observe()
        assertTrue(events(n).none { it.kind == EventKind.REMOTE_CHANGE })
        val count = sent.size
        graph.actions.retryPendingRetime()
        assertEquals(count, sent.size)
        assertEquals(target, demo.status.value!!.targetTemperature)
    }

    @Test fun aRemoteTimerChangeTheBedJetDidNotTakeIsPending() = runBlocking {
        val n = start(); advance(30 * 60)
        drops = ::isTimer
        val outcome = graph.actions.adjust("Timer set", ManualAdjust(minutes = 45))
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
        val adjusted = graph.repository.activeNight.value!!
        assertTrue(adjusted.trackingManual)
        assertEquals(Confidence.PENDING, adjusted.confidence)
        assertEquals(graph.now().plusMinutes(45).toInstant(), ZonedDateTime.parse(adjusted.endsAt).toInstant())
        assertNotEquals(n.identity, adjusted.identity)
    }

    @Test fun lightsAndBeepsStillLeaveTheProgrammedNightAsItIs() = runBlocking {
        val n = start()
        graph.actions.remote("Lights") { it.setLed(false) }
        assertEquals(n, graph.repository.activeNight.value)
    }
}
