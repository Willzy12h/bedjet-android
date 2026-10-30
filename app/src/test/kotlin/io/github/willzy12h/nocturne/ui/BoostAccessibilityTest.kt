package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BoostAccessibilityTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun quickBoostDescribesTheActualTurboThenHeatProgramme() {
        ui.compose.onNodeWithTag("quick-mode-BOOST", useUnmergedTree = true).performScrollTo()
        ui.compose.onNode(hasTestTag("quick-mode-BOOST") and hasContentDescription("Turbo heat for 10 minutes, then Heat for 5 minutes", substring = true))
            .assertHasClickAction().assertIsDisplayed()
    }

    @Test fun remoteNoLongerCarriesItsOwnBoost() {
        ui.compose.openRemote()
        ui.compose.onAllNodesWithContentDescription("Turbo heat for 10 minutes, then Heat for 5 minutes", substring = true).assertCountEquals(0)
    }
}
