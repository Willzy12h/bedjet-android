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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Phone-clock corrections cannot reorder receipt of a target's stopped state and an ending request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndRequestOrderingTest {
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

    private suspend fun unresolvedOff(): ActiveNight {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val night = graph.repository.activeNight.value!!
        swallow = true
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
        return night
    }

    private fun rollClockBack() {
        graph.clock = Clock.fixed(graph.now().minusHours(1).toInstant(), ZoneOffset.UTC)
        context.graph.clock = graph.clock
    }

    private suspend fun standby(source: String = "demo", target: String = "demo",
        receivedOrder: Long = graph.actions.captureStatusOrder()) = graph.actions.observeStatus(
        demo.status.value!!.copy(mode = Mode.STANDBY, extra = null), target, graph.now(), source, receivedOrder)

    private fun stopped(night: ActiveNight) = graph.repository.history.value.first { it.id == night.recordId }
        .events.filter { it.kind == EventKind.STOPPED }

    @Test fun aLaterTargetStandbyFinishesOffAfterThePhoneClockMovesBackwards() = runBlocking {
        val night = unresolvedOff()
        rollClockBack()
        standby()
        assertNull("a later target report must confirm Off even when its wall time is earlier", graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
        assertNull(graph.repository.runtime.value.endRequested)
        standby()
        assertEquals("repeated reports cannot finish the night twice", 1, stopped(night).size)
    }

    @Test fun reconciliationAfterRestartUsesFreshStandbyDespiteClockRollback() = runBlocking {
        val night = unresolvedOff()
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        rollClockBack()
        buildGraph(graph.clock)
        graph.actions.reconcile()
        assertNull("a fresh target read after restart is later than the pending Off", graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
    }

    @Test fun aLegacyTimestampedRequestCanFinishAfterClockRollback() = runBlocking {
        val night = unresolvedOff()
        val legacy = """{"endRequested":{"nightId":"${night.recordId}","revision":${night.revision}},"endRequestedLabel":"BedJet off.","endRequestedAt":"${graph.now()}"}"""
        graph.repository.updateRuntime { BackupCodec.json.decodeFromString(RuntimeState.serializer(), legacy) }
        rollClockBack()
        standby()
        assertNull("an upgraded timestamp-only journal must not retain the wall-clock ordering bug", graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
    }

    @Test fun rollbackDoesNotMakeAnotherDevicesStandbyConfirmTheOffRequest() = runBlocking {
        val night = unresolvedOff()
        rollClockBack()
        standby(source = "ble:AA:BB:CC:DD:EE:FF")
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertTrue(stopped(night).isEmpty())
    }

    @Test fun aReceiptBeforeTheRequestCannotCloseTheNightEvenWithALaterWallTimestamp() = runBlocking {
        val oldReceipt = graph.actions.captureStatusOrder()
        val night = unresolvedOff()
        graph.clock = Clock.fixed(graph.now().plusHours(1).toInstant(), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        standby(receivedOrder = oldReceipt)
        assertEquals("old same-target reports stay old across phone-clock corrections", night.identity,
            graph.repository.activeNight.value?.identity)
        assertTrue(stopped(night).isEmpty())
        standby()
        assertNull(graph.repository.activeNight.value)
        assertEquals(1, stopped(night).size)
    }

    @Test fun aReceiptAtTheRequestBoundaryIsNotAfterTheRequest() = runBlocking {
        val night = unresolvedOff()
        standby(receivedOrder = graph.repository.runtime.value.endRequestedOrder!!)
        assertEquals("only a strictly later receipt can settle the request", night.identity,
            graph.repository.activeNight.value?.identity)
        assertTrue(stopped(night).isEmpty())
        standby()
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun aReceiptFromBeforeTheRestartCannotSettleALegacyTimestampedRequest() = runBlocking {
        val night = unresolvedOff()
        val legacy = """{"endRequested":{"nightId":"${night.recordId}","revision":${night.revision}},"endRequestedLabel":"BedJet off.","endRequestedAt":"${graph.now()}"}"""
        graph.repository.updateRuntime { BackupCodec.json.decodeFromString(RuntimeState.serializer(), legacy) }
        val oldReceipt = graph.actions.captureStatusOrder()
        rollClockBack()
        buildGraph(graph.clock)
        standby(receivedOrder = oldReceipt)
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertTrue(stopped(night).isEmpty())
        standby()
        assertNull(graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
    }

    @Test fun aPreviousProcessesCounterDoesNotBlockFreshReportsAfterRestart() = runBlocking {
        val night = unresolvedOff()
        graph.repository.updateRuntime { it.copy(endRequestedOrder = Long.MAX_VALUE, endRequestedProcess = "previous-process") }
        val oldReceipt = graph.actions.captureStatusOrder()
        rollClockBack()
        buildGraph(graph.clock)
        standby(receivedOrder = oldReceipt)
        assertEquals("a previous instance's receipt is not fresh in this process", night.identity,
            graph.repository.activeNight.value?.identity)
        standby()
        assertNull("another process's counter is not comparable to this process's counter", graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
    }

    @Test fun takeBackCanSettleTheOffWithFreshStandbyAfterClockRollback() = runBlocking {
        val night = unresolvedOff()
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        rollClockBack()
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertNull(graph.repository.activeNight.value)
        assertEquals("BedJet off.", stopped(night).single().detail)
    }

    @Test fun aReportForAnotherCapturedTargetCannotSettleThePendingOff() = runBlocking {
        val night = unresolvedOff()
        rollClockBack()
        standby(source = "ble:AA:BB:CC:DD:EE:FF", target = "ble:AA:BB:CC:DD:EE:FF")
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertTrue(stopped(night).isEmpty())
    }
}
