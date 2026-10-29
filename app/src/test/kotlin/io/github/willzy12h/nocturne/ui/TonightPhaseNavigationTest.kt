package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TonightPhaseNavigationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun longPressingTonightsCurveOpensThatSourcePhaseInTheEditor() {
        ui.compose.openTonightDetail()
        ui.compose.onNodeWithTag("night-curve").performScrollTo()
            .performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-save").assertExists()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertExists()
        ui.compose.onNodeWithTag("editor-phase-START-0").assertIsDisplayed()
    }
}
