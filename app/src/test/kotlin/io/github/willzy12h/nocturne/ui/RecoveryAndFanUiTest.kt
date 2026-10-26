package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RecoveryAndFanUiTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, _ ->
        runBlocking {
            f.graph.actions.confirmMs = 20
            f.graph.actions.freshStatusMs = 20
            f.graph.actions.execute(f.graph.actions.capture(NightActions.Command.START_NIGHT))
            f.graph.actions.execute(f.graph.actions.capture(NightActions.Command.SNOOZE_WARM))
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun resumeUsesAResumeConfirmationAndRestoresTheProgramme() {
        assertTrue(ui.graph.repository.activeNight.value!!.trackingManual)
        ui.compose.onNodeWithText("Resume night").performScrollTo().performClick()
        val dialog = hasAnyAncestor(isDialog())
        ui.compose.onNode(hasText("Resume night?") and dialog).assertExists()
        ui.compose.onNode(hasText("Resume night") and hasClickAction() and dialog).performClick()
        ui.waitUntil { ui.graph.repository.activeNight.value?.trackingManual == false &&
            ui.graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED && !ui.model.busy.value }
        assertNull(ui.graph.repository.runtime.value.resumeOffer)
    }

    @Test fun stopNightKeepsItsConfirmationAndOnlyAcceptanceEndsTheProgramme() {
        val original = ui.graph.repository.activeNight.value!!.identity
        ui.compose.onNode(hasText("Stop night") and hasClickAction()).performScrollTo().performClick()
        val dialog = hasAnyAncestor(isDialog())
        ui.compose.onNode(hasText("Stop night?") and dialog).assertExists()
        ui.compose.onNode(hasText("Keep tonight") and hasClickAction() and dialog).performClick()
        assertEquals(original, ui.graph.repository.activeNight.value?.identity)
        assertEquals(Mode.HEAT, ui.graph.link.status.value?.mode)
        ui.compose.onNode(hasText("Stop night") and hasClickAction()).performScrollTo().performClick()
        ui.compose.onNode(hasText("Stop night") and hasClickAction() and dialog).performClick()
        ui.waitUntil { !ui.model.busy.value && ui.graph.repository.activeNight.value == null }
        assertEquals(Mode.STANDBY, ui.graph.link.status.value?.mode)
    }

    @Test fun confirmedFanAdjustmentsKeepTheirReceiptButDoNotShowFanSetBubbles() {
        val messages = CopyOnWriteArrayList<String>()
        val outcomes = CopyOnWriteArrayList<CommandOutcome>()
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { ui.model.messages.collect { messages += it } }
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { ui.graph.actions.outcomes.collect { outcomes += it } }
        ui.compose.onNodeWithText("Remote").performClick()
        ui.waitUntil { ui.model.status.value?.mode == Mode.HEAT }
        ui.compose.onNode(hasContentDescription(ui.s(R.string.slider_fan))).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(65f) }
        ui.waitUntil { outcomes.any { it.kind == OutcomeKind.CONFIRMED && it.message == "Fan set" } }
        assertEquals(12, ui.model.status.value?.fanStep)
        assertTrue("Confirmed fan feedback must not create a bubble", messages.none { it.contains("Fan set") })
    }

    @Test fun failedAndUnknownFanFeedbackRemainsVisible() {
        val messages = CopyOnWriteArrayList<String>()
        ui.graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { ui.model.messages.collect { messages += it } }
        ui.compose.runOnIdle {
            ui.model.report(CommandOutcome(OutcomeKind.FAILED, "Fan change failed", origin = CommandOrigin.APP))
            ui.model.report(CommandOutcome(OutcomeKind.UNKNOWN, "Fan state unknown", origin = CommandOrigin.APP))
        }
        ui.waitUntil { messages.size == 2 }
        assertEquals(listOf("Fan change failed", "Fan state unknown"), messages.toList())
    }
}
