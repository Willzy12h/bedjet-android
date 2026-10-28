package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P4EditorBehaviourTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private lateinit var draft: Profile

    private fun show(profile: Profile) {
        draft = profile
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                ProfileEditor(ui.model, profile, onDone = {}, onDraftChange = { draft = it })
            }
        } }
        ui.compose.waitForIdle()
    }
    private fun choose(label: String) {
        ui.compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        ui.compose.waitForIdle()
    }
    private fun progress(label: String, value: Float) {
        ui.compose.onNode(hasContentDescription(label)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        ui.compose.waitForIdle()
    }

    @Test fun fanShapeChoicesUpdateDraftWithoutChangingTemperatureShape() {
        val profile = ui.graph.repository.profiles.value.first()
        show(profile)
        val temperatures = listOf(profile.middle.tempStart, profile.middle.tempEnd, profile.middle.rampSteps)
        choose("Quiet middle")
        assertEquals(QuietFan(50, 30, 25, 45, 40), draft.middle.quietFan)
        assertEquals(50, draft.middle.fanStart)
        assertEquals(45, draft.middle.fanEnd)
        assertEquals(temperatures, listOf(draft.middle.tempStart, draft.middle.tempEnd, draft.middle.rampSteps))
        choose("Steady")
        assertNull(draft.middle.quietFan)
        assertEquals(50, draft.middle.fanStart)
        assertEquals(50, draft.middle.fanEnd)
        choose("Ease down")
        assertNull(draft.middle.quietFan)
        assertEquals(50, draft.middle.fanStart)
        assertEquals(temperatures, listOf(draft.middle.tempStart, draft.middle.tempEnd, draft.middle.rampSteps))
    }

    @Test fun quietControlsRespectFanLimitsAndMinuteBounds() {
        val profile = ui.graph.repository.profiles.value.first().copy(limits = ProfileLimits(minFan = 30, maxFan = 60))
        show(profile)
        choose("Quiet middle")
        progress("Settle speed", 99f)
        progress("Asleep speed", 7f)
        progress("Rise speed", 53f)
        progress("Settle minutes", 0f)
        progress("Rise minutes", 720f)
        assertEquals(QuietFan(60, 0, 30, 55, 720), draft.middle.quietFan)
        assertEquals(60, draft.middle.fanStart)
        assertEquals(55, draft.middle.fanEnd)
        progress("Settle minutes", -1f)
        progress("Rise minutes", 721f)
        assertEquals(0, draft.middle.quietFan!!.settleMinutes)
        assertEquals(720, draft.middle.quietFan!!.riseMinutes)
        choose("Ease down")
        assertEquals(60, draft.middle.fanStart)
        assertNull(draft.middle.quietFan)
    }

    @Test fun nonMultipleLimitsKeepQuietSpeedsOnTheFivePercentLattice() {
        val profile = ui.graph.repository.profiles.value.first().copy(limits = ProfileLimits(minFan = 32, maxFan = 58))
        show(profile)
        choose("Quiet middle")
        fun assertLegal() {
            val q = draft.middle.quietFan!!
            listOf(q.settle, q.asleep, q.rise).forEach { assertTrue("$it must be a legal fan speed", it in 35..55 && it % 5 == 0) }
            assertEquals(q.settle, draft.middle.fanStart)
            assertEquals(q.rise, draft.middle.fanEnd)
        }
        assertLegal()
        progress("Settle speed", 32f)
        progress("Asleep speed", 58f)
        progress("Rise speed", 53f)
        assertLegal()
    }

    @Test fun changingLimitsReconcilesQuietFanAndCompatibilitySpeeds() {
        val profile = ui.graph.repository.profiles.value.first()
        show(profile)
        choose("Quiet middle")
        val label = ui.s(R.string.slider_fan_limits)
        ui.compose.onNode(hasContentDescription(label)).performScrollTo()
        val thumbs = ui.compose.onAllNodes(hasAnyAncestor(hasContentDescription(label)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress), useUnmergedTree = true)
        thumbs.assertCountEquals(2)
        thumbs[1].performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        thumbs[0].performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        ui.compose.waitForIdle()
        assertEquals(60, draft.limits.minFan)
        assertEquals(80, draft.limits.maxFan)
        val q = draft.middle.quietFan!!
        assertTrue(q.settle in draft.limits.minFan..draft.limits.maxFan)
        assertTrue(q.asleep in draft.limits.minFan..draft.limits.maxFan)
        assertTrue(q.rise in draft.limits.minFan..draft.limits.maxFan)
        assertEquals(q.settle, draft.middle.fanStart)
        assertEquals(q.rise, draft.middle.fanEnd)
    }

    @Test fun limitsWithoutAnyFivePercentSpeedCannotBeSaved() {
        val profile = ui.graph.repository.profiles.value.first().copy(limits = ProfileLimits(minFan = 32, maxFan = 34))
        show(profile)
        choose("Quiet middle")
        ui.compose.onNode(hasText(ui.s(R.string.editor_save)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun phaseLengthSliderAcceptsEndpointsAndClampsOutsideRange() {
        val profile = ui.graph.repository.profiles.value.first().copy(start = listOf(Phase(Mode.COOL, 44, 40, 30)))
        show(profile)
        ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        val label = ui.s(R.string.slider_length)
        for ((input, expected) in listOf(1f to 1, 720f to 720, 0f to 1, 721f to 720)) {
            progress(label, input)
            assertEquals(expected, draft.start.single().minutes)
        }
    }

    @Test fun validAfterWakeDurationEndpointsCanBeSaved() {
        val original = ui.graph.repository.profiles.value.first()
        for (minutes in listOf(1, 720)) {
            val profile = original.copy(id = "valid-$minutes", afterWake = listOf(Phase(Mode.COOL, 44, 40, minutes)))
            show(profile)
            ui.compose.onNode(hasText(ui.s(R.string.editor_save)) and hasClickAction()).assertIsEnabled()
        }
    }

    @Test fun invalidStoredPhaseDurationsCannotBeSaved() {
        val original = ui.graph.repository.profiles.value.first()
        for (minutes in listOf(0, 721)) {
            val invalid = original.copy(id = "invalid-$minutes", start = listOf(Phase(Mode.COOL, 44, 40, minutes)))
            show(invalid)
            ui.compose.onNode(hasText(ui.s(R.string.editor_save)) and hasClickAction()).assertIsNotEnabled()
        }
    }

    @Test fun selectingTurboFixesLengthAndTemperatureAndHidesTheirSliders() {
        val profile = ui.graph.repository.profiles.value.first().copy(start = listOf(Phase(Mode.COOL, 44, 40, 30)))
        show(profile)
        ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        ui.compose.onNode(hasText("Turbo") and hasClickAction() and
            hasAnyAncestor(hasTestTag("editor-phase-controls-START-0"))).performScrollTo().performClick()
        ui.compose.waitForIdle()
        assertEquals(Mode.TURBO, draft.start.single().mode)
        assertEquals(86, draft.start.single().temperature)
        assertEquals(10, draft.start.single().minutes)
        val withinPhase = hasAnyAncestor(hasTestTag("editor-phase-controls-START-0"))
        ui.compose.onAllNodes(hasContentDescription(ui.s(R.string.slider_length)) and withinPhase).assertCountEquals(0)
        ui.compose.onAllNodes(hasContentDescription(ui.s(R.string.slider_temp)) and withinPhase).assertCountEquals(0)
        ui.compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and withinPhase).assertCountEquals(0)
        ui.compose.onNodeWithTag("editor-phase-START-0").assertTextContains("10 min", substring = true)
        ui.compose.onNodeWithTag("editor-phase-START-0").assertTextContains("43°C", substring = true)
    }
}
