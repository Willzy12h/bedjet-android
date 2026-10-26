package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.platform.PlatformGraphFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** R5: Pending feedback is durable before delivery, but command admission must precede that wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetImportAdmissionTest : PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var id: GlanceId
    private var writes = 0

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        // A transport fixture, not an explicit isolated demo session: restore admission remains testable.
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 25
        graph.actions.freshStatusMs = 25
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, QuickControlsReceiver::class.java) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(73, info)
        id = GlanceAppWidgetManager(app).getGlanceIdBy(73)
    }

    private suspend fun submit(): Job = WidgetDelivery.submit(app, id, WidgetActionCallback.QUICK,
        widgetRequest(app, WidgetAction("Cool", Command.COOL)))

    /** Hold the actual Glance transaction so WidgetFeedback.pending cannot complete. */
    private suspend fun CoroutineScope.holdFeedback(): Pair<CompletableDeferred<Unit>, Job> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = launch(Dispatchers.Default) {
            updateAppWidgetState(app, id) { entered.complete(Unit); release.await() }
        }
        withTimeout(5_000) { entered.await() }
        return release to held
    }

    @Test fun restoreIsRefusedWhileWidgetTapWaitsForPendingFeedback() = runBlocking {
        val incoming = graph.repository.bundle().copy(profiles = emptyList())
        val before = graph.repository.profiles.value
        val (release, held) = holdFeedback()
        val submission = async(start = CoroutineStart.UNDISPATCHED) { submit() }
        try {
            assertFalse("The widget is waiting for the Pending state transaction", submission.isCompleted)
            assertEquals(0, graph.actions.busy.value.queuedCount)
            assertNull(graph.actions.busy.value.runningLabel)
            val refusal = runCatching { graph.repository.restore(incoming) }.exceptionOrNull()
            assertTrue("A submitted widget command must refuse restore before reaching the owner", refusal is BackupException)
            assertEquals(before, graph.repository.profiles.value)
            assertEquals(0, writes)
        } finally {
            submission.cancel()
            release.complete(Unit)
            withTimeout(5_000) { submission.join(); held.join() }
        }
        assertTrue("Cancelling before handoff releases the widget admission", graph.repository.operationAdmission.isIdle())
    }

    @Test fun anImportActiveAtSubmissionRefusesTheWidgetEvenIfItEndsBeforeFeedbackUnblocks() = runBlocking {
        val importLease = checkNotNull(graph.repository.operationAdmission.tryImport())
        val (release, held) = holdFeedback()
        val submission = async(start = CoroutineStart.UNDISPATCHED) { submit() }
        try {
            assertFalse(submission.isCompleted)
            importLease.close()
            release.complete(Unit)
            withTimeout(5_000) { submission.await().join(); held.join() }
            assertEquals("The import refusal is captured at the tap, before the feedback wait", 0, writes)
            val prefs = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)
            assertEquals("FAILED", prefs[stringPreferencesKey("widget_feedback_state")])
            assertEquals(app.getString(R.string.import_command_blocked), prefs[stringPreferencesKey("widget_feedback_message")])
            assertTrue(graph.repository.operationAdmission.isIdle())
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            importLease.close()
            submission.cancel()
            release.complete(Unit)
            withTimeout(5_000) { submission.join(); held.join() }
        }
    }

    @Test fun cancellingThePendingFeedbackWaitReleasesCommandAdmission() = runBlocking {
        val (release, held) = holdFeedback()
        val submission = async(start = CoroutineStart.UNDISPATCHED) { submit() }
        try {
            assertFalse("The command must already be admitted while Pending feedback waits", graph.repository.operationAdmission.isIdle())
        } finally {
            submission.cancel()
            release.complete(Unit)
            withTimeout(5_000) { submission.join(); held.join() }
        }
        assertTrue("A cancelled feedback wait must not block later imports", graph.repository.operationAdmission.isIdle())
        assertEquals(0, writes)
    }

    @Test fun aCancelledGraphBeforeDeliveryHandoffReleasesCommandAdmission() = runBlocking {
        val (release, held) = holdFeedback()
        val submission = async(start = CoroutineStart.UNDISPATCHED) { submit() }
        try {
            assertFalse("Admission belongs to the submission before the application job exists", graph.repository.operationAdmission.isIdle())
            graph.scope.cancel()
            release.complete(Unit)
            withTimeout(5_000) { submission.await().join(); held.join() }
            assertTrue("A job cancelled before it starts must release the submission lease", graph.repository.operationAdmission.isIdle())
            assertEquals(0, writes)
        } finally {
            submission.cancel()
            release.complete(Unit)
            withTimeout(5_000) { submission.join(); held.join() }
        }
    }
}
