package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.NightActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A tap is already pending while AppViewModel waits for a prior profile save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportUiQueueAdmissionTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var holdSave = false

    @After fun clear() { release.countDown(); models.clear() }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Expected UI queue state was not reached", check())
    }

    @Test fun aTapWaitingForASaveBlocksImportBeforeServiceOrOwnerAdmission() {
        val graph = graphs.fresh { Graph(app, DemoBedJet(), repositoryFactory = { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                if (holdSave && file.name == "profiles.json") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                Repository.writeDurably(file, bytes)
            }, now = now)
        }) }
        ReflectionHelpers.setField(app, "graph", graph)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = true) } }
        val model = AppViewModel(app).also { models.put("queued-import", it) }
        holdSave = true
        model.saveProfile(graph.repository.profiles.value.first().copy(id = "queued-save"))
        until { entered.count == 0L }
        model.run(NightActions.Command.STOP_TRACKING)
        until { model.busy.value }
        assertEquals(0, graph.actions.busy.value.queuedCount)
        assertNull(graph.actions.busy.value.runningLabel)
        val attempt = graph.scope.async(Dispatchers.IO) { runCatching { graph.repository.restore(graph.repository.bundle()) } }
        try {
            val result = runBlocking { withTimeoutOrNull(1_000) { attempt.await() } }
            assertNotNull("A tap waiting for a save must refuse import immediately, not join the writer queue", result)
            assertTrue(result!!.exceptionOrNull() is BackupException)
        } finally { release.countDown(); until { attempt.isCompleted }; runBlocking { attempt.await() } }
    }
}
