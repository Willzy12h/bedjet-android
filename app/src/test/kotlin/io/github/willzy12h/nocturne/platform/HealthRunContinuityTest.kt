package io.github.willzy12h.nocturne.platform

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class HealthRunContinuityTest : PlatformGraphFixture() {
    private suspend fun setup(): Graph {
        val context: Context = RuntimeEnvironment.getApplication()
        lateinit var graph: Graph
        graph = fresh { Graph(context, DemoBedJet(now = { graph.now() })) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        graph.actions.confirmMs = 1
        graph.actions.perform(Command.START_NIGHT, graph.repository.profiles.value.first().id)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        return graph
    }
    private suspend fun manualTimer(graph: Graph) {
        graph.actions.remote("Timer set", affectsProgramme = true, confirms = { it.remainingSeconds == 1800 }) { it.setRuntime(30) }
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
    }
    private suspend fun complete(graph: Graph) {
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.endsAt).toInstant(), ZoneOffset.UTC)
        assertTrue(graph.actions.expire(night.identity))
    }

    @Test fun resumingAfterAnUncertainManualTimerCannotReestablishWholeNightExportProof() = runBlocking {
        val graph = setup()
        manualTimer(graph)
        val outcome = graph.actions.perform(Command.RESUME, replacementConfirmed = true)
        assertEquals(outcome.toString(), Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        assertFalse(graph.repository.activeNight.value!!.trackingManual)
        complete(graph)
        assertNull("A confirmed resumed tail cannot prove the interrupted whole night",
            graph.repository.history.value.single().confirmedCompletionAt)
        assertFalse(graph.repository.history.value.single().bedJetRunConfirmed)
    }

    @Test fun aFreshManualTimerReceiptNeverMakesTheNightAnExportableCompletedProgramme() = runBlocking {
        val graph = setup()
        manualTimer(graph)
        graph.actions.perform(Command.KEEP_TRACKING)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        complete(graph)
        assertNull(graph.repository.history.value.single().confirmedCompletionAt)
    }
}
