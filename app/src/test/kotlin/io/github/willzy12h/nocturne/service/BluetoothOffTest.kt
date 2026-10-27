package io.github.willzy12h.nocturne.service

import android.Manifest
import android.app.Service
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.night.CommandOriginContext
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothOffTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var context: ServiceStartContext

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        context = ServiceStartContext(app)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { idle(); Thread.yield() }
        assertTrue("Expected the asynchronous result to finish", condition())
    }

    @Test fun anOffRadioFailsAtTheOwnerWithTheCorrectReason() = runBlocking {
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.OFF))
        assertEquals(OutcomeKind.FAILED, result.kind)
        assertEquals("Bluetooth is off", result.message)
        assertNull(graph.repository.runtime.value.endRequested)
    }

    @Test fun anIdleTonightWakeOverrideIsSavedLocallyWithBluetoothOff() = runBlocking {
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        val wake = graph.now().plusHours(8)
        val outcome = graph.actions.setWakeOverride(wake)
        assertEquals("Choosing tonight's wake without a tracked night is a local settings save", OutcomeKind.LOCAL, outcome.kind)
        assertEquals(CommandOrigin.SETTINGS, outcome.origin)
        assertEquals(wake.toString(), graph.repository.settings.value.tonightOverride?.wakeAt)
        assertNull(graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.retime)
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertTrue(context.requests.isEmpty())
        assertEquals(0, radioReceivers())
    }

    @Test fun aUiHandoffFailsBeforeStartingAServiceOrWaitingForItsTimeout() {
        var invoked = false
        val call = graph.scope.async { runCatching { BedJetService.runOwned(context, notifyFailures = false) { invoked = true } } }
        try {
            idle()
            assertTrue("Disabled Bluetooth must fail immediately rather than waiting for service ownership", call.isCompleted)
            assertEquals("Bluetooth is off", runBlocking { call.await() }.exceptionOrNull()?.message)
            assertFalse(invoked)
            assertTrue(context.requests.isEmpty())
        } finally { call.cancel() }
    }

    @Test fun repeatedBackgroundRequestsPostOneNoticeAndAreNotQueued() {
        repeat(3) {
            val request = graph.actions.capture(NightActions.Command.COOL).copy(origin = CommandOrigin.WIDGET)
            assertFalse("No background service should be launched with the radio off", BedJetService.start(context, request))
        }
        until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM && it.text.contains("Bluetooth is off") } }
        assertTrue(context.requests.isEmpty())
        assertEquals(1, graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM && it.text.contains("Bluetooth is off") })
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        idle()
        assertTrue("Enabling the radio must not replay refused commands", context.requests.isEmpty())
    }

    @Test fun aFreshRequestCanNotifyAgainOnlyAfterTheNamedWindow() {
        val start = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.clock = start
        fun request() = BedJetService.start(context, graph.actions.capture(NightActions.Command.COOL).copy(origin = CommandOrigin.WIDGET))
        fun notices() = graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM && it.text.contains("Bluetooth is off") }
        assertFalse(request())
        until { notices() == 1 }
        graph.clock = Clock.offset(start, Duration.ofMillis(Notifications.BLUETOOTH_NOTICE_WINDOW_MS - 1))
        repeat(3) { assertFalse(request()) }
        idle()
        assertEquals(1, notices())
        graph.clock = Clock.offset(start, Duration.ofMillis(Notifications.BLUETOOTH_NOTICE_WINDOW_MS))
        assertFalse(request())
        until { notices() == 2 }
        assertTrue(context.requests.isEmpty())
    }

    @Test fun anOwnerQueuedWhileBluetoothWasOnRechecksBeforeConnecting() = runBlocking {
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val mutex = ReflectionHelpers.getField<Mutex>(graph.actions, "lock")
        mutex.lock()
        val call = graph.scope.async { graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)) }
        try {
            idle()
            assertFalse(call.isCompleted)
            shadowOf(adapter).setEnabled(false)
            mutex.unlock()
            until { call.isCompleted }
            assertEquals(OutcomeKind.FAILED, call.await().kind)
            assertEquals("Bluetooth is off", call.await().message)
            assertNull(graph.repository.runtime.value.endRequested)
        } finally {
            if (mutex.isLocked) mutex.unlock()
            call.cancel()
        }
    }

    @Test fun serviceDeliveryRechecksAnAdapterDisabledAfterTheTap() {
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        assertTrue(BedJetService.start(context, graph.actions.capture(NightActions.Command.OFF).copy(origin = CommandOrigin.WIDGET)))
        shadowOf(adapter).setEnabled(false)
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(context.delivered, 0, 1))
            assertNull(shadowOf(service).lastForegroundNotification)
            assertEquals("Bluetooth is off", BedJetService.lastResult)
            assertNull(graph.repository.runtime.value.endRequested)
        } finally { controller.destroy() }
    }

    @Test fun aRetimeDeliveryAfterTheNightEndedDoesNotPostABluetoothFailure() = runBlocking {
        val now = graph.now()
        graph.repository.setActiveNight(ActiveNight("ended", "preset-summer", now.toString(), now.plusHours(8).toString(),
            false, 2, now.plusHours(7).toString(), now.plusHours(9).toString(), deviceId = "ble:AA:BB:CC:DD:EE:FF"))
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val initialReceivers = radioReceivers()
        assertTrue(BedJetService.start(context, NightActions.Command.RETRY_RETIME))
        assertEquals(initialReceivers + 1, radioReceivers())
        val delivery = context.delivered
        graph.repository.setActiveNight(null)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            controller.get().onStartCommand(delivery, 0, 1)
            assertNull("An ended night has no hardware work to fail", shadowOf(app.getSystemService(NotificationManager::class.java))
                .getNotification(8))
            assertNull(shadowOf(controller.get()).lastForegroundNotification)
            assertNull(graph.repository.runtime.value.retime)
            assertEquals("The quiet stale delivery closes its transferred admission receiver", initialReceivers, radioReceivers())
        } finally { controller.destroy() }
    }

    private fun switchRadio(enabled: Boolean) {
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(enabled)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,
            if (enabled) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF))
        idle()
    }

    private fun radioReceivers() = shadowOf(app).registeredReceivers.count {
        it.intentFilter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    }

    @Test fun cancellingAQueuedOwnerRemovesItsTemporaryRadioReceiver() {
        switchRadio(true)
        val initial = radioReceivers()
        val mutex = ReflectionHelpers.getField<Mutex>(graph.actions, "lock")
        runBlocking { mutex.lock() }
        val call = graph.scope.async { graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)) }
        try {
            idle()
            assertEquals(initial + 1, radioReceivers())
            call.cancel()
            until { call.isCompleted }
            assertEquals(initial, radioReceivers())
        } finally { call.cancel(); mutex.unlock() }
    }

    @Test fun cancellingAnUndeliveredHandoffRemovesItsTemporaryRadioReceiver() {
        switchRadio(true)
        val initial = radioReceivers()
        val call = graph.scope.async { BedJetService.runOwned(context, notifyFailures = false) { Unit } }
        try {
            until { context.requests.isNotEmpty() }
            assertEquals(initial + 1, radioReceivers())
            call.cancel()
            until { call.isCompleted }
            assertEquals(initial, radioReceivers())
        } finally { call.cancel() }
    }

    @Test fun switchingBluetoothOffAndOnNeverReplaysACommandWaitingForTheOwner() = runBlocking {
        switchRadio(true)
        val mutex = ReflectionHelpers.getField<Mutex>(graph.actions, "lock")
        mutex.lock()
        var held = true
        val call = graph.scope.async { graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)) }
        try {
            idle()
            assertFalse(call.isCompleted)
            switchRadio(false)
            switchRadio(true)
            mutex.unlock()
            held = false
            until { call.isCompleted }
            assertEquals(OutcomeKind.FAILED, call.await().kind)
            assertEquals("Bluetooth was switched off. Try the action again.", call.await().message)
            assertNull(graph.repository.runtime.value.endRequested)
        } finally {
            if (held) mutex.unlock()
            call.cancel()
        }
    }

    @Test fun aServiceHandoffInvalidatedBeforeDeliveryDoesNotReplayWhenBluetoothReturns() {
        switchRadio(true)
        var invoked = false
        val call = graph.scope.async { runCatching { BedJetService.runOwned(context, notifyFailures = false) { invoked = true } } }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            until { context.requests.isNotEmpty() }
            switchRadio(false)
            switchRadio(true)
            controller.get().onStartCommand(context.delivered, 0, 1)
            until { call.isCompleted }
            assertFalse("A radio interruption must cancel a queued action instead of replaying it", invoked)
            assertEquals("Bluetooth was switched off. Try the action again.", runBlocking { call.await() }.exceptionOrNull()?.message)
            assertNull(shadowOf(controller.get()).lastForegroundNotification)
        } finally { call.cancel(); controller.destroy() }
    }

    @Test fun aBackgroundRequestInvalidatedBeforeDeliveryDoesNotReplayWhenBluetoothReturns() {
        switchRadio(true)
        assertTrue(BedJetService.start(context, graph.actions.capture(NightActions.Command.OFF).copy(origin = CommandOrigin.WIDGET)))
        switchRadio(false)
        switchRadio(true)
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            controller.get().onStartCommand(context.delivered, 0, 1)
            assertNull("Invalidated requests must be refused before foreground promotion", shadowOf(controller.get()).lastForegroundNotification)
            assertEquals("Bluetooth was switched off. Try the action again.", BedJetService.lastResult)
            assertNull(graph.repository.runtime.value.endRequested)
        } finally { controller.destroy() }
    }

    @Test fun localWidgetStopTrackingStillUsesTheOwnerWithBluetoothOff() = runBlocking {
        val now = graph.now()
        graph.repository.setActiveNight(ActiveNight("local-stop", "preset-summer", now.toString(), now.plusHours(8).toString(),
            false, 2, now.plusHours(7).toString(), now.plusHours(9).toString(), deviceId = "ble:AA:BB:CC:DD:EE:FF"))
        val result = withContext(CommandOriginContext(CommandOrigin.WIDGET)) {
            BedJetService.runOwned(context, notifyFailures = false, hardware = false) {
                graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING))
            }
        }
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertEquals(CommandOrigin.WIDGET, result.origin)
        assertNull(graph.repository.activeNight.value)
        assertTrue(context.requests.isEmpty())
    }
}
