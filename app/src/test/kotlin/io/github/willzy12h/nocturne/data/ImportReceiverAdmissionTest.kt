package io.github.willzy12h.nocturne.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.night.LocalDelivery
import io.github.willzy12h.nocturne.night.NightAlarmReceiver
import io.github.willzy12h.nocturne.night.NightAlarms
import kotlinx.coroutines.*
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportReceiverAdmissionTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var originalScope: CoroutineScope
    private val queued = ConcurrentLinkedQueue<Runnable>()
    private val receiverScope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
    })
    private val receiver = NightAlarmReceiver()
    private var registered = false

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app, DemoBedJet()) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        originalScope = graph.scope
        ReflectionHelpers.setField(graph, "scope", receiverScope)
    }

    @After fun finish() {
        receiverScope.cancel()
        until { receiverScope.coroutineContext[Job]!!.isCompleted }
        if (registered) app.unregisterReceiver(receiver)
        ReflectionHelpers.setField(graph, "scope", originalScope)
    }

    private fun drain() {
        repeat(100) { (queued.poll() ?: return).run() }
    }
    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); drain(); Thread.yield() }
        assertTrue("Receiver work did not reach its expected state", check())
    }
    private fun send(action: String, night: ActiveNight? = null) {
        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        registered = true
        app.sendBroadcast(Intent(action).putExtra(NightAlarms.EXTRA_NIGHT, night?.recordId)
            .putExtra(NightAlarms.EXTRA_REVISION, night?.revision ?: -1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("The receiver must have queued its coroutine", queued.isNotEmpty())
    }
    private suspend fun active(): ActiveNight {
        val now = graph.now()
        val night = ActiveNight("received-night", "profile", now.minusHours(7).toString(), now.plusHours(1).toString(),
            false, 2, now.minusMinutes(1).toString(), now.plusHours(2).toString(), deviceId = "demo", confidence = Confidence.CONFIRMED)
        graph.repository.setActiveNight(night)
        return night
    }

    @Test fun aReceiverAlreadyQueuedBeforeItsCoroutineRunsRefusesImport() {
        send(NightAlarms.ACTION_LOCAL_RETRY)
        val slipped = graph.repository.operationAdmission.tryImport()
        try { assertNull("An accepted receiver delivery is queued work before launch runs", slipped) }
        finally { slipped?.close() }
        until { graph.repository.operationAdmission.isIdle() && queued.isEmpty() }
    }

    @Test fun cancellationBeforeTheReceiverBodyRunsReleasesTheQueuedAdmission() {
        send(NightAlarms.ACTION_LOCAL_RETRY)
        assertFalse("The receiver must be admitted synchronously", graph.repository.operationAdmission.isIdle())
        receiverScope.cancel()
        until { receiverScope.coroutineContext[Job]!!.isCompleted }
        assertTrue(graph.repository.operationAdmission.isIdle())
        runBlocking { graph.repository.restore(graph.repository.bundle()) }
    }

    @Test fun aDeliveredWakeDuringImportWaitsThenRunsWithoutLettingAnotherImportJumpAhead() = runBlocking {
        val night = active()
        val repo = graph.repository
        val importing = requireNotNull(repo.operationAdmission.tryImport())
        try {
            send(NightAlarms.ACTION_WAKE_UP, night)
            drain()
            // Fence any writes the baseline wrongly submitted before import completed.
            repeat(3) { repo.updateSettings { it }; drain() }
            assertTrue("The received delivery waits outside repository writes while import owns admission", repo.runtime.value.localDeliveries.isEmpty())
            assertNull("Wake publication cannot run during the import", repo.runtime.value.wakeShown)
            importing.close()
            val jumped = repo.operationAdmission.tryImport()
            try { assertNull("The already-delivered wake keeps its queue position when import finishes", jumped) }
            finally { jumped?.close() }
            until { repo.runtime.value.wakeShown == night.identity && repo.operationAdmission.isIdle() }
            assertTrue(repo.runtime.value.localDeliveries.isEmpty())
        } finally { importing.close() }
    }

    @Test fun aDurableLocalDeliveryStillCountsAsQueuedWorkWithoutAnInMemoryOwner() = runBlocking {
        val night = active()
        val repo = graph.repository
        repo.updateRuntime { it.copy(localDeliveries = listOf(LocalDelivery("queued", NightAlarms.ACTION_WAKE_UP, night.identity, graph.now().toString()))) }
        val before = repo.bundle()
        val error = runCatching { repo.restore(before) }.exceptionOrNull()
        assertTrue("The durable queued delivery must refuse import", error is BackupException)
        assertEquals(before.copy(createdAt = ""), repo.bundle().copy(createdAt = ""))
        assertEquals(0, repo.snapshotList.value.count { it.manual })
    }
}
