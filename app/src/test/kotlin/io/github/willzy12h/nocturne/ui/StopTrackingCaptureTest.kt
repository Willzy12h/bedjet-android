package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.ResumeOffer
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** The real recovery dialog must approve the displayed night, even when its local command waits for the owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StopTrackingCaptureTest {
    private val compose = createEmptyComposeRule()
    private val graphs = GraphLifecycle()
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(compose)
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private lateinit var graph: Graph
    private lateinit var model: AppViewModel
    private lateinit var original: ActiveNight
    private var activity: ActivityController<ComponentActivity>? = null
    private var connects = 0
    private var disconnects = 0
    private var reads = 0
    private val writes = CopyOnWriteArrayList<ByteArray>()
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()

    @Before fun showRealRecoveryControlsWithBluetoothOff() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
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
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        graph.repository.updateSettings {
            it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF", themeMode = ThemeMode.DARK,
                autoUpdateCheck = false)
        }
        val started = graph.actions.execute(graph.actions.capture(Command.START_NIGHT))
        assertEquals(started.message, OutcomeKind.CONFIRMED, started.kind)
        original = graph.repository.activeNight.value!!
        assertEquals("ble:AA:BB:CC:DD:EE:FF", original.deviceId)
        val previous = original.copy(recordId = "earlier-night")
        graph.repository.updateRuntime {
            it.copy(resumeOffer = ResumeOffer(previous, previous.applied.steps, graph.now().toString(),
                graph.now().plusMinutes(15).toString(), it.generation))
        }
        shadowOf(adapter).setEnabled(false)
        model = AppViewModel(app)
        models.put("stop-tracking-capture", model)
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.collect { outcomes += it } }
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true)
        compose.runOnUiThread {
            activity!!.get().setContent {
                NocturneTheme(ThemeMode.DARK) {
                    val night by model.activeNight.collectAsState()
                    NightRecoveryControls(model, night, rememberNightGuard(model), graph.now())
                }
            }
            activity!!.visible().windowFocusChanged(true)
        }
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        connects = 0
        disconnects = 0
        reads = 0
        writes.clear()
        Unit
    }

    @After fun closeUi() {
        activity?.pause()?.stop()?.destroy()
        activity = null
        models.clear()
    }

    @Test fun approvalAfterAnotherNightReplacesTheDisplayedNightIsStaleAndPreservesIt() = runBlocking {
        openStopTracking()
        val replacement = replaceNight()
        val history = graph.repository.history.value
        val runtime = graph.repository.runtime.value
        approveStopTracking()
        awaitOutcome()
        assertEquals("Approval belongs to the night shown when the dialog opened", OutcomeKind.STALE, outcomes.single().kind)
        assertEquals(CommandOrigin.APP, outcomes.single().origin)
        assertEquals(app.getString(R.string.runtime_stale), outcomes.single().message)
        assertEquals(replacement, graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
        assertEquals("The replacement's generation and existing resume offer must be untouched", runtime, graph.repository.runtime.value)
        assertNoHardware()
    }

    @Test fun approvalQueuedBehindTheOwnerCannotStopAReplacementNight() = runBlocking {
        openStopTracking()
        // Hold the real owner mutex directly: using its read-only connection helper would itself
        // connect, hiding whether this local UI request touched hardware while it waited.
        val mutex = ReflectionHelpers.getField<Mutex>(graph.actions, "lock")
        mutex.lock()
        var held = true
        try {
            approveStopTracking()
            until { graph.actions.busy.value.queuedCount == 1 }
            assertTrue("The reviewed command is still waiting for the owner", outcomes.isEmpty())
            val replacement = replaceNight()
            val history = graph.repository.history.value
            val runtime = graph.repository.runtime.value
            mutex.unlock()
            held = false
            awaitOutcome()
            assertEquals("Owner admission must recheck the identity captured by the dialog", OutcomeKind.STALE, outcomes.single().kind)
            assertEquals(CommandOrigin.APP, outcomes.single().origin)
            assertEquals(replacement, graph.repository.activeNight.value)
            assertEquals(history, graph.repository.history.value)
            assertEquals(runtime, graph.repository.runtime.value)
            assertNoHardware()
        } finally {
            if (held) mutex.unlock()
        }
    }

    @Test fun approvingTheSameNightStopsTrackingLocallyWithBluetoothOffAndKeepsTheUnitRunning() {
        val status = graph.link.status.value
        val before = graph.repository.history.value.single { it.id == original.recordId }
        openStopTracking()
        approveStopTracking()
        awaitOutcome()
        assertEquals(OutcomeKind.LOCAL, outcomes.single().kind)
        assertEquals(CommandOrigin.APP, outcomes.single().origin)
        assertNull(graph.repository.activeNight.value)
        val after = graph.repository.history.value.single { it.id == original.recordId }
        assertEquals(before.events, after.events.dropLast(1))
        assertEquals(EventKind.STOPPED, after.events.last().kind)
        assertEquals(1, after.events.count { it.kind == EventKind.STOPPED })
        assertEquals(original.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
        assertEquals(status, graph.link.status.value)
        assertTrue("The local command leaves the stored programme running", graph.link.status.value!!.extra!!.programStep > 0)
        assertNoHardware()
    }

    @Test fun cancellingStopTrackingKeepsTheNightHistoryAndResumeOfferWithoutSubmitting() {
        val history = graph.repository.history.value
        val runtime = graph.repository.runtime.value
        openStopTracking()
        compose.onNode(hasText(app.getString(R.string.runtime_command_keep_tracking)) and hasAnyAncestor(isDialog()))
            .performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(original, graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
        assertEquals(runtime, graph.repository.runtime.value)
        assertTrue(outcomes.isEmpty())
        assertEquals(0, graph.actions.busy.value.queuedCount)
        assertNoHardware()
    }

    private fun openStopTracking() {
        compose.onNode(hasText(app.getString(R.string.runtime_command_stop_tracking)) and hasClickAction()).performClick()
        compose.onNodeWithText(app.getString(R.string.recovery_stop_tracking_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stop_tracking_text)).assertIsDisplayed()
        assertNoHardware()
    }

    private fun approveStopTracking() {
        compose.onNode(hasText(app.getString(R.string.runtime_command_stop_tracking)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
    }

    private suspend fun replaceNight(): ActiveNight {
        val replacement = original.copy(recordId = "replacement-night", revision = original.revision + 1)
        graph.repository.addRecord(graph.repository.history.value.single { it.id == original.recordId }.copy(id = replacement.recordId))
        graph.repository.setActiveNight(replacement)
        return replacement
    }

    private fun awaitOutcome() {
        until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
        assertEquals(1, outcomes.size)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    private fun assertNoHardware() {
        assertEquals("Stop tracking must not connect", 0, connects)
        assertEquals("Stop tracking must not disconnect", 0, disconnects)
        assertEquals("Stop tracking must not read the BedJet", 0, reads)
        assertTrue("Stop tracking must not write the BedJet", writes.isEmpty())
        assertNull("Local tracking changes must not start a service", shadowOf(app).nextStartedService)
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("The actual UI request must reach its expected owner boundary", check())
    }
}
