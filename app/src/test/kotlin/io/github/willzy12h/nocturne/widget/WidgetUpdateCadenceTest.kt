package io.github.willzy12h.nocturne.widget

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdateCadenceTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun anIdleWidgetDoesNotRedrawOnARepeatingMinuteTimer() = runTest {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = fresh { Graph(app, DemoBedJet()) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-10-26T12:00:00Z").plusMillis(testScheduler.currentTime)
        }
        var redraws = 0
        val observer = launch { WidgetUpdater.observeUpdates(app) { redraws++ } }
        try {
            advanceTimeBy(600); runCurrent()
            assertEquals("The initial state is drawn", 1, redraws)
            advanceTimeBy(180_000); runCurrent()
            assertEquals("No data changed during three idle minutes", 1, redraws)
        } finally { observer.cancelAndJoin() }
    }

    @Test fun aTrackedPlanRefreshesAtItsPhaseBoundaryWithoutAnIdleMinuteLoop() = runTest {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = fresh { Graph(app, DemoBedJet()) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-10-26T22:30:00Z").plusMillis(testScheduler.currentTime)
        }
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val start = graph.now()
        val profile = graph.repository.profiles.value.first().copy(
            start = listOf(io.github.willzy12h.nocturne.core.plan.Phase(io.github.willzy12h.nocturne.core.protocol.Mode.HEAT, 70, 40, 1)),
            wake = emptyList(), afterWake = emptyList())
        val plan = io.github.willzy12h.nocturne.core.plan.PlanCompiler.compile(profile, start, start.plusHours(8))
        val night = io.github.willzy12h.nocturne.core.night.ActiveNight("phase-boundary", profile.id, start.toString(), plan.wake.toString(),
            false, 2, plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo", profile = profile,
            applied = io.github.willzy12h.nocturne.core.night.AppliedProgram.from(plan),
            confidence = io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED)
        graph.repository.setActiveNight(night)
        val phases = mutableListOf<String>()
        val observer = launch { WidgetUpdater.observeUpdates(app) { phases += widgetPhase(app, graph.repository.activeNight.value) } }
        try {
            advanceTimeBy(600); runCurrent()
            assertEquals(1, phases.size)
            assertTrue(phases.single().startsWith("Planned phase: Start · 26 Oct 22:30–22:31"))
            advanceTimeBy(60_000); runCurrent()
            assertEquals("The planned phase boundary is an event even with no BLE connection", 2, phases.size)
            assertTrue(phases.last().startsWith("Planned phase: Middle · 26 Oct 22:31–"))
            graph.repository.setActiveNight(null)
            advanceTimeBy(600); runCurrent()
            val afterClear = phases.size
            advanceTimeBy(180_000); runCurrent()
            assertEquals("Clearing the night cancels its future phase observations", afterClear, phases.size)
        } finally { observer.cancelAndJoin() }
    }
}
