package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.flow.first
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

/** Delivers buffered reports using Graph's actual call shape, which does not pass a receipt token. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedEndReportTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var swallow = false
    private var failSend = false
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                if (failSend) throw BedJetError.NotConnected()
                if (!swallow) demo.send(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
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

    private suspend fun unresolvedOff(): ActiveNight {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val night = graph.repository.activeNight.value!!
        swallow = true
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
        return night
    }

    private suspend fun deliver(report: SourcedStatus) {
        val target = "demo"
        graph.actions.observeStatus(report.status, target, graph.now(), report.source ?: target)
    }

    private fun stopped(night: ActiveNight) = graph.repository.history.value.first { it.id == night.recordId }
        .events.count { it.kind == EventKind.STOPPED }

    @Test fun standbyBufferedBeforeTheRequestCannotCloseALaterPendingOff() = runBlocking {
        val oldStandby = link.sourcedStatus.first()!!
        assertEquals(Mode.STANDBY, oldStandby.status.mode)
        val night = unresolvedOff()
        deliver(oldStandby)
        assertEquals("a buffered pre-request Standby must not be timestamped at processing time", night.identity,
            graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertEquals(0, stopped(night))

        graph.clock = Clock.fixed(graph.now().minusHours(1).toInstant(), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val freshStandby = link.sourcedStatus.first()!!
        deliver(freshStandby)
        assertNull(graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.endRequested)
        assertEquals(1, stopped(night))
        deliver(freshStandby)
        assertEquals(1, stopped(night))
    }

    @Test fun anOldReportCannotBorrowTheReceiptOfAnEqualValuedNewReport() = runBlocking {
        // Match the initial Summer Cool step, then normalise Standby's limits before capturing the old receipt.
        demo.send(Codec.setFan(13))
        demo.send(Codec.setTemperature(44))
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val oldStandby = link.sourcedStatus.first()!!
        val night = unresolvedOff()
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val freshStandby = link.sourcedStatus.first()!!
        assertEquals(freshStandby.status, oldStandby.status)
        assertNotSame(freshStandby.status, oldStandby.status)
        deliver(oldStandby)
        assertEquals("value equality cannot attribute a buffered report to the latest receipt", night.identity,
            graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertEquals(0, stopped(night))
        deliver(freshStandby)
        assertNull(graph.repository.activeNight.value)
        assertEquals(1, stopped(night))
    }

    @Test fun firstWrappingAPreExistingStandbyDuringAFailedSendCannotMakeItFresh() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val night = graph.repository.activeNight.value!!
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        // The raw status exists, but no sourced report has yet wrapped this particular object.
        val oldStandby = demo.status.value!!
        failSend = true
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
        graph.actions.observeStatus(oldStandby, "demo", graph.now(), "demo")
        assertEquals("the first wrapper around cached pre-request state must not count as a later receipt", night.identity,
            graph.repository.activeNight.value?.identity)
        assertEquals(night.identity, graph.repository.runtime.value.endRequested)
        assertEquals(0, stopped(night))

        demo.send(Codec.setMode(Mode.HEAT)!!)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        deliver(link.sourcedStatus.first()!!)
        assertNull(graph.repository.activeNight.value)
        assertEquals(1, stopped(night))
    }
}
