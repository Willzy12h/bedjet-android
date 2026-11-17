package io.github.willzy12h.nocturne.night

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Start-up and clock changes often arrive as a burst; Nocturne repairs the night at most twice for one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BroadcastBurstTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app, DemoBedJet(now = { graph.now() })) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        App::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Timed out pumping main looper", check())
    }

    @Test fun fiveTimeChangesWhileBusyRepairTwice() {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val held = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        until { entered.isCompleted }
        val receiver = BootReceiver()
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_CHANGED), Context.RECEIVER_EXPORTED)
        try {
            repeat(5) { app.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED)) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("One repair waits on the busy night; the rest share the next", 1, graph.repairs.runsStarted)
            release.complete(Unit)
            // Settled: no broadcast is still in flight or holding its place in the queue.
            until { held.isCompleted && graph.repository.operationAdmission.isIdle() }
            assertEquals(2, graph.repairs.runsStarted)
        } finally {
            app.unregisterReceiver(receiver)
        }
    }
}
