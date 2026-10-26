package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoostCommandMappingTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun eachBoostOptionLabelsTheProgrammeActuallyWrittenThroughTheOwner() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        val demo = DemoBedJet()
        val graph = graphs.fresh { Graph(app, demo) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false) }
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            graph.repository.updateSettings { it.copy(boostMinutes = minutes) }
            val result = graph.actions.execute(graph.actions.capture(NightActions.Command.BOOST))
            assertEquals(OutcomeKind.CONFIRMED, result.kind)
            val steps = demo.slots[graph.repository.settings.value.workingSlot]!!.steps
            assertEquals(Mode.TURBO, steps.first().mode)
            assertEquals(10, steps.first().durationMinutes)
            assertEquals(minutes, steps.sumOf { it.durationMinutes })
            assertTrue(steps.drop(1).all { it.mode == Mode.HEAT })
            assertEquals("Turbo ${minutes}m", app.getString(R.string.remote_boost, minutes))
            assertEquals("Confirmation must describe the same segments as accessibility feedback",
                boostDescription(app, minutes), result.message)
        }
    }
}
