package io.github.willzy12h.nocturne

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the real app on Robolectric with the simulated BedJet, and walks every screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = compose.activity.graph

    @Before
    fun demoMode() {
        compose.runOnUiThread {
            graph.repository.updateSettings { it.copy(demoMode = true) }
            graph.repository.setActiveNight(null)
        }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun everyTabOpens() {
        compose.onNodeWithText("Start night now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithText("New profile").assertIsDisplayed()
        compose.onNodeWithText("Remote").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Connect to your BedJet to use the remote.") or hasText("Lights")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Nights you run appear here, with every change made during them.").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Backups and snapshots").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun startingANightOnTheDemoBedJetShowsItRunning() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        // Waiting on what is shown lets Robolectric run the main thread, where the work happens.
        waitForText("End night")
        assertNotNull(graph.repository.activeNight.value)
        assertEquals(1, graph.repository.history.value.size)
        assertEquals(1, graph.link.status.value?.extra?.programStep)
        compose.onNodeWithText("End night").performScrollTo().performClick()
        waitForText("Start night now")
        assertNull(graph.repository.activeNight.value)
    }

    @Test
    fun profileEditorOpensAndSaves() {
        compose.onNodeWithText("Profiles").performClick()
        compose.onAllNodes(hasText("Edit"))[0].performClick()
        compose.onNodeWithText("Edit profile").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("New profile").assertIsDisplayed()
    }
}
