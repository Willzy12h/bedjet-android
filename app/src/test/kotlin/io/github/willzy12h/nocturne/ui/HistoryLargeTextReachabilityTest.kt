package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.core.store.NightRecord
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class HistoryLargeTextReachabilityTest(private val look: U06Look) {
    private val ui = U06Fixture(look)
    @get:Rule val rules = ui.rules
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = listOf(U06Look.MIDNIGHT, U06Look.DAYLIGHT).map { arrayOf(it) }
    }

    private fun show(checkedIn: Boolean): String {
        val start = ui.graph.now().minusDays(1)
        val wake = start.plusHours(8)
        val events = listOf(HistoryEvent(start.toString(), EventKind.STARTED, "Started"),
            HistoryEvent(wake.toString(), EventKind.ENDED, "Ended")) +
            (1..4).map { HistoryEvent(start.plusMinutes(it.toLong()).toString(), EventKind.ERROR, "Outcome unknown") } +
            if (checkedIn) listOf(HistoryEvent(wake.toString(), EventKind.MORNING_FEEDBACK, "Just right", MorningComfort.JUST_RIGHT)) else emptyList()
        runBlocking { ui.graph.repository.addRecord(NightRecord("history-large", "p", "Warm Start, Cool Night",
            start.toString(), wake.toString(), false, events = events)) }
        ui.tab("History")
        ui.waitUntil { ui.compose.onAllNodesWithText("Warm Start, Cool Night", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return "${Format.clock(start)} – ${Format.clock(wake)}"
    }

    @Test fun completeProfileAndTimesRemainReadableAtLargeText() {
        val times = show(false)
        ui.checkLayout(ui.compose.onNodeWithText("Warm Start, Cool Night", useUnmergedTree = true), "History profile")
        ui.checkLayout(ui.compose.onNodeWithText(times, useUnmergedTree = true), "History times")
    }

    @Test fun everyMorningComfortChoiceIsReachableAtLargeText() {
        show(false)
        ui.compose.onNodeWithText(ui.s(io.github.willzy12h.nocturne.R.string.checkin_button)).performScrollTo().performClick()
        for (id in listOf(io.github.willzy12h.nocturne.R.string.checkin_too_hot,
            io.github.willzy12h.nocturne.R.string.checkin_just_right, io.github.willzy12h.nocturne.R.string.checkin_too_cold)) {
            val choice = ui.compose.onNode(hasText(ui.s(id)) and hasClickAction() and hasAnyAncestor(isDialog()))
            // A dialog can display a choice immediately or expose it through a scroll container.
            val node = choice.fetchSemanticsNode()
            val scrollable = generateSequence(node.parent) { it.parent }.any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
            }
            ui.checkControl(choice, ui.s(id), scroll = scrollable)
        }
    }

    @Test fun issueAndComfortPillsRemainWholeTogether() {
        show(true)
        ui.checkLayout(ui.compose.onNodeWithText("Just right", useUnmergedTree = true), "History comfort")
        ui.checkLayout(ui.compose.onNodeWithTag("history-issues-history-large", useUnmergedTree = true), "History issues")
        ui.checkLayout(ui.compose.onNodeWithText("Last night", useUnmergedTree = true), "History date")
    }
}
