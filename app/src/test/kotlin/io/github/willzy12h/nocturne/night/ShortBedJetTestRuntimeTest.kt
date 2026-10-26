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
class ShortBedJetTestRuntimeTest {
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

    @Test fun previewAndSettingsSavesNeverStartTheTestButConfirmedStartIsMarked() = runBlocking {
        val settings = graph.repository.settings.value
        val profiles = graph.repository.profiles.value
        val preview = graph.actions.shortTestPreview()!!
        graph.actions.updateSettings { it.copy(fahrenheit = true) }
        assertEquals(0, writes)
        assertNull(graph.repository.activeNight.value)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.startShortTest(preview).kind)
        val night = graph.repository.activeNight.value!!
        assertTrue(night.isTestRun)
        assertEquals(Confidence.CONFIRMED, night.confidence)
        assertTrue(graph.repository.history.value.single().isTestRun)
        assertEquals(profiles, graph.repository.profiles.value)
        assertEquals(settings.tonightOverride, graph.repository.settings.value.tonightOverride)
        assertTrue(writes > 0)
        assertTrue(graph.repository.slotBackups.value.any { it.deviceId == preview.deviceId && it.slot == preview.slot })
        val models = androidx.lifecycle.ViewModelStore()
        try {
            val model = io.github.willzy12h.nocturne.ui.AppViewModel(app)
            models.put("test", model)
            assertTrue(model.history.value.isEmpty())
        } finally { models.clear() }
        graph = graphs.fresh { Graph(app) }
        org.robolectric.util.ReflectionHelpers.setField(app, "graph", graph)
        assertTrue(graph.repository.activeNight.value!!.isTestRun)
        assertTrue(graph.repository.history.value.single().isTestRun)
    }

    @Test fun changedSlotOrTrackedNightRefusesTheCapturedTestWithoutWrites() = runBlocking {
        val old = graph.actions.shortTestPreview()!!
        graph.actions.updateSettings { it.copy(workingSlot = 1) }
        assertFalse(graph.actions.startShortTest(old).succeeded)
        assertEquals(0, writes)
        val preview = graph.actions.shortTestPreview()!!
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.startShortTest(preview).kind)
        val before = writes
        assertFalse(graph.actions.startShortTest(preview).succeeded)
        assertEquals(before, writes)
    }

    @Test fun markedRecordsNeverQueryHealthOrEnterSleepCsv() = runBlocking {
        val test = NightRecord("test-record", "test-profile", "Equipment test", graph.now().toString(),
            graph.now().plusMinutes(5).toString(), false, isTestRun = true)
        var clientCreated = false
        val reader = SleepOverlayReader({ true }) { clientCreated = true; error("No Health query for an equipment test") }
        assertEquals(SleepAvailability.EMPTY, reader.read(test).availability)
        assertFalse(clientCreated)
        assertFalse(HistoryCsv.encode(listOf(test), false, true).contains(test.id))
        graph.repository.addRecord(test.copy(events = listOf(HistoryEvent(test.wakeAt, EventKind.ENDED, "Test ended"))))
        assertNull(graph.repository.recordMorningCheckIn(test.id, MorningComfort.JUST_RIGHT))
    }

    @Test fun endingATestPreservesTheRealTonightWakeOverride() = runBlocking {
        graph.repository.updateSettings { it.setWakeOverride(graph.now().plusHours(7), graph.now()) }
        val saved = graph.repository.settings.value.tonightOverride
        val prepared = graph.runner().prepareStart(graph.repository.tonightsProfile()!!, graph.now().plusHours(8),
            2, DeviceId.DEMO, null, null) as io.github.willzy12h.nocturne.core.night.Preparation.Ready
        val night = prepared.change.intended.copy(isTestRun = true)
        graph.repository.setActiveNight(night)
        graph.actions.finish(night, EventKind.ENDED, "Test ended")
        assertEquals(saved, graph.repository.settings.value.tonightOverride)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun interruptedTestEndKeepsItsMarkerAfterActiveStateWasCleared() = runBlocking {
        graph.repository.updateSettings { it.setWakeOverride(graph.now().plusHours(7), graph.now()) }
        val saved = graph.repository.settings.value.tonightOverride
        graph.repository.updateRuntime { it.copy(ending = PendingEnd(
            io.github.willzy12h.nocturne.core.night.NightIdentity("test-ended", 0),
            HistoryEvent(graph.now().toString(), EventKind.ENDED, "Test ended"), isTestRun = true)) }
        graph.actions.reconcile()
        assertEquals(saved, graph.repository.settings.value.tonightOverride)
        assertNull(graph.repository.runtime.value.ending)
    }
}
