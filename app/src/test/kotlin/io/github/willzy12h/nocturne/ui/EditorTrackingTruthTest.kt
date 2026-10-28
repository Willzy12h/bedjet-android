package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Editing a saved profile neither confirms a tracked programme nor adopts a different night's consent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EditorTrackingTruthTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, _ ->
        runBlocking {
            f.graph.actions.confirmMs = 20
            f.graph.actions.freshStatusMs = 20
            assertEquals(OutcomeKind.CONFIRMED, f.graph.actions.execute(f.graph.actions.capture(
                NightActions.Command.START_NIGHT, f.graph.repository.profiles.value.first().id)).kind)
        }
    }
    @get:Rule val rules = ui.rules

    private fun openApplyDialog(): Profile {
        val original = ui.graph.repository.profile(ui.graph.repository.activeNight.value!!.profileId)!!
        ui.compose.onNode(hasText(ui.s(R.string.tab_profiles)) and hasClickAction()).performClick()
        ui.compose.onNode(hasScrollToKeyAction()).performScrollToKey(original.id)
        ui.compose.onNodeWithTag("profile-open-${original.id}").performScrollTo().performClick()
        val edited = original.copy(name = "Edited tracked profile")
        ui.compose.onNode(hasSetTextAction() and hasText(ui.s(R.string.editor_name)))
            .performTextReplacement(edited.name)
        ui.compose.onNodeWithText(ui.s(R.string.editor_save)).performClick()
        val dialog = hasAnyAncestor(isDialog())
        ui.compose.onNode(hasText(ui.s(R.string.editor_update_tonight_title)) and dialog).assertIsDisplayed()
        ui.compose.onNode(hasText(ui.s(R.string.editor_update_tonight)) and hasClickAction() and dialog).assertIsDisplayed()
        ui.compose.onNode(hasText(ui.s(R.string.editor_next_night)) and hasClickAction() and dialog).assertIsDisplayed()
        return edited
    }

    private fun unconfirmed(confidence: Confidence) = runBlocking {
        val night = ui.graph.repository.activeNight.value!!
        ui.graph.repository.setActiveNight(night.copy(confidence = confidence,
            pending = if (confidence == Confidence.PENDING) PendingChange("editor-pending", ChangeKind.RETIME,
                ui.graph.now().plusHours(7).toString(), ui.graph.now().toString(),
                ui.graph.now().plusMinutes(1).toString(), night.identity) else null))
    }

    private fun assertNeutralEditorAndSaveChoice() {
        val tracked = ui.graph.repository.activeNight.value!!
        val programme = (ui.graph.link as DemoBedJet).slots[tracked.slot]
        val edited = openApplyDialog()
        // Inspect both the editor's information line and its open save-choice dialog.
        ui.compose.onAllNodes(hasText("is running", substring = true, ignoreCase = true)).assertCountEquals(0)
        ui.compose.onNode(hasText(ui.s(R.string.editor_next_night)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        ui.waitUntil { ui.graph.repository.profile(edited.id) == edited }
        assertEquals("Next night only changes the reusable profile", tracked, ui.graph.repository.activeNight.value)
        assertEquals(programme, (ui.graph.link as DemoBedJet).slots[tracked.slot])
    }

    @Test fun anUnknownTrackedNightIsNotCalledRunningInTheEditorOrItsSaveDialog() {
        unconfirmed(Confidence.UNKNOWN)
        assertNeutralEditorAndSaveChoice()
    }

    @Test fun aPendingTrackedNightIsNotCalledRunningInTheEditorOrItsSaveDialog() {
        unconfirmed(Confidence.PENDING)
        assertNeutralEditorAndSaveChoice()
    }

    @Test fun aKeptManualNightIsNotCalledARunningSavedProfile() {
        runBlocking {
            ui.graph.actions.execute(ui.graph.actions.capture(NightActions.Command.SNOOZE_WARM))
            ui.graph.actions.execute(ui.graph.actions.capture(NightActions.Command.KEEP_TRACKING))
        }
        assertTrue(ui.graph.repository.activeNight.value!!.trackingManual)
        assertNeutralEditorAndSaveChoice()
    }

    @Test fun anUpdateTonightDialogCannotRewriteANightThatReplacedItsOriginal() {
        val edited = openApplyDialog()
        val originalIdentity = ui.graph.repository.activeNight.value!!.identity
        val other = ui.graph.repository.profiles.value.first { it.id != edited.id }
        ui.compose.runOnUiThread { runBlocking {
            assertEquals(OutcomeKind.CONFIRMED, ui.graph.actions.execute(ui.graph.actions.capture(
                NightActions.Command.START_NIGHT, other.id, requireIdentity = true)
                .copy(replacementConfirmed = true)).kind)
        } }
        ui.compose.waitForIdle()
        val replacement = ui.graph.repository.activeNight.value!!
        assertNotEquals(originalIdentity, replacement.identity)
        val programme = (ui.graph.link as DemoBedJet).slots[replacement.slot]
        val generation = ui.graph.repository.runtime.value.generation
        val messages = CopyOnWriteArrayList<String>()
        val collector = ui.graph.scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            ui.model.messages.collect { messages += it }
        }
        try {
            ui.compose.onNode(hasText(ui.s(R.string.editor_update_tonight)) and hasClickAction() and hasAnyAncestor(isDialog()))
                .performClick()
            ui.waitUntil { !ui.model.busy.value && ui.graph.actions.busy.value.runningLabel == null && messages.isNotEmpty() }
            assertEquals("Consent for the original night cannot revise the replacement", replacement, ui.graph.repository.activeNight.value)
            assertEquals(programme, (ui.graph.link as DemoBedJet).slots[replacement.slot])
            assertEquals("A stale dialog cannot advance the command generation", generation, ui.graph.repository.runtime.value.generation)
            assertTrue(messages.toString(), messages.contains(ui.s(R.string.runtime_stale)))
        } finally { collector.cancel() }
    }
}
