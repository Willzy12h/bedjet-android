package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration
import java.time.ZonedDateTime

/** NightActions and LinkSessions on the simulated BedJet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightActionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graph get() = context.graph

    @Before
    fun demoMode() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.repository.setActiveNight(null)
    }

    private suspend fun runningNight(): ActiveNight {
        val start = ZonedDateTime.now().minusHours(1)
        val profile = graph.repository.profiles.value.first()
        val night = ActiveNight(
            recordId = "night-test", profileId = profile.id, startedAt = start.toString(),
            wakeAt = start.plusHours(8).toString(), wakeFromAlarm = false, slot = 2,
            wakeUpStart = start.plusHours(7).toString(), endsAt = start.plusHours(9).toString(),
        )
        graph.repository.addRecord(
            NightRecord(
                id = night.recordId, profileId = profile.id, profileName = profile.name, startedAt = night.startedAt,
                wakeAt = night.wakeAt, wakeFromAlarm = false, steps = emptyList(),
                events = listOf(HistoryEvent(night.startedAt, EventKind.STARTED, "Started")),
            ),
        )
        graph.repository.setActiveNight(night)
        return night
    }

    @Test
    fun aMovedAlarmMovesTheWakeUpAndSaysSo() = runBlocking {
        graph.actions.perform(Command.START_NIGHT)
        val before = graph.repository.activeNight.value!!
        // No creator package, so it counts as the clock app's alarm.
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = ZonedDateTime.now().plusHours(5).withSecond(0).withNano(0)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), null), fire)

        graph.actions.perform(Command.RETIME)

        val after = graph.repository.activeNight.value!!
        assertEquals(at.toInstant(), ZonedDateTime.parse(after.wakeAt).toInstant())
        // The notification log is saved in the background.
        val notice = withTimeout(5_000) { graph.repository.notices.first { it.firstOrNull()?.kind == io.github.willzy12h.nocturne.core.store.NoticeKind.INFO } }.first()
        assertEquals(io.github.willzy12h.nocturne.core.store.NoticeKind.INFO, notice.kind)
        assertEquals(true, notice.title.startsWith("Wake-up moved to"))
        assertEquals(true, notice.text.contains(java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(ZonedDateTime.parse(before.wakeAt))))
    }

    @Test
    fun aDeletedProfileFromAWidgetDoesNotStartAnother() = runBlocking {
        assertEquals("That profile was deleted.", graph.actions.perform(Command.START_NIGHT, "deleted-profile"))
        assertNull(graph.repository.activeNight.value)
    }

    @Test
    fun anotherAppsAlarmLeavesTheWakeUpAlone() = runBlocking {
        val night = runningNight()
        // Created by this app, which isn't one of the allowed clock apps.
        val show = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = ZonedDateTime.now().plusHours(3).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        assertEquals(false, AlarmSource.describe(context, graph.repository.settings.value)?.counts)

        assertEquals("Wake-up unchanged.", graph.actions.perform(Command.RETIME))
        assertEquals(night, graph.repository.activeNight.value)
        assertFalse(graph.actions.retimePending)
    }

    @Test
    fun turboEndsTheNightOnceTheBedJetHasIt() = runBlocking {
        val started = graph.actions.perform(Command.START_NIGHT, graph.repository.profiles.value.first().id)
        val night = graph.repository.activeNight.value
        assertNotNull(started, night)
        assertEquals("Turbo for 10 minutes.", graph.actions.perform(Command.TURBO))
        assertNull(graph.repository.activeNight.value)
        val events = graph.repository.history.value.first { it.id == night!!.recordId }.events
        assertEquals(EventKind.STOPPED, events.last().kind)
    }

    @Test
    fun aRetimeWithNoNightDoesNothing() = runBlocking {
        assertEquals("No night running.", graph.actions.perform(Command.RETIME))
    }

    @Test
    fun endingTheNightClearsAPendingRetime() = runBlocking {
        val night = runningNight()
        context.getSharedPreferences("night-actions", Context.MODE_PRIVATE).edit().putBoolean("retimePending", true).commit()
        graph.actions.finish(night, EventKind.ENDED, "Night finished")
        assertFalse(graph.actions.retimePending)
        assertNull(graph.repository.activeNight.value)
    }

    @Test
    fun theScreensHoldIsTakenAndLetGoOnce() {
        val sessions = graph.sessions
        repeat(3) { sessions.holdForScreen(true) }
        idle()
        assertEquals(LinkState.READY, graph.link.state.value)
        sessions.holdForScreen(false)
        idleFor(Duration.ofSeconds(6))
        // One release undoes three "visible" calls.
        assertEquals(LinkState.SEARCHING, graph.link.state.value)
    }

    @Test
    fun theScreenLettingGoTwiceKeepsAnotherHoldersConnection() {
        val sessions = graph.sessions
        graph.scope.launch { sessions.acquire() }
        sessions.holdForScreen(true)
        idle()
        repeat(3) { sessions.holdForScreen(false) }
        idleFor(Duration.ofSeconds(6))
        assertEquals(LinkState.READY, graph.link.state.value)
        sessions.releaseLater()
        idleFor(Duration.ofSeconds(6))
        assertEquals(LinkState.SEARCHING, graph.link.state.value)
    }

    @Test
    fun reconnectTakesTheScreensHoldWhenNoneIsHeld() {
        graph.sessions.reconnect()
        idle()
        assertEquals(LinkState.READY, graph.link.state.value)
        graph.sessions.reconnect()
        idleFor(Duration.ofSeconds(6))
        // The retry gave its own hold back; the screen's is still there.
        assertEquals(LinkState.READY, graph.link.state.value)
        graph.sessions.holdForScreen(false)
        idleFor(Duration.ofSeconds(6))
        assertEquals(LinkState.SEARCHING, graph.link.state.value)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)
}
