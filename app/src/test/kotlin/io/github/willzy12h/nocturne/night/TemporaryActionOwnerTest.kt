package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import kotlinx.coroutines.*
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

/** Exercises real owner admission and programme confirmation; no later reconciliation repairs assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryActionOwnerTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    private val turbo = ActionPreset(Mode.TURBO, 86, 100, 10)
    private val warm = ActionPreset(Mode.HEAT, 64, 30, 15)

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private suspend fun start(): ActiveNight {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        graph.clock = Clock.offset(graph.clock, java.time.Duration.ofMinutes(30))
        demo.tick()
        sent.clear()
        return graph.repository.activeNight.value!!
    }

    @Test fun aTemporaryTurboKeepsTheNightAndItsWakeWithOneCompositeStart() = runBlocking {
        val before = start()
        val result = graph.actions.execute(graph.actions.captureTemporary(turbo))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val after = graph.repository.activeNight.value!!
        assertEquals(before.recordId, after.recordId)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.wakeAt, after.wakeAt)
        assertEquals(Confidence.CONFIRMED, after.confidence)
        assertFalse(after.trackingManual)
        assertEquals(Mode.TURBO, after.applied.steps.first().mode)
        assertEquals(after.applied.programSteps, demo.slots[after.slot]!!.steps)
        assertEquals(1, sent.count { it.contentEquals(Codec.startProgram(after.slot)) })
        assertTrue(graph.repository.history.value.single { it.id == before.recordId }.events.none {
            it.kind == EventKind.STOPPED || it.kind == EventKind.ENDED
        })
    }

    @Test fun previewUsesTheCapturedActionAndDoesNotConnectOrWrite() = runBlocking {
        val before = start()
        val request = graph.actions.captureTemporary(warm)
        graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.DRY, 70, 80, 30)) }
        val preview = graph.actions.previewTemporary(request)
        assertNull(preview.refusal)
        val plan = preview.plan!!
        assertEquals(Mode.HEAT, plan.steps.first().step.mode)
        assertEquals(64, plan.steps.first().step.temperature)
        assertEquals(before.wakeAt, plan.wake.toString())
        assertTrue(sent.isEmpty())
        assertEquals(before, graph.repository.activeNight.value)
    }

    @Test fun aTemporaryWarmActionDoesNotRebindToEditedSavedSettings() = runBlocking {
        start()
        val request = graph.actions.captureTemporary(warm)
        graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.DRY, 70, 80, 30)) }
        val result = graph.actions.execute(request)
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val first = graph.repository.activeNight.value!!.applied.steps.first()
        assertEquals(Mode.HEAT, first.mode)
        assertEquals(64, first.temperature)
        assertEquals(30, first.fanPercent)
        assertEquals(15, first.minutes)
    }

    @Test fun theEndNightChoiceUsesThePresetThatWasReviewed() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val before = start()
        val request = graph.actions.capture(NightActions.Command.WARM, requireIdentity = true)
            .copy(replacementConfirmed = true, actionPreset = warm)
        graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.COOL, 44, 80, 30)) }
        val result = graph.actions.execute(request)
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertNull(graph.repository.activeNight.value)
        assertEquals(Mode.HEAT, demo.status.value!!.mode)
        assertEquals(64, demo.status.value!!.targetTemperature)
        assertEquals(5, demo.status.value!!.fanStep)
        assertEquals(15 * 60, demo.status.value!!.remainingSeconds)
        assertEquals(1, graph.repository.history.value.single { it.id == before.recordId }.events.count { it.kind == EventKind.STOPPED })
    }

    @Test fun aQueuedTemporaryActionCannotReplaceANewerNightRevision() = runBlocking {
        val before = start()
        val request = graph.actions.captureTemporary(turbo)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val work = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.execute(request) }
        try {
            assertEquals(1, graph.actions.busy.value.queuedCount)
            graph.repository.setActiveNight(before.copy(revision = before.revision + 1))
            release.complete(Unit); holder.await()
            assertEquals(OutcomeKind.STALE, work.await().kind)
            assertTrue(sent.isEmpty())
        } finally { release.complete(Unit); holder.cancelAndJoin(); work.cancelAndJoin() }
    }

    @Test fun aTemporaryRequestCannotOptOutOfItsCapturedIdentity() = runBlocking {
        val before = start()
        val request = graph.actions.captureTemporary(turbo).copy(requireIdentity = false)
        graph.repository.setActiveNight(before.copy(revision = before.revision + 1))
        val result = graph.actions.execute(request)
        assertEquals(OutcomeKind.STALE, result.kind)
        assertTrue(sent.isEmpty())
    }

    @Test fun pendingOrUnknownTrackingCannotPromiseAReturn() = runBlocking {
        val before = start()
        for (confidence in listOf(Confidence.PENDING, Confidence.UNKNOWN, Confidence.FAILED)) {
            graph.repository.setActiveNight(before.copy(confidence = confidence))
            val request = graph.actions.captureTemporary(turbo)
            assertNotNull(graph.actions.previewTemporary(request).refusal)
            assertEquals(OutcomeKind.REFUSED, graph.actions.execute(request).kind)
            assertEquals(confidence, graph.repository.activeNight.value!!.confidence)
        }
        assertTrue(sent.isEmpty())
    }

    @Test fun aTemporaryRequestWithNoPresetWritesNothing() = runBlocking {
        val before = start()
        val result = graph.actions.execute(graph.actions.captureTemporary(turbo).copy(actionPreset = null))
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(before, graph.repository.activeNight.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun aReturnPreviewRefusesAnActionWhichCannotFitBeforeWake() = runBlocking {
        val before = start()
        graph.clock = Clock.fixed(java.time.ZonedDateTime.parse(before.wakeUpStart).minusMinutes(5).toInstant(), ZoneOffset.UTC)
        demo.tick()
        val request = graph.actions.captureTemporary(turbo)
        assertNotNull(graph.actions.previewTemporary(request).refusal)
        assertEquals(OutcomeKind.REFUSED, graph.actions.execute(request).kind)
        assertTrue(sent.isEmpty())
    }
}
