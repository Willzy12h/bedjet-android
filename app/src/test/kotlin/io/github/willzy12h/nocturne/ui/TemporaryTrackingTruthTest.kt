package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Choosing an unavailable Temporary route must not turn tracked state into a running-programme claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemporaryTrackingTruthTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ -> runBlocking {
        fixture.graph.actions.confirmMs = 20
        fixture.graph.actions.freshStatusMs = 20
        assertEquals(OutcomeKind.CONFIRMED, fixture.graph.actions.execute(
            fixture.graph.actions.capture(Command.START_NIGHT)).kind)
    } }
    @get:Rule val rules = ui.rules

    private fun uncertainty(confidence: Confidence) = runBlocking {
        val night = ui.graph.repository.activeNight.value!!
        val pending = if (confidence == Confidence.PENDING) PendingChange("temporary-truth-pending", ChangeKind.RETIME,
            ui.graph.now().plusHours(7).toString(), ui.graph.now().toString(),
            ui.graph.now().plusMinutes(1).toString(), night.identity) else null
        ui.graph.repository.setActiveNight(night.copy(confidence = confidence, pending = pending))
    }

    private fun checkChoice(becomeUnknownWhileOpen: Boolean = false) {
        val identity = ui.graph.repository.activeNight.value!!.identity
        val physical = ui.graph.link.status.value
        ui.control(ui.s(R.string.feature_warm)).performClick()
        val inDialog = hasAnyAncestor(isDialog())
        val selectedChoice = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        fun button(id: Int) = ui.compose.onNode(hasText(ui.s(id)) and hasClickAction() and inDialog and !selectedChoice)
        fun choice(id: Int) = ui.compose.onNode(hasText(ui.s(id)) and hasClickAction() and inDialog and selectedChoice)
        if (becomeUnknownWhileOpen) {
            button(R.string.temporary_confirm).assertIsEnabled()
            uncertainty(Confidence.UNKNOWN)
        } else {
            choice(R.string.temporary_end_choice).assertIsSelected()
            button(R.string.guard_confirm).assertIsEnabled()
            choice(R.string.temporary_choice).performScrollTo().performClick()
        }
        ui.compose.waitForIdle()
        button(R.string.temporary_confirm).assertIsNotEnabled()
        ui.compose.onAllNodes(hasText("is running", substring = true, ignoreCase = true) and inDialog).assertCountEquals(0)
        ui.compose.onAllNodes(hasText("waking you at", substring = true, ignoreCase = true) and inDialog).assertCountEquals(0)
        val beforeCancel = ui.graph.repository.activeNight.value
        button(R.string.guard_keep).performClick()
        assertEquals(identity, ui.graph.repository.activeNight.value?.identity)
        assertEquals(beforeCancel, ui.graph.repository.activeNight.value)
        assertEquals(physical, ui.graph.link.status.value)
    }

    @Test fun unknownTemporaryChoiceDoesNotClaimARunningWake() {
        uncertainty(Confidence.UNKNOWN)
        checkChoice()
    }
    @Test fun pendingTemporaryChoiceDoesNotClaimARunningWake() {
        uncertainty(Confidence.PENDING)
        checkChoice()
    }
    @Test fun manualTemporaryChoiceDoesNotClaimARunningSavedProgramme() {
        ui.compose.runOnUiThread { runBlocking {
            ui.graph.actions.execute(ui.graph.actions.capture(Command.SNOOZE_WARM))
            ui.graph.actions.execute(ui.graph.actions.capture(Command.KEEP_TRACKING))
        } }
        assertTrue(ui.graph.repository.activeNight.value!!.trackingManual)
        checkChoice()
    }
    @Test fun anOpenTemporaryChoiceDoesNotKeepAConfirmedClaimAfterUncertainty() = checkChoice(becomeUnknownWhileOpen = true)

    @Test fun theTemporaryAlternativeNamesStopNightAndStillRequiresItsGuardedConfirmation() {
        val before = ui.graph.repository.activeNight.value
        val physical = ui.graph.link.status.value
        ui.control(ui.s(R.string.feature_warm)).performClick()
        val inDialog = hasAnyAncestor(isDialog())
        val selectedChoice = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        val stopChoice = ui.compose.onNode(hasText(ui.s(R.string.temporary_end_choice)) and
            hasClickAction() and inDialog and selectedChoice)
        stopChoice.assertTextEquals("Stop night")
        stopChoice.performScrollTo().performClick()
        ui.compose.onNode(hasText(ui.s(R.string.guard_confirm)) and hasClickAction() and inDialog and !selectedChoice)
            .assertIsEnabled()
        assertEquals("Selecting Stop still waits for the explicit confirmation", before, ui.graph.repository.activeNight.value)
        ui.compose.onNode(hasText(ui.s(R.string.guard_keep)) and hasClickAction() and inDialog and !selectedChoice)
            .performClick()
        assertEquals(before, ui.graph.repository.activeNight.value)
        assertEquals(physical, ui.graph.link.status.value)
    }
}
