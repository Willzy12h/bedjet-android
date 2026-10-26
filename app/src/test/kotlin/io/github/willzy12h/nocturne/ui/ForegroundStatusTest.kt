package io.github.willzy12h.nocturne.ui

import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.CompletableDeferred
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
        assertTrue(model.hasLiveStatus.value)
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
        assertEquals("Hiding retains the last displayed target snapshot", 50, model.status.value?.actualTemperature)
        link.receive(temperature = 64)
        idle(2_000)
        assertEquals("Hidden raw updates must not repaint the retained snapshot", 50, model.status.value?.actualTemperature)
        model.onScreenVisible()
        idle()
        assertEquals(64, model.status.value?.actualTemperature)
    }

    @Test fun returningDuringReconnectKeepsTheControlsSnapshotUntilFreshStatusArrives() {
        visible()
        model.onScreenHidden()
        idle()
        link.retire()
        val connecting = CompletableDeferred<Unit>()
        link.connectGate = connecting
        model.onScreenVisible()
        idle()
        assertEquals("The connection indicator reports reconnection", LinkState.SEARCHING, model.linkState.value)
        assertEquals("Quick app switching must not blank the existing controls", 50, model.status.value?.actualTemperature)
        assertNull("The presentation cache must not become a raw command receipt", link.latestSourcedStatus())
        connecting.complete(Unit)
        link.receive(temperature = 64)
        val fresh = link.latestSourcedStatus()
        idle()
        assertEquals(64, model.status.value?.actualTemperature)
        assertSame(fresh, link.latestSourcedStatus())
    }

    @Test fun aVisibleDisconnectRetainsPresentationButNotARawReceipt() {
        visible()
        link.receive(temperature = 60)
        idle()
        link.retire()
        idle()
        assertFalse("A raw disconnect must invalidate freshness before the sensor timer expires", model.hasLiveStatus.value)
        idle(1_000)
        assertEquals(LinkState.SEARCHING, model.linkState.value)
        assertEquals(50, model.status.value?.actualTemperature)
        assertFalse("A queued sensor update cannot restore a retired connection's freshness", model.hasLiveStatus.value)
        assertNull(link.latestSourcedStatus())
    }

    @Test fun changingTargetWhileHiddenClearsThePreviousUnitsSnapshot() = runBlocking {
        visible()
        model.onScreenHidden()
        idle()
        val other = DeviceId.ble("AA:BB:CC:DD:EE:FF")
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = other) }
        idle()
        assertNull("A different target must never inherit the retained snapshot", model.status.value)
        model.onScreenVisible()
        idle()
        link.receive(temperature = 66, source = "demo")
        idle(1_000)
        assertNull("Wrong-target reports cannot populate the new target's screen", model.status.value)
        link.receive(temperature = 62, source = other)
        idle(1_000)
        assertEquals(62, model.status.value?.actualTemperature)
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

    @Test fun controlChangesDisplayImmediatelyWhileOnlySensorTemperaturesWait() {
        visible()
        val previous = model.status.value!!
        val received = previous.copy(actualTemperature = 60, ambientTemperature = 48,
            mode = Mode.COOL, fanStep = 4, targetTemperature = 70,
            extra = previous.extra!!.copy(ledEnabled = true))
        link.receive(received)
        val receipt = link.latestSourcedStatus()!!
        idle()
        val shown = model.status.value!!
        assertEquals("Mode changes must display without waiting for sensor sampling", received.mode, shown.mode)
        assertEquals("Fan changes must display without waiting for sensor sampling", received.fanStep, shown.fanStep)
        assertEquals("Lights must display the current report immediately", true, shown.extra?.ledEnabled)
        assertEquals("The setpoint is a control value, not a sampled sensor", received.targetTemperature, shown.targetTemperature)
        assertEquals(previous.actualTemperature, shown.actualTemperature)
        assertEquals(previous.ambientTemperature, shown.ambientTemperature)
        assertTrue(model.hasLiveStatus.value)
        assertSame(received, receipt.status)
        assertSame(receipt, link.latestSourcedStatus())
        idle(100)
        val latest = received.copy(actualTemperature = 62, ambientTemperature = 50,
            mode = Mode.DRY, fanStep = 6, targetTemperature = 72, extra = null)
        link.receive(latest)
        val latestReceipt = link.latestSourcedStatus()!!
        idle()
        assertEquals("New control fields must replace the prior report, including absent accessory data",
            latest.copy(actualTemperature = previous.actualTemperature, ambientTemperature = previous.ambientTemperature),
            model.status.value)
        assertNull(model.status.value?.extra)
        idle(899)
        assertEquals(previous.actualTemperature, model.status.value?.actualTemperature)
        assertEquals(previous.ambientTemperature, model.status.value?.ambientTemperature)
        idle(1)
        assertEquals("A delayed sensor tick must keep the latest control fields", latest, model.status.value)
        assertSame(latest, latestReceipt.status)
        assertSame("UI sampling cannot replace the owner's raw receipt", latestReceipt, link.latestSourcedStatus())
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

    @Test fun switchingTargetDiscardsThePreviousDevicesQueuedTemperatures() = runBlocking {
        val first = DeviceId.ble("AA:BB:CC:DD:EE:01")
        val second = DeviceId.ble("AA:BB:CC:DD:EE:02")
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = first) }
        link.receive(ReportsLink.sample(50), first)
        visible()
        link.receive(ReportsLink.sample(60), first)
        idle(100)

        graph.repository.updateSettings { it.copy(targetDevice = second) }
        idle()
        assertNull("The previous target's snapshot cannot stand for the new target", model.status.value)
        assertFalse(model.hasLiveStatus.value)
        val selected = ReportsLink.sample(70).copy(mode = Mode.COOL, ambientTemperature = 52)
        link.receive(selected, second)
        idle()
        assertEquals("A new target gets its own immediate sensor snapshot", selected, model.status.value)
        assertTrue(model.hasLiveStatus.value)

        link.receive(ReportsLink.sample(61), first)
        idle()
        assertEquals("A later old-target report cannot replace the selected target's retained snapshot",
            selected, model.status.value)
        assertFalse("A mismatched report must invalidate live control status", model.hasLiveStatus.value)
        idle(1_000)
        assertEquals("No delayed old sensor sample may replace the selected target's snapshot", selected, model.status.value)
        assertFalse("A discarded sensor timer cannot make the retained snapshot live", model.hasLiveStatus.value)
    }

    private class ReportsLink : BedJetLink {
        var connectGate: CompletableDeferred<Unit>? = null
        override val state = MutableStateFlow(LinkState.SEARCHING)
        override val status = MutableStateFlow<DeviceStatus?>(sample(50))
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(SourcedStatus("demo", status.value!!))
        override fun latestSourcedStatus() = sourcedStatus.value
        fun receive(temperature: Int, source: String = "demo") = receive(sample(temperature), source)
        fun receive(report: DeviceStatus, source: String = "demo") {
            status.value = report
            sourcedStatus.value = SourcedStatus(source, status.value!!)
        }
        fun retire() { state.value = LinkState.SEARCHING; status.value = null; sourcedStatus.value = null }
        override suspend fun connect() { connectGate?.await(); state.value = LinkState.READY }
        override suspend fun disconnect() { retire() }
        override suspend fun send(command: ByteArray) = Unit
        override suspend fun query(request: Int) = byteArrayOf()
        companion object {
            fun sample(t: Int) = DeviceStatus(600, t, 64, Mode.HEAT, 9, 600, 38, 86, 44, 0,
                DeviceStatus.Extra(false, false, 0, 0))
        }
    }
}
