package io.github.willzy12h.nocturne.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.service.ServiceStartContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** R5: protect restore at the actual request boundary, before the UI's busy state exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportAdmissionTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var context: ServiceStartContext

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app, DemoBedJet()) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        context = ServiceStartContext(app)
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Expected request state was not reached", check())
    }

    private fun assertRestoreRefused() {
        val before = graph.repository.profiles.value
        val incoming = graph.repository.bundle().copy(profiles = emptyList())
        assertThrows(BackupException::class.java) { runBlocking { graph.repository.restore(incoming) } }
        assertEquals("Refusal must leave every profile intact", before, graph.repository.profiles.value)
    }

    @Test fun importIsRefusedWhileAnOwnerCommandWaitsInTheQueue() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }
        val queued = graph.scope.launch { graph.actions.perform(NightActions.Command.STOP_TRACKING) }
        try {
            until { graph.actions.busy.value.queuedCount == 1 }
            assertRestoreRefused()
        } finally {
            queued.cancel(); release.complete(Unit)
            until { held.isCompleted && queued.isCompleted }
        }
    }

    @Test fun importIsRefusedBeforeUiHandoffReachesNightActionsAndCancellationReleasesIt() {
        val caller = graph.scope.async { BedJetService.runOwned(context, notifyFailures = false) { Unit } }
        try {
            until { context.delivered != null }
            assertEquals(0, graph.actions.busy.value.queuedCount)
            assertNull(graph.actions.busy.value.runningLabel)
            assertRestoreRefused()
        } finally { caller.cancel(); until { caller.isCompleted } }
        runBlocking { graph.repository.restore(graph.repository.bundle()) }
    }

    @Test fun importIsRefusedWhileAReceiverCommandAwaitsServiceDelivery() {
        assertTrue(BedJetService.start(context, NightActions.Command.STOP_TRACKING, notifyFailures = false))
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            assertEquals(0, graph.actions.busy.value.queuedCount)
            assertRestoreRefused()
        } finally {
            controller.get().onStartCommand(context.delivered, 0, 1)
            until { shadowOf(controller.get()).isStoppedBySelf }
            controller.destroy()
        }
        runBlocking { graph.repository.restore(graph.repository.bundle()) }
    }

    @Test fun importIsRefusedWhileAHardwareOriginalRestoreIsRunning() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val demo = object : io.github.willzy12h.nocturne.core.link.BedJetLink by DemoBedJet() {
            override suspend fun send(bytes: ByteArray) { entered.complete(Unit); release.await() }
        }
        graph = graphs.fresh { Graph(app, demo) }
        ReflectionHelpers.setField(app, "graph", graph)
        runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = true) }
            graph.repository.recordOriginalSlot("demo", 0, null)
        }
        val restore = graph.scope.launch { graph.actions.restoreOriginal(0) }
        try {
            until { entered.isCompleted }
            assertRestoreRefused()
        } finally { release.complete(Unit); restore.cancel(); until { restore.isCompleted } }
    }
    @Test fun alarmObservationCannotQueueAWriteWhileImportOwnsAdmission() = runBlocking {
        val now = graph.now()
        val repo = graph.repository
        repo.setActiveNight(io.github.willzy12h.nocturne.core.night.ActiveNight("live", "profile", now.toString(),
            now.plusHours(8).toString(), false, 2, now.plusHours(7).toString(), now.plusHours(9).toString(),
            deviceId = "ble:AA:BB:CC:DD:EE:FF", confidence = io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED))
        val before = repo.runtime.value
        val admission = requireNotNull(repo.operationAdmission.tryImport())
        try {
            val outcome = graph.actions.submitAlarmChange()
            assertEquals(io.github.willzy12h.nocturne.night.OutcomeKind.REFUSED, outcome.kind)
            assertEquals("Import exclusion includes alarm observations before their later hardware retry", before, repo.runtime.value)
        } finally { admission.close() }
    }

}
