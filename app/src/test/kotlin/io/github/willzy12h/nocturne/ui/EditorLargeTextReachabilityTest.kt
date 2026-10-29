package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
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
        ui.checkControl(ui.compose.onNode(hasText("Done") and hasClickAction() and hasAnyAncestor(isDialog())), "Accept typed time", scroll = false)
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
    }
}
