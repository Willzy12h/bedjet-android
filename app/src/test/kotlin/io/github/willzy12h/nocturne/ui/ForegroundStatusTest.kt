package io.github.willzy12h.nocturne.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** UI scheduling cannot slow or rewrite the raw receipts used by the command owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundStatusTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private val link = ReportsLink()
    private lateinit var graph: Graph
    private lateinit var model: AppViewModel

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false) }
        model = AppViewModel(app)
        models.put("foreground", model)
        idle()
    }
    @After fun close() { models.clear() }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun visible() {
        model.onScreenVisible()
        idle()
        assertEquals(50, model.status.value?.actualTemperature)
    }

    @Test fun aBackgroundCommandDoesNotExposeLiveStatusToAHiddenScreen() = runBlocking {
        graph.sessions.withConnection { link.receive(temperature = 60) }
        idle(2_000)
        assertEquals(60, link.status.value?.actualTemperature)
        assertNull("Hidden activities must not publish full live status", model.status.value)
    }

    @Test fun hidingStopsUpdatesAndReturningShowsTheLatestTargetStatus() {
        visible()
        model.onScreenHidden()
        idle()
        assertNull("Hiding clears the UI's live status", model.status.value)
        link.receive(temperature = 64)
        idle(2_000)
        assertNull(model.status.value)
        model.onScreenVisible()
        idle()
        assertEquals(64, model.status.value?.actualTemperature)
    }

    @Test fun temperaturesUpdateAtMostOncePerSecondWithoutThrottlingRawReceipts() {
        visible()
        val displayed = mutableListOf<Pair<Long, Int>>()
        graph.scope.launch { model.status.collect { s ->
            s?.let { if (displayed.lastOrNull()?.second != it.actualTemperature)
                displayed += SystemClock.elapsedRealtime() to it.actualTemperature }
        } }
        idle()
        val initialReceipt = link.latestSourcedStatus()!!.receiptOrder
        repeat(9) { index -> link.receive(51 + index); idle(100) }
        val lastRaw = link.latestSourcedStatus()!!
        assertEquals("All raw readings remain available to commands", 59, lastRaw.status.actualTemperature)
        assertTrue(lastRaw.receiptOrder > initialReceipt)
        assertEquals("A burst must not repaint every incoming temperature", listOf(50), displayed.map { it.second })
        idle(100)
        assertEquals(listOf(50, 59), displayed.map { it.second })
        assertTrue(displayed[1].first - displayed[0].first >= 1_000)
        assertSame("Displaying status must not stamp a new command receipt", lastRaw, link.latestSourcedStatus())
    }

    private class ReportsLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.SEARCHING)
        override val status = MutableStateFlow<DeviceStatus?>(sample(50))
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(SourcedStatus("demo", status.value!!))
        override fun latestSourcedStatus() = sourcedStatus.value
        fun receive(temperature: Int) {
            status.value = sample(temperature)
            sourcedStatus.value = SourcedStatus("demo", status.value!!)
        }
        override suspend fun connect() { state.value = LinkState.READY }
        override suspend fun disconnect() { state.value = LinkState.SEARCHING }
        override suspend fun send(command: ByteArray) = Unit
        override suspend fun query(request: Int) = byteArrayOf()
        companion object {
            fun sample(t: Int) = DeviceStatus(600, t, 64, Mode.HEAT, 9, 600, 38, 86, 44, 0,
                DeviceStatus.Extra(false, false, 0, 0))
        }
    }
}
