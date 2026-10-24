package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

/** Equal StateFlow values need no new emission to confirm a state the target already reported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnchangedManualConfirmationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var disconnects = 0
    private val sent = mutableListOf<ByteArray>()
    private var swallow = false
    private var failSend = false
    private var source = "demo"
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override val sourcedStatus: Flow<SourcedStatus?>
                get() = demo.sourcedStatus.map { it?.copy(source = source) }
            override fun latestSourcedStatus() = demo.latestSourcedStatus()?.copy(source = source)
            override suspend fun disconnect() { disconnects++; demo.disconnect() }
            override suspend fun send(command: ByteArray) {
                sent += command
                if (failSend) throw BedJetError.NotConnected()
                if (!swallow) demo.send(command)
            }
        }
    }

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 40
        demo.connect()
    }

    @Test fun acceptedOffAlreadyReportedByTheTargetNeedsNoReconnect() = runBlocking {
        swallow = true
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.OFF))
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals("an unchanged target report confirms immediately after the accepted send", 0, disconnects)
        assertEquals(1, sent.size)
        assertArrayEquals(Codec.setMode(Mode.STANDBY), sent.single())
    }

    @Test fun acceptedLightsAlreadyReportedByTheTargetNeedNoReconnect() = runBlocking {
        swallow = true
        val outcome = graph.actions.remote("Lights on", confirms = { it.extra?.ledEnabled == true }) { it.setLed(true) }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals("an unchanged auxiliary setting must not force a reconnect", 0, disconnects)
        assertEquals(1, sent.size)
        assertArrayEquals(Codec.setLed(true), sent.single())
    }

    @Test fun acceptedOffCanCloseATrackedNightFromTheAlreadyStoppedTargetReport() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val readsBefore = disconnects
        sent.clear()
        swallow = true
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.OFF))
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertNull(graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.endRequested)
        assertEquals("the pending end must not require a newer report when the target already stopped", readsBefore, disconnects)
        assertEquals(1, sent.size)
        assertArrayEquals(Codec.setMode(Mode.STANDBY), sent.single())
    }

    @Test fun changingLightsStillNeedsTheTargetToReportTheRequestedState() = runBlocking {
        swallow = true
        val outcome = graph.actions.remote("Lights off", confirms = { it.extra?.ledEnabled == false }) { it.setLed(false) }
        assertEquals("the old lights-on report cannot confirm lights-off", OutcomeKind.ACCEPTED, outcome.kind)
        assertEquals("a changed state still gets the bounded fresh read", 1, disconnects)
    }

    @Test fun changingLightsCanBeConfirmedByThePostSendReportWithoutReconnect() = runBlocking {
        val outcome = graph.actions.remote("Lights off", confirms = { it.extra?.ledEnabled == false }) { it.setLed(false) }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(0, disconnects)
        assertEquals(false, demo.status.value?.extra?.ledEnabled)
    }

    @Test fun anotherUnitsMatchingReportCannotConfirmTheSelectedTargetsCommand() = runBlocking {
        source = "ble:AA:BB:CC:DD:EE:FF"
        swallow = true
        val outcome = graph.actions.remote("Lights on", confirms = { it.extra?.ledEnabled == true }) { it.setLed(true) }
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
    }

    @Test fun aMatchingReportDoesNotConfirmASendThatFailed() = runBlocking {
        failSend = true
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.OFF))
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(1, sent.size)
        assertEquals(0, disconnects)
    }

    @Test fun aMatchingReportDoesNotAllowACommandForAnotherCapturedTarget() = runBlocking {
        val outcome = graph.actions.remote("Lights on", target = "ble:AA:BB:CC:DD:EE:FF",
            confirms = { it.extra?.ledEnabled == true }) { it.setLed(true) }
        assertEquals(OutcomeKind.STALE, outcome.kind)
        assertTrue(sent.isEmpty())
    }
}
