package io.github.willzy12h.nocturne.ui

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class LightThemeConfirmationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private fun tab(id: Int) {
        ui.compose.onNode(hasText(ui.s(id)) and hasClickAction()).performClick()
        ui.compose.waitForIdle()
    }
    private val warning = "Switch to light theme? The screen will get much brighter."
    private fun choose(label: String) {
        tab(R.string.tab_settings)
        ui.compose.onNodeWithText(label).performScrollTo().performClick()
    }

    @Test fun daylightDoesNotChangeTheSavedThemeUntilSwitchIsConfirmed() {
        choose(ui.s(R.string.theme_light))
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
        ui.compose.onNodeWithText("Switch").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.LIGHT }
    }

    @Test fun cancelLeavesTheSavedThemeUnchanged() {
        choose(ui.s(R.string.theme_light))
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        ui.compose.onNodeWithText("Cancel").performClick()
        ui.compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
    }

    @Test fun outsideDismissalIsARealCancel() {
        choose(ui.s(R.string.theme_light))
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        ui.compose.runOnUiThread {
            val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -50f, -50f, 0)
            try { ShadowDialog.getLatestDialog().onTouchEvent(outside) } finally { outside.recycle() }
        }
        ui.compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
    }

    @Test fun backDismissalIsARealCancel() {
        choose(ui.s(R.string.theme_light))
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        ui.compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        ui.compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
    }

    @Test fun systemCurrentlyLightRequiresTheSameConfirmation() {
        choose(ui.s(R.string.theme_system))
        ui.compose.onNodeWithText(warning).assertIsDisplayed()
        assertEquals(ThemeMode.DARK, ui.graph.repository.settings.value.themeMode)
        ui.compose.onNodeWithText("Switch").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.SYSTEM }
    }

    @Test fun systemCurrentlyDarkAndExplicitDarkNeverPrompt() {
        ui.compose.runOnUiThread {
            val config = Configuration(ui.compose.activity.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            // Android updates Resources before delivering this callback; reproduce both parts.
            @Suppress("DEPRECATION")
            ui.compose.activity.resources.updateConfiguration(config, ui.compose.activity.resources.displayMetrics)
            ui.compose.activity.onConfigurationChanged(config)
            ui.compose.activity.window.decorView.dispatchConfigurationChanged(config)
        }
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
            ui.compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        choose(ui.s(R.string.theme_system))
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.SYSTEM }
        ui.compose.onNodeWithText(warning).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.theme_dark)).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.DARK }
        ui.compose.onNodeWithText(warning).assertDoesNotExist()
    }

    @Test fun releaseBedJetIsReachableFromSettingsAndUsesTheExistingHandover() {
        tab(R.string.tab_settings)
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).performScrollTo().performClick()
        ui.waitUntil { ui.graph.sessions.released.value }
        ui.compose.onNodeWithText(ui.s(R.string.feature_take_back)).performScrollTo().assertIsDisplayed()
    }
}
