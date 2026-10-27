package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import io.github.willzy12h.nocturne.ui.AppViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** Visible shortcut approval binds Off to the reviewed night, unlike an unreviewed emergency Off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutStopConfirmationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private lateinit var original: ActiveNight
    private val sent = CopyOnWriteArrayList<ByteArray>()
    private val outcomes = CopyOnWriteArrayList<CommandOutcome>()
    private var connects = 0
    private var reads = 0

    @Before fun startTrackedNight() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val observed = object : BedJetLink by demo {
            override suspend fun connect() { connects++; demo.connect() }
            override suspend fun query(request: Int): ByteArray { reads++; return demo.query(request) }
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, observed) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        // Owner confirmation and durable History use a real session; all packets still use the injected fake.
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(android.bluetooth.BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val result = graph.actions.execute(graph.actions.capture(Command.START_NIGHT))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        original = requireNotNull(graph.repository.activeNight.value)
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.collect { outcomes += it } }
        resetTraffic()
    }

    private fun resetTraffic() { sent.clear(); connects = 0; reads = 0 }

    private fun open(command: Command): ActivityController<ShortcutActivity> =
        Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", command.name)).setup()

    private fun confirmation(command: Command): AlertDialog {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("A tracked shortcut $command needs visible approval", dialog)
        assertEquals("Opening the shortcut must not connect", 0, connects)
        assertEquals("Opening the shortcut must not read hardware", 0, reads)
        assertTrue("Opening the shortcut must not send", sent.isEmpty())
        assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility)
        assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertEquals(original, graph.repository.activeNight.value)
        return dialog
    }

    private fun approveAndDeliver(command: Command): ServiceController<BedJetService> {
        confirmation(command).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        return deliverService()
    }

    private fun deliverService(): ServiceController<BedJetService> {
        var delivery: Intent? = null
        until { delivery = delivery ?: shadowOf(app).nextStartedService; delivery != null }
        return Robolectric.buildService(BedJetService::class.java).create().also {
            it.get().onStartCommand(delivery, 0, 1)
        }
    }

    @Test fun shortcutOffCancelDoesNotDeliverAServiceOrTouchHardware() {
        val activity = open(Command.OFF)
        try {
            confirmation(Command.OFF).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.get().isFinishing)
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(original, graph.repository.activeNight.value)
            assertTrue(outcomes.isEmpty())
            assertEquals(0, connects)
            assertEquals(0, reads)
            assertTrue(sent.isEmpty())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun shortcutApprovedOffConfirmsItsCapturedNightThroughTheService() {
        val activity = open(Command.OFF)
        var service: ServiceController<BedJetService>? = null
        try {
            val running = approveAndDeliver(Command.OFF)
            service = running
            until { outcomes.isNotEmpty() && shadowOf(running.get()).isStoppedBySelf }
            val outcome = outcomes.single()
            assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
            assertEquals(CommandOrigin.SHORTCUT, outcome.origin)
            assertNull(graph.repository.activeNight.value)
            assertNull(graph.repository.runtime.value.ending)
            assertEquals(Mode.STANDBY, demo.status.value!!.mode)
            assertEquals(1, sent.count { it.contentEquals(Codec.setMode(Mode.STANDBY)!!) })
            assertEquals(1, graph.repository.history.value.single { it.id == original.recordId }
                .events.count { it.kind == EventKind.STOPPED })
        } finally { service?.destroy(); activity.pause().stop().destroy() }
    }

    @Test fun shortcutOffApprovalQueuedBehindANewerNightIsRefused() = runBlocking {
        assertQueuedApprovalCannotEndANewerNight(Command.OFF)
    }

    @Test fun shortcutEndNightApprovalQueuedBehindANewerNightIsRefused() = runBlocking {
        assertQueuedApprovalCannotEndANewerNight(Command.END_NIGHT)
    }

    private suspend fun assertQueuedApprovalCannotEndANewerNight(command: Command) = kotlinx.coroutines.coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            graph.actions.exclusive { entered.complete(Unit); release.await() }
        }
        entered.await()
        resetTraffic()
        val activity = open(command)
        var service: ServiceController<BedJetService>? = null
        try {
            val running = approveAndDeliver(command)
            service = running
            until { graph.actions.busy.value.queuedCount == 1 }
            val newer = original.copy(revision = original.revision + 1)
            graph.repository.setActiveNight(newer)
            release.complete(Unit)
            holder.await()
            until { outcomes.isNotEmpty() && shadowOf(running.get()).isStoppedBySelf }
            assertEquals("An old shortcut $command approval must not stop a newer night", OutcomeKind.STALE, outcomes.single().kind)
            assertEquals(CommandOrigin.SHORTCUT, outcomes.single().origin)
            assertEquals(newer, graph.repository.activeNight.value)
            assertEquals(0, connects)
            assertEquals(0, reads)
            assertTrue("A stale shortcut approval must send no Off packet", sent.isEmpty())
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
            service?.destroy()
            activity.pause().stop().destroy()
        }
    }

    @Test fun confirmedTonightOffQueuedBehindANewerNightIsRefused() = runBlocking {
        val models = ViewModelStore()
        val model = AppViewModel(app)
        models.put("confirmed-tonight-off", model)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            graph.actions.exclusive { entered.complete(Unit); release.await() }
        }
        entered.await()
        resetTraffic()
        var service: ServiceController<BedJetService>? = null
        try {
            // This is the synchronous approval callback used by Tonight's NightGuard Off button.
            model.withConfirmedNight(original.identity) { model.run(Command.OFF) }
            service = deliverService()
            until { graph.actions.busy.value.queuedCount == 1 }
            val newer = original.copy(revision = original.revision + 1)
            graph.repository.setActiveNight(newer)
            release.complete(Unit)
            holder.await()
            until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
            assertEquals("An approved Tonight Off must stay bound to the reviewed night", OutcomeKind.STALE, outcomes.single().kind)
            assertEquals(CommandOrigin.APP, outcomes.single().origin)
            assertEquals(newer, graph.repository.activeNight.value)
            assertEquals(0, connects)
            assertEquals(0, reads)
            assertTrue(sent.isEmpty())
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
            service?.destroy()
            models.clear()
        }
    }

    @Test fun anUnconfirmedEmergencyOffStillUsesTheSelectedTarget() = runBlocking {
        val emergency = graph.actions.capture(Command.OFF, requireIdentity = true)
        graph.repository.setActiveNight(original.copy(revision = original.revision + 1))
        val outcome = graph.actions.execute(emergency)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        assertNull(graph.repository.activeNight.value)
        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        assertEquals(1, sent.count { it.contentEquals(Codec.setMode(Mode.STANDBY)!!) })
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var lastTick = System.nanoTime()
        var remainder = 0L
        while (!check() && System.nanoTime() < deadline) {
            val tick = System.nanoTime()
            val elapsed = tick - lastTick + remainder
            lastTick = tick
            remainder = elapsed % 1_000_000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed / 1_000_000))
            Thread.yield()
        }
        assertTrue("Timed out driving the actual shortcut and service", check())
    }
}
