package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.core.store.NightRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun suggestionActionsStaySeparateAndOnlyApplyChangesTheProfile() {
        val profile = Presets.summerCool.copy(id = "large-cool", name = "Summer comfort",
            middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 25, tempStart = 44, tempEnd = 44, rampSteps = 3))
        val start = ui.graph.now().minusDays(1)
        val wake = start.plusHours(8)
        runBlocking {
            ui.graph.repository.saveProfile(profile)
            ui.graph.repository.addRecord(NightRecord("large-suggestion", profile.id, profile.name,
                start.toString(), wake.toString(), false,
                events = listOf(HistoryEvent(wake.toString(), EventKind.ENDED, "Night ended"))))
        }
        ui.tab("History")
        ui.compose.onNodeWithText(ui.s(R.string.checkin_button)).performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.checkin_too_hot)).performScrollTo().performClick()
        ui.waitUntil { ui.compose.onAllNodesWithText(ui.s(R.string.checkin_change_title)).fetchSemanticsNodes().isNotEmpty() }
        val apply = ui.compose.onNode(hasText(ui.s(R.string.checkin_apply)) and hasClickAction())
        val keep = ui.compose.onNode(hasText(ui.s(R.string.checkin_keep)) and hasClickAction())
        ui.checkControl(apply, "Apply suggestion", scroll = false)
        ui.checkControl(keep, "Keep profile", scroll = false)
        val applyBounds = apply.fetchSemanticsNode().touchBoundsInRoot
        val keepBounds = keep.fetchSemanticsNode().touchBoundsInRoot
        assertFalse("Suggestion actions overlap: $applyBounds / $keepBounds", applyBounds.overlaps(keepBounds))

        keep.performTouchInput { click() }
        ui.waitUntil { ui.compose.onAllNodesWithText(ui.s(R.string.checkin_change_title)).fetchSemanticsNodes().isEmpty() }
        assertEquals(profile.middle, ui.graph.repository.profile(profile.id)!!.middle)
        // The "check-in saved" bubble can cover the link; checkControl dismisses it the way a person would.
        ui.checkControl(ui.compose.onNodeWithText(ui.s(R.string.checkin_review)), "Review suggestion").performClick()
        ui.checkControl(apply, "Apply reviewed suggestion", scroll = false).performTouchInput { click() }
        ui.waitUntil { ui.graph.repository.profile(profile.id)!!.middle.fanStart == 60 }
        assertEquals(35, ui.graph.repository.profile(profile.id)!!.middle.fanEnd)
        assertEquals(profile.middle.tempStart, ui.graph.repository.profile(profile.id)!!.middle.tempStart)
    }
}
