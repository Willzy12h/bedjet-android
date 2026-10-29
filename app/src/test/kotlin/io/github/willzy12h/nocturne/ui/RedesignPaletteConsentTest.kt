package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class RedesignPaletteConsentTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, test ->
        val legacy = test.methodName == "legacyDefaultPaletteHoldSurvivesUpgrade"
        fixture.graph.clock = Clock.fixed(Instant.parse(if (legacy) "2026-10-26T08:00:00Z" else "2026-10-26T21:59:00Z"), ZoneOffset.UTC)
        runBlocking { fixture.graph.repository.updateSettings {
            it.copy(themeMode = ThemeMode.SCHEDULED, nightPalette = if (legacy) Palette.MIDNIGHT_INK else Palette.OVERCAST,
                dayPalette = if (legacy) Palette.OVERCAST else Palette.FOREST, scheduledDarkMinute = 22 * 60, scheduledLightMinute = 7 * 60)
        } }
        if (legacy) RuntimeEnvironment.getApplication().getSharedPreferences("appearance-consent", Context.MODE_PRIVATE)
            .edit().putBoolean("lastDark", true).putString("holdSelection", "SCHEDULED:1320:420")
            .putLong("holdUntil", Instant.parse("2026-10-26T22:00:00Z").toEpochMilli()).commit()
    }
    @get:Rule val rules = ui.rules

    private fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            ui.compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        ui.compose.waitForIdle()
    }

    private fun headerColour(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        ui.compose.onNodeWithTag("status-connection", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().layoutInput.style.color
    }

    @Test fun darkDayPaletteDoesNotPromptAndNativeDialogStaysDark() {
        assertEquals(Palette.FOREST.tokens.ink2, headerColour())
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertDoesNotExist()
        assertTrue(nativeThemeIsDark(ui.compose.activity, ui.graph.repository.settings.value, ui.graph.now(), false))
    }

    @Test fun legacyDefaultPaletteHoldSurvivesUpgrade() {
        assertEquals(Palette.MIDNIGHT_INK.tokens.ink2, headerColour())
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertDoesNotExist()
    }

    @Test fun selectingOvercastNightRequiresConfirmationBeforePublishing() {
        // Begin with Forest in the active night slot so choosing Overcast visibly brightens the app.
        runBlocking { ui.graph.repository.updateSettings { it.copy(themeMode = ThemeMode.DARK, nightPalette = Palette.FOREST) } }
        settle()
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("appearance")
        ui.compose.onNodeWithTag("night-palette-OVERCAST").performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.ux_light_theme_warning)).assertIsDisplayed()
        assertEquals(Palette.FOREST, ui.graph.repository.settings.value.nightPalette)
        ui.compose.onNodeWithText(ui.s(R.string.ux_switch)).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.nightPalette == Palette.OVERCAST }
    }

    @Test fun scheduleKeepsPreviousDarkPaletteUntilLightIsApproved() {
        assertEquals(Palette.FOREST.tokens.ink2, headerColour())
        ui.compose.runOnUiThread {
            ui.graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:01:00Z"), ZoneOffset.UTC)
            ui.compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        }
        settle()
        ui.compose.onNodeWithText(ui.s(R.string.theme_light_due)).assertIsDisplayed()
        assertEquals(Palette.FOREST.tokens.ink2, headerColour())
        ui.compose.onNodeWithText(ui.s(R.string.theme_switch_now)).performClick()
        settle()
        assertEquals(Palette.OVERCAST.tokens.ink2, headerColour())
    }
}
