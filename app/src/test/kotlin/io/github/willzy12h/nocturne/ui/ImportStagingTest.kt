package io.github.willzy12h.nocturne.ui

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportStagingTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private lateinit var graph: Graph
    private lateinit var model: AppViewModel
    private var input = ""

    @Before fun setup() {
        graph = graphs.fresh { Graph(app, repositoryFactory = { scope, now ->
            Repository(app, scope, openInput = { input.byteInputStream() }, now = now)
        }) }
        ReflectionHelpers.setField(app, "graph", graph)
        model = AppViewModel(app).also { models.put("import", it) }
    }

    @After fun clear() { models.clear() }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Expected import request state was not reached", check())
    }

    @Test fun choosingAnImportFileDoesNotApplyItBeforeTheReviewIsAccepted() {
        val before = graph.repository.bundle()
        input = BackupCodec.encode(before.copy(profiles = emptyList(), settings = before.settings.copy(fahrenheit = !before.settings.fahrenheit)))
        val message = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        model.import(Uri.parse("content://test/backup.json"))
        until { message.isCompleted }
        assertEquals("Reading a selected file must only stage the preview", before.profiles, graph.repository.profiles.value)
        assertEquals(before.settings, graph.repository.settings.value)
        assertEquals(before.history, graph.repository.history.value)
        assertEquals(before.slotBackups, graph.repository.slotBackups.value)
        assertTrue("The before-import recovery snapshot belongs to confirmation, not preview", graph.repository.snapshotList.value.none { it.manual })
    }

    @Test fun choosingASnapshotDoesNotApplyItBeforeTheReviewIsAccepted() {
        runBlocking { graph.repository.snapshotNow("Older backup") }
        val snapshot = graph.repository.snapshotList.value.first { it.manual }
        runBlocking { graph.repository.saveProfile(graph.repository.profiles.value.first().copy(id = "local-only", name = "Keep until reviewed")) }
        val before = graph.repository.profiles.value
        val message = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        model.restoreSnapshot(snapshot.id)
        until { message.isCompleted }
        assertEquals("Snapshot restore must stage the same preview as a file import", before, graph.repository.profiles.value)
        assertEquals(1, graph.repository.snapshotList.value.count { it.manual })
    }
}
