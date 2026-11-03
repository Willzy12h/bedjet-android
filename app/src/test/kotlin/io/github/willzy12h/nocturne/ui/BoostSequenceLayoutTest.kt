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
    if (minutes == 10) "Turbo 10m" else "Turbo + Heat ${minutes - 10}m"

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

    private fun everyDuration() {
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            ui.compose.runOnUiThread { runBlocking {
                ui.graph.repository.updateSettings { it.copy(boostMinutes = minutes) }
            } }
            ui.compose.waitForIdle()
            val label = expectedBoostActionLabel(minutes)
            val description = if (minutes == 10) "Turbo heat for 10 minutes"
                else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes"
            val node = ui.compose.onNode(hasTestTag("quick-mode-BOOST") and hasText(label, substring = true) and hasClickAction())
            node.assertContentDescriptionContains(description, substring = true)
            // U06 checks the actual rendered characters/line bounds, viewport clipping and >=48dp touch bounds.
            ui.checkControl(node, "Tonight Boost $label")
        }
    }

    @Test fun tonightTurboRemainsTenMinutesWhenTheLegacyBoostDurationChanges() {
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            ui.compose.runOnUiThread { runBlocking { ui.graph.repository.updateSettings { it.copy(boostMinutes = minutes) } } }
            val node = ui.compose.onNodeWithTag("quick-mode-TURBO")
            node.assertTextContains("Turbo")
            node.assertTextContains("10 min")
            node.assertContentDescriptionEquals("Turbo · fan 100% · 10 min")
            ui.checkControl(node, "Turbo ten-minute Quick")
        }
    }

    @Test fun tonightBoostShowsBothActualSegmentsForEveryDurationAtTwoHundredPercent() {
        everyDuration()
    }
}
