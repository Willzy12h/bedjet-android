package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A successful reconnect call cannot make cached controls count as a newly received status. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreshReadBoundaryTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var link: BedJetLink
    private var disconnects = 0
    private var writes = 0

    private suspend fun graph(reportDuringDisconnect: Boolean = false, defaultReceipts: Boolean = false) {
        val demo = DemoBedJet(now = { graph.now() })
        val snapshots = MutableStateFlow<DeviceStatus?>(null)
        val reports = MutableStateFlow<SourcedStatus?>(null)
        fun receive(status: DeviceStatus) {
            // Stamp at simulated receipt, before either the flow or a getter can expose the report.
            reports.value = SourcedStatus(null, status)
            snapshots.value = status
        }
        val transport = object : BedJetLink {
            override val state = MutableStateFlow(LinkState.READY)
            override val status = snapshots
            // This reconnect succeeds without a new unit report; it only replays the last snapshot.
            override suspend fun connect() { state.value = LinkState.READY }
            override suspend fun disconnect() {
                disconnects++
                if (reportDuringDisconnect) {
                    demo.send(Codec.setTemperature(72))
                    receive(demo.status.value!!)
                }
                state.value = LinkState.SEARCHING
            }
            // The link accepts the write, but the unit has not sent its response yet.
            override suspend fun send(command: ByteArray) { writes++ }
            override suspend fun query(request: Int): ByteArray = demo.query(request)
        }
        link = if (defaultReceipts) transport else object : BedJetLink by transport {
            override val sourcedStatus = reports
            override fun latestSourcedStatus(): SourcedStatus? = reports.value
        }
        graph = graphs.fresh { Graph(context, link) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 1
        graph.actions.freshStatusMs = 20
        demo.connect()
        demo.send(Codec.setMode(Mode.HEAT)!!)
        demo.send(Codec.setTemperature(70))
        receive(demo.status.value!!)
        assertEquals(70, link.status.value!!.targetTemperature)
    }

    @Test fun cachedDemoModeCannotConfirmAfterTheSendAndFreshReadFallbackTimeouts() = runBlocking {
        graph()
        val before = link.latestSourcedStatus()!!

        val outcome = graph.actions.remote("Heat", endsNight = true, confirms = { it.mode == Mode.HEAT }) {
            it.setMode(Mode.HEAT)
        }

        assertEquals("A matching pre-send demo snapshot is not a fresh fallback response", OutcomeKind.ACCEPTED, outcome.kind)
        assertEquals(1, writes)
        assertEquals("The command did attempt its fresh-read fallback", 1, disconnects)
        assertEquals("A reconnect without a unit report keeps the cached receipt", before.receiptOrder,
            link.latestSourcedStatus()!!.receiptOrder)
    }

    @Test fun changedReportReceivedDuringDisconnectIsAfterTheFreshReadBoundary() = runBlocking {
        graph(reportDuringDisconnect = true)

        val outcome = graph.actions.remote("Temperature set", affectsProgramme = true, confirms = { it.targetTemperature == 72 }) {
            it.setTemperature(72)
        }

        assertEquals("The receipt boundary must precede disconnect", OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(1, writes)
        assertEquals(1, disconnects)
    }

    @Test fun firstCollectionOfACachedDemoSnapshotCannotCountAsAFreshRead() = runBlocking {
        graph(defaultReceipts = true)

        val outcome = graph.actions.takeBack()

        assertEquals("A lazily created receipt cannot turn the pre-read snapshot into a fresh response",
            OutcomeKind.FAILED, outcome.kind)
        assertEquals(1, disconnects)
        assertEquals(0, writes)
    }
}
