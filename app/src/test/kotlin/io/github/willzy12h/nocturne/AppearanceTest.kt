package io.github.willzy12h.nocturne

import android.os.Looper
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** The theme choice and simple view, on the real app over the demo BedJet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = compose.activity.graph
    private var previousClock: java.time.Clock? = null

    @org.junit.Before
    fun fixedNightClock() {
        previousClock = graph.clock
        graph.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T22:30:00Z"), java.time.ZoneOffset.UTC)
    }

    @org.junit.After
    fun restoreClock() { previousClock?.let { graph.clock = it } }


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
        compose.onNodeWithText("Boost").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun themeChoiceIsSaved() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Light").performScrollTo().performClick()
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
