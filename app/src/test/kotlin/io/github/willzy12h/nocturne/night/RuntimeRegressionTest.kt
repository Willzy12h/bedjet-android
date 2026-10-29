package io.github.willzy12h.nocturne.night

import android.content.Context
import android.app.AlarmManager
import android.content.Intent
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.deviceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.*
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeRegressionTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graph get() = context.graph
    @Before fun setup() = runBlocking {
        // Direct coordinator calls must not wait on runtime observers queued on Robolectric's paused Main looper.
        val demo = DemoBedJet(now = { graph.now() })
        val isolated = graphs.fresh { Graph(context, demo) }
        ReflectionHelpers.setField(context as App, "graph", isolated)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }
    @Test fun startDurablyRecordsAnEmptyOriginalBeforeOverwritingIt() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        assertNotNull(graph.repository.originalSlot(graph.repository.settings.value.deviceId()!!, 2))
        assertNull(graph.repository.originalSlot("demo", 2)!!.program)
    }
    @Test fun shorterManualRunEndsTrackingWhenItEnds() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        assertEquals(graph.now().plusMinutes(10).toInstant(), ZonedDateTime.parse(graph.repository.activeNight.value!!.endsAt).toInstant())
    }
    @Test fun schedulingAfterWakeNeverRearmsPastWake() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        NightAlarms.schedule(context, night)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue(alarms.none { it.triggerAtMs < graph.clock.millis() })
    }
    @Test fun oldFinishCannotEraseAReplacementNight() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val old = graph.repository.activeNight.value!!
        graph.actions.perform(NightActions.Command.START_NIGHT, replacementConfirmed = true)
        val replacement = graph.repository.activeNight.value!!
        graph.actions.finish(old, EventKind.ENDED, "Old alarm")
        assertEquals(replacement, graph.repository.activeNight.value)
    }
    @Test fun productionClockTracksZoneChangesButAnInjectedClockKeepsItsZone() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val production = graphs.fresh { io.github.willzy12h.nocturne.Graph(context) }
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals(ZoneId.of("Europe/London"), production.now().zone)
            assertEquals(ZoneOffset.UTC, graph.now().zone)
        } finally { TimeZone.setDefault(old) }
    }
}
