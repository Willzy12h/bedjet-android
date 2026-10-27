package io.github.willzy12h.nocturne.ui

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/** A real backup review belongs to the real session in which the person requested it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DemoImportPreviewInvalidationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private val pausedCompletion = PausedCompletion()
    private lateinit var graph: Graph
    private lateinit var originalScope: CoroutineScope
    private lateinit var model: AppViewModel
    private lateinit var before: BackupBundle
    private lateinit var filesBefore: Map<String, String>
    private var manualSnapshotsBefore = 0
    private var input = ""
    @Volatile private var holdReadCompletion = false

    @Before fun setup() {
        graph = graphs.fresh { Graph(app, repositoryFactory = { scope, now ->
            Repository(app, scope, openInput = {
                object : ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)) {
                    override fun close() {
                        super.close()
                        // The next graph-scope dispatch returns the completed read to AppViewModel. The
                        // repository writer is released before that dispatch, so real demo actions can run.
                        if (holdReadCompletion) {
                            holdReadCompletion = false
                            pausedCompletion.arm()
                        }
                    }
                }
            }, now = now)
        }).also { it.clock = Clock.fixed(Instant.parse("2026-10-26T21:00:00Z"), ZoneOffset.UTC) } }
        ReflectionHelpers.setField(app, "graph", graph)
        originalScope = graph.scope
        // Share the original SupervisorJob: GraphLifecycle still owns and finishes every graph child.
        ReflectionHelpers.setField(graph, "scope", CoroutineScope(originalScope.coroutineContext + pausedCompletion))
        runBlocking {
            val repo = graph.repository
            repo.updateSettings { it.copy(demoMode = false, targetDevice = REAL, rememberedBedJets = listOf(REAL)) }
            val profile = repo.profiles.value.first().copy(id = "real-personal-profile", name = "Keep real personal profile")
            repo.saveProfile(profile)
            repo.addRecord(NightRecord("real-history", profile.id, profile.name,
                graph.now().minusHours(8).toString(), graph.now().minusHours(1).toString(), false, deviceId = REAL))
            repo.recordOriginalSlot(REAL, 2, null)
        }
        model = AppViewModel(app).also { models.put("demo-import", it) }
        shadowOf(Looper.getMainLooper()).idle()
        // Fence startup storage work before comparing the real repository and its durable files.
        runBlocking { graph.repository.updateSettings { it } }
        before = graph.repository.bundle()
        filesBefore = userFiles()
        manualSnapshotsBefore = graph.repository.snapshotList.value.count { it.manual }
        input = BackupCodec.encode(before.copy(
            profiles = listOf(before.profiles.first().copy(id = "foreign-import", name = "Unreviewed file profile")),
            settings = before.settings.copy(fahrenheit = !before.settings.fahrenheit), history = emptyList()))
    }

    @After fun finish() {
        pausedCompletion.release()
        models.clear()
        ReflectionHelpers.setField(graph, "scope", originalScope)
    }

    @Test fun enteringDemoInvalidatesTheDisplayedRealPreviewAndItsExactApplyToken() {
        val staged = stageFile()
        until { staged.isCompleted }
        val reviewed = requireNotNull(model.importPreview.value)
        assertTrue(reviewed.canApply)

        demo(true)
        shadowOf(Looper.getMainLooper()).idle()
        val previewSeenInDemo = model.importPreview.value
        demo(false)
        shadowOf(Looper.getMainLooper()).idle()
        val previewSeenAfterExit = model.importPreview.value

        // Invoke the exact old token, even if a stale UI callback survived the session transition.
        val message = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        model.confirmImport(reviewed)
        until { message.isCompleted && !model.importApplying.value }
        assertNull("Entering disposable demo must remove a displayed real backup review", previewSeenInDemo)
        assertNull("Leaving demo must not resurrect the old review", previewSeenAfterExit)
        assertEquals(app.getString(R.string.import_preview_changed), runBlocking { message.await() })
        assertRealDataUnchanged()
    }

    @Test fun aHeldReadCannotPublishItsOldReviewAfterDemoEntryAndReturnToReal() {
        val staged = holdSelectedFileRead()
        demo(true)
        shadowOf(Looper.getMainLooper()).idle() // Give the demo observer its true emission.
        assertNull(model.importPreview.value)
        demo(false)
        shadowOf(Looper.getMainLooper()).idle() // Give the observer its return-to-real emission too.
        pausedCompletion.release()
        until { staged.isCompleted }

        assertNull("A read requested before demo must not publish a review after returning to real", model.importPreview.value)
        assertFalse(model.importApplying.value)
        assertRealDataUnchanged()
    }

    @Test fun aHeldReadIsInvalidatedEvenWhenMainConflatesRapidDemoEntryAndExit() {
        val staged = holdSelectedFileRead()
        val publication = graph.repository.settingsPublication.value
        // Do not idle Main between these public boundaries: demoMode can return to its observed false value
        // before its StateFlow collector runs. A monotonic session revision still records both transitions.
        demo(true)
        demo(false)
        assertFalse(graph.repository.isDemoSession)
        assertTrue("Both disposable-session boundaries must have occurred",
            graph.repository.settingsPublication.value >= publication + 2)
        shadowOf(Looper.getMainLooper()).idle()
        pausedCompletion.release()
        until { staged.isCompleted }

        assertNull("A conflated enter/exit cannot revive the pre-demo import request", model.importPreview.value)
        assertFalse(model.importApplying.value)
        assertRealDataUnchanged()
    }

    private fun stageFile(): Job {
        model.import(Uri.parse("content://test/real-backup.json"))
        // Observe completion without waiting for a message: invalidated reads intentionally publish neither.
        return requireNotNull(ReflectionHelpers.getField<Job?>(model, "lastSave"))
    }

    private fun holdSelectedFileRead(): Job {
        holdReadCompletion = true
        val staged = stageFile()
        until { pausedCompletion.isHeld }
        assertFalse(staged.isCompleted)
        assertNull(model.importPreview.value)
        assertTrue("Reading has not acquired apply admission", graph.repository.operationAdmission.isIdle())
        return staged
    }

    private fun demo(enabled: Boolean) {
        val outcome = runBlocking { withTimeout(5_000) { graph.actions.setDemoMode(enabled) } }
        assertEquals(outcome.message, OutcomeKind.LOCAL, outcome.kind)
        assertEquals(enabled, graph.repository.isDemoSession)
    }

    private fun assertRealDataUnchanged() {
        assertFalse(graph.repository.isDemoSession)
        assertEquals(before.copy(createdAt = ""), graph.repository.bundle().copy(createdAt = ""))
        assertEquals("Demo transitions and stale reviews cannot change real user files", filesBefore, userFiles())
        assertEquals("No rejected review creates a before-import snapshot", manualSnapshotsBefore,
            graph.repository.snapshotList.value.count { it.manual })
    }

    private fun userFiles(): Map<String, String> = app.filesDir.walkTopDown().filter { it.isFile &&
        it.name in setOf("profiles.json", "settings.json", "history.json", "slot-backups.json", "runtime.json",
            "active-night.json", "automation.json", "notices.json")
    }.associate { it.relativeTo(app.filesDir).path to it.readText() }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("The import/demo fixture did not reach its expected state", check())
    }

    private class PausedCompletion : CoroutineDispatcher() {
        private var armed = false
        @Volatile private var held: Pair<CoroutineContext, Runnable>? = null
        val isHeld: Boolean get() = held != null
        @Synchronized fun arm() { armed = true }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (armed) {
                    armed = false
                    check(held == null)
                    held = context to block
                    return
                }
            }
            Dispatchers.Main.dispatch(context, block)
        }

        fun release() {
            val pending = synchronized(this) { armed = false; held.also { held = null } } ?: return
            Dispatchers.Main.dispatch(pending.first, pending.second)
        }
    }

    companion object { private const val REAL = "ble:AA:BB:CC:DD:EE:01" }
}
