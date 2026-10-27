package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class FinalThemeAcceptanceTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, description ->
        val automatic = description.methodName.startsWith("automaticScheduled")
        val edit = description.methodName.startsWith("scheduledTime")
        f.graph.clock = Clock.fixed(Instant.parse(when {
            automatic -> "2026-10-26T06:59:00Z"
            edit -> "2026-10-26T23:00:00Z"
            else -> "2026-10-26T12:00:00Z"
        }), ZoneOffset.UTC)
        if (automatic || edit) runBlocking { f.graph.repository.updateSettings { it.copy(themeMode = ThemeMode.SCHEDULED) } }
    }
    @get:Rule val rules = ui.rules
    private val warning = "Switch to light theme? The screen will get much brighter."
    private val due = "Light theme is due"

    private fun chooseScheduled() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.onNodeWithText(ui.s(R.string.theme_scheduled)).performScrollTo().performClick()
    }
    private fun moveClock(instant: String) {
        ui.compose.runOnUiThread {
            ui.graph.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
            ui.compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        }
        ui.compose.waitForIdle()
    }
    private fun assertDarkPrompt() {
        ui.compose.onNodeWithText(due).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        ui.compose.onNodeWithText(due).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The light transition prompt must retain the dark palette", MidnightInkScheme.onSurface, layouts.single().layoutInput.style.color)
    }

    @Test fun scheduledDayChoiceWaitsForExplicitConfirmation() {
        chooseScheduled()
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
        ui.compose.onNodeWithText("Switch").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.SCHEDULED }
        ui.compose.onNodeWithText(due).assertDoesNotExist()
    }

    @Test fun scheduledDayCancelLeavesSettingUnchanged() {
        chooseScheduled()
        ui.compose.onNodeWithText("Cancel").performClick()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
    }

    @Test fun scheduledDayBackLeavesSettingUnchanged() {
        chooseScheduled()
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        ui.compose.runOnUiThread {
            ShadowDialog.getLatestDialog().apply {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
        }
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
    }

    @Test fun scheduledDayOutsideDismissalLeavesSettingUnchanged() {
        chooseScheduled()
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        ui.compose.runOnUiThread {
            val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -50f, -50f, 0)
            try { ShadowDialog.getLatestDialog().onTouchEvent(outside) } finally { outside.recycle() }
        }
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
    }

    @Test fun automaticScheduledLightPromptsBeforeChangingTheVisiblePalette() {
        moveClock("2026-10-26T07:01:00Z")
        assertDarkPrompt()
        ui.compose.onNodeWithText("Switch now").performClick()
        ui.compose.onNodeWithText(due).assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        ui.compose.onAllNodesWithText("Tonight", useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(DaylightScheme.onSurface, layouts.single().layoutInput.style.color)
    }

    @Test fun automaticScheduledStayDarkSurvivesRecreationAndExpiresTonight() {
        moveClock("2026-10-26T07:01:00Z")
        ui.compose.onNodeWithText("Stay dark until tonight").performClick()
        ui.compose.activityRule.scenario.recreate()
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText(due).assertDoesNotExist()
        moveClock("2026-10-26T22:01:00Z")
        ui.compose.onNodeWithText(due).assertDoesNotExist()
        moveClock("2026-10-27T07:01:00Z")
        assertDarkPrompt()
    }

    @Test fun automaticScheduledLightWhileHiddenPromptsOnNextOpen() {
        ui.compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.graph.clock = Clock.fixed(Instant.parse("2026-10-26T08:00:00Z"), ZoneOffset.UTC)
        ui.compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.compose.waitForIdle()
        assertDarkPrompt()
    }

    @Test fun automaticSystemLightAlsoWaitsForConsent() {
        fun system(dark: Boolean) = ui.compose.runOnUiThread {
            val activity = ui.compose.activity
            val config = Configuration(activity.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            activity.onConfigurationChanged(config)
            activity.window.decorView.dispatchConfigurationChanged(config)
        }
        system(true)
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.onNodeWithText(ui.s(R.string.theme_system)).performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.SYSTEM }
        system(false)
        ui.compose.waitForIdle()
        assertDarkPrompt()
    }

    @Test fun automaticScheduledNativeConfirmationKeepsDarkUntilScreenConsent() {
        moveClock("2026-10-26T07:01:00Z")
        val dialog = nativeActionDialogContext(ui.compose.activity, ui.graph)
        val value = android.util.TypedValue()
        assertTrue(dialog.theme.resolveAttribute(android.R.attr.isLightTheme, value, true))
        assertEquals("A shortcut confirmation must not bypass pending screen consent", 0, value.data)
        ui.compose.onNodeWithText("Stay dark until tonight").performClick()
        val held = nativeActionDialogContext(ui.compose.activity, ui.graph)
        assertTrue(held.theme.resolveAttribute(android.R.attr.isLightTheme, value, true))
        assertEquals("Native confirmations respect the persisted dark hold", 0, value.data)
    }

    private fun editSchedule(darkBoundary: Boolean = true) {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.control(if (darkBoundary) "22:00" else "07:00").performClick()
        ui.compose.runOnUiThread {
            val picker = ShadowDialog.getLatestDialog() as android.app.TimePickerDialog
            picker.updateTime(if (darkBoundary) 1 else 22, if (darkBoundary) 0 else 45)
            picker.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        repeat(3) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            ui.compose.mainClock.advanceTimeByFrame()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        assertEquals(22 * 60, ui.graph.repository.settings.value.scheduledDarkMinute)
        assertEquals(7 * 60, ui.graph.repository.settings.value.scheduledLightMinute)
    }

    @Test fun scheduledTimeEditWaitsForExplicitConfirmation() {
        editSchedule()
        ui.compose.onNodeWithText("Switch").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.scheduledDarkMinute == 60 }
        ui.compose.onNodeWithText(due).assertDoesNotExist()
    }

    @Test fun scheduledTimeLightBoundaryEditAlsoWaitsForConfirmation() {
        editSchedule(darkBoundary = false)
        ui.compose.onNodeWithText("Switch").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.scheduledLightMinute == 22 * 60 + 45 }
    }

    @Test fun scheduledTimeCancelKeepsBothOriginalTimes() {
        editSchedule()
        ui.compose.onNodeWithText("Cancel").performClick()
        assertEquals(22 * 60, ui.graph.repository.settings.value.scheduledDarkMinute)
        assertEquals(7 * 60, ui.graph.repository.settings.value.scheduledLightMinute)
    }

    @Test fun scheduledTimeBackKeepsBothOriginalTimes() {
        editSchedule()
        ui.compose.runOnUiThread {
            ShadowDialog.getLatestDialog().apply {
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
        }
        assertEquals(22 * 60, ui.graph.repository.settings.value.scheduledDarkMinute)
        assertEquals(7 * 60, ui.graph.repository.settings.value.scheduledLightMinute)
    }

    @Test fun scheduledTimeOutsideDismissalKeepsBothOriginalTimes() {
        editSchedule()
        ui.compose.runOnUiThread {
            val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -50f, -50f, 0)
            try { ShadowDialog.getLatestDialog().onTouchEvent(outside) } finally { outside.recycle() }
        }
        assertEquals(22 * 60, ui.graph.repository.settings.value.scheduledDarkMinute)
        assertEquals(7 * 60, ui.graph.repository.settings.value.scheduledLightMinute)
    }
}
