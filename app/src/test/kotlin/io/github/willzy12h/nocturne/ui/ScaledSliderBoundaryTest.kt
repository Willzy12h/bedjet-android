package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.willzy12h.nocturne.GraphLifecycle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xhdpi")
class ScaledSliderBoundaryTest {
    private val compose = createComposeRule()
    private val graphs = GraphLifecycle()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)

    @Test fun aSingleReportedTemperatureLimitRemainsReadableWithoutAnInvalidScale() {
        compose.setContent {
            MaterialTheme {
                ScaledSlider(64f, {}, 64f..64f, 0, true, Modifier,
                    listOf(64f to "32°C", 64f to "32°C", 64f to "32°C"), "temperature", {})
            }
        }
        compose.onNodeWithTag("temperature-tick-1").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
            .assertCountEquals(0)
    }
}
