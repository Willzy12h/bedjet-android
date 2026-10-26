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

    @Test fun modeChangesReachTheScreenBeforeTheNextSensorUpdate() {
        visible()
        link.receive(ReportsLink.sample(60).copy(mode = Mode.COOL, ambientTemperature = 48))
        val received = link.latestSourcedStatus()
        idle()

        assertEquals("Mode controls must follow the unit without waiting for sensor sampling", Mode.COOL, model.status.value?.mode)
        assertEquals(50, model.status.value?.actualTemperature)
        assertEquals(44, model.status.value?.ambientTemperature)
        assertSame("Presentation must leave the received report untouched", received, link.latestSourcedStatus())
    }

    @Test fun accessoryChangesReachTheScreenBeforeTheNextSensorUpdate() {
        visible()
        link.receive(ReportsLink.sample(60).copy(extra = DeviceStatus.Extra(true, true, 3, 1)))
        idle()

        assertEquals("Lights, beeps and programme details must remain live", DeviceStatus.Extra(true, true, 3, 1), model.status.value?.extra)
        assertEquals(50, model.status.value?.actualTemperature)
    }

    @Test fun targetTemperatureFanAndTimerChangesDoNotReleaseSensorSamplesEarly() {
        visible()
        repeat(4) { index ->
            link.receive(ReportsLink.sample(60 + index).copy(targetTemperature = 68 + index,
                fanStep = 12 + index, remainingSeconds = 900 + index, ambientTemperature = 48 + index))
            idle(100)
            assertEquals("The chosen temperature is a control value", 68 + index, model.status.value?.targetTemperature)
            assertEquals(12 + index, model.status.value?.fanStep)
            assertEquals(900 + index, model.status.value?.remainingSeconds)
            assertEquals("Control changes must not bypass the sensor cadence", 50, model.status.value?.actualTemperature)
            assertEquals(44, model.status.value?.ambientTemperature)
        }
        idle(600)
        assertEquals(63, model.status.value?.actualTemperature)
        assertEquals(51, model.status.value?.ambientTemperature)
        assertEquals("The sensor update must retain the latest control values", 71, model.status.value?.targetTemperature)
        assertEquals(15, model.status.value?.fanStep)
        assertEquals(903, model.status.value?.remainingSeconds)
    }

    @Test fun sensorOnlyBurstsDoNotPublishDuplicatePresentationValues() {
        visible()
        val displayed = mutableListOf<DeviceStatus>()
        graph.scope.launch { model.status.collect { it?.let(displayed::add) } }
        idle()
        repeat(9) { index ->
            link.receive(ReportsLink.sample(51 + index).copy(ambientTemperature = 45 + index))
            idle(100)
        }
        assertEquals("Sensor-only input must not repaint equal presentation values", 1, displayed.size)
        idle(100)
        assertEquals(2, displayed.size)
        assertEquals(59, displayed.last().actualTemperature)
        assertEquals(53, displayed.last().ambientTemperature)
    }

    @Test fun losingStatusClearsTheControlsBeforeTheNextSensorUpdate() {
        visible()
        link.clearStatus()
        idle()
        assertNull("Unavailable status must not leave old controls on screen", model.status.value)
    }

    @Test fun switchingTargetDiscardsThePreviousDevicesQueuedTemperatures() = runBlocking {
        val first = "ble:AA:BB:CC:DD:EE:01"
        val second = "ble:AA:BB:CC:DD:EE:02"
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = first) }
        link.receive(ReportsLink.sample(50), first)
        visible()
        link.receive(ReportsLink.sample(60), first)
        idle(100)

        graph.repository.updateSettings { it.copy(targetDevice = second) }
        idle()
        assertNull("The previous target's snapshot cannot stand for the new target", model.status.value)
        link.receive(ReportsLink.sample(70).copy(mode = Mode.COOL, ambientTemperature = 52), second)
        idle()
        assertEquals("A new target gets its own immediate sensor snapshot", 70, model.status.value?.actualTemperature)
        assertEquals(52, model.status.value?.ambientTemperature)
        assertEquals(Mode.COOL, model.status.value?.mode)

        link.receive(ReportsLink.sample(61), first)
        idle()
        assertNull("A later old-target report must clear rather than replace the selected target", model.status.value)
        idle(1_000)
        assertNull("No delayed old sensor sample may reappear", model.status.value)
    }

    private class ReportsLink : BedJetLink {
        override val state = MutableStateFlow(LinkState.SEARCHING)
        override val status = MutableStateFlow<DeviceStatus?>(sample(50))
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(SourcedStatus("demo", status.value!!))
        override fun latestSourcedStatus() = sourcedStatus.value
        fun receive(temperature: Int) = receive(sample(temperature))
        fun receive(value: DeviceStatus, source: String = "demo") {
            status.value = value
            sourcedStatus.value = SourcedStatus(source, value)
        }
        fun clearStatus() {
            status.value = null
            sourcedStatus.value = null
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
