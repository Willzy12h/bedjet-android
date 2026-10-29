package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every edit sheet retains its controls and complete text at 320 dp / 200%, in both day and night palettes. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class EditorLargeTextReachabilityTest(private val look: U06Look) {
    private val ui = U06Fixture(look)
    @get:Rule val rules = ui.rules
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = listOf(U06Look.MIDNIGHT, U06Look.DAYLIGHT).map { arrayOf(it) }
    }
    @Test fun everyStepControlAndItsFullLabelIsReachable() {
        ui.compose.openTonightProfileEditor()
        ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        for (mode in listOf(Mode.TURBO, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY, Mode.WAIT))
            ui.checkControl(ui.compose.onNodeWithTag("step-mode-$mode"), "Mode $mode")
        ui.checkLayout(ui.compose.onNodeWithTag("step-temp"), "Temperature value")
        ui.checkLayout(ui.compose.onNodeWithTag("step-length"), "Length value")
        for (tag in listOf("step-temp-down", "step-temp-up", "step-length-down", "step-length-up", "step-chip-30", "step-chip-45", "step-chip-60", "step-chip-120", "step-type-time", "step-delete", "step-done"))
            ui.checkControl(ui.compose.onNodeWithTag(tag), tag)
        ui.compose.onNodeWithTag("step-type-time").performScrollTo().performClick()
        ui.checkControl(ui.compose.onNode(hasText("Done") and hasClickAction() and hasAnyAncestor(isDialog() and hasAnyDescendant(hasTestTag("type-time-hours")))), "Accept typed time", scroll = false)
    }
    @Test fun allAddStepChoicesAndNextRemainReachable() {
        ui.compose.openTonightProfileEditor()
        ui.compose.onNodeWithTag("editor-add-START").performScrollTo().performClick()
        for (mode in listOf(Mode.TURBO, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY, Mode.WAIT))
            ui.checkControl(ui.compose.onNodeWithTag("add-choice-$mode"), "Add $mode")
        ui.checkControl(ui.compose.onNodeWithTag("add-next"), "Next").performClick()
        ui.compose.onNodeWithTag("editor-phase-controls-START-1").assertExists()
    }
    @Test fun middleTemperatureAndShapeLabelsRemainWhole() {
        ui.compose.openTonightProfileEditor()
        ui.compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        for (tag in listOf("middle-temp-start", "middle-temp-end")) ui.checkLayout(ui.compose.onNodeWithTag(tag), tag)
        for (tag in listOf("middle-temp-start-down", "middle-temp-start-up", "middle-temp-end-down", "middle-temp-end-up", "middle-shape-1", "middle-shape-2", "middle-shape-3", "middle-shape-6"))
            ui.checkControl(ui.compose.onNodeWithTag(tag), tag)
        val plotLabels = ui.compose.onAllNodes(hasAnyAncestor(hasTestTag("editor-middle-sheet")), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.singleOrNull()?.takeIf { result ->
                    result.layoutInput.text.text.matches(Regex("[0-9.:–]+(?:°C)?")) && result.layoutInput.style.fontSize.value in 12f..13f
                }
            }
        assertTrue("Inspect the actual temperature and time labels below the shape controls", plotLabels.size >= 3)
        plotLabels.forEach { ui.assertLinesFit(it, "Middle plot ${it.layoutInput.text}") }
    }

    @Test fun aVisibleNeighbourCanBeReachedByDraggingAtLargeText() {
        val cool = Phase(Mode.COOL, 44, 30, 30)
        val heat = Phase(Mode.EXTENDED_HEAT, 67, 40, 40)
        val stored = ui.graph.repository.profiles.value.toList()
        val original = stored.first().copy(start = listOf(cool, heat))
        var draft: Profile = original
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                ProfileEditor(ui.model, original, onDone = {}, onDraftChange = { draft = it })
            }
        } }
        val target = ui.compose.onNodeWithTag("editor-phase-START-1").performScrollTo()
        val source = ui.compose.onNodeWithTag("editor-phase-START-0")
        ui.checkControl(source, "Large-text source row", scroll = false)
        ui.checkControl(target, "Large-text target row", scroll = false)
        val distance = target.fetchSemanticsNode().boundsInRoot.center.y - source.fetchSemanticsNode().boundsInRoot.center.y
        source.performTouchInput {
            down(Offset(23f * ui.compose.density.density, centerY))
            advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            moveBy(Offset(0f, distance))
            up()
        }
        ui.compose.waitForIdle()
        assertEquals("The visible neighbour is the drop destination", listOf(heat, cool), draft.start)
        assertEquals("Large-text reordering is still an unsaved draft", stored, ui.graph.repository.profiles.value)
        ui.capture("large-text-reordered-rows")
    }
}
