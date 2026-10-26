package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.NightPreview
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartAlarmSourceTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun approvedCreatorStartsThePreviewedAlarmWakeWhenUnknownCreatorsAreDisabled() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        lateinit var graph: Graph
        graph = graphs.fresh { Graph(app, DemoBedJet(now = { graph.now() })) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, alarmPackages = setOf("approved.clock"),
            acceptUnknownAlarmSource = false) }
        val alarmAt = Instant.parse("2026-10-22T04:30:00Z")
        val alarmIntent = PendingIntent.getBroadcast(app, 74, Intent("test.approved.start.alarm"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(alarmIntent).setCreatorPackage("approved.clock")
        app.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(alarmAt.toEpochMilli(), alarmIntent), alarmIntent)
        val settings = graph.repository.settings.value
        val alarm = AlarmSource.describe(app, settings)!!
        assertTrue(alarm.counts)
        val preview = NightPreview.forStartNow(graph.repository.tonightsProfile()!!, graph.now(), alarm.time,
            settings.resolveWakeOverride(graph.now()), settings, alarm.creator)
        assertEquals(alarmAt, preview.wake.time.toInstant())

        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))

        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        val started = graph.repository.activeNight.value!!
        assertEquals("Start must retain the approved creator when choosing the wake", alarmAt,
            ZonedDateTime.parse(started.wakeAt).toInstant())
        assertEquals(WakeOrigin.ALARM, started.wakeOrigin)
        assertEquals(preview.wake.time.toInstant(), ZonedDateTime.parse(started.wakeAt).toInstant())
    }
}
