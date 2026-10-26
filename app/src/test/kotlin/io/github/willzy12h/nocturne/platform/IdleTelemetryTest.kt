package io.github.willzy12h.nocturne.platform

import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The process observer must not persist every unsolicited report during idle connection grace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleTelemetryTest {
    @get:Rule val graphs = GraphLifecycle()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet

    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        demo = DemoBedJet(now = { graph.now() })
        graph = graphs.fresh { Graph(app, demo) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false) }
    }

    private fun showScreen() {
        graph.sessions.holdForScreen(true)
        val deadline = System.nanoTime() + 2_000_000_000
        while (demo.state.value != LinkState.READY && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertEquals(LinkState.READY, demo.state.value)
    }

    @Test fun idleReportsLeaveTheLastDemandedObservationIntactUntilForegroundReturns() = runBlocking {
        showScreen()
        graph.platform.observe(demo.status.value!!, "demo")
        val before = graph.repository.automation.value.observed
        graph.sessions.holdForScreen(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The test is inside the idle grace", LinkState.READY, demo.state.value)
        graph.clock = Clock.offset(graph.clock, Duration.ofSeconds(1))
        val changed = demo.status.value!!.copy(mode = Mode.COOL)
        graph.platform.observe(changed, "demo")
        assertEquals("An idle report must not cause another automation-file write", before, graph.repository.automation.value.observed)
        showScreen()
        graph.platform.observe(changed, "demo")
        assertEquals("COOL", graph.repository.automation.value.observed?.mode)
    }

    @Test fun aBackgroundOwnerStillRecordsTheStatusItNeeds() = runBlocking {
        graph.sessions.withConnection {
            graph.platform.observe(demo.status.value!!.copy(mode = Mode.HEAT), "demo")
            assertEquals("HEAT", graph.repository.automation.value.observed?.mode)
            assertTrue(graph.repository.automation.value.observed?.running == true)
        }
    }
}
