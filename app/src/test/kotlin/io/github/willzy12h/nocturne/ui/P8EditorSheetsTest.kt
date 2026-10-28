package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Profile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P8EditorSheetsTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private lateinit var draft: Profile
    private fun show(initialPhase: EditorPhaseSelection? = null) {
        val profile = ui.graph.repository.profiles.value.first()
        draft = profile
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                ProfileEditor(ui.model, profile, onDone = {}, onDraftChange = { draft = it }, initialPhase = initialPhase)
            }
        } }
        ui.compose.waitForIdle()
    }
    @Test fun phaseGroupsPrecedeTimingAndLimits() {
        show()
        val phase = ui.compose.onNodeWithTag("editor-phase-START-0").fetchSemanticsNode().positionInRoot.y
        val timing = ui.compose.onNodeWithText(ui.s(R.string.editor_timing)).fetchSemanticsNode().positionInRoot.y
        val limits = ui.compose.onNodeWithText(ui.s(R.string.editor_limits)).fetchSemanticsNode().positionInRoot.y
        assertTrue(phase < timing && timing < limits)
    }
    @Test fun middleSheetRetainsDraftAfterDone() {
        show()
        ui.compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-middle-sheet").assertIsDisplayed()
        ui.compose.onNode(hasText("Quiet middle") and hasClickAction()).performScrollTo().performClick()
        ui.compose.onNode(hasContentDescription("Settle speed")).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        ui.compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasAnyAncestor(hasTestTag("editor-middle-sheet"))).performClick()
        ui.compose.onNodeWithTag("editor-middle-sheet").assertDoesNotExist()
        assertEquals(60, draft.middle.quietFan!!.settle)
    }
    @Test fun phaseSheetRetainsDraftAndInitialPhaseOpens() {
        show(EditorPhaseSelection(Block.START, 0))
        val sheet = "editor-phase-controls-START-0"
        ui.compose.onNodeWithTag(sheet).assertIsDisplayed()
        ui.compose.onNode(hasContentDescription(ui.s(R.string.slider_length))).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
        ui.compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasAnyAncestor(hasTestTag(sheet))).performScrollTo().performClick()
        ui.compose.onNodeWithTag(sheet).assertDoesNotExist()
        assertEquals(17, draft.start.first().minutes)
        ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        ui.compose.onNodeWithTag(sheet).assertIsDisplayed()
    }
    @Test fun backClosesMiddleSheetAndKeepsDraft() {
        show()
        ui.compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        ui.compose.onNode(hasText("Quiet middle") and hasClickAction()).performScrollTo().performClick()
        ui.compose.runOnUiThread {
            val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .last { it.isShown && it !== ui.compose.activity.window.decorView }
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithTag("editor-middle-sheet").assertDoesNotExist()
        assertNotNull(draft.middle.quietFan)
    }
    @Test fun curveMiddleLongPressOpensMiddleSheet() {
        show()
        ui.compose.onNodeWithTag("night-curve").performTouchInput { longClick(Offset(width * .5f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-middle-sheet").assertIsDisplayed()
    }

}
