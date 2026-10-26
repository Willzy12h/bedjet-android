package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RemoteModeLimitsTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun everyRemoteModeNamesItsModelMaximumIncludingDry() {
        ui.compose.onNodeWithText(ui.s(R.string.tab_remote)).performClick()
        ui.waitUntil { ui.graph.link.status.value != null }
        listOf("Heat" to "max 40°C", "Turbo" to "max 43°C", "Gentle heat" to "max 33.5°C",
            "Cool" to "max 31°C", "Dry" to "max 31°C").forEach { (name, maximum) ->
            ui.compose.onNode(hasText(name) and hasText(maximum) and hasClickAction()).performScrollTo().assertIsDisplayed()
        }
    }
}
