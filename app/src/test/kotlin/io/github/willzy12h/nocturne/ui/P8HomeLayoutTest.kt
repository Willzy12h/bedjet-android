package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import io.github.willzy12h.nocturne.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P8HomeLayoutTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun nightStartPrecedesQuickPresets() {
        val start = ui.compose.onNode(hasText(ui.s(R.string.tonight_start_now)) and hasClickAction())
        val quick = ui.compose.onNodeWithTag("quick-controls")
        val startBounds = start.getUnclippedBoundsInRoot()
        val quickBounds = quick.getUnclippedBoundsInRoot()
        assertTrue("Tonight's Start should precede Quick presets", startBounds.bottom < quickBounds.top)
    }

    @Test fun liveTemperatureUsesTheProminentReadingStyle() {
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.model.status.value != null }
        val results = mutableListOf<TextLayoutResult>()
        ui.compose.onNodeWithTag("home-temperature", useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val style = results.single().layoutInput.style
        assertEquals(64f, style.fontSize.value, .01f)
        assertEquals(FontWeight.Bold, style.fontWeight)
    }
}
