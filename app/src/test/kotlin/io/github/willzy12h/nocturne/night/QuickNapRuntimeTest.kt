package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.*
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.platform.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickNapRuntimeTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var writes = 0
    @Before fun setup() = runBlocking {
        val demo = DemoBedJet { graph.now() }
        graph = graphs.fresh { Graph(app, object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }) }
        org.robolectric.util.ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    @Test fun capturesDurationAtStartAndRecordsTheProgrammeAcrossRelaunch() = runBlocking {
        val captured = graph.actions.captureNap(null)
        graph.clock = Clock.offset(graph.clock, java.time.Duration.ofMinutes(5))
        val start = graph.now()
        val outcome = graph.actions.startNap(captured)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        val night = graph.repository.activeNight.value!!
        assertEquals(start.plusMinutes(90).toString(), night.wakeAt)
        assertTrue(night.isNap)
        assertFalse(night.wakeFromAlarm)
        graph.actions.submitAlarmChange()
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertNull(graph.repository.runtime.value.retime)
        assertEquals(listOf(10, 30, 50, 10), night.applied.steps.map { it.minutes })
        assertTrue(graph.repository.slotBackups.value.any { it.deviceId == captured.request.deviceId })
        graph = graphs.fresh { Graph(app) }
        assertTrue(graph.repository.activeNight.value!!.isNap)
        val record = graph.repository.history.value.single()
        assertTrue(record.isNap)
        assertEquals(start.toString(), record.startedAt)
        assertEquals(night.wakeAt, record.wakeAt)
        assertTrue(HistoryCsv.encode(listOf(record), false, false).lineSequence().any {
            it.startsWith("nap,") && it.endsWith("Fixed nap timing")
        })
    }

    @Test fun changedPresetOrSlotAndUnconfirmedReplacementDoNotSend() = runBlocking {
        val original = graph.actions.captureNap(null)
        graph.repository.updateSettings { it.copy(napPreset = it.napPreset.copy(durationMinutes = 55)) }
        assertEquals(OutcomeKind.STALE, graph.actions.startNap(original).kind)
        val changedSlot = graph.actions.captureNap(null)
        graph.repository.updateSettings { it.copy(workingSlot = (it.workingSlot + 1) % 3) }
        assertEquals(OutcomeKind.STALE, graph.actions.startNap(changedSlot).kind)
        assertEquals(0, writes)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.startNap(graph.actions.captureNap(null)).kind)
        val before = writes
        assertEquals(OutcomeKind.STALE, graph.actions.startNap(changedSlot).kind)
        assertEquals(OutcomeKind.CONFIRMATION_REQUIRED, graph.actions.startNap(graph.actions.captureNap(null)).kind)
        assertEquals(before, writes)
    }

    @Test fun existingAlarmIsUsedOnlyByConsentAndLaterChangesCannotRetimeTheNap() = runBlocking {
        val manager = app.getSystemService(android.app.AlarmManager::class.java)
        val sender = android.app.PendingIntent.getBroadcast(app, 191, android.content.Intent("test.nap.clock"),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        fun alarmAt(minutes: Long) {
            val time = graph.now().plusMinutes(minutes).toInstant().toEpochMilli()
            manager.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(time, clockAlarmShow(app)), sender)
        }
        alarmAt(35)
        val consent = AlarmSource.describe(app, graph.repository.settings.value)!!
        assertTrue(consent.counts)
        val first = graph.actions.captureNap(null, consent.time, consent)
        alarmAt(45)
        assertEquals(OutcomeKind.STALE, graph.actions.startNap(first).kind)
        assertEquals(0, writes)
        val approved = AlarmSource.describe(app, graph.repository.settings.value)!!
        val result = graph.actions.startNap(graph.actions.captureNap(null, approved.time, approved))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val run = graph.repository.activeNight.value!!
        assertEquals(approved.time.toInstant(), java.time.ZonedDateTime.parse(run.wakeAt).toInstant())
        assertTrue(run.wakeFromAlarm)
        assertEquals(approved.time.toInstant().toEpochMilli(), manager.nextAlarmClock.triggerTime)
        alarmAt(30)
        val before = writes
        graph.actions.submitAlarmChange()
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertEquals(before, writes)
        assertEquals(run, graph.repository.activeNight.value)
    }

    @Test fun unacceptedOrChangedAlarmCannotAuthoriseAnExplicitAlarmStart() = runBlocking {
        val unaccepted = AlarmSource.NextAlarm(graph.now().plusMinutes(20), "unaccepted", false)
        assertFalse(graph.actions.startNap(graph.actions.captureNap(null, unaccepted.time, unaccepted)).succeeded)
        assertEquals(0, writes)
    }
}
