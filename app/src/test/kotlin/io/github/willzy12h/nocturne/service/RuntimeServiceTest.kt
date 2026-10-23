package io.github.willzy12h.nocturne.service

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.night.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeServiceTest {
    private val app: App = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var beforeSend: suspend (ByteArray) -> Unit = {}
    private var writes = 0
    private var connects = 0
    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun connect() { connects++; demo.connect() }
            override suspend fun send(command: ByteArray) { writes++; beforeSend(command); demo.send(command) }
        }
        graph = Graph(app, link)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        App::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }
    /** Drive the main looper, not real-time sleeps or clock changes, while IO persistence completes. */
    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Timed out pumping main looper", check())
    }
    @Test fun busyTimeChangeReconciliationRetriesWithoutAFalseError() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }
        val receiver = BootReceiver()
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_CHANGED), Context.RECEIVER_EXPORTED)
        try {
            app.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            val manager = shadowOf(app.getSystemService(AlarmManager::class.java))
            until { manager.scheduledAlarms.any { it.triggerAtTime == graph.clock.millis() + 60_000 } }
            assertNull(shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).getNotification(3))

            release.complete(Unit); until { held.isCompleted }
            val retry = NightAlarmReceiver()
            app.registerReceiver(retry, IntentFilter(NightAlarms.ACTION_RECONCILE_RETRY), Context.RECEIVER_NOT_EXPORTED)
            try {
                app.sendBroadcast(Intent(NightAlarms.ACTION_RECONCILE_RETRY))
                until { manager.scheduledAlarms.none { it.triggerAtTime == graph.clock.millis() + 60_000 } }
            } finally { app.unregisterReceiver(retry) }
        } finally { release.complete(Unit); app.unregisterReceiver(receiver) }
    }
    @Test fun wakeReceiverRetainsDeliveryBeyondItsBudgetWhileOwnerIsBusy() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val n = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.wakeUpStart).toInstant(), ZoneOffset.UTC)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }
        val count = writes; val connectsBefore = connects
        val receiver = NightAlarmReceiver()
        app.registerReceiver(receiver, IntentFilter(NightAlarms.ACTION_WAKE_UP), Context.RECEIVER_NOT_EXPORTED)
        try {
            val wake = Intent(NightAlarms.ACTION_WAKE_UP).putExtra(NightAlarms.EXTRA_NIGHT, n.recordId).putExtra(NightAlarms.EXTRA_REVISION, n.revision)
            app.sendBroadcast(wake)
            until { graph.repository.runtime.value.localDeliveries.isNotEmpty() }
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(n.recordId))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(count, writes)
            release.complete(Unit); until { held.isCompleted }
            until { graph.repository.runtime.value.wakeShown == n.identity }
            app.sendBroadcast(wake); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(n.identity, graph.repository.runtime.value.wakeShown)
            assertEquals(count, writes)
            assertEquals(connectsBefore, connects)
            until { graph.repository.notices.value.count { it.kind == io.github.willzy12h.nocturne.core.store.NoticeKind.WAKE_UP } == 1 }
        } finally { release.complete(Unit); app.unregisterReceiver(receiver) }
    }
    @Test fun endReceiverRetainsDeliveryBeyondItsBudgetWithoutHardware() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val n = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.endsAt).toInstant(), ZoneOffset.UTC)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }; val count = writes; val connectsBefore = connects
        val receiver = NightAlarmReceiver()
        app.registerReceiver(receiver, IntentFilter(NightAlarms.ACTION_NIGHT_END), Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendBroadcast(Intent(NightAlarms.ACTION_NIGHT_END).putExtra(NightAlarms.EXTRA_NIGHT, n.recordId).putExtra(NightAlarms.EXTRA_REVISION, n.revision))
            until { graph.repository.runtime.value.localDeliveries.isNotEmpty() }
            assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(n.recordId))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            release.complete(Unit); until { held.isCompleted }
            until { graph.repository.activeNight.value == null }
            assertEquals(count, writes); assertEquals(connectsBefore, connects)
        } finally { release.complete(Unit); app.unregisterReceiver(receiver) }
    }
    @Test fun delayedWakeReceiverRejectsRevisionChangedByItsOwner() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val n = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.wakeUpStart).toInstant(), ZoneOffset.UTC)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }
        val receiver = NightAlarmReceiver()
        app.registerReceiver(receiver, IntentFilter(NightAlarms.ACTION_WAKE_UP), Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendBroadcast(Intent(NightAlarms.ACTION_WAKE_UP).putExtra(NightAlarms.EXTRA_NIGHT, n.recordId).putExtra(NightAlarms.EXTRA_REVISION, n.revision))
            until { graph.repository.runtime.value.localDeliveries.isNotEmpty() }
            graph.repository.setActiveNight(n.copy(revision = n.revision + 1))
            release.complete(Unit); until { held.isCompleted }
            assertNull(graph.repository.runtime.value.wakeShown)
            assertTrue(graph.repository.runtime.value.localDeliveries.isEmpty())
            assertEquals(n.revision + 1, graph.repository.activeNight.value!!.revision)
        } finally { release.complete(Unit); app.unregisterReceiver(receiver) }
    }
    @Test fun cancelledUiDeliveryCannotExecuteLater() {
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        var invoked = false
        var delivered: Intent? = null
        val caller = graph.scope.launch { BedJetService.runOwned(app) { invoked = true } }
        until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null }
        caller.cancel(); until { caller.isCompleted }
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(delivered, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(invoked); assertEquals(0, writes)
        service.onDestroy()
    }
    @Test fun deferredUiPinRejectsTargetChangedBeforeDeliveryInsideCoordinator() {
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        var delivered: Intent? = null
        var result: CommandOutcome? = null
        graph.scope.launch { result = BedJetService.runOwned(app) { graph.actions.pin(graph.repository.profiles.value.first().id, 0) } }
        until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null }
        runBlocking { graph.repository.updateSettings { it.copy(targetDevice = "ble:11:22:33:44:55:66") } }
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(delivered, 0, 1)
        until { result != null }
        assertEquals(OutcomeKind.STALE, result!!.kind); assertEquals(0, writes)
        service.onDestroy()
    }
    @Test fun uiOperationWaitsForForegroundOwnershipAndServiceCancellationReachesUpload() {
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val reached = CompletableDeferred<Unit>()
        beforeSend = { if (it[0] == 0x40.toByte()) { reached.complete(Unit); awaitCancellation() } }
        var delivered: Intent? = null
        val caller = graph.scope.launch {
            BedJetService.runOwned(app) { graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)) }
        }
        until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null || reached.isCompleted }
        assertNotNull("UI work must request service ownership before upload", delivered)
        assertFalse(reached.isCompleted)
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(delivered, 0, 1)
        until { reached.isCompleted }
        assertNotNull(shadowOf(service).lastForegroundNotification)
        service.onDestroy()
        until { caller.isCompleted && graph.repository.activeNight.value?.confidence == Confidence.UNKNOWN }
        assertTrue(caller.isCancelled)
    }
    @Test fun serviceOwnsUploadAndCancellationPersistsUnknown() {
        val reached = CompletableDeferred<Unit>()
        beforeSend = { if (it[0] == 0x40.toByte()) { reached.complete(Unit); awaitCancellation() } }
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(BedJetService.intent(app, NightActions.Command.START_NIGHT), 0, 1)
        until { reached.isCompleted }
        assertNotNull(shadowOf(service).lastForegroundNotification)
        service.onDestroy()
        until { graph.repository.activeNight.value?.confidence == Confidence.UNKNOWN && graph.actions.busy.value.runningLabel == null }
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
    }
    @Test fun receiverFallbackPersistsNewestSignalWhileUploadOwnsMutexAndNeverWrites() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeSend = { if (it[0] == 0x40.toByte()) { entered.complete(Unit); release.await() } }
        val operation = async(Dispatchers.Default) { graph.actions.perform(NightActions.Command.START_NIGHT) }
        entered.await()
        val receiver = AlarmChangedReceiver()
        app.registerReceiver(receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), Context.RECEIVER_EXPORTED)
        try {
            val count = writes
            app.sendBroadcast(Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED))
            until { graph.repository.runtime.value.alarmInbox != null }
            val first = graph.repository.runtime.value.alarmInbox!!.id
            app.sendBroadcast(Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED))
            until { graph.repository.runtime.value.alarmInbox?.id != first }
            assertEquals(count, writes)
            assertFalse(operation.isCompleted)
        } finally {
            app.unregisterReceiver(receiver); release.complete(Unit); operation.await()
        }
        val latest = graph.repository.runtime.value.alarmInbox!!.id
        assertTrue(java.io.File(app.filesDir, "runtime.json").readText().contains(latest))
    }
    @Test fun jobRouteNeverStartsForegroundServiceAndChecksFreshIdleAfterConnection() = runBlocking {
        val result = graph.actions.performJobOwned(graph.actions.capture(NightActions.Command.START_NIGHT)) { !it.isRunning }
        assertEquals(OutcomeKind.CONFIRMED, result.kind)
        assertNull(shadowOf(app).nextStartedService)
    }
}
