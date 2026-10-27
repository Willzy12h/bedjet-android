package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Distinguishes a blocked pointer target from lazy-index selection or an editor callback failure. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class ProfilesEditHitTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT) { f, _ ->
        runBlocking {
            assertTrue(f.graph.actions.setDemoMode(false).succeeded)
            assertTrue(f.graph.actions.setDemoMode(true).succeeded)
            // One ordinary profile removes every dependency on onAllNodes(...)[0] and lazy ordering.
            f.graph.repository.profiles.value.drop(1).forEach { f.graph.repository.deleteProfile(it.id) }
            assertEquals(1, f.graph.repository.profiles.value.size)
        }
    }
    @get:Rule val rules = ui.rules

    private fun edit() = ui.compose.onNode(hasText(ui.s(R.string.action_edit)) and hasClickAction())
    private fun add() = ui.compose.onNode(hasText(ui.s(R.string.profiles_new)) and hasClickAction())

    private fun bounds(): Pair<Rect, Rect> =
        edit().fetchSemanticsNode().boundsInRoot to add().fetchSemanticsNode().touchBoundsInRoot

    private fun description(): String {
        val (editBounds, fabTouch) = bounds()
        return "Edit visible=$editBounds; Edit touch=${edit().fetchSemanticsNode().touchBoundsInRoot}; " +
            "New profile touch=$fabTouch; FAB covers Edit centre=${fabTouch.contains(editBounds.center)}"
    }

    private fun reveal() {
        ui.tab("Profiles")
        edit().performScrollTo()
        ui.compose.waitForIdle()
        edit().assertIsDisplayed()
    }

    private fun tapAndCheckEditor(geometry: String) {
        // Explicit pointer injection preserves hit testing. A semantic OnClick would bypass an overlay.
        edit().performTouchInput { click(center) }
        ui.compose.waitForIdle()
        val presetOpened = ui.compose.onAllNodesWithText(ui.s(R.string.profiles_start_from))
            .fetchSemanticsNodes().isNotEmpty()
        val diagnostic = "$geometry; New profile preset dialog opened=$presetOpened"
        println(diagnostic)
        ui.compose.onNodeWithText(ui.s(R.string.editor_title)).assertExists(diagnostic)
        assertFalse("An Edit tap must not open the New profile chooser: $diagnostic", presetOpened)
    }

    @Test fun editRemainsReachableByScrollingAboveTheFloatingActionAtTwoHundredPercent() {
        reveal()
        val (initialEdit, initialFab) = bounds()
        val gap = with(ui.compose.density) { 8.dp.toPx() }
        val scroll = (initialEdit.bottom - initialFab.top + gap).coerceAtLeast(0f)
        if (scroll > 0f) {
            ui.compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll) }
            ui.compose.waitForIdle()
        }
        ui.checkControl(edit(), "Profile Edit", scroll = false)
        val (editBounds, fabTouch) = bounds()
        val geometry = description()
        assertTrue("The list must scroll Edit above the FAB without clipping it: $geometry",
            editBounds.bottom <= fabTouch.top)
        tapAndCheckEditor(geometry)
    }
}
