package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.NapPreset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class NapPresetEditorTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun editsDurationIndependentlyOfAfterWakeAndPreventsZeroLength() {
        val now = ZonedDateTime.parse("2026-11-20T13:00:00Z")
        val settings = ui.graph.repository.settings.value
        var saved: NapPreset? = null
        ui.compose.runOnUiThread {
            ui.compose.activity.setContent {
                NocturneTheme(settings, now) {
                    NapPresetEditor(NapPreset(), selectedProfile = null, fahrenheit = false, now = now,
                        onSave = { saved = it }, onDismiss = {})
                }
            }
        }
        val hours = ui.compose.onNodeWithTag("nap-hours")
        val minutes = ui.compose.onNodeWithTag("nap-minutes")
        hours.performTextReplacement("0")
        minutes.performTextReplacement("0")
        ui.compose.onNodeWithTag("save-nap-preset").assertIsNotEnabled()
        hours.performTextReplacement("2")
        val clocks = ui.compose.activity.getString(R.string.nap_editor_clock_preview,
            Format.clock(now.plusHours(2)), Format.clock(now.plusHours(2).plusMinutes(10)))
        ui.compose.onNodeWithText(clocks).performScrollTo().assertExists()
        ui.compose.onNodeWithTag("save-nap-preset").performClick()
        assertEquals(NapPreset().copy(durationMinutes = 120), saved)
    }
}
