package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** P5 U05: sliders carry a label, a unit and their value; switch rows are one 48 dp toggle. Semantics only, not TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AccessibilitySemanticsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.setActiveNight(null)
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK, boostMinutes = 15, fahrenheit = false) }
            }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(io.github.willzy12h.nocturne.GraphLifecycle()).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun slider(label: String) = compose.onNode(hasContentDescription(label) and hasProgressBarRangeInfoAnyValue())
    private fun hasProgressBarRangeInfoAnyValue() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private fun stateOf(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun editorSlidersSayWhatTheySetInWhichUnitAndTheirValue() {
        val profile = graph.repository.profiles.value.first()
        compose.navTab("Profiles")
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(profile.id)
        compose.onNodeWithTag("profile-open-${profile.id}").performScrollTo().performClick()
        compose.onNodeWithTag("editor-save").assertExists()
        val middle = profile.middle
        compose.onNodeWithTag("editor-middle-summary").performScrollTo().performClick()
        if (middle.mode.usesFan) {
            val fan = slider(s(R.string.slider_fan_start)).performScrollTo()
            assertEquals(s(R.string.a11y_percent, middle.fanStart), stateOf(fan))
        }
        if (middle.mode.usesTemperature) {
            val temp = slider(s(R.string.slider_temp_start)).performScrollTo()
            assertTrue(stateOf(temp).orEmpty(), stateOf(temp).orEmpty().endsWith("degrees Celsius"))
        }
        val steps = slider(s(R.string.slider_steps)).performScrollTo()
        assertEquals(compose.activity.resources.getQuantityString(R.plurals.a11y_steps, middle.rampSteps, middle.rampSteps), stateOf(steps))
        compose.onNode(hasText(s(R.string.uat_setup_done)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("editor-middle-sheet"))).performScrollTo().performClick()
        val fanLimits = compose.onNode(hasContentDescription(s(R.string.slider_fan_limits))).performScrollTo()
        assertEquals(s(R.string.a11y_range, s(R.string.a11y_percent, profile.limits.minFan), s(R.string.a11y_percent, profile.limits.maxFan)), stateOf(fanLimits))
    }

    @Test fun theBoostSliderSaysItsMinutes() {
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.openSettingsPage("notifications")
        // Settings opens the one Boost editor, whose slider sets the Heat that follows Turbo's ten minutes.
        compose.onNodeWithTag("settings-boost-row").performScrollTo().performClick()
        val heat = slider(s(R.string.boost_editor_heat))
        assertEquals(compose.activity.resources.getQuantityString(R.plurals.a11y_minutes, 5, 5), stateOf(heat))
    }

    @Test fun featureSwitchesAreOneToggleableRowAtLeast48dpTall() {
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.openSettingsPage("night")
        val label = s(R.string.feature_reminders)
        val row = compose.onNode(hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.performScrollTo()
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height >= 48f * density - 1f)
        assertTrue(row.fetchSemanticsNode().config.contains(SemanticsProperties.ToggleableState))
        // One action per row: no second clickable switch inside it.
        val clickable = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasContentDescription(label))).fetchSemanticsNodes()
        assertTrue("$clickable", clickable.isEmpty())
    }
}
