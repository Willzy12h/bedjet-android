package io.github.willzy12h.nocturne.widget

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.compose
import androidx.activity.ComponentActivity
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetConfirmationTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private lateinit var id: GlanceId
    private lateinit var original: ActiveNight
    private var writes = 0
    private val stateKey = stringPreferencesKey("widget_feedback_state")
    private val confirmationClass = "io.github.willzy12h.nocturne.widget.WidgetConfirmationActivity"

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
        original = graph.repository.activeNight.value!!
        writes = 0
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, QuickControlsReceiver::class.java) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(74, info)
        id = GlanceAppWidgetManager(app).getGlanceIdBy(74)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private suspend fun confirmation(command: Command): Intent {
        val page = when (command) {
            Command.OFF, Command.STOP_TRACKING -> "device:0"
            Command.COOL, Command.WARM -> "quick:0"
            else -> "night:0"
        }
        val label = widgetActions(app).first { it.command == command && it.profileId == null }.label
        val remote = QuickControlsWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
            state = preferencesOf(QuickControlsWidget.KEY_PAGE to page))
        val button = descendants(remote.apply(app, FrameLayout(app))).filterIsInstance<Button>().firstOrNull { it.text.toString() == label }
        assertNotNull("The rendered widget must expose $label", button)
        button!!.performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("A tracked-night $command tap must directly request its confirmation activity", intent)
        assertEquals("Only the small widget confirmation route may be opened", confirmationClass, intent!!.component?.className)
        return intent
    }
    private fun open(intent: Intent): ActivityController<out ComponentActivity> = Robolectric.buildActivity(
        Class.forName(intent.component!!.className).asSubclass(ComponentActivity::class.java), intent).setup()
    private suspend fun feedback(): String? = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)[stateKey]
    private suspend fun waitForFeedback(expected: String) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (feedback() == expected) return
            delay(5)
        }
        assertEquals("The widget must receive the result after the overlay closes", expected, feedback())
    }

    @Test fun trackedOffUsesAnExcludedTranslucentActivityWithoutTheAppTask() = runBlocking {
        val intent = confirmation(Command.OFF)
        val info = app.packageManager.getActivityInfo(intent.component!!, 0)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertNull("An empty manifest taskAffinity is parsed as no task affinity", info.taskAffinity)
        val theme = app.resources.newTheme().apply { applyStyle(info.theme, true) }
        val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        try { assertTrue(attrs.getBoolean(0, false)) } finally { attrs.recycle() }
        assertEquals(0, writes)
        assertEquals(original.identity, graph.repository.activeNight.value?.identity)
    }

    @Test fun trackedStopUsesTheSameSmallConfirmationOnly() = runBlocking {
        confirmation(Command.STOP_TRACKING)
        assertEquals(0, writes)
        assertEquals(original.identity, graph.repository.activeNight.value?.identity)
    }

    @Test fun replacementStartAndManualControlsKeepTheirConfirmationGuard() = runBlocking {
        for (command in listOf(Command.START_NIGHT, Command.BOOST, Command.TURBO, Command.COOL, Command.WARM)) {
            confirmation(command)
            assertEquals("No replacement runs before approval", 0, writes)
            assertEquals(original.identity, graph.repository.activeNight.value?.identity)
        }
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun cancellingTheOffDialogSendsNothing() = runBlocking {
        val activity = open(confirmation(Command.OFF))
        try {
            assertEquals(0, writes)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.get().isFinishing)
            assertEquals(0, writes)
            assertEquals(original.identity, graph.repository.activeNight.value?.identity)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun confirmingOffEndsTheCapturedNightAndClosesTheOverlay() = runBlocking {
        val activity = open(confirmation(Command.OFF))
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitForFeedback("CONFIRMED")
            assertTrue("Confirmation returns to the launcher", activity.get().isFinishing)
            assertEquals(1, writes)
            assertNull(graph.repository.activeNight.value)
            assertEquals(Mode.STANDBY, demo.status.value?.mode)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun confirmedStopTrackingIsLocalAndDoesNotSendOff() = runBlocking {
        val before = demo.status.value
        val activity = open(confirmation(Command.STOP_TRACKING))
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitForFeedback("LOCAL")
            assertNull(graph.repository.activeNight.value)
            assertEquals(0, writes)
            assertEquals(before, demo.status.value)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun aChangedNightWhileOffConfirmationIsOpenIsRefused() = runBlocking {
        val activity = open(confirmation(Command.OFF))
        val newer = original.copy(revision = original.revision + 1)
        try {
            graph.repository.setActiveNight(newer)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitForFeedback("FAILED")
            assertEquals(0, writes)
            assertEquals(newer.identity, graph.repository.activeNight.value?.identity)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun aChangedTargetWhileOffConfirmationIsOpenIsRefused() = runBlocking {
        val activity = open(confirmation(Command.OFF))
        try {
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:00:11:22:33:44:55") }
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitForFeedback("FAILED")
            assertEquals(0, writes)
            assertEquals(original.identity, graph.repository.activeNight.value?.identity)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun aNightChangedAfterApprovalButBeforeOwnerAdmissionIsAlsoRefused() = runBlocking {
        val activity = open(confirmation(Command.OFF))
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.Default) { graph.actions.exclusive { acquired.complete(Unit); release.await() } }
        try {
            withTimeout(5_000) { acquired.await() }
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            withTimeout(5_000) {
                while (graph.actions.busy.value.queuedCount == 0) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            }
            val newer = original.copy(revision = original.revision + 1)
            graph.repository.setActiveNight(newer)
            release.complete(Unit)
            owner.await()
            waitForFeedback("FAILED")
            assertEquals(0, writes)
            assertEquals(newer.identity, graph.repository.activeNight.value?.identity)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            release.complete(Unit)
            owner.cancelAndJoin()
            activity.pause().stop().destroy()
        }
    }
}
