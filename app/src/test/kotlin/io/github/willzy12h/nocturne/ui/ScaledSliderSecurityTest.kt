package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.willzy12h.nocturne.GraphLifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xhdpi")
class ScaledSliderSecurityTest {
    private val compose = createComposeRule()
    private val graphs = GraphLifecycle()
    @get:Rule val rules = RuleChain.outerRule(graphs).around(compose)
    private fun invalid(range: ClosedFloatingPointRange<Float>, ticks: List<Pair<Float, String>>) {
        var changes = 0
        compose.setContent {
            MaterialTheme {
                ScaledSlider(60f, { changes++ }, range, 0, true, Modifier, ticks, "temperature", { changes++ })
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
        assertEquals(0, changes)
    }
    @Test fun aReversedRangeCannotCrashOrOfferAnAdjustableControl() = invalid(100f..40f,
        listOf(100f to "50°C", 70f to "35°C", 40f to "20°C"))
    @Test fun missingTicksCannotCrashAnEqualRange() = invalid(60f..60f, emptyList())
    @Test fun aNonFiniteEndpointCannotBecomeASlider() = invalid(Float.NaN..80f,
        listOf(40f to "20°C", 60f to "30°C", 80f to "40°C"))
}
