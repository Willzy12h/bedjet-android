package io.github.willzy12h.nocturne.night

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Resolve the requested wake before deciding whether uncertain/manual hardware could safely be changed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnchangedAlarmObservationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var writes = 0
    private var connects = 0
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val alarmIntent by lazy { PendingIntent.getBroadcast(app, 59, Intent("test.same.wake"), PendingIntent.FLAG_IMMUTABLE) }

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun connect() { connects++; demo.connect() }
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) { scope, now -> Repository(app, scope, now = now) } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, alarmPackages = setOf("approved.clock"), acceptUnknownAlarmSource = false) }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun alarm(at: ZonedDateTime?, creator: String = "approved.clock") {
        if (at == null) alarms.cancel(alarmIntent)
        else {
            shadowOf(alarmIntent).setCreatorPackage(creator)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), alarmIntent), alarmIntent)
        }
    }

    private fun wake(hour: Int) = graph.now().plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0)

    private suspend fun start(alarmHour: Int? = null): ActiveNight {
        alarm(alarmHour?.let(::wake))
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT,
            profileId = "preset-summer")).kind)
        return graph.repository.activeNight.value!!
    }

    private suspend fun unsafe(night: ActiveNight, manual: Boolean): ActiveNight =
        night.copy(confidence = if (manual) Confidence.CONFIRMED else Confidence.UNKNOWN, trackingManual = manual).also {
            graph.repository.setActiveNight(it)
            graph.repository.updateRuntime { runtime -> runtime.copy(reason = "Existing tracking reason") }
        }

    private suspend fun assertQuietObservation(night: ActiveNight) {
        val history = graph.repository.history.value
        val beforeWrites = writes
        val beforeConnects = connects
        val reason = graph.repository.runtime.value.reason
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))

        assertEquals("An observation with no resolved wake change is a quiet local result", OutcomeKind.LOCAL, outcome.kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(beforeWrites, writes)
        assertEquals("An unchanged alarm needs no hardware read", beforeConnects, connects)
        assertEquals("No terminal History event belongs to an unchanged wake", history, graph.repository.history.value)
        assertEquals(reason, graph.repository.runtime.value.reason)
        assertFalse(graph.actions.retimePending)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
        shadowOf(Looper.getMainLooper()).idle()
        graph.repository.updateRuntime { it }
        assertTrue("No problem notice belongs to an unchanged wake", graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }

    private suspend fun sameWake(manual: Boolean, fromAlarm: Boolean) {
        val night = unsafe(start(if (fromAlarm) 7 else null), manual)
        assertEquals(wake(7).toInstant(), ZonedDateTime.parse(night.wakeAt).toInstant())
        assertEquals(if (fromAlarm) WakeOrigin.ALARM else WakeOrigin.PROFILE_DEFAULT, night.wakeOrigin)
        assertQuietObservation(night)
    }

    @Test fun anUnknownNightWithTheSameAcceptedAlarmIsQuiet() = runBlocking { sameWake(manual = false, fromAlarm = true) }
    @Test fun aManualNightWithTheSameAcceptedAlarmIsQuiet() = runBlocking { sameWake(manual = true, fromAlarm = true) }
    @Test fun anUnknownNightWithTheSameFallbackDefaultIsQuiet() = runBlocking { sameWake(manual = false, fromAlarm = false) }
    @Test fun aManualNightWithTheSameFallbackDefaultIsQuiet() = runBlocking { sameWake(manual = true, fromAlarm = false) }

    @Test fun anOutOfWindowAlarmResolvingToTheSameDefaultIsQuiet() = runBlocking {
        val night = unsafe(start(), manual = false)
        alarm(graph.now().plusMinutes(30))
        assertQuietObservation(night)
    }

    @Test fun aConfirmedNightWithTheSameAcceptedAlarmRemainsQuiet() = runBlocking { assertQuietObservation(start(7)) }

    private suspend fun changedWake(manual: Boolean, fallback: Boolean) {
        val night = unsafe(start(if (fallback) 6 else 7), manual)
        alarm(if (fallback) null else wake(6))
        val beforeWrites = writes
        val before = graph.repository.history.value.single { it.id == night.recordId }.events
        graph.actions.submitAlarmChange()
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))

        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertTrue("A genuine requested wake change still owns terminal feedback", outcome.notificationHandled)
        assertEquals(beforeWrites, writes)
        assertEquals(night, graph.repository.activeNight.value)
        val added = graph.repository.history.value.single { it.id == night.recordId }.events - before.toSet()
        assertEquals(1, added.size)
        assertEquals(EventKind.ERROR, added.single().kind)
        assertTrue(added.single().detail, added.single().detail.contains("[REFUSED]"))
        assertTrue(added.single().detail, added.single().detail.contains(outcome.message))
        assertFalse(graph.actions.retimePending)
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM } && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
    }

    @Test fun aChangedAcceptedAlarmStillReportsForAnUnknownNight() = runBlocking { changedWake(manual = false, fallback = false) }
    @Test fun aChangedAcceptedAlarmStillReportsForAManualNight() = runBlocking { changedWake(manual = true, fallback = false) }
    @Test fun aChangedFallbackDefaultStillReportsForAnUnknownNight() = runBlocking { changedWake(manual = false, fallback = true) }
    @Test fun aChangedFallbackDefaultStillReportsForAManualNight() = runBlocking { changedWake(manual = true, fallback = true) }

    @Test fun aFilteredAlarmObservationRemainsQuietForAnUnknownNight() = runBlocking {
        val night = unsafe(start(7), manual = false)
        alarm(wake(6), creator = "ignored.clock")
        assertQuietObservation(night)
    }

    @Test fun anOverrideRemainsQuietForAManualNight() = runBlocking {
        val night = unsafe(start(7).copy(wakeOrigin = WakeOrigin.OVERRIDE), manual = true)
        alarm(wake(6))
        assertQuietObservation(night)
    }

    private suspend fun unchangedObservationAcrossWake(night: ActiveNight, repeatBroadcast: Boolean = false) {
        val boundary = ZonedDateTime.parse(night.wakeUpStart)
        graph.clock = Clock.fixed(boundary.minusSeconds(1).toInstant(), ZoneOffset.UTC)
        val history = graph.repository.history.value
        val reason = graph.repository.runtime.value.reason
        val beforeWrites = writes
        val beforeConnects = connects
        graph.actions.submitAlarmChange()
        assertNotNull(graph.repository.runtime.value.alarmInbox)
        assertNull("This is an observation, not an already-prepared change", graph.repository.runtime.value.retime)
        graph.clock = Clock.fixed(boundary.plusSeconds(1).toInstant(), ZoneOffset.UTC)

        val outcome = if (repeatBroadcast) graph.actions.submitAlarmChange()
            else graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))

        assertEquals("A queued unchanged observation must remain quiet across wake-up start", OutcomeKind.LOCAL, outcome.kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(reason, graph.repository.runtime.value.reason)
        assertEquals(history, graph.repository.history.value)
        assertEquals(beforeWrites, writes)
        assertEquals(beforeConnects, connects)
        assertFalse(graph.actions.retimePending)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
        shadowOf(Looper.getMainLooper()).idle()
        graph.repository.updateRuntime { it }
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }

    @Test fun anUnchangedObservationQueuedBeforeWakeRemainsQuietWhenConsumedAfterWake() = runBlocking {
        unchangedObservationAcrossWake(unsafe(start(7), manual = false))
    }

    @Test fun anUnchangedManualObservationQueuedBeforeWakeKeepsItsTrackingReason() = runBlocking {
        unchangedObservationAcrossWake(unsafe(start(7), manual = true))
    }

    @Test fun anUnchangedConfirmedObservationQueuedBeforeWakeCannotInventAnExpiryReason() = runBlocking {
        unchangedObservationAcrossWake(start(7))
    }

    @Test fun aRepeatedPostWakeObservationCannotInventAnExpiryForAConfirmedNight() = runBlocking {
        unchangedObservationAcrossWake(start(7), repeatBroadcast = true)
    }

    @Test fun aRepeatedPostWakeObservationPreservesAManualTrackingReason() = runBlocking {
        unchangedObservationAcrossWake(unsafe(start(7), manual = true), repeatBroadcast = true)
    }

    @Test fun aPostWakeObservationWithNoPendingRequestRemainsQuiet() = runBlocking {
        val night = unsafe(start(7), manual = false)
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        val history = graph.repository.history.value
        val beforeWrites = writes

        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)

        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
        assertEquals(beforeWrites, writes)
        assertFalse(graph.actions.retimePending)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
    }
}
