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
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** Launches the real app on Robolectric with the simulated BedJet, and walks every screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AppSmokeTest {
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
                graph.repository.updateSettings { it.copy(demoMode = true) }
                graph.repository.setActiveNight(null)
            }
        }
    }

    /** Lets Robolectric's clock run while waiting, as real time would; BedJet replies are given short time limits. */
    private fun waitForText(text: String) =
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForConfirmedNight() = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        graph.repository.activeNight.value?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED &&
            graph.repository.history.value.isNotEmpty() && graph.actions.busy.value.runningLabel == null
    }

    @Test
    fun confirmationCannotStopANightThatChangedWhileDialogWasOpen() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        compose.onNodeWithText("End night").performScrollTo().performClick()
        val original = graph.repository.activeNight.value!!
        val replacement = original.copy(revision = original.revision + 1)
        runBlocking { graph.repository.setActiveNight(replacement) }
        compose.waitForIdle()
        compose.onNodeWithText("Stop night and continue").performClick()
        compose.waitForIdle()
        waitForText(compose.activity.getString(R.string.runtime_stale))
        assertEquals(replacement.identity, graph.repository.activeNight.value?.identity)
    }

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
        waitForConfirmedNight()
        assertNotNull(graph.repository.activeNight.value)
        assertEquals(1, graph.repository.history.value.size)
        assertEquals(1, graph.link.status.value?.extra?.programStep)
        compose.onNodeWithText("End night").performScrollTo().performClick()
        // Ending a running night asks first.
        compose.onNodeWithText("Stop night and continue").performClick()
        waitForText("Start night now")
        assertNull(graph.repository.activeNight.value)
    }

    @Test
    fun aStrayTapDuringANightAsksBeforeStoppingIt() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        // Let the "started" message bar time out; it sits over the bottom of the screen.
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            compose.onAllNodes(hasText("started", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.onNodeWithText("Stop tonight's night?").assertIsDisplayed()
        compose.onNodeWithText("Keep tonight").performClick()
        compose.waitForIdle()
        assertNotNull(graph.repository.activeNight.value)
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
