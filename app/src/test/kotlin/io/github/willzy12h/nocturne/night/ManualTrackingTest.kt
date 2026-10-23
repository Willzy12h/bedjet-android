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

/**
 * IR-08 and IR-09: a kept manual run is watched against its own step, and a Remote-screen change applies only to the
 * night it was tapped for. A snooze or extend is left UNKNOWN until Keep tracking reads a fresh status, so each kept
 * run here is kept that way first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualTrackingTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        graph = graphs.fresh { Graph(context, demo) { scope, now -> Repository(context, scope, now = now) } }
        at(Instant.parse("2026-10-21T22:30:00Z"))
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }
    private fun at(instant: Instant) {
        graph.clock = Clock.fixed(instant, ZoneOffset.UTC); context.graph.clock = graph.clock
        demo.tick()
    }
    private fun advance(seconds: Long) = at(graph.now().plusSeconds(seconds).toInstant())
    private suspend fun start(): ActiveNight {
        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        return graph.repository.activeNight.value!!
    }
    /** Snooze, then keep tracking the run the BedJet reports. */
    private suspend fun keptSnooze(): ActiveNight {
        start()
        assertEquals(OutcomeKind.ACCEPTED, graph.actions.execute(graph.actions.capture(NightActions.Command.SNOOZE_WARM)).kind)
        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.KEEP_TRACKING)).kind)
        val n = graph.repository.activeNight.value!!
        assertTrue(n.trackingManual)
        assertEquals(Confidence.CONFIRMED, n.confidence)
        return n
    }
    private suspend fun observe() = graph.actions.observeStatus(demo.status.value!!, "demo")
    private fun events(n: ActiveNight) = graph.repository.history.value.first { it.id == n.recordId }.events

    // IR-08

    @Test fun aManualRunChangedByTheRemoteIsATakeover() = runBlocking {
        val n = keptSnooze()
        advance(120)
        // The physical remote switches to Cool; the app sees the next report.
        demo.send(Codec.setMode(Mode.COOL)!!)
        observe()
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertTrue(events(n).any { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun aManualRunEndingByItselfIsNotATakeover() = runBlocking {
        val n = keptSnooze()
        advance(120); observe()
        assertEquals("ordinary progress within the run is not a takeover", n, graph.repository.activeNight.value)
        at(ZonedDateTime.parse(n.endsAt).minusSeconds(30).toInstant())
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        observe()
        assertEquals(n, graph.repository.activeNight.value)
        assertTrue(events(n).none { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun takeBackKeepsAManualRunTheBedJetStillRuns() = runBlocking {
        val n = keptSnooze()
        advance(120)
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertEquals("compared with its own manual step, not a programme step", n, graph.repository.activeNight.value)
    }

    @Test fun takeBackKeepsAManualRunThatStoppedByItselfNearItsEnd() = runBlocking {
        val n = keptSnooze()
        at(ZonedDateTime.parse(n.endsAt).minusSeconds(30).toInstant())
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertEquals("the same end window as takeover detection", n, graph.repository.activeNight.value)
    }

    @Test fun takeBackStillFindsAManualRunTheRemoteChanged() = runBlocking {
        val n = keptSnooze()
        advance(120)
        demo.send(Codec.setMode(Mode.COOL)!!)
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertTrue(events(n).any { it.kind == EventKind.REMOTE_CHANGE })
    }

    // IR-09

    @Test fun anAdjustQueuedBehindAStartLeavesTheNewNightProgrammed() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val startRequest = graph.actions.capture(NightActions.Command.START_NIGHT)
        val starting = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.execute(startRequest) }
        // Tapped while no night was shown: it captured no night.
        val adjusting = async(start = CoroutineStart.UNDISPATCHED) {
            graph.actions.remote("Temperature set", affectsProgramme = true, target = "demo", expected = null) { it.setTemperature(50) } }
        release.complete(Unit); holder.await()
        assertEquals(OutcomeKind.CONFIRMED, starting.await().kind)
        assertEquals(OutcomeKind.STALE, adjusting.await().kind)
        val n = graph.repository.activeNight.value!!
        assertEquals(Confidence.CONFIRMED, n.confidence)
        assertFalse("still the programmed night", n.trackingManual)
        assertEquals(0, n.revision)
    }

    @Test fun anAdjustTappedForTheShownNightStillSwitchesToManualTracking() = runBlocking {
        val n = start(); advance(30 * 60)
        assertEquals(OutcomeKind.ACCEPTED, graph.actions.remote("Temperature set", affectsProgramme = true,
            target = "demo", expected = n.identity) { it.setTemperature(50) }.kind)
        val adjusted = graph.repository.activeNight.value!!
        assertTrue(adjusted.trackingManual)
        assertEquals(Confidence.UNKNOWN, adjusted.confidence)
    }
}
