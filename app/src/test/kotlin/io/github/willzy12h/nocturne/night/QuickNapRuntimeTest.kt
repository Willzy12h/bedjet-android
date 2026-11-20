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
    private var delayStartSeconds = 0L
    @Before fun setup() = runBlocking {
        val demo = DemoBedJet { graph.now() }
        graph = graphs.fresh { Graph(app, object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                if (delayStartSeconds > 0 && command.contentEquals(io.github.willzy12h.nocturne.core.protocol.Codec.startProgram(graph.repository.settings.value.workingSlot))) {
                    graph.clock = Clock.offset(graph.clock, java.time.Duration.ofSeconds(delayStartSeconds))
                    delayStartSeconds = 0
                }
                beforeWrite()
                send(command)
            }
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

    @Test fun selectedRegularOpeningChangingWhileWaitingRefusesWithoutAWrite() = runBlocking {
        val profiles = graph.repository.profiles.value
        graph.repository.updateSettings { it.chooseTonight(profiles[0].id, graph.now())
            .copy(napPreset = it.napPreset.copy(useProfileOpening = true)) }
        val captured = graph.actions.captureNap(profiles[0])
        graph.repository.updateSettings { it.chooseTonight(profiles[1].id, graph.now()) }
        assertEquals(profiles[0], graph.repository.profile(profiles[0].id))
        assertEquals(OutcomeKind.STALE, graph.actions.startNap(captured).kind)
        assertEquals(0, writes)
    }

    @Test fun oneOffDurationStartsAtTheTransportWriteAndKeepsTheSavedPreset() = runBlocking {
        val saved = graph.repository.settings.value.napPreset
        val captured = graph.actions.captureNap(null, durationOverride = 30)
        val actualStart = graph.now().plusSeconds(19)
        delayStartSeconds = 19

        val outcome = graph.actions.startNap(captured)

        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        val night = graph.repository.activeNight.value!!
        assertEquals(actualStart.toString(), night.startedAt)
        assertEquals(actualStart.plusMinutes(30).toString(), night.wakeAt)
        assertEquals(actualStart.plusMinutes(40).toString(), night.endsAt)
        assertEquals(listOf(10, 20, 10), night.applied.steps.map { it.minutes })
        assertEquals(night.wakeAt, graph.repository.history.value.single().wakeAt)
        assertEquals(saved, graph.repository.settings.value.napPreset)
    }

    @Test fun interruptedHistorySettlementRecoversTheActualStartAndItsSteps() = runBlocking {
        val start = graph.now()
        val prep = graph.runner().prepareNap("Quick nap", graph.repository.settings.value.napPreset, start.plusMinutes(90), false, null,
            2, DeviceId.DEMO, null, null) as io.github.willzy12h.nocturne.core.night.Preparation.Ready
        val planned = prep.change.intended
        val actual = prep.change.confirmedAt(start.plusSeconds(24))
        graph.repository.addRecord(NightRecord(planned.recordId, planned.profileId, "Quick nap", planned.startedAt,
            planned.wakeAt, false, planned.applied.steps, deviceId = DeviceId.DEMO, isNap = true))
        graph.repository.setActiveNight(actual)
        graph.actions.reconcile()
        val record = graph.repository.history.value.single()
        assertEquals(actual.startedAt, record.startedAt)
        assertEquals(actual.wakeAt, record.wakeAt)
        assertEquals(actual.applied.steps, record.steps)
        assertEquals(actual.applied, record.revisions.single())
    }

    @Test fun changedPresetOrSlotAndUnconfirmedReplacementDoNotSend() = runBlocking {
        val original = graph.actions.captureNap(null, durationOverride = 30)
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
        alarmAt(60)
        assertEquals(OutcomeKind.STALE, graph.actions.startNap(first).kind)
        assertEquals(0, writes)
        val approved = AlarmSource.describe(app, graph.repository.settings.value)!!
        val expectedStart = graph.now().plusSeconds(19)
        delayStartSeconds = 19
        val result = graph.actions.startNap(graph.actions.captureNap(null, approved.time, approved))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val run = graph.repository.activeNight.value!!
        assertEquals(approved.time.toInstant(), java.time.ZonedDateTime.parse(run.wakeAt).toInstant())
        assertTrue(run.wakeFromAlarm)
        assertEquals(expectedStart.toString(), run.startedAt)
        assertEquals(run.startedAt, graph.repository.history.value.single().startedAt)
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
