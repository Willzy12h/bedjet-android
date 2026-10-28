package io.github.willzy12h.nocturne

import android.os.Looper
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** The theme choice and simple view, on the real app over the demo BedJet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AppearanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val pinClock = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication() as App
                val oldClock = app.graph.clock
                app.graph.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T22:30:00Z"), java.time.ZoneOffset.UTC)
                try { base.evaluate() } finally { app.graph.clock = oldClock }
            }
        }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(pinClock).around(compose)

    private val graph get() = compose.activity.graph


    @Before
    fun demoMode() {
        compose.runOnUiThread {
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, simpleView = false, themeMode = ThemeMode.SYSTEM) }
                graph.repository.setActiveNight(null)
            }
        }
    }

    /** Lets Robolectric's clock run while waiting, as the smoke tests do. */
    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(text: String) = waitFor(hasText(text))

    @Test
    fun simpleViewListsTheNightAndEndsAtTheWakeTime() {
        compose.runOnUiThread { runBlocking { graph.repository.updateSettings { it.copy(simpleView = true) } } }
        waitFor(hasText("Wake time") or hasText("Your alarm"))
        compose.onNodeWithText("Start night now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("quick-mode-TURBO")
            .performScrollTo().assertIsDisplayed()
            .assertTextContains("Turbo").assertTextContains("10 min")
    }

    @Test
    fun themeChoiceIsSaved() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Light").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.ux_light_theme_warning)).assertIsDisplayed()
        assertEquals(ThemeMode.SYSTEM, graph.repository.settings.value.themeMode)
        compose.onNodeWithText(compose.activity.getString(R.string.ux_switch)).performClick()
        waitForText("Simple view")
        // Settings are saved off the main thread and show once they are on disk.
        compose.waitUntil(5_000) { graph.repository.settings.value.themeMode != ThemeMode.SYSTEM }
        assertEquals(ThemeMode.LIGHT, graph.repository.settings.value.themeMode)
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        waitForText("Simple view")
        compose.waitUntil(5_000) { graph.repository.settings.value.themeMode != ThemeMode.LIGHT }
        assertEquals(ThemeMode.DARK, graph.repository.settings.value.themeMode)
    }
}
