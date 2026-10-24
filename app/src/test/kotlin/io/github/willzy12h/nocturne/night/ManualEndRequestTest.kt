package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

/** An unresolved manual end keeps the command's label and only accepts reports received from its request onward. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualEndRequestTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var swallow = false
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { if (!swallow) demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
    }

    @Before fun setup() = runBlocking {
        buildGraph(Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC))
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun buildGraph(clock: Clock) {
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = clock
        context.graph.clock = clock
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 40
    }

    private suspend fun start(): ActiveNight {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(
            NightActions.Command.START_NIGHT, graph.repository.profiles.value.first().id)).kind)
        return graph.repository.activeNight.value!!
    }

    private suspend fun run(command: NightActions.Command) = graph.actions.execute(
        graph.actions.capture(command).copy(replacementConfirmed = true))

    private suspend fun unresolved(command: NightActions.Command): ActiveNight {
        val night = start()
        swallow = true
        assertEquals(OutcomeKind.UNKNOWN, run(command).kind)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        return night
    }

    private fun stopped(night: ActiveNight) = graph.repository.history.value.first { it.id == night.recordId }
        .events.filter { it.kind == EventKind.STOPPED }

    private fun assertNoEndRequest() {
        val runtime = graph.repository.runtime.value
        assertNull(runtime.endRequested)
        assertEquals("the cleared request leaves no command label or request time", null to null,
            runtime.endRequestedLabel to runtime.endRequestedAt)
        assertEquals("the cleared request leaves no receipt boundary", null to null,
            runtime.endRequestedOrder to runtime.endRequestedProcess)
    }

    private suspend fun standby(receivedAt: ZonedDateTime = graph.now(), receivedOrder: Long = graph.actions.captureStatusOrder()) {
        graph.actions.observeStatus(demo.status.value!!.copy(mode = Mode.STANDBY, extra = null), "demo", receivedAt,
            receivedOrder = receivedOrder)
    }

    private suspend fun assertLateLabel(command: NightActions.Command, label: String) {
        val night = unresolved(command)
        standby(graph.now().plusSeconds(1))
        assertNull(graph.repository.activeNight.value)
        assertNoEndRequest()
        assertEquals(label, stopped(night).single().detail)
    }

    @Test fun aLateOffKeepsItsOffLabel() = runBlocking {
        assertLateLabel(NightActions.Command.OFF, "BedJet off.")
    }

    @Test fun aLateEndNightKeepsItsOffLabel() = runBlocking {
        assertLateLabel(NightActions.Command.END_NIGHT, "BedJet off.")
    }

    @Test fun aLateTurboEndKeepsTheTurboLabel() = runBlocking {
        assertLateLabel(NightActions.Command.TURBO, "Turbo for 10 minutes.")
    }

    @Test fun aLateWarmEndKeepsTheWarmingLabel() = runBlocking {
        assertLateLabel(NightActions.Command.WARM, "Warming.")
    }

    @Test fun aLateCoolEndKeepsTheCoolingLabel() = runBlocking {
        assertLateLabel(NightActions.Command.COOL, "Cooling.")
    }

    @Test fun aLateRemoteEndKeepsTheSentModeLabel() = runBlocking {
        val night = start()
        swallow = true
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.remote("Heat", endsNight = true, expected = night.identity,
            confirms = { it.mode == Mode.HEAT }) { it.setMode(Mode.HEAT) }.kind)
        standby()
        assertNull(graph.repository.activeNight.value)
        assertEquals("Heat", stopped(night).single().detail)
    }

    @Test fun aLateRemoteOffKeepsTheSentOffLabel() = runBlocking {
        val night = start()
        swallow = true
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.remote("Off", endsNight = true, expected = night.identity,
            confirms = { it.mode == Mode.STANDBY }) { it.stop() }.kind)
        standby()
        assertNull(graph.repository.activeNight.value)
        assertEquals("Off", stopped(night).single().detail)
    }

    @Test fun aStandbyReceivedBeforeTheRequestCannotFinishItButAnEqualTimeCan() = runBlocking {
        val oldReceipt = graph.actions.captureStatusOrder()
        val night = unresolved(NightActions.Command.OFF)
        val requestedAt = graph.now()
        standby(requestedAt.minusNanos(1), oldReceipt)
        assertEquals("an old report cannot confirm this command", night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertTrue(stopped(night).isEmpty())

        standby(requestedAt.withZoneSameInstant(ZoneOffset.ofHours(1)))
        assertNull("a later receipt can share the request's wall instant, regardless of its zone", graph.repository.activeNight.value)
        assertNoEndRequest()
        assertEquals("BedJet off.", stopped(night).single().detail)
        standby(requestedAt.plusSeconds(1))
        assertEquals("a repeated report cannot finish twice", 1, stopped(night).size)
    }

    @Test fun restartingRetainsTheRequestTimeAndLabel() = runBlocking {
        val oldReceipt = graph.actions.captureStatusOrder()
        val night = unresolved(NightActions.Command.TURBO)
        val requestedAt = graph.now()
        val requestOrder = graph.repository.runtime.value.endRequestedOrder
        val requestProcess = graph.repository.runtime.value.endRequestedProcess
        buildGraph(Clock.fixed(requestedAt.plusMinutes(1).toInstant(), ZoneOffset.UTC))
        assertEquals(requestedAt.toString(), graph.repository.runtime.value.endRequestedAt)
        assertEquals(requestOrder, graph.repository.runtime.value.endRequestedOrder)
        assertEquals(requestProcess, graph.repository.runtime.value.endRequestedProcess)
        standby(requestedAt.minusSeconds(1), oldReceipt)
        assertEquals("a restart must not make an old report fresh", night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertTrue(stopped(night).isEmpty())
        standby(requestedAt.plusSeconds(1))
        assertNull(graph.repository.activeNight.value)
        assertNoEndRequest()
        assertEquals("Turbo for 10 minutes.", stopped(night).single().detail)
    }

    @Test fun reconcileUsesTheSavedCommandLabelAfterRestart() = runBlocking {
        val night = unresolved(NightActions.Command.WARM)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        buildGraph(graph.clock)
        graph.actions.reconcile()
        assertNull(graph.repository.activeNight.value)
        assertNoEndRequest()
        assertEquals("Warming.", stopped(night).single().detail)
    }

    @Test fun takeBackUsesTheSavedCommandLabel() = runBlocking {
        val night = unresolved(NightActions.Command.COOL)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertNull(graph.repository.activeNight.value)
        assertNoEndRequest()
        assertEquals("Cooling.", stopped(night).single().detail)
    }

    @Test fun aNewManualEndReplacesBothTheOldLabelAndRequestTime() = runBlocking {
        val night = unresolved(NightActions.Command.OFF)
        val oldRequestAt = graph.now()
        val oldReceipt = graph.actions.captureStatusOrder()
        graph.clock = Clock.fixed(oldRequestAt.plusSeconds(1).toInstant(), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.TURBO).kind)
        standby(oldRequestAt, oldReceipt)
        assertEquals("the old command's report cannot confirm the newer command", night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        standby(graph.now())
        assertEquals("Turbo for 10 minutes.", stopped(night).single().detail)
    }

    @Test fun aRunningReportRetainsTheEndingRequest() = runBlocking {
        val night = unresolved(NightActions.Command.TURBO)
        graph.actions.observeStatus(demo.status.value!!, "demo", graph.now().plusSeconds(1))
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertTrue(stopped(night).isEmpty())
    }

    @Test fun aConfirmedManualEndClearsThePreviousRequest() = runBlocking {
        val night = unresolved(NightActions.Command.TURBO)
        swallow = false
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.OFF).kind)
        assertNoEndRequest()
        assertNull(graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
    }

    @Test fun stopTrackingClearsTheUnconfirmedRequest() = runBlocking {
        val night = unresolved(NightActions.Command.TURBO)
        assertEquals(OutcomeKind.LOCAL, run(NightActions.Command.STOP_TRACKING).kind)
        assertNoEndRequest()
        assertNull(graph.repository.activeNight.value)
        assertEquals("Stopped tracking. This does not turn the BedJet off.", stopped(night).single().detail)
    }

    @Test fun aReplacementClearsThePreviousNightsRequest() = runBlocking {
        val old = unresolved(NightActions.Command.TURBO)
        swallow = false
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val replacement = graph.repository.activeNight.value!!
        assertNotEquals(old.identity, replacement.identity)
        assertNoEndRequest()
        standby()
        assertEquals(replacement.identity, graph.repository.activeNight.value?.identity)
        assertTrue(stopped(replacement).isEmpty())
    }

    @Test fun aLegacyIdentityOnlyMarkerCanFinishWithoutInventingTheCommand() = runBlocking {
        val night = start()
        val oldJournal = """{"endRequested":{"nightId":"${night.recordId}","revision":${night.revision}}}"""
        graph.repository.updateRuntime { BackupCodec.json.decodeFromString(RuntimeState.serializer(), oldJournal) }
        standby()
        assertNull(graph.repository.activeNight.value)
        assertNoEndRequest()
        assertEquals("Night stopped.", stopped(night).single().detail)
    }

    @Test fun reconcileClearsTheRequestForAnIdentitySupersededBeforeACrash() = runBlocking {
        val old = unresolved(NightActions.Command.TURBO)
        val replacement = graph.repository.activeNight.value!!.copy(revision = old.revision + 1)
        // The newer night reached disk, but the process died before publishNight cleared the old marker.
        graph.repository.setActiveNight(replacement)
        buildGraph(graph.clock)
        assertEquals(old.identity, graph.repository.runtime.value.endRequested)
        graph.actions.reconcile()
        assertEquals(replacement.identity, graph.repository.activeNight.value?.identity)
        assertNoEndRequest()
        assertTrue(stopped(replacement).isEmpty())
    }
}
