package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.night.NightActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A guarded Stop stays available without presenting a tracked intention as a running wake programme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class NightGuardTruthTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, _ ->
        runBlocking {
            f.graph.actions.confirmMs = 20
            f.graph.actions.freshStatusMs = 20
            f.graph.actions.execute(f.graph.actions.capture(NightActions.Command.START_NIGHT))
        }
    }
    @get:Rule val rules = ui.rules

    private fun uncertainty(confidence: Confidence) = runBlocking {
        val night = ui.graph.repository.activeNight.value!!
        val pending = if (confidence == Confidence.PENDING) PendingChange(
            "guard-pending", ChangeKind.RETIME, ui.graph.now().plusHours(7).toString(),
            ui.graph.now().toString(), ui.graph.now().plusMinutes(1).toString(), night.identity
        ) else null
        ui.graph.repository.setActiveNight(night.copy(confidence = confidence, pending = pending))
    }

    private fun keepManual() = runBlocking {
        ui.graph.actions.execute(ui.graph.actions.capture(NightActions.Command.SNOOZE_WARM))
        ui.graph.actions.execute(ui.graph.actions.capture(NightActions.Command.KEEP_TRACKING))
        assertTrue(ui.graph.repository.activeNight.value!!.trackingManual)
        assertEquals(Confidence.CONFIRMED, ui.graph.repository.activeNight.value!!.confidence)
    }

    private fun assertTruthfulGuard(action: Int, becomeUnknownWhileOpen: Boolean = false) {
        val expected = ui.graph.repository.activeNight.value!!.identity
        val physical = ui.graph.link.status.value
        ui.compose.onNode(hasText(ui.s(action)) and hasClickAction()).performScrollTo().performClick()
        if (becomeUnknownWhileOpen) uncertainty(Confidence.UNKNOWN)
        ui.compose.waitForIdle()
        val dialog = hasAnyAncestor(isDialog())
        ui.compose.onNode(hasText(ui.s(R.string.guard_title)) and dialog).assertIsDisplayed()
        ui.compose.onNode(hasText(ui.s(R.string.guard_confirm)) and hasClickAction() and dialog).assertIsDisplayed()
        ui.compose.onNode(hasText(ui.s(R.string.guard_keep)) and hasClickAction() and dialog).assertIsDisplayed()
        ui.compose.onNode(hasText(ui.s(R.string.guard_turn_off), substring = true) and dialog).assertExists()
        // These are factual claims, unlike the action's name or a category such as Wake and recovery.
        ui.compose.onAllNodes(hasText("is running", substring = true, ignoreCase = true) and dialog).assertCountEquals(0)
        ui.compose.onAllNodes(hasText("waking you at", substring = true, ignoreCase = true) and dialog).assertCountEquals(0)
        val beforeCancel = ui.graph.repository.activeNight.value
        ui.compose.onNode(hasText(ui.s(R.string.guard_keep)) and hasClickAction() and dialog).performClick()
        assertEquals(expected, ui.graph.repository.activeNight.value?.identity)
        assertEquals(beforeCancel, ui.graph.repository.activeNight.value)
        assertEquals(physical, ui.graph.link.status.value)
    }

    @Test fun unknownStopDoesNotClaimARunningWake() {
        uncertainty(Confidence.UNKNOWN)
        assertTruthfulGuard(R.string.parity_end_night)
    }
    @Test fun unknownQuickOffDoesNotClaimARunningWake() {
        uncertainty(Confidence.UNKNOWN)
        assertTruthfulGuard(R.string.feature_off)
    }
    @Test fun pendingStopDoesNotClaimARunningWake() {
        uncertainty(Confidence.PENDING)
        assertTruthfulGuard(R.string.parity_end_night)
    }
    @Test fun pendingQuickOffDoesNotClaimARunningWake() {
        uncertainty(Confidence.PENDING)
        assertTruthfulGuard(R.string.feature_off)
    }
    @Test fun keptManualStopDoesNotClaimAnAlarmWake() {
        keepManual()
        assertTruthfulGuard(R.string.parity_end_night)
    }
    @Test fun keptManualQuickOffDoesNotClaimAnAlarmWake() {
        keepManual()
        assertTruthfulGuard(R.string.feature_off)
    }
    @Test fun anOpenStopDialogDoesNotRetainAConfirmedClaimAfterUncertainty() {
        assertTruthfulGuard(R.string.parity_end_night, becomeUnknownWhileOpen = true)
    }
    @Test fun anOpenQuickOffDialogDoesNotRetainAConfirmedClaimAfterUncertainty() {
        assertTruthfulGuard(R.string.feature_off, becomeUnknownWhileOpen = true)
    }
}
