package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Portable settings may retain a Turbo duration which a single manual run cannot honour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimedPresetLimitTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var connects = 0
    private var disconnects = 0
    private var reads = 0
    private val writes = mutableListOf<ByteArray>()

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val observed = object : BedJetLink by demo {
            override suspend fun connect() { connects++; demo.connect() }
            override suspend fun disconnect() { disconnects++; demo.disconnect() }
            override suspend fun query(request: Int): ByteArray { reads++; return demo.query(request) }
            override suspend fun send(command: ByteArray) { writes += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, observed) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
    }

    @Test fun importedFifteenMinuteTurboSnoozeWarmIsRefusedBeforeTransportOrMutation() = runBlocking {
        assertOverlongTurboRefused(Command.SNOOZE_WARM)
    }

    @Test fun importedFifteenMinuteTurboSnoozeCoolIsRefusedBeforeTransportOrMutation() = runBlocking {
        assertOverlongTurboRefused(Command.SNOOZE_COOL)
    }

    @Test fun importedFifteenMinuteTurboExtendWarmIsRefusedBeforeTransportOrMutation() = runBlocking {
        assertOverlongTurboRefused(Command.EXTEND_WARM)
    }

    @Test fun importedFifteenMinuteTurboExtendCoolIsRefusedBeforeTransportOrMutation() = runBlocking {
        assertOverlongTurboRefused(Command.EXTEND_COOL)
    }

    @Test fun importedTenMinuteTurboStillConfirmsTheExistingTimedManualActionForEveryRole() = runBlocking {
        for (command in listOf(Command.SNOOZE_WARM, Command.SNOOZE_COOL, Command.EXTEND_WARM, Command.EXTEND_COOL)) {
            if (graph.repository.activeNight.value != null) {
                assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(Command.STOP_TRACKING)).kind)
            }
            val preset = importPreset(command, 10)
            val original = startNight()
            val history = graph.repository.history.value.single { it.id == original.recordId }

            val outcome = graph.actions.execute(graph.actions.capture(command, requireIdentity = true))

            assertEquals("$command must still confirm from the actual manual report: ${outcome.message}", OutcomeKind.CONFIRMED, outcome.kind)
            assertEquals(CommandOrigin.APP, outcome.origin)
            assertTrue("$command must actually send the manual action", writes.isNotEmpty())
            assertEquals(Mode.TURBO, demo.status.value!!.mode)
            assertEquals(0, demo.status.value!!.extra!!.programStep)
            assertEquals(10 * 60, demo.status.value!!.remainingSeconds)
            val after = graph.repository.activeNight.value!!
            assertEquals(original.recordId, after.recordId)
            assertEquals(original.revision + 1, after.revision)
            assertEquals(original.wakeAt, after.wakeAt)
            assertEquals(graph.now().plusMinutes(10).toString(), after.endsAt)
            assertTrue(after.trackingManual)
            assertEquals("Timed manual tracking still needs its existing Keep tracking decision", Confidence.UNKNOWN, after.confidence)
            assertNull(after.temporary)
            assertEquals(listOf(Mode.TURBO to 10), after.applied.steps.map { it.mode to it.minutes })
            val recorded = graph.repository.history.value.single { it.id == original.recordId }
            assertEquals(after.applied, recorded.revisions.last())
            val kind = if (command == Command.SNOOZE_WARM || command == Command.SNOOZE_COOL) EventKind.SNOOZED else EventKind.EXTENDED
            assertEquals(history.events.count { it.kind == kind } + 1, recorded.events.count { it.kind == kind })
            assertTrue(recorded.events.none { it.kind == EventKind.STOPPED || it.kind == EventKind.ENDED })
            assertEquals(preset, selectedPreset(graph.repository.settings.value, command))
        }
    }

    private suspend fun assertOverlongTurboRefused(command: Command) {
        val preset = importPreset(command, 15)
        val original = startNight()
        val runtime = graph.repository.runtime.value
        val history = graph.repository.history.value
        val settings = graph.repository.settings.value
        val originals = graph.repository.slotBackups.value
        val status = demo.status.value
        val slots = demo.slots.toList()

        val outcome = graph.actions.execute(graph.actions.capture(command, requireIdentity = true))

        assertEquals("$command must refuse Turbo above the unit's ten-minute limit", OutcomeKind.REFUSED, outcome.kind)
        assertEquals("Turbo can run for at most 10 minutes. Choose a shorter duration.", outcome.message)
        assertEquals(CommandOrigin.APP, outcome.origin)
        assertEquals("No connection is needed to refuse a known duration limit", 0, connects)
        assertEquals(0, disconnects)
        assertEquals(0, reads)
        assertTrue("No manual mode, timer or programme packet may be sent", writes.isEmpty())
        assertEquals(original, graph.repository.activeNight.value)
        assertEquals(runtime, graph.repository.runtime.value)
        assertEquals(history, graph.repository.history.value)
        assertEquals(settings, graph.repository.settings.value)
        assertEquals(preset, selectedPreset(settings, command))
        assertEquals(originals, graph.repository.slotBackups.value)
        assertEquals(status, demo.status.value)
        assertEquals(slots, demo.slots.toList())
    }

    private suspend fun importPreset(command: Command, minutes: Int): ActionPreset {
        val preset = ActionPreset(Mode.TURBO, 86, 100, minutes)
        val repo = graph.repository
        val bundle = repo.bundle().let { it.copy(settings = withPreset(it.settings, command, preset)) }
        val decoded = BackupCodec.decode(BackupCodec.encode(bundle))
        assertEquals("This remains a valid portable backup", emptyList<String>(), BackupValidator.validate(decoded))
        repo.restore(repo.importFrom(BackupCodec.encode(decoded).byteInputStream()))
        assertEquals(preset, selectedPreset(repo.settings.value, command))
        return preset
    }

    private suspend fun startNight(): ActiveNight {
        val outcome = graph.actions.execute(graph.actions.capture(Command.START_NIGHT))
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        connects = 0
        disconnects = 0
        reads = 0
        writes.clear()
        return graph.repository.activeNight.value!!
    }

    private fun selectedPreset(settings: Settings, command: Command): ActionPreset = when (command) {
        Command.SNOOZE_WARM -> settings.snoozeWarm
        Command.SNOOZE_COOL -> settings.snoozeCool
        Command.EXTEND_WARM -> settings.extendWarm
        Command.EXTEND_COOL -> settings.extendCool
        else -> error("Not a timed manual action")
    }

    private fun withPreset(settings: Settings, command: Command, preset: ActionPreset): Settings = when (command) {
        Command.SNOOZE_WARM -> settings.copy(snoozeWarm = preset)
        Command.SNOOZE_COOL -> settings.copy(snoozeCool = preset)
        Command.EXTEND_WARM -> settings.copy(extendWarm = preset)
        Command.EXTEND_COOL -> settings.copy(extendCool = preset)
        else -> error("Not a timed manual action")
    }
}
