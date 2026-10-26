package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.compose
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

/** Real service/owner calls with controlled raw receipts; Glance preferences hold the visible result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCommandFeedbackTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private lateinit var id: GlanceId
    private var frozen: SourcedStatus? = null
    private var beforeSend: suspend () -> Unit = {}
    private var writes = 0
    private val stateKey = stringPreferencesKey("widget_feedback_state")

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { beforeSend(); writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            override val sourcedStatus: Flow<SourcedStatus?>
                get() = frozen?.let { cached -> flow { emit(cached); awaitCancellation() } } ?: demo.sourcedStatus
            override fun latestSourcedStatus(): SourcedStatus? = frozen ?: demo.latestSourcedStatus()
        }
        graph = fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 25
        graph.actions.freshStatusMs = 25
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, QuickControlsReceiver::class.java) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(73, info)
        id = GlanceAppWidgetManager(app).getGlanceIdBy(73)
    }

    private suspend fun displayed(prefs: Preferences): List<String> {
        fun text(view: View): List<String> = (if (view is TextView) listOf(view.text.toString(),
            view.contentDescription?.toString()?.substringBefore(" — ")?.substringBefore(". ").orEmpty()) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { text(view.getChildAt(it)) } else emptyList()
        val views = QuickControlsWidget().compose(app, size = DpSize(200.dp, 128.dp), state = prefs)
        return text(views.apply(app, FrameLayout(app)))
    }
    private suspend fun state(): String? = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)[stateKey]
    private suspend fun awaitState(expected: String) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline && state() != expected) delay(5)
        assertEquals(expected, state())
    }
    private suspend fun tap(command: Command, profile: String? = null) {
        val request = widgetRequest(app, WidgetAction(command.name, command, profile))
        WidgetActionCallback().onAction(app, id, widgetParameters(request, WidgetActionCallback.QUICK))
    }

    @Test fun pendingIsPublishedBeforeTheFirstBedJetWrite() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        beforeSend = { entered.complete(Unit); awaitCancellation() }
        val task = async(Dispatchers.Default) { tap(Command.COOL) }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals("Pending must be visible before sending", "PENDING", state())
            assertTrue("Real RemoteViews show Pending", displayed(getAppWidgetState(app, PreferencesGlanceStateDefinition, id)).contains("Pending"))
            assertEquals(0, writes)
        } finally { task.cancelAndJoin(); graphs.finish() }
    }

    @Test fun aNewMatchingReceiptConfirmsInPlaceWithWidgetOrigin() = runBlocking {
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        try {
            tap(Command.COOL)
            awaitState("CONFIRMED")
            assertTrue("Real RemoteViews show Confirmed", displayed(getAppWidgetState(app, PreferencesGlanceStateDefinition, id)).contains("Confirmed"))
            assertEquals(Mode.COOL, demo.status.value?.mode)
            val result = withTimeout(5_000) { outcome.await() }
            assertEquals(OutcomeKind.CONFIRMED, result.kind)
            assertEquals(CommandOrigin.WIDGET, result.origin)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { outcome.cancelAndJoin() }
    }

    @Test fun aMatchingCachedStatusCannotConfirmANewWidgetRequest() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.COOL)).kind)
        frozen = demo.latestSourcedStatus()
        val boundary = frozen!!.receiptOrder
        val before = writes
        tap(Command.COOL)
        awaitState("UNKNOWN")
        assertTrue("The command really wrote to the simulated BedJet", writes > before)
        assertEquals("Only the pre-request report was visible", boundary, graph.link.latestSourcedStatus()?.receiptOrder)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun aRefusedStartShowsFailedWithoutClaimingConfirmation() = runBlocking {
        tap(Command.START_NIGHT, "deleted-profile")
        awaitState("FAILED")
        assertEquals(0, writes)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun cancellingWhileAWriteIsUnsettledChangesPendingToUnknown() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        beforeSend = { entered.complete(Unit); awaitCancellation() }
        val task = async(Dispatchers.Default) { tap(Command.WARM) }
        withTimeout(5_000) { entered.await() }
        graphs.finish()
        task.cancelAndJoin()
        assertEquals("Cancellation must settle the widget as well as the owner", "UNKNOWN", state())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun anOffTappedBeforeANightStartsCannotEndThatNewNightWhileQueued() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
        val previous = graph.repository.activeNight.value!!
        graph.repository.setActiveNight(null)
        writes = 0
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.Default) { graph.actions.exclusive { acquired.complete(Unit); release.await() } }
        var off: Deferred<Unit>? = null
        try {
            withTimeout(5_000) { acquired.await() }
            off = async(Dispatchers.Default) { tap(Command.OFF) }
            withTimeout(5_000) { while (graph.actions.busy.value.queuedCount == 0) delay(1) }
            val newlyStarted = previous.copy(revision = previous.revision + 1)
            graph.repository.setActiveNight(newlyStarted)
            release.complete(Unit)
            withTimeout(5_000) { owner.await(); off.await() }
            val deadline = System.nanoTime() + 3_000_000_000L
            while ((graph.actions.busy.value.runningLabel != null || graph.actions.busy.value.queuedCount != 0) && System.nanoTime() < deadline) delay(5)
            assertEquals("An unconfirmed stale Off must not write", 0, writes)
            assertEquals(newlyStarted.identity, graph.repository.activeNight.value?.identity)
            awaitState("FAILED")
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { release.complete(Unit); owner.cancelAndJoin(); off?.cancelAndJoin() }
    }

    @Test fun aLaterPendingTapCannotBeOverwrittenByAnEarlierResult() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        beforeSend = {
            when (graph.actions.busy.value.runningLabel) {
                app.getString(io.github.willzy12h.nocturne.R.string.runtime_command_cool) -> if (!firstEntered.isCompleted) {
                    firstEntered.complete(Unit); releaseFirst.await()
                }
                app.getString(io.github.willzy12h.nocturne.R.string.runtime_command_warm) -> {
                    secondEntered.complete(Unit); awaitCancellation()
                }
            }
        }
        val beforeJobs = graph.scope.coroutineContext[Job]!!.children.toSet()
        val first = async(Dispatchers.Default) { tap(Command.COOL) }
        var second: Deferred<Unit>? = null
        try {
            withTimeout(5_000) { firstEntered.await() }
            val firstDelivery = graph.scope.coroutineContext[Job]!!.children.filterNot { it in beforeJobs }.toList()
            second = async(Dispatchers.Default) { tap(Command.WARM) }
            withTimeout(5_000) { while (graph.actions.busy.value.queuedCount == 0) delay(1) }
            releaseFirst.complete(Unit)
            withTimeout(5_000) { first.await(); secondEntered.await(); firstDelivery.joinAll() }
            assertEquals("The newer Warm tap is still pending", "PENDING", state())
        } finally {
            releaseFirst.complete(Unit)
            first.cancelAndJoin()
            second?.cancelAndJoin()
            graphs.finish()
        }
    }

    @Test fun theCallbackReleasesItsReceiverWhileTheOwnedCommandStillWaits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        beforeSend = { entered.complete(Unit); awaitCancellation() }
        val callback = async(Dispatchers.Default) { tap(Command.COOL) }
        try {
            withTimeout(5_000) { entered.await() }
            val returned = withTimeoutOrNull(1_000) { callback.await(); true } ?: false
            assertTrue("The broadcast must return after handoff, not wait for the BedJet receipt", returned)
            assertEquals("PENDING", state())
        } finally { callback.cancelAndJoin(); graphs.finish() }
    }

    @Test fun aCompletedReceiptLeavesNoLiveDeliveryOrDeadlineChild() = runBlocking {
        val request = widgetRequest(app, WidgetAction("Cool", Command.COOL))
        val delivery = WidgetDelivery.submit(app, id, WidgetActionCallback.QUICK, request)
        awaitState("CONFIRMED")
        // LinkSessions legitimately owns a separate 30-second release grace job. Inspect this delivery only.
        val completed = withTimeoutOrNull(3_000) { delivery.join(); true } ?: false
        assertTrue("Settling the result must cancel its one-shot deadline", completed)
        assertFalse("The completed delivery has no live deadline child", delivery.children.any { it.isActive })
    }

    @Test fun pendingFromAnEarlierProcessRendersUnknown() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        beforeSend = { entered.complete(Unit); awaitCancellation() }
        val callback = async(Dispatchers.Default) { tap(Command.COOL) }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals("PENDING", state())
            val prefs = getAppWidgetState(app, PreferencesGlanceStateDefinition, id).toMutablePreferences()
            prefs[stringPreferencesKey("widget_feedback_process")] = "previous-process"
            val text = displayed(prefs)
            assertTrue(text.toString(), text.contains("Unknown"))
            assertFalse(text.toString(), text.contains("Pending"))
        } finally { callback.cancelAndJoin(); graphs.finish() }
    }

    @Test fun pendingPastItsBoundRendersUnknownAtTheNextRender() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        beforeSend = { entered.complete(Unit); awaitCancellation() }
        val callback = async(Dispatchers.Default) { tap(Command.COOL) }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals("PENDING", state())
            graph.clock = Clock.offset(graph.clock, java.time.Duration.ofMillis(io.github.willzy12h.nocturne.service.BedJetService.RUN_OWNED_MS + 1))
            val text = displayed(getAppWidgetState(app, PreferencesGlanceStateDefinition, id))
            assertTrue(text.toString(), text.contains("Unknown"))
            assertFalse(text.toString(), text.contains("Pending"))
        } finally { callback.cancelAndJoin(); graphs.finish() }
    }

    @Test fun aConfirmedResultForAnotherTargetIsNotShownAsCurrentConfirmation() = runBlocking {
        tap(Command.COOL)
        awaitState("CONFIRMED")
        val prefs = getAppWidgetState(app, PreferencesGlanceStateDefinition, id).toMutablePreferences()
        prefs[stringPreferencesKey("widget_feedback_target")] = "ble:another-bedjet"
        val text = displayed(prefs)
        assertFalse(text.toString(), text.contains("Confirmed"))
        assertTrue(text.toString(), text.contains("Unknown"))
    }
}
