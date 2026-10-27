package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.TemporaryOverride
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.boostLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.ZonedDateTime
import java.time.Duration

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class CoolOnlyLimits

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemporaryActionDispatchTest {
    private val originalPreset = ActionPreset(Mode.DRY, 60, 25, 15)
    private val ui = U06Fixture(U06Look.MIDNIGHT) { fixture, description -> runBlocking {
        fixture.graph.repository.updateSettings { it.copy(extendWarm = originalPreset) }
        if (description.getAnnotation(CoolOnlyLimits::class.java) != null) {
            val profile = fixture.graph.repository.profiles.value.first()
            fixture.graph.repository.saveProfile(profile.copy(limits = ProfileLimits(maxTemp = 44),
                wake = profile.wake.map { it.copy(mode = Mode.COOL, temperature = 44) }))
        }
        val result = fixture.graph.actions.execute(fixture.graph.actions.capture(Command.START_NIGHT))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
    } }
    @get:Rule val rules = ui.rules
    private val graph get() = ui.graph
    private fun dialogText(text: String) = ui.compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    private fun dialogButton(id: Int): SemanticsNodeInteraction {
        val choice = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        val role = if (id == R.string.temporary_choice || id == R.string.temporary_end_choice) choice else !choice
        return ui.compose.onNode(hasText(ui.s(id)) and hasAnyAncestor(isDialog()) and role)
    }

    private fun duration(minutes: Int) {
        ui.compose.onNode(hasContentDescription(ui.s(R.string.slider_minutes)) and hasAnyAncestor(isDialog()))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(minutes.toFloat())) }
    }

    @Test fun temporaryUsesTheReviewedPresetAndOneActionDurationAndShowsOnlyAPlannedReturn() {
        val original = graph.repository.activeNight.value!!
        ui.checkControl(ui.compose.onNodeWithTag("quick-mode-DRY"), "Dry quick").performClick()
        dialogText("Dry · 30°C · fan 25% · 15 min").assertExists()
        runBlocking { graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.COOL, 44, 40, 30)) } }
        duration(5)
        dialogButton(R.string.temporary_confirm).performClick()
        ui.waitUntil {
            graph.repository.activeNight.value?.let { it.revision == original.revision + 1 && it.confidence == Confidence.CONFIRMED } == true &&
                graph.actions.busy.value.runningLabel == null && !ui.model.busy.value
        }
        val updated = graph.repository.activeNight.value!!
        assertEquals(original.recordId, updated.recordId)
        assertEquals(original.wakeAt, updated.wakeAt)
        val temporary = updated.temporary!!
        assertEquals(listOf(Mode.DRY), temporary.steps.map { it.mode }.distinct())
        assertEquals(5, temporary.steps.sumOf { it.minutes })
        assertTrue(temporary.steps.all { it.temperature == 60 && it.fanPercent == 25 })
        assertEquals(30, graph.repository.settings.value.extendWarm.minutes)
        val planned = ui.s(R.string.temporary_planned, Format.clock(temporary.until))
        ui.compose.onNodeWithText(planned).performScrollTo().assertIsDisplayed()
        val history = graph.repository.history.value
        graph.clock = Clock.fixed(ZonedDateTime.parse(temporary.until).plusMinutes(1).toInstant(), graph.clock.zone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30_100))
        ui.compose.mainClock.advanceTimeBy(30_100)
        ui.waitUntil { ui.compose.onAllNodesWithText(planned).fetchSemanticsNodes().isEmpty() }
        assertEquals("A clock crossing cannot fabricate another confirmed transition", updated, graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
    }

    @Test fun endingUsesTheOriginalPresetAfterSettingsChangeWhileTheChoiceIsOpen() {
        val original = graph.repository.activeNight.value!!
        ui.checkControl(ui.compose.onNodeWithTag("quick-mode-DRY"), "Dry quick").performClick()
        runBlocking { graph.repository.updateSettings { it.copy(extendWarm = ActionPreset(Mode.COOL, 44, 40, 30)) } }
        duration(5)
        dialogButton(R.string.temporary_end_choice).performScrollTo().performClick()
        dialogText(ui.s(R.string.guard_running_waking, original.profile!!.name,
            ui.s(R.string.guard_switch_to, "Dry"))).assertExists()
        dialogButton(R.string.guard_confirm).performClick()
        ui.waitUntil { graph.repository.activeNight.value == null && graph.actions.busy.value.runningLabel == null }
        val status = graph.link.status.value!!
        assertEquals(Mode.DRY, status.mode)
        assertEquals(60, status.targetTemperature)
        assertEquals(25, status.fanPercent)
        assertEquals(15 * 60, status.remainingSeconds)
    }

    @Test fun anUnknownNightExplainsWhyTemporaryIsUnavailableAndRetainsEndingAndCancellation() {
        val unknown = graph.repository.activeNight.value!!.copy(confidence = Confidence.UNKNOWN)
        runBlocking { graph.repository.setActiveNight(unknown) }
        ui.checkControl(ui.compose.onNodeWithTag("quick-mode-DRY"), "Dry quick").performClick()
        dialogButton(R.string.temporary_end_choice).assertIsSelected()
        val preview = graph.actions.previewTemporary(ui.model.quickAction(Command.WARM).temporary.request)
        ui.checkControl(dialogButton(R.string.guard_confirm), "End unavailable temporary", scroll = false).assertIsEnabled()
        ui.checkControl(dialogButton(R.string.guard_keep), "Keep uncertain tonight", scroll = false)
        dialogButton(R.string.temporary_choice).performScrollTo().performClick()
        ui.checkLayout(dialogText(requireNotNull(preview.refusal)), "Complete temporary refusal")
        dialogButton(R.string.temporary_confirm).assertIsNotEnabled()
        dialogButton(R.string.guard_keep).performClick()
        assertEquals(unknown, graph.repository.activeNight.value)
    }

    @Test fun aChangedRevisionCannotBeConfirmedFromTheOpenDialogue() {
        ui.checkControl(ui.compose.onNodeWithTag("quick-mode-DRY"), "Dry quick").performClick()
        // Observe the eligible open dialogue before changing the revision. A first render after
        // the change correctly starts on End instead, which is a different scenario.
        dialogButton(R.string.temporary_choice).assertIsSelected()
        dialogButton(R.string.temporary_confirm).assertIsEnabled()
        val newer = graph.repository.activeNight.value!!.let { it.copy(revision = it.revision + 1) }
        runBlocking { graph.repository.setActiveNight(newer) }
        ui.compose.waitForIdle()
        dialogButton(R.string.temporary_confirm).assertIsNotEnabled()
        dialogButton(R.string.temporary_end_choice).performScrollTo().performClick()
        dialogButton(R.string.guard_confirm).performClick()
        ui.waitUntil { ui.compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertEquals(newer, graph.repository.activeNight.value)
    }

    @Test fun remoteHeatCapturesReportedControlsAndDefaultsToTenMinutes() {
        val original = graph.repository.activeNight.value!!
        ui.tab(ui.s(R.string.tab_remote))
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.model.status.value != null }
        val reported = ui.model.status.value!!
        ui.control(ui.s(R.string.mode_heat)).performClick()
        dialogButton(R.string.temporary_choice).assertIsSelected()
        dialogText(ui.s(R.string.temporary_duration, "10 min")).assertExists()
        duration(3)
        dialogButton(R.string.temporary_confirm).performClick()
        ui.waitUntil { graph.repository.activeNight.value?.revision == original.revision + 1 && graph.actions.busy.value.runningLabel == null }
        val prefix = graph.repository.activeNight.value!!.temporary!!.steps
        assertEquals(listOf(Mode.HEAT), prefix.map { it.mode }.distinct())
        assertEquals(3, prefix.sumOf { it.minutes })
        assertEquals(PlanCompiler.temperatureFor(Mode.HEAT, reported.targetTemperature, original.profile!!.limits), prefix.first().temperature)
        assertEquals(reported.fanPercent, prefix.first().fanPercent)
    }

    @Test fun remoteEndingStillSendsOnlyTheChosenModeAndEndsTheNight() {
        ui.tab(ui.s(R.string.tab_remote))
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.compose.onAllNodes(
            hasText(ui.s(R.string.mode_heat)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        ui.control(ui.s(R.string.mode_heat)).performClick()
        dialogButton(R.string.temporary_end_choice).performScrollTo().performClick()
        ui.compose.onAllNodes(hasText("10 min", substring = true) and hasAnyAncestor(isDialog())).assertCountEquals(0)
        dialogButton(R.string.guard_confirm).performClick()
        ui.waitUntil { graph.repository.activeNight.value == null && graph.actions.busy.value.runningLabel == null }
        assertEquals(Mode.HEAT, graph.link.status.value!!.mode)
    }

    @Test fun turboPreviewIsBoundedWhileLegacyBoostSplittingAndOffGuardRemainIntact() {
        // Boost is still a legacy widget/shortcut action, no longer Tonight's Turbo button.
        val legacy = ui.model.quickAction(Command.BOOST).temporary.request
        val preview = graph.actions.previewTemporary(legacy)
        val details = temporaryDetails(graph.context, legacy, preview, false)
        assertTrue(details.contains("Turbo · fan 100% · 10 min"))
        assertTrue(details.contains("Heat · 40°C · fan 100% · 5 min"))
        ui.checkControl(ui.compose.onNodeWithTag("quick-mode-TURBO"), "Turbo quick").performClick()
        dialogText("Turbo · fan 100% · 10 min").assertExists()
        dialogText("Heat · 40°C · fan 100% · 5 min").assertDoesNotExist()
        dialogButton(R.string.guard_keep).performClick()
        ui.control(ui.s(R.string.feature_off)).performClick()
        dialogButton(R.string.guard_title).assertExists()
        dialogButton(R.string.temporary_choice).assertDoesNotExist()
        dialogButton(R.string.guard_keep).performClick()
        assertNotNull(graph.repository.activeNight.value)
    }

    @CoolOnlyLimits
    @Test fun remoteHeatOutsideTheSavedLimitsOpensARefusalInsteadOfThrowing() {
        ui.tab(ui.s(R.string.tab_remote))
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.compose.onAllNodes(
            hasText(ui.s(R.string.mode_heat)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val failure = runCatching { ui.control(ui.s(R.string.mode_heat)).performClick() }.exceptionOrNull()
        assertNull("A mode outside the saved limits must open a choice/refusal, never throw from the tap", failure)
        dialogButton(R.string.temporary_end_choice).assertIsSelected()
        val reason = graph.actions.previewTemporary(graph.actions.captureTemporary(ActionPreset(Mode.HEAT, 44, 70, 10))).refusal!!
        assertTrue("The refusal must explain the saved limits", reason.contains("limits"))
        dialogButton(R.string.guard_confirm).assertIsEnabled()
        dialogButton(R.string.temporary_choice).performScrollTo().performClick()
        ui.checkLayout(dialogText(reason), "Complete saved-limits refusal")
        dialogButton(R.string.temporary_confirm).assertIsNotEnabled()
    }

    @Test fun unknownProgrammeDoesNotPromiseThePreviousTemporaryReturn() {
        assertNoReturnPromise(Confidence.UNKNOWN, manual = false)
    }

    @Test fun confirmedManualRunDoesNotPromiseThePreviousTemporaryReturn() {
        assertNoReturnPromise(Confidence.CONFIRMED, manual = true)
    }

    private fun assertNoReturnPromise(confidence: Confidence, manual: Boolean) {
        val previous = graph.repository.activeNight.value!!
        val prefix = previous.applied.steps.first().copy(block = Block.TEMPORARY)
        val until = ZonedDateTime.parse(prefix.startsAt).plusMinutes(prefix.minutes.toLong()).toString()
        val uncertain = previous.copy(confidence = confidence, trackingManual = manual,
            applied = previous.applied.copy(steps = listOf(prefix) + previous.applied.steps.drop(1)),
            temporary = TemporaryOverride(previous.applied, listOf(prefix), until))
        runBlocking { graph.repository.setActiveNight(uncertain) }
        ui.compose.waitForIdle()
        ui.compose.onNodeWithText(ui.s(R.string.temporary_planned, Format.clock(until))).assertDoesNotExist()
    }
}
