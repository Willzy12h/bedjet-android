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
    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
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
