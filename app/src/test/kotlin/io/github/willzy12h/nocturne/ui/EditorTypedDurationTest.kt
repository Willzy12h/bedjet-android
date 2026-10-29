package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import io.github.willzy12h.nocturne.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the nested time dialog through the Activity after changing both Middle and a phase. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorTypedDurationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun typedDurationAtTwoTimesDensityKeepsTheCorrectPhaseAndSaves() = editAndSaveTypedDuration()

    @Test fun standaloneTypedDurationStartsWithTheExistingValue() {
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                TypeTimeDialog(30, onDismiss = {}, onSet = {})
            }
        } }
        ui.compose.onNodeWithTag("type-time-hours").assertTextContains("0")
        ui.compose.onNodeWithTag("type-time-minutes").assertTextContains("30")
    }

    @Test fun typedDurationAfterMiddleEditsKeepsTheCorrectPhaseAndSaves() = editAndSaveTypedDuration()

    @Test fun middleTemperatureValuesKeepTheirUnitOnOneLineAtNormalSize() {
        ui.compose.openTonightProfileEditor()
        ui.compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        for (tag in listOf("middle-temp-start", "middle-temp-end")) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            val value = results.single()
            assertEquals("The complete temperature and unit must fit together: ${value.layoutInput.text}", 1, value.lineCount)
            org.junit.Assert.assertFalse("Temperature must not be clipped", value.hasVisualOverflow)
        }
    }

    private fun editAndSaveTypedDuration() {
        val profile = ui.graph.repository.profiles.value.first()
        val compose = ui.compose
        compose.openTonightProfileEditor()
        compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        compose.onNodeWithTag("middle-shape-6").performScrollTo().performClick()
        compose.onNodeWithTag("middle-fan-shape-QUIET_MIDDLE").performScrollTo().performClick()
        compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("editor-middle-sheet"))).performScrollTo().performClick()
        compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        compose.onNodeWithTag("step-mode-HEAT").performScrollTo().performClick()
        compose.onNodeWithTag("step-type-time").performScrollTo().performClick()
        compose.onNodeWithTag("type-time-hours").assertTextContains("0").performTextReplacement("2")
        compose.onNodeWithTag("type-time-minutes").assertTextContains("30").performTextReplacement("45")
        compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasClickAction() and
            hasAnyAncestor(isDialog() and hasAnyDescendant(hasTestTag("type-time-hours")))).performClick()
        compose.onNodeWithTag("step-length").assertTextEquals(ui.s(R.string.parity_h_min, 2, 45))
        compose.onNodeWithTag("step-done").performScrollTo().performClick()
        compose.onNodeWithTag("editor-save").performClick()
        ui.waitUntil { ui.graph.repository.profiles.value.first { it.id == profile.id }.start.first().minutes == 165 }
        val saved = ui.graph.repository.profiles.value.first { it.id == profile.id }
        assertEquals(profile.wake, saved.wake)
        assertEquals(profile.afterWake, saved.afterWake)
        assertEquals(io.github.willzy12h.nocturne.core.plan.FanShape.QUIET_MIDDLE, saved.middle.fanShape)
    }
}
