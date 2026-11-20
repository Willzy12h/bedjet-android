package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The source line wraps at large text in all four looks; the existing wake-title suite remains separate. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmExplanationLayoutTest(private val look: U06Look) {
    private val ui = U06Fixture(look)
    @get:Rule val rules = ui.rules

    @Test fun sourceExplanationRemainsCompleteAndActionableAt320DpAnd200Percent() {
        ui.checkControl(ui.compose.onNodeWithTag("tonight-alarm-row"), "Wake source explanation")
            .assertTextEquals("No alarm found · using your profile wake")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }
    }
}
