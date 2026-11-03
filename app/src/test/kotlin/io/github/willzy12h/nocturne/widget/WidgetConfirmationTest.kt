package io.github.willzy12h.nocturne.widget

import android.app.AlertDialog
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import android.widget.SeekBar
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
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.service.BedJetService
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
import org.robolectric.android.controller.ServiceController
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
    private val requestKey = stringPreferencesKey("widget_feedback_request")
    private var feedbackBeforeOpening: String? = null
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
    /** Saves [preset] both as the legacy Warm action and as the Controls preset for its mode. */
    private suspend fun review(preset: ActionPreset) = graph.repository.updateSettings {
        it.copy(extendWarm = preset, quickDefaults = it.quickDefaults.filterNot { saved -> saved.mode == preset.mode } + preset) }

    private suspend fun confirmation(command: Command, requestedSingle: Boolean = false, preset: String? = null): Intent {
        // Controls has no Stop tracking; an older single-action placement still does.
        val key = preset ?: quickPresetKey(command)
        val single = requestedSingle || key == null
        val label = if (single) widgetActions(app).first { it.command == command && it.profileId == null }.label
            else widgetPreset(app, key!!)!!.label
        if (single) {
            val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, SingleActionReceiver::class.java) }
            shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(75, info)
            id = GlanceAppWidgetManager(app).getGlanceIdBy(75)
        }
        val remote = if (single) SingleActionWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
            state = preferencesOf(SingleActionWidget.KEY_COMMAND to command.name, SingleActionWidget.KEY_LABEL to label))
            else QuickControlsWidget().compose(app, id = id, size = DpSize(340.dp, 240.dp),
                state = preferencesOf(QuickControlsWidget.KEY_PRESETS to key!!))
        val button = widgetControl(remote.apply(app, FrameLayout(app)), label)
        assertNotNull("The rendered widget must expose $label", button)
        button!!.performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("A tracked-night $command tap must directly request its confirmation activity", intent)
        assertEquals("Only the small widget confirmation route may be opened", confirmationClass, intent!!.component?.className)
        return intent
    }
    private suspend fun open(intent: Intent): ActivityController<out ComponentActivity> {
        feedbackBeforeOpening = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)[requestKey]
        return Robolectric.buildActivity(
            Class.forName(intent.component!!.className).asSubclass(ComponentActivity::class.java), intent).setup()
    }
    private suspend fun waitForFeedback(expected: String) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val feedback = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)
            if (feedback[requestKey] != null && feedback[requestKey] != feedbackBeforeOpening && feedback[stateKey] == expected) return
            delay(5)
        }
        val feedback = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)
        assertNotEquals("The result must belong to this approval, not an earlier widget request", feedbackBeforeOpening, feedback[requestKey])
        assertEquals("The widget must receive the result after the overlay closes", expected, feedback[stateKey])
    }

    private fun assertTemporaryChoices(dialog: AlertDialog, selected: Int = 0) {
        val choices = dialog.listView
        assertNotNull("A real tracked-night widget tap must offer Temporary and End before sending", choices)
        assertEquals(listOf(app.getString(R.string.temporary_choice), app.getString(R.string.temporary_end_choice)),
            (0 until choices.adapter.count).map { choices.adapter.getItem(it).toString() })
        assertEquals("The initial choice must reflect the captured action's eligibility", selected, choices.checkedItemPosition)
        assertEquals("Widgets keep their Cancel action", app.getString(R.string.feature_cancel),
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
    }

    @Test fun bothRenderedWidgetsOfferTemporaryAndEndWithoutOpeningTheMainApp() = runBlocking {
        for (single in listOf(false, true)) {
            val activity = open(confirmation(Command.WARM, single))
            try {
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                assertTemporaryChoices(dialog)
                assertEquals(0, writes)
                assertEquals(original.identity, graph.repository.activeNight.value?.identity)
                dialog.cancel()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Back/outside cancellation returns to the launcher", activity.get().isFinishing)
                assertEquals(0, writes)
                assertEquals(original.identity, graph.repository.activeNight.value?.identity)
                assertNull("The widget overlay must not open MainActivity", shadowOf(app).nextStartedActivity)
            } finally { activity.pause().stop().destroy() }
        }
    }

    @Test fun renderedTurboAndBoostKeepTheirTenAndThirtyMinuteCaps() = runBlocking {
        graph.repository.updateSettings { it.copy(boostMinutes = 30) }
        for ((command, maximum) in listOf(Command.TURBO to 10, Command.BOOST to 30)) {
            val activity = open(confirmation(command))
            try {
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                assertTemporaryChoices(dialog)
                val seek = dialog.window!!.decorView.findViewWithTag<SeekBar>("temporary-duration")
                assertNotNull("The actual widget choice needs its bounded duration control", seek)
                assertEquals(maximum, seek.max)
                assertEquals(maximum, seek.progress)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(0, writes)
                assertEquals(original.identity, graph.repository.activeNight.value?.identity)
                assertNull(shadowOf(app).nextStartedActivity)
            } finally { activity.pause().stop().destroy() }
        }
    }

    @Test fun aChangedPresetGetsADistinctRenderedConfirmationIntent() = runBlocking {
        review(ActionPreset(Mode.DRY, 60, 25, 15))
        val first = confirmation(Command.WARM, preset = "DRY")
        review(ActionPreset(Mode.DRY, 56, 40, 30))
        val second = confirmation(Command.WARM, preset = "DRY")
        assertNotEquals("PendingIntent equality must not replace an earlier reviewed preset", first.data, second.data)
        assertEquals(0, writes)
        assertEquals(original.identity, graph.repository.activeNight.value?.identity)
    }

    private suspend fun capturedTemporaryPreservesWake(single: Boolean) {
        val reviewed = ActionPreset(Mode.DRY, 60, 25, 15)
        review(reviewed)
        val rendered = confirmation(Command.WARM, single, preset = "DRY".takeUnless { single })
        // The PendingIntent must retain the preset seen when rendered, even before the overlay opens.
        review(ActionPreset(Mode.DRY, 56, 40, 30))
        val activity = open(rendered)
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTemporaryChoices(dialog)
            assertEquals(15, dialog.window!!.decorView.findViewWithTag<SeekBar>("temporary-duration").progress)
            review(ActionPreset(Mode.DRY, 52, 50, 20))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitForFeedback("CONFIRMED")
            val current = requireNotNull(graph.repository.activeNight.value)
            assertEquals("Temporary must retain the original night", original.recordId, current.recordId)
            assertEquals("Temporary must retain its wake", original.wakeAt, current.wakeAt)
            assertTrue(current.revision > original.revision)
            val temporary = requireNotNull(current.temporary)
            assertEquals(15, temporary.steps.sumOf { it.minutes })
            assertTrue(temporary.steps.all { it.mode == reviewed.mode && it.temperature == reviewed.temperature && it.fanPercent == reviewed.fanPercent })
            assertTrue("The confirmed widget phase must name Temporary", widgetPhase(app, current).startsWith("Planned phase: Temporary"))
            assertTrue("The frozen widget phase keeps its absolute span", widgetPhase(app, current).contains("26 Oct 22:30–22:45"))
            assertEquals("Plan: Temporary", widgetPhaseText(app, current, compact = true).title)
            assertTrue(writes > 0)
            assertTrue(activity.get().isFinishing)
            assertNull("A completed widget action must not open MainActivity", shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun quickWidgetTemporaryRetainsItsRenderedPresetAndOriginalWake() = runBlocking {
        capturedTemporaryPreservesWake(single = false)
    }

    @Test fun singleWidgetTemporaryRetainsItsRenderedPresetAndOriginalWake() = runBlocking {
        capturedTemporaryPreservesWake(single = true)
    }

    @Test fun widgetEndUsesTheRenderedPresetAfterSettingsChange() = runBlocking {
        review(ActionPreset(Mode.DRY, 60, 25, 15))
        val rendered = confirmation(Command.WARM, preset = "DRY")
        review(ActionPreset(Mode.DRY, 56, 40, 30))
        val activity = open(rendered)
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTemporaryChoices(dialog)
            dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
            assertEquals("The rendered End choice must be selected before approval", 1, dialog.listView.checkedItemPosition)
            assertEquals(app.getString(R.string.guard_confirm), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitForFeedback("CONFIRMED")
            assertNull(graph.repository.activeNight.value)
            assertEquals(Mode.DRY, demo.status.value?.mode)
            assertEquals(15 * 60, demo.status.value?.remainingSeconds)
            assertTrue(activity.get().isFinishing)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun temporaryApprovalCannotReplaceANewerNightWhileWaitingForItsOwner() = runBlocking {
        val activity = open(confirmation(Command.WARM))
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var owner: Deferred<Unit>? = null
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTemporaryChoices(dialog)
            val queuedOwner = async(Dispatchers.Default) { graph.actions.exclusive { acquired.complete(Unit); release.await() } }
            owner = queuedOwner
            withTimeout(5_000) { acquired.await() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            withTimeout(5_000) {
                while (graph.actions.busy.value.queuedCount == 0) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            }
            val newer = original.copy(revision = original.revision + 1)
            graph.repository.setActiveNight(newer)
            release.complete(Unit)
            queuedOwner.await()
            waitForFeedback("FAILED")
            assertEquals(0, writes)
            assertEquals(newer.identity, graph.repository.activeNight.value?.identity)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            release.complete(Unit)
            owner?.cancelAndJoin()
            activity.pause().stop().destroy()
        }
    }

    @Test fun aChangedTargetBeforeTheWidgetChoiceOpensCannotReceiveItsEndAction() = runBlocking {
        val rendered = confirmation(Command.WARM)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:00:11:22:33:44:55") }
        val activity = open(rendered)
        var service: ServiceController<BedJetService>? = null
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTemporaryChoices(dialog, selected = 1)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            // A non-demo request really crosses the service boundary; Robolectric records the Intent
            // but requires the test to deliver it, just as RuntimeServiceTest does.
            val delivery = withTimeout(5_000) {
                var intent: Intent? = null
                while (intent == null) {
                    shadowOf(Looper.getMainLooper()).idle()
                    intent = shadowOf(app).nextStartedService
                    if (intent == null) delay(5)
                }
                intent
            }
            assertEquals(BedJetService::class.java.name, delivery.component?.className)
            service = Robolectric.buildService(BedJetService::class.java).create()
            service.get().onStartCommand(delivery, 0, 1)
            waitForFeedback("FAILED")
            assertEquals(0, writes)
            assertEquals(original.identity, graph.repository.activeNight.value?.identity)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { service?.destroy(); activity.pause().stop().destroy() }
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
