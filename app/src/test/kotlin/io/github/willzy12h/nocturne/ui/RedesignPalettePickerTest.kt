package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class RedesignPalettePickerTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun choosingNightAndDaySwatchesSavesBothIndependently() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("appearance")
        ui.compose.onNodeWithTag("night-palette-FOREST").performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.settings.value.nightPalette == Palette.FOREST }
        ui.compose.onNodeWithTag("day-palette-WINTER_STORM").performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.settings.value.dayPalette == Palette.WINTER_STORM }
        assertEquals(Palette.FOREST, ui.graph.repository.settings.value.nightPalette)
        ui.compose.onNodeWithTag("day-palette-WINTER_STORM").assertIsSelected()
    }
}
