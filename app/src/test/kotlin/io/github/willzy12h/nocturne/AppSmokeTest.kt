package io.github.willzy12h.nocturne

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking

/** Launches the real app on Robolectric with the simulated BedJet, and walks every screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AppSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val pinClock = TestRule { base, description ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication() as App
                val graph = app.graph
                val previous = graph.clock
                val previousScale = app.resources.configuration.fontScale
                graph.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T22:30:00Z"), java.time.ZoneOffset.UTC)
                if (description.methodName == "largeTextNavigationKeepsFiveTabsReachable") {
                    val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = 2f }
                    @Suppress("DEPRECATION")
                    app.resources.updateConfiguration(config, app.resources.displayMetrics)
                }
                try { base.evaluate() } finally {
                    graph.clock = previous
                    if (app.resources.configuration.fontScale != previousScale) {
                        val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = previousScale }
                        @Suppress("DEPRECATION")
                        app.resources.updateConfiguration(config, app.resources.displayMetrics)
                    }
                }
            }
        }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(pinClock).around(compose)

    private val graph get() = compose.activity.graph


    @Before
    fun demoMode() {
        compose.runOnUiThread {
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, simpleView = false) }
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
    fun handoverIsReachableWhileIdle() {
        compose.onNodeWithText("More").performScrollTo().performClick()
        compose.onNodeWithText("Release BedJet for another phone").assertIsDisplayed()
    }

    @Test
    fun platformSettingsAndHistoryExportAreReachableFromTabs() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Bedtime reminder").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Export history CSV").assertIsDisplayed()
    }

    @Test
    fun anUnknownNightDoesNotClaimTheBedJetIsRunningItsPlan() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        runBlocking {
            val night = graph.repository.activeNight.value!!
            graph.repository.setActiveNight(night.copy(confidence = io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("BedJet state unknown", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Planned wake").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("End night")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Keep tracking").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stoppedNightOffersIdleResumeUntilItExpires() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        compose.onNodeWithText("Stop tracking").performScrollTo().assertIsDisplayed()
        runBlocking { graph.actions.execute(graph.actions.capture(io.github.willzy12h.nocturne.night.NightActions.Command.STOP_TRACKING)) }
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.onAllNodes(hasText("Resume previous night")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Resume previous night").performScrollTo().assertIsDisplayed()
        graph.clock = java.time.Clock.offset(graph.clock, Duration.ofMinutes(16))
        compose.mainClock.advanceTimeBy(30_001)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Resume previous night")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aReplacedNightCanBeResumedFromTonightWhileItsOfferLasts() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        runBlocking { graph.actions.execute(graph.actions.capture(io.github.willzy12h.nocturne.night.NightActions.Command.START_NIGHT,
            requireIdentity = true).copy(replacementConfirmed = true)) }
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.onAllNodes(hasText("Resume previous night")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Resume previous night").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aKeptManualRunShowsItsEndNotAWakeUp() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        runBlocking {
            graph.actions.execute(graph.actions.capture(io.github.willzy12h.nocturne.night.NightActions.Command.SNOOZE_WARM))
            graph.actions.execute(graph.actions.capture(io.github.willzy12h.nocturne.night.NightActions.Command.KEEP_TRACKING))
        }
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.onAllNodes(hasText("Manual run until")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Waking you at")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Resume programme").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completedNightCheckInRecordsComfortWithoutChangingAProfile() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        val profileBefore = graph.repository.profiles.value.first { it.id == graph.repository.activeNight.value!!.profileId }
        compose.onNodeWithText("End night").performScrollTo().performClick()
        compose.onNodeWithText("Stop night and continue").performClick()
        waitForText("Start night now")
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Morning check-in").performScrollTo().performClick()
        compose.onNodeWithText("Just right").performClick()
        waitForText("Morning check-in: Just right")
        assertEquals(profileBefore, graph.repository.profiles.value.first { it.id == profileBefore.id })
        assertTrue(compose.onAllNodes(hasText("Review Middle suggestion")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun pinPreviewProtectsTheWorkingSlotAndStaleSelection() {
        compose.onNodeWithText("Profiles").performClick()
        compose.onAllNodesWithText("More")[0].performScrollTo().performClick()
        compose.onNodeWithText("Keep on BedJet slot…").performClick()
        compose.onNodeWithText("Slot 1", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("Slot 3", substring = false)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Keep on slot 1").assertIsDisplayed()
        runBlocking { graph.repository.updateSettings { it.copy(workingSlot = 0) } }
        waitForText("The BedJet, slot setting or profile changed. Close this preview and open it again.")
        compose.onNodeWithText("Keep on slot 1").assertIsNotEnabled()
    }

    @Test
    fun heldOpenPinPreviewExpiresBeforeConfirmation() {
        compose.onNodeWithText("Profiles").performClick()
        compose.onAllNodesWithText("More")[0].performScrollTo().performClick()
        compose.onNodeWithText("Keep on BedJet slot…").performClick()
        compose.onNodeWithText("Keep on slot 1").assertIsDisplayed()
        graph.clock = java.time.Clock.offset(graph.clock, Duration.ofMinutes(2))
        compose.mainClock.advanceTimeBy(5_001)
        waitForText("This programme preview is out of date. Close it and open it again before writing a slot.")
        compose.onNodeWithText("Keep on slot 1").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    fun largeTextNavigationKeepsFiveTabsReachable() {
        val navigation = compose.onNodeWithTag("main-navigation").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Compact navigation must fit in its bar", navigation.height <= 90f * density)
        listOf("Tonight", "Profiles", "Remote", "History", "Settings").forEach { label ->
            val destination = compose.onNodeWithContentDescription(label)
            destination.assertIsDisplayed()
            val bounds = destination.fetchSemanticsNode().boundsInRoot
            assertTrue("$label is clipped by the navigation bar", bounds.top >= navigation.top && bounds.bottom <= navigation.bottom)
            destination.performClick()
            compose.waitForIdle()
            compose.onNodeWithText(label).assertIsDisplayed()
        }
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
