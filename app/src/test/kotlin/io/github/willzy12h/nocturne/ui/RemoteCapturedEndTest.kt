package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** Real ViewModel/owner route: matching mode alone cannot prove a programme stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteCapturedEndTest {
    @get:Rule val graphs = GraphLifecycle()
    private val models = ViewModelStore()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var model: AppViewModel
    private var swallowMode = false
    private var modeAttempted = false
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()

    @Before fun startHeatProgramme() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                if (command.contentEquals(Codec.setMode(Mode.HEAT))) {
                    modeAttempted = true
                    if (swallowMode) return
                }
                demo.send(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 100
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(start = listOf(Phase(Mode.HEAT, 64, 30, 30))))
        val started = graph.actions.execute(graph.actions.capture(Command.START_NIGHT))
        assertEquals(started.message, OutcomeKind.CONFIRMED, started.kind)
        assertEquals(Mode.HEAT, graph.link.status.value!!.mode)
        assertEquals(1, graph.link.status.value!!.extra!!.programStep)
        model = AppViewModel(app)
        models.put("captured-end", model)
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.collect { outcomes += it } }
        Unit
    }
    @After fun closeModel() { models.clear() }

    @Test fun aFreshMatchingModeStillInProgrammeCannotConfirmEnding() {
        val night = graph.repository.activeNight.value!!
        swallowMode = true
        endHeat()
        assertEquals(OutcomeKind.UNKNOWN, outcomes.single().kind)
        assertEquals(night.recordId, graph.repository.activeNight.value?.recordId)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertEquals(1, graph.link.status.value!!.extra!!.programStep)
        assertTrue(graph.repository.history.value.first { it.id == night.recordId }.events.none { it.kind == EventKind.STOPPED })
    }

    @Test fun theSameModeReportedAsManualConfirmsEnding() {
        endHeat()
        assertEquals(OutcomeKind.CONFIRMED, outcomes.single().kind)
        assertNull(graph.repository.activeNight.value)
        assertEquals(0, graph.link.status.value!!.extra!!.programStep)
    }

    private fun endHeat() {
        model.remoteCaptured("Heat", graph.actions.captureTemporary(ActionPreset(Mode.HEAT, 64, 30, 10)), Mode.HEAT)
        until { modeAttempted }
        // Cross the configured receipt timeout once. IO publication must not race a busy loop
        // that advances Android's elapsed clock beyond the whole owner's budget.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
        assertEquals("The actual ViewModel request must complete", 1, outcomes.size)
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("The actual ViewModel request must complete", check())
    }
}
