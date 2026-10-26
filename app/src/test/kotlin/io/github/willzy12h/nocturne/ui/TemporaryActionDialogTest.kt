package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real running-night Quick entry, including the narrow large-text dialogue. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemporaryActionDialogTest(look: U06Look) {
    private val ui = U06Fixture(look) { fixture, _ ->
        runBlocking {
            val outcome = fixture.graph.actions.execute(fixture.graph.actions.capture(NightActions.Command.START_NIGHT))
            assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        }
    }
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }
    }

    @Test fun runningQuickOffersTemporaryAndEndAndKeepsCancellationAtLargeText() {
        val before = ui.graph.repository.activeNight.value
        ui.control(ui.s(R.string.feature_warm)).performClick()
        val inDialog = hasAnyAncestor(isDialog())
        val temporary = ui.compose.onNode(hasText("Temporarily, then resume") and inDialog)
        temporary.assertExists("A running-night Quick action must offer automatic return as well as ending the night")
        temporary.assertIsSelected()
        ui.checkControl(temporary, "Temporary choice")
        ui.checkControl(ui.compose.onNode(hasText("Stop night") and inDialog and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)), "Stop choice")
        val preview = ui.graph.actions.previewTemporary(ui.model.quickAction(NightActions.Command.WARM).temporary.request)
        ui.checkLayout(ui.compose.onNode(hasText(requireNotNull(temporaryReturn(ui.compose.activity, preview))) and inDialog), "Complete return and wake")
        ui.checkLayout(ui.compose.onNode(hasText(ui.s(R.string.temporary_schedule)) and inDialog), "Complete schedule explanation")
        ui.checkControl(ui.compose.onNode(hasText(ui.s(R.string.temporary_confirm)) and inDialog), "Run, then resume", scroll = false)
        ui.checkControl(ui.compose.onNode(hasText(ui.s(R.string.guard_keep)) and inDialog), "Keep tonight", scroll = false).performClick()
        assertEquals("Cancelling the choice must preserve the running programme", before, ui.graph.repository.activeNight.value)
    }
}
