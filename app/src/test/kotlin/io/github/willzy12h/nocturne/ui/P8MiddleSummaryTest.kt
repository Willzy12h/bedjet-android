package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.plan.QuietFan
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P8MiddleSummaryTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun quietMiddleSummaryNamesDraftTemperaturesAndFanPathAndOpensEditor() {
        val original = ui.graph.repository.profiles.value.first()
        val middle = original.middle.copy(mode = Mode.HEAT, tempStart = 64, tempEnd = 58,
            fanStart = 50, fanEnd = 45, quietFan = QuietFan(50, 30, 25, 45, 40))
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                ProfileEditor(ui.model, original, onDone = {}, initialDraft = original.copy(middle = middle))
            }
        } }
        val summary = ui.compose.onNodeWithTag("editor-middle-summary").performScrollTo()
        summary.assertTextContains("Heat", substring = true).assertTextContains("32°C", substring = true)
            .assertTextContains("29°C", substring = true).assertTextContains("50%", substring = true)
            .assertTextContains("25%", substring = true).assertTextContains("45%", substring = true)
            .assertHasClickAction().performClick()
        ui.compose.onNodeWithTag("editor-middle-sheet").assertIsDisplayed()
    }
}
