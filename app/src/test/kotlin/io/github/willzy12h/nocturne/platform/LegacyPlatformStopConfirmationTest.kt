package io.github.willzy12h.nocturne.platform

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
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

/** A retained pre-P4 widget PendingIntent can still enter the private legacy activity after upgrade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyPlatformStopConfirmationTest {
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
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.bluetooth.BluetoothManager::class.java).adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val result = graph.actions.execute(graph.actions.capture(Command.START_NIGHT))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        original = requireNotNull(graph.repository.activeNight.value)
        graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.collect { outcomes += it } }
        resetTraffic()
    }

    private fun resetTraffic() { sent.clear(); connects = 0; reads = 0 }

    private fun open(command: Command): ActivityController<PlatformActionActivity> =
        Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app, graph.actions.capture(command, requireIdentity = true))).setup()

    private fun confirmation(command: Command): AlertDialog {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("A tracked legacy $command needs a visible confirmation", dialog)
        assertEquals("Opening the tracked $command confirmation must not connect", 0, connects)
        assertEquals("Opening the tracked $command confirmation must not read hardware", 0, reads)
        assertTrue("Opening the tracked $command confirmation must not send", sent.isEmpty())
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertNotNull("A tracked legacy $command must offer cancellation before owner work", cancel)
        assertEquals(View.VISIBLE, cancel.visibility)
        val approve = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertNotNull("A tracked legacy $command needs explicit approval", approve)
        assertEquals(View.VISIBLE, approve.visibility)
        assertEquals(original, graph.repository.activeNight.value)
        return dialog
    }

    @Test fun legacyTrackedOffWaitsForConfirmationAndCancelSendsNothing() {
        assertCancelSendsNothing(Command.OFF)
    }

    @Test fun legacyTrackedEndNightWaitsForConfirmationAndCancelSendsNothing() {
        assertCancelSendsNothing(Command.END_NIGHT)
    }

    private fun assertCancelSendsNothing(command: Command) {
        val activity = open(command)
        try {
            confirmation(command).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.get().isFinishing)
            assertEquals(original, graph.repository.activeNight.value)
            assertTrue(outcomes.isEmpty())
            assertEquals(0, connects)
            assertEquals(0, reads)
            assertTrue(sent.isEmpty())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun legacyConfirmedOffStopsOnlyItsCapturedNight() {
        assertApprovedStop(Command.OFF)
    }

    @Test fun legacyConfirmedEndNightStopsOnlyItsCapturedNight() {
        assertApprovedStop(Command.END_NIGHT)
    }

    private fun assertApprovedStop(command: Command) {
        val activity = open(command)
        var service: ServiceController<BedJetService>? = null
        try {
            confirmation(command).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            service = deliverService()
            until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
            val outcome = outcomes.single()
            assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
            assertEquals(CommandOrigin.WIDGET, outcome.origin)
            assertNull(graph.repository.activeNight.value)
            assertNull(graph.repository.runtime.value.ending)
            assertEquals(Mode.STANDBY, demo.status.value!!.mode)
            assertEquals(1, sent.count { it.contentEquals(Codec.setMode(Mode.STANDBY)!!) })
            assertEquals(1, graph.repository.history.value.single { it.id == original.recordId }
                .events.count { it.kind == EventKind.STOPPED })
        } finally { service?.destroy(); activity.pause().stop().destroy() }
    }

    @Test fun legacyOffApprovalQueuedBehindANewerNightIsRefused() = runBlocking {
        assertQueuedApprovalCannotEndANewerNight(Command.OFF)
    }

    @Test fun legacyEndNightApprovalQueuedBehindANewerNightIsRefused() = runBlocking {
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
            // The separate cancel cases require the visible guard. Here the owner is already held, so the
            // old immediate-dispatch defect cannot obscure the captured-identity race at admission.
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE)
                ?.takeIf { it.visibility == View.VISIBLE && it.isEnabled }?.performClick()
            service = deliverService()
            until { graph.actions.busy.value.queuedCount == 1 }
            val newer = original.copy(revision = original.revision + 1)
            graph.repository.setActiveNight(newer)
            release.complete(Unit)
            holder.await()
            until { outcomes.isNotEmpty() && graph.actions.busy.value.runningLabel == null }
            assertEquals("An old legacy $command approval must not stop a newer night", OutcomeKind.STALE, outcomes.single().kind)
            assertEquals(CommandOrigin.WIDGET, outcomes.single().origin)
            assertEquals(newer, graph.repository.activeNight.value)
            assertEquals(0, connects)
            assertEquals(0, reads)
            assertTrue("A stale widget approval must send no Off packet", sent.isEmpty())
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
            service?.destroy()
            activity.pause().stop().destroy()
        }
    }

    private fun deliverService(): ServiceController<BedJetService> {
        var delivery: Intent? = null
        until { delivery = delivery ?: shadowOf(app).nextStartedService; delivery != null }
        return Robolectric.buildService(BedJetService::class.java).create().also {
            it.get().onStartCommand(delivery, 0, 1)
        }
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
        assertTrue("Timed out driving the actual legacy widget activity", check())
    }
}
