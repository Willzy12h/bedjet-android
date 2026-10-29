package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Offset
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Mode
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

    @Test fun profilePreviewHasNoPhaseActionsAndCardTapOpensItsEditor() {
        tab(R.string.tab_profiles)
        val id = ui.graph.repository.profiles.value.first().id
        val previewTag = "profile-timeline-$id"
        ui.compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("profile-open-$id"))
        val preview = ui.compose.onNodeWithTag(previewTag, useUnmergedTree = true)
        val semantics = preview.fetchSemanticsNode().config
        assertFalse("A profile strip must not offer a separate phase click", semantics.contains(SemanticsActions.OnClick))
        assertFalse("A profile strip must not offer phase actions", semantics.contains(SemanticsActions.CustomActions))
        ui.compose.onAllNodes(hasAnyAncestor(hasTestTag(previewTag)) and hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        ui.compose.onAllNodes(hasAnyAncestor(hasTestTag(previewTag)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true).assertCountEquals(0)
        preview.performTouchInput { click() }
        ui.compose.onNodeWithTag("curve-phase-detail").assertDoesNotExist()
        ui.compose.onNodeWithTag("editor-phase-START-0").assertExists()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertDoesNotExist()
    }

    @Test fun phaseRowsStayCompactUntilTappedAndOpenTheirEditSheet() {
        open()
        val phase = ui.compose.onNodeWithTag("editor-phase-START-0")
        phase.performScrollTo().assertIsDisplayed()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertDoesNotExist()
        ui.capture("editor-compact-phase")
        phase.performTouchInput { click() }
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertExists()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertIsDisplayed()
        ui.capture("editor-expanded-phase")
        ui.compose.onNode(hasContentDescription(ui.s(R.string.slider_length))).performScrollTo().assertIsDisplayed()
    }

    @Test fun addingAPhaseOpensAnEditableRowWithoutSavingItPrematurely() {
        open()
        val initial = ui.graph.repository.profiles.value.first().start.size
        ui.compose.onNodeWithTag("editor-add-START").performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-add-sheet").assertExists()
        ui.compose.onNodeWithTag("add-next").performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-phase-controls-START-$initial").assertExists()
        assertEquals(initial, ui.graph.repository.profiles.value.first().start.size)
        ui.compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasAnyAncestor(hasTestTag("editor-phase-controls-START-$initial"))).performClick()
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

    @Test fun compactModeChoicesSpeakFullNamesAndModelMaximums() {
        open()
        ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        listOf("Heat" to "max 40°C", "Turbo" to "max 43°C", "Long heat" to "max 33.5°C", "Cool" to "max 31°C").forEach { (mode, maximum) ->
            ui.compose.onNode(hasContentDescription("$mode,", substring = true) and hasClickAction() and
                hasAnyAncestor(hasTestTag("editor-phase-controls-START-0"))).performScrollTo().assertIsDisplayed()
                .assertContentDescriptionContains(maximum, substring = true)
        }
    }

    @Test fun longPressOnTheCurveOpensAndScrollsToTheSourcePhase() {
        open()
        ui.compose.onNodeWithTag("night-curve").performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertExists()
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertIsDisplayed()
    }

    @Test fun longPressingTheSameCurvePhaseAgainReturnsToItsEditor() {
        open()
        val curve = ui.compose.onNodeWithTag("night-curve")
        curve.performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasAnyAncestor(hasTestTag("editor-phase-controls-START-0"))).performClick()
        curve.performScrollTo().performTouchInput { longClick(Offset(width * .03f, height / 2f)) }
        ui.compose.onNodeWithTag("editor-phase-controls-START-0").assertIsDisplayed()
    }
    @Test fun draggingAcrossTwoNeighboursMovesTheGrabbedPhaseOnlyOnceOnDrop() {
        val a = Phase(Mode.HEAT, 64, 40, 20)
        val b = Phase(Mode.COOL, 44, 30, 30)
        val c = Phase(Mode.DRY, 56, 50, 10)
        val stored = ui.graph.repository.profiles.value.toList()
        val original = stored.first().copy(start = listOf(a, b, c))
        var draft: Profile = original
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                ProfileEditor(ui.model, original, onDone = {}, onDraftChange = { draft = it })
            }
        } }
        val card = ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo()
        val density = ui.compose.activity.resources.displayMetrics.density
        card.performTouchInput {
            down(Offset(23f * density, centerY))
            advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveBy(Offset(0f, 80f * density))
        }
        ui.compose.waitForIdle()
        card.performTouchInput {
            moveBy(Offset(0f, 80f * density))
            up()
        }
        ui.compose.waitForIdle()
        assertEquals("The grabbed phase must follow both neighbours, without moving its replacement", listOf(b, c, a), draft.start)
        assertEquals("Reordering is still an unsaved draft", stored, ui.graph.repository.profiles.value)
    }

}
