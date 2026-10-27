package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Independent copy contract: the displayed number belongs to its actual mode segment. */
internal fun expectedBoostActionLabel(minutes: Int): String =
    if (minutes == 10) "Turbo 10m" else "Turbo 10m + Heat ${minutes - 10}m"

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoostSequenceLayoutTest(look: U06Look) {
    private val ui = U06Fixture(look)
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }
    }

    private fun everyDuration(quick: Boolean) {
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            ui.compose.runOnUiThread { runBlocking {
                ui.graph.repository.updateSettings { it.copy(boostMinutes = minutes) }
            } }
            ui.compose.waitForIdle()
            val label = expectedBoostActionLabel(minutes)
            val description = if (minutes == 10) "Turbo heat for 10 minutes"
                else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes"
            val control = hasText(label) and hasClickAction()
            val node = ui.compose.onNode(if (quick) control and hasAnyAncestor(hasTestTag("quick-controls")) else control)
            node.assertContentDescriptionEquals(description)
            // U06 checks the actual rendered characters/line bounds, viewport clipping and >=48dp touch bounds.
            ui.checkControl(node, "${if (quick) "Tonight" else "Remote"} $label")
        }
    }

    @Test fun tonightShowsBothActualSegmentsForEveryDurationAtTwoHundredPercent() = everyDuration(quick = true)

    @Test fun remoteShowsBothActualSegmentsForEveryDurationAtTwoHundredPercent() {
        ui.tab(ui.s(R.string.tab_remote))
        ui.waitUntil { ui.compose.onAllNodes(hasText(ui.s(R.string.mode_heat)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        everyDuration(quick = false)
    }
}
