package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
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
class ProfileEditorInteractionTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private fun tab(id: Int) {
        ui.compose.onNode(hasText(ui.s(id)) and hasClickAction()).performClick()
        ui.compose.waitForIdle()
    }
    private fun open() {
        tab(R.string.tab_profiles)
        val tag = "profile-open-${ui.graph.repository.profiles.value.first().id}"
        ui.compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        ui.compose.onNodeWithTag(tag).performClick()
    }

    @Test fun profilePreviewsStayPassiveUntilTheirEditorIsOpened() {
        tab(R.string.tab_profiles)
        val previewTag = "profile-timeline-${ui.graph.repository.profiles.value.first().id}"
        ui.compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(previewTag))
        val preview = ui.compose.onNodeWithTag(previewTag).performScrollTo()
        val semantics = preview.fetchSemanticsNode().config
        assertFalse("A profile preview must not offer a phase click action", semantics.contains(SemanticsActions.OnClick))
        assertFalse("A profile preview must not offer phase actions", semantics.contains(SemanticsActions.CustomActions))
        ui.compose.onAllNodes(hasAnyAncestor(hasTestTag(previewTag)) and hasClickAction()).assertCountEquals(0)
        ui.compose.onAllNodes(hasAnyAncestor(hasTestTag(previewTag)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertCountEquals(0)
        preview.performTouchInput { click(); longClick() }
        ui.compose.onNodeWithTag("curve-phase-detail").assertDoesNotExist()
        ui.compose.onNodeWithTag("editor-phase-START-0").assertDoesNotExist()
        ui.compose.onNode(hasText(ui.s(R.string.action_edit)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("profile-card-${ui.graph.repository.profiles.value.first().id}")))
            .performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-phase-START-0").assertExists()
    }

    @Test fun phaseRowsStayCompactUntilExpandedAndCanBeEditedByLongPress() {
        open()
        val phase = ui.compose.onNodeWithTag("editor-phase-START-0")
        phase.performScrollTo().assertIsDisplayed()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertDoesNotExist()
        ui.capture("editor-compact-phase")
        phase.performTouchInput { longClick() }
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertExists()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").performScrollTo()
        ui.capture("editor-expanded-phase")
        ui.compose.onNode(hasContentDescription(ui.s(R.string.slider_length))).performScrollTo().assertIsDisplayed()
    }

    @Test fun addingAPhaseOpensAnEditableRowWithoutSavingItPrematurely() {
        open()
        val initial = ui.graph.repository.profiles.value.first().start.size
        ui.compose.onNodeWithTag("editor-add-START").performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-phase-controls-START-$initial").assertExists()
        assertEquals(initial, ui.graph.repository.profiles.value.first().start.size)
        ui.compose.onNodeWithText(ui.s(R.string.editor_save)).performClick()
        ui.waitUntil { ui.graph.repository.profiles.value.first().start.size == initial + 1 }
    }

    @Test fun tappingTheCurveShowsTheSelectedPhaseTimeframeAndTarget() {
        open()
        val curve = ui.compose.onNodeWithTag("night-curve")
        curve.performTouchInput { click(Offset(width * 0.03f, height / 2f)) }
        ui.compose.onNodeWithTag("curve-phase-detail").assertIsDisplayed()
        ui.compose.onNodeWithTag("curve-phase-detail").assertTextContains("23:30–00:00", substring = true)
        ui.compose.onNodeWithTag("curve-phase-detail").assertTextContains("22°C", substring = true)
    }

    @Test fun modeChoicesShowModelMaximumsWithoutReplacingTheirNames() {
        open()
        ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        listOf("Heat" to "max 40°C", "Turbo" to "max 43°C", "Long heat" to "max 33.5°C", "Cool" to "max 31°C").forEach { (mode, maximum) ->
            ui.compose.onNode(hasText(mode) and hasText(maximum) and hasClickAction() and
                hasAnyAncestor(hasTestTag("editor-phase-controls-START-0"))).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun longPressOnTheCurveOpensAndScrollsToTheSourcePhase() {
        open()
        ui.compose.onNodeWithTag("night-curve").performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertExists()
        ui.compose.onNodeWithTag("editor-phase-START-0").assertIsDisplayed()
    }

    @Test fun longPressingTheSameCurvePhaseAgainReturnsToItsEditor() {
        open()
        val curve = ui.compose.onNodeWithTag("night-curve")
        curve.performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-phase-START-0").assertIsDisplayed()
        curve.performScrollTo().performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-phase-START-0").assertIsDisplayed()
    }
}
