package io.github.willzy12h.nocturne.ui

import android.os.Looper
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

/** P5 F08: with no BedJet chosen, setup is reachable from Tonight and Remote, with a separate demo choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SetupRouteTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.setActiveNight(null)
                graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null, themeMode = ThemeMode.DARK) }
            }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int) = compose.activity.getString(id)
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        condition()
    }

    @Test fun tonightOffersTheDemoAsASeparateChoice() {
        val graph = graph
        compose.onNodeWithText(s(R.string.setup_use_demo)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.setup_use_demo_help)).assertExists()
        compose.onNodeWithText(s(R.string.setup_use_demo)).performClick()
        waitUntil { graph.repository.settings.value.demoMode }
        assertNull("the demo never becomes the real target", graph.repository.settings.value.targetDevice)
    }

    @Test fun remoteRoutesToChoosingABedJet() {
        compose.onNodeWithText("Remote").performClick()
        compose.onNodeWithText(s(R.string.setup_choose_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.setup_find)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Connect to your BedJet to use the remote.").fetchSemanticsNodes().isEmpty())
    }

    @Test fun settingsPhoneSetupHasSamsungBatteryGuidance() {
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.onNodeWithText(s(R.string.setup_samsung_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.setup_samsung_help)).assertExists()
    }
}
