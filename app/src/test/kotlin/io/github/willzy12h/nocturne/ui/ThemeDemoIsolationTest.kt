package io.github.willzy12h.nocturne.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** B2/B4: approving a brighter disposable demo must not revoke the real session's dark hold. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class ThemeDemoIsolationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ ->
        fixture.graph.clock = Clock.fixed(Instant.parse("2026-10-26T06:59:00Z"), ZoneOffset.UTC)
        runBlocking {
            // Remove U06's legacy transport fixture flag before the Activity exists. The measured
            // disposable session below must be entered and exited through the public buttons.
            assertTrue(fixture.graph.actions.setDemoMode(false).succeeded)
            fixture.graph.repository.updateSettings {
                it.copy(themeMode = ThemeMode.SCHEDULED,
                    scheduledDarkMinute = 22 * 60, scheduledLightMinute = 7 * 60)
            }
        }
    }
    @get:Rule val rules = ui.rules

    private fun settleFrames() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            ui.compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        ui.compose.waitForIdle()
    }

    private fun moveClock(instant: String) {
        ui.compose.runOnUiThread {
            ui.graph.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
            ui.compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        }
        settleFrames()
    }

    private fun settings() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
    }

    private fun renderedHeaderColour(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        ui.compose.onNodeWithTag("status-device", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().layoutInput.style.color
    }

    @Test fun demoLightApprovalKeepsTheRealScheduledDarkHoldOnExitAndRecreation() {
        assertFalse(ui.graph.repository.isDemoSession)
        assertFalse(ui.graph.repository.settings.value.demoMode)
        assertEquals(MidnightInkScheme.onSurface, renderedHeaderColour())

        moveClock("2026-10-26T07:01:00Z")
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertIsDisplayed()
        ui.compose.onNodeWithText(ui.s(R.string.theme_stay_dark)).performClick()
        settleFrames()
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertDoesNotExist()
        assertEquals(MidnightInkScheme.onSurface, renderedHeaderColour())
        val realSettings = ui.graph.repository.settings.value
        val preferences = ui.compose.activity.getSharedPreferences("appearance-consent", Context.MODE_PRIVATE)
        val realConsent = preferences.all.toMap()
        assertTrue("The real Stay dark choice must be persisted before entering Demo", preferences.getBoolean("lastDark", false))
        assertNotNull(preferences.getString("holdSelection", null))
        assertTrue(preferences.getLong("holdUntil", 0) > ui.graph.now().toInstant().toEpochMilli())

        settings()
        ui.compose.onNodeWithText(ui.s(R.string.setup_use_demo)).performScrollTo().performClick()
        ui.waitUntil {
            ui.graph.repository.isDemoSession && ui.graph.repository.settings.value.demoMode &&
                ui.graph.actions.busy.value.runningLabel == null
        }
        settleFrames()
        ui.compose.onNodeWithText(ui.s(R.string.gpt12_demo_banner)).assertIsDisplayed()
        assertEquals("Entering Demo must not brighten the held screen", MidnightInkScheme.onSurface, renderedHeaderColour())

        settings()
        ui.compose.onNodeWithText(ui.s(R.string.theme_light)).performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.ux_light_theme_warning)).assertIsDisplayed()
        assertEquals(ThemeMode.SCHEDULED, ui.graph.repository.settings.value.themeMode)
        ui.compose.onNodeWithText(ui.s(R.string.ux_switch)).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.LIGHT }
        settleFrames()
        assertTrue(ui.graph.repository.isDemoSession)
        assertEquals("The explicit demo choice must actually show the light palette", DaylightScheme.onSurface, renderedHeaderColour())
        assertEquals("Demo must leave real consent on disk intact even if the process dies before Exit demo",
            realConsent, preferences.all)

        ui.compose.onNodeWithText(ui.s(R.string.gpt12_exit_demo)).assertIsDisplayed().performClick()
        ui.waitUntil {
            !ui.graph.repository.isDemoSession && !ui.graph.repository.settings.value.demoMode &&
                ui.graph.actions.busy.value.runningLabel == null
        }
        settleFrames()
        assertEquals(realSettings, ui.graph.repository.settings.value)
        ui.compose.onNodeWithText(ui.s(R.string.gpt12_demo_banner)).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertDoesNotExist()
        assertEquals("Exit demo must return to the real Stay dark palette without another consent choice",
            MidnightInkScheme.onSurface, renderedHeaderColour())
        assertEquals("A disposable light approval must preserve the real session's persisted consent", realConsent, preferences.all)

        ui.compose.activityRule.scenario.recreate()
        settleFrames()
        assertEquals(realSettings, ui.graph.repository.settings.value)
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertDoesNotExist()
        assertEquals("The restored real hold must still protect the recreated screen",
            MidnightInkScheme.onSurface, renderedHeaderColour())
        assertEquals(realConsent, preferences.all)
    }

    @Test fun aNewScopeWithStaleDemoSettingsCannotClearRealConsentAfterExit() {
        moveClock("2026-10-26T07:01:00Z")
        ui.compose.onNodeWithText(ui.s(R.string.theme_stay_dark)).performClick()
        settleFrames()
        val preferences = ui.compose.activity.getSharedPreferences("appearance-consent", Context.MODE_PRIVATE)
        val realConsent = preferences.all.toMap()
        assertTrue(preferences.getBoolean("lastDark", false))
        assertNotNull(preferences.getString("holdSelection", null))

        settings()
        ui.compose.onNodeWithText(ui.s(R.string.setup_use_demo)).performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.isDemoSession && ui.graph.actions.busy.value.runningLabel == null }
        settleFrames()
        settings()
        ui.compose.onNodeWithText(ui.s(R.string.theme_light)).performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.ux_switch)).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.themeMode == ThemeMode.LIGHT }
        settleFrames()
        val displayedDemoSettings = ui.graph.repository.settings.value
        val displayedRevision = ui.graph.repository.sessionRevision.value
        assertTrue(displayedDemoSettings.demoMode)
        assertEquals(DaylightScheme.onSurface, renderedHeaderColour())
        assertEquals(realConsent, preferences.all)

        ui.compose.onNodeWithText(ui.s(R.string.gpt12_exit_demo)).performClick()
        ui.waitUntil { !ui.graph.repository.isDemoSession && ui.graph.actions.busy.value.runningLabel == null }
        settleFrames()
        assertFalse(ui.graph.repository.settings.value.demoMode)
        assertTrue(ui.graph.repository.sessionRevision.value > displayedRevision)
        assertEquals(realConsent, preferences.all)

        // Settings and sessionRevision are collected separately. Reproduce a new scope at the
        // incoming revision while its caller still supplies the last displayed Demo settings.
        ui.compose.runOnUiThread {
            ui.compose.activity.setContent {
                val theme = rememberThemePresentation(displayedDemoSettings, ui.graph::now)
                NocturneTheme(displayedDemoSettings, theme.at, theme.dark) { ThemeDuePrompt(theme) }
            }
        }
        settleFrames()
        assertEquals("A stale displayed Demo frame must not clear the incoming real hold", realConsent, preferences.all)
        ui.compose.onNodeWithText(ui.s(R.string.theme_switch_now)).performClick()
        settleFrames()
        assertEquals("A callback captured by that stale frame must not change real consent", realConsent, preferences.all)
    }
}
