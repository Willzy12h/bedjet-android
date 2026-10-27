package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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

/** P5 verification: a programme runs on the BedJet; its tracking is not an indefinite BLE holder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackedNightForegroundConnectionTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun aTrackedProgrammeNeitherExtendsGraceNorChangesItsWakeAndEndWhenGraceExpires() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = graphs.fresh { Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.sessions.holdForScreen(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OutcomeKind.CONFIRMED,
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        shadowOf(Looper.getMainLooper()).idle()
        val night = graph.repository.activeNight.value!!
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        fun scheduled() = alarms.scheduledAlarms.map {
            shadowOf(it.operation).savedIntent.action to it.triggerAtTime
        }.toSet()
        val before = scheduled()
        assertTrue(before.any { it.first == NightAlarms.ACTION_WAKE_UP })
        assertTrue(before.any { it.first == NightAlarms.ACTION_NIGHT_END })
        graph.sessions.holdForScreen(false)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(29_999))
        assertEquals(LinkState.READY, graph.link.state.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(LinkState.SEARCHING, graph.link.state.value)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(before, scheduled())
        assertFalse(graph.sessions.hasStatusDemand)
    }
}
