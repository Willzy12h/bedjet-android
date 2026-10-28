package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
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

/** Actual Compose flows, with time fixed before the first composition. No hardware evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UiFinalisationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking { graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) } }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)
    private val graph get() = compose.activity.graph

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun newProfileDraftSurvivesRecreationAndOnlySaveAddsIt() {
        val originalIds = graph.repository.profiles.value.map { it.id }.toSet()
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithTag("profiles-new", useUnmergedTree = true).performClick()
        compose.onNode(hasText("Summer Cool") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        waitFor("Edit profile")
        assertEquals(originalIds, graph.repository.profiles.value.map { it.id }.toSet())
        compose.onNode(hasSetTextAction() and hasText("Summer Cool")).performTextReplacement("Recreated new profile")
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        waitFor("Edit profile")
        compose.onNode(hasSetTextAction() and hasText("Recreated new profile")).assertExists()
        assertEquals(originalIds, graph.repository.profiles.value.map { it.id }.toSet())
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { graph.repository.profiles.value.size == originalIds.size + 1 }
        val added = graph.repository.profiles.value.single { it.id !in originalIds }
        assertEquals("Recreated new profile", added.name)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun tonightOverrideIsLabelledAsAnOverrideInEveryLook() {
        for ((theme, simple) in listOf(ThemeMode.DARK to false, ThemeMode.LIGHT to false,
            ThemeMode.DARK to true, ThemeMode.LIGHT to true)) {
            // Resolve the graph and the time here: the repository runs this lambda off the main thread,
            // where compose.activity (behind `graph`) cannot be read under Robolectric.
            val graph = graph
            val now = graph.now()
            compose.runOnUiThread { runBlocking {
                graph.repository.updateSettings {
                    it.copy(themeMode = theme, simpleView = simple)
                        .setWakeOverride(now.plusHours(7), now)
                }
            } }
            waitFor("Tonight-only wake time")
            compose.onAllNodesWithText("Profile's wake time").assertCountEquals(0)
            assertNull(graph.repository.activeNight.value)
        }
    }

    @Test fun feedbackFloatsOverTheScreenWithoutMovingIt() {
        compose.onNodeWithText("Off").performScrollTo()
        val before = compose.onNodeWithText("Off").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Off").performClick()
        waitFor("BedJet off.")
        // Do not wait for the bubble to disappear: the content must not shift while it shows.
        compose.onNodeWithTag("feedback-bubble").assertIsDisplayed()
        val during = compose.onNodeWithText("Off").fetchSemanticsNode().boundsInRoot
        assertEquals("Feedback must overlay, not push the content: before=$before during=$during", before, during)
    }

}
