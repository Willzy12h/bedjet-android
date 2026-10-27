package io.github.willzy12h.nocturne

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
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
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.service.BedJetService
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import android.Manifest
import android.bluetooth.BluetoothAdapter
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
    private val graphs = GraphLifecycle()
    private var usesRealHistory = false
    private val pinClock = TestRule { base, description ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication() as App
                val fixedClock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T22:30:00Z"), java.time.ZoneOffset.UTC)
                usesRealHistory = description.methodName == "completedNightCheckInRecordsComfortWithoutChangingAProfile"
                val graph = if (usesRealHistory) {
                    // This case needs real History while the external BedJet remains an injected receipt fake.
                    val replacement = graphs.fresh { Graph(app, DemoBedJet(now = { java.time.ZonedDateTime.now(fixedClock) })) }
                    ReflectionHelpers.setField(app, "graph", replacement)
                    shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                    @Suppress("DEPRECATION")
                    shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
                    replacement
                } else app.graph
                val previous = graph.clock
                val previousScale = app.resources.configuration.fontScale
                graph.clock = fixedClock
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
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(pinClock).around(compose)

    private val graph get() = compose.activity.graph
    private fun s(id: Int) = compose.activity.getString(id)
    private fun confirmStopNight() = compose.onNode(
        hasText(s(R.string.guard_confirm)) and hasClickAction() and hasAnyAncestor(isDialog())
    ).performClick()


    @Before
    fun demoMode() {
        compose.runOnUiThread {
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = !usesRealHistory, simpleView = false,
                    targetDevice = if (usesRealHistory) "ble:AA:BB:CC:DD:EE:FF" else it.targetDevice) }
                graph.repository.setActiveNight(null)
            }
        }
    }

    /** Lets Robolectric's clock run while waiting, as real time would; BedJet replies are given short time limits. */
    private fun deliverServiceRequest(service: BedJetService?) {
        if (service == null) return
        shadowOf(RuntimeEnvironment.getApplication() as App).nextStartedService?.let {
            service.onStartCommand(it, 0, 1)
        }
    }

    private fun waitForText(text: String, service: BedJetService? = null) =
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            deliverServiceRequest(service)
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForConfirmedNight(service: BedJetService? = null) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        deliverServiceRequest(service)
        graph.repository.activeNight.value?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED &&
            graph.repository.history.value.isNotEmpty() && graph.actions.busy.value.runningLabel == null
    }

    @Test
    fun confirmationCannotStopANightThatChangedWhileDialogWasOpen() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitForConfirmedNight()
        compose.onNodeWithText(s(R.string.tonight_end_night)).performScrollTo().performClick()
        val original = graph.repository.activeNight.value!!
        val replacement = original.copy(revision = original.revision + 1)
        runBlocking { graph.repository.setActiveNight(replacement) }
        compose.waitForIdle()
        confirmStopNight()
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
            compose.onAllNodes(hasText(s(R.string.remote_connect)) or
                (hasText(s(R.string.mode_cool)) and hasClickAction())).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Nights you run appear here, with every change made during them.").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Backups and snapshots").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun handoverIsReachableWhileIdle() {
        compose.onNodeWithText(s(R.string.tab_settings)).performClick()
        compose.onNodeWithText(s(R.string.feature_release)).performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithTag("tonight-fixed-wake").assertIsDisplayed()
            .assertTextContains(s(R.string.gpt12_wake_profile_fallback))
        compose.onNodeWithText(s(R.string.tonight_waking_at)).assertDoesNotExist()
        assertTrue(compose.onAllNodes(hasText("Now:", substring = true)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("night-active-phase").assertDoesNotExist()
        // Stop is deliberately available in UNKNOWN state; its presence must not claim a confirmed run.
        val original = graph.repository.activeNight.value!!.identity
        compose.onNodeWithText(s(R.string.tonight_end_night)).performScrollTo().performClick()
        compose.onNode(hasText(s(R.string.guard_title)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(s(R.string.guard_keep)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(original, graph.repository.activeNight.value?.identity)
        assertEquals(io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
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
            compose.onAllNodes(hasText(s(R.string.recovery_resume_previous))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(s(R.string.recovery_resume_previous)).performScrollTo().assertIsDisplayed()
        graph.clock = java.time.Clock.offset(graph.clock, Duration.ofMinutes(16))
        compose.mainClock.advanceTimeBy(30_001)
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText(s(R.string.recovery_resume_previous))).fetchSemanticsNodes().isEmpty())
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
            compose.onAllNodes(hasText(s(R.string.recovery_resume_previous))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(s(R.string.recovery_resume_previous)).performScrollTo().assertIsDisplayed()
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
            compose.onAllNodes(hasText(s(R.string.tonight_manual_until), substring = true) and
                hasAnyAncestor(hasTestTag("tonight-section-night"))).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Waking you at")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(s(R.string.recovery_resume_programme)).performScrollTo().assertIsDisplayed()
        // P5 (P3b review): the applied plan claims no wake: no wake label, marker text or spoken "wake".
        // The separate "Wake and recovery" navigation heading names a category, not this manual run's end.
        fun noWakeClaims(look: String) {
            val inPlan = hasAnyAncestor(hasTestTag("tonight-section-night"))
            listOf("Wake ", "Your alarm", "Wake time", "Profile's wake time", "From your alarm", "Tonight-only wake time").forEach { text ->
                assertTrue("$look shows '$text'", compose.onAllNodes(hasText(text, substring = true) and inPlan).fetchSemanticsNodes().isEmpty())
            }
            val spoken = compose.onAllNodes(SemanticsMatcher("speaks of a wake") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                    ?.any { it.contains("wake", ignoreCase = true) } == true
            } and inPlan).fetchSemanticsNodes()
            assertTrue("$look speaks of a wake", spoken.isEmpty())
            assertTrue("$look has no manual end in the plan", compose.onAllNodes(
                hasText(s(R.string.tonight_manual_until), substring = true) and inPlan
            ).fetchSemanticsNodes().isNotEmpty())
        }
        noWakeClaims("curve")
        runBlocking { graph.repository.updateSettings { it.copy(simpleView = true) } }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            compose.onAllNodes(hasText(s(R.string.tonight_manual_until), substring = true) and
                hasAnyAncestor(hasTestTag("tonight-section-night"))).fetchSemanticsNodes().isNotEmpty()
        }
        noWakeClaims("simple view")
        // The snooze's wake-up notification now says the same.
        val posted = shadowOf(compose.activity.getSystemService(android.app.NotificationManager::class.java)).allNotifications
            .map { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() }
        assertTrue(posted.toString(), posted.none { it.startsWith("Waking you at") })
        assertTrue(posted.toString(), posted.any { it.startsWith("Manual run until") })
    }

    @Test
    fun completedNightCheckInRecordsComfortWithoutChangingAProfile() {
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            compose.onNodeWithText("Start night now").performScrollTo().performClick()
            waitForConfirmedNight(controller.get())
            val profileBefore = graph.repository.profiles.value.first { it.id == graph.repository.activeNight.value!!.profileId }
            compose.onNodeWithText(s(R.string.tonight_end_night)).performScrollTo().performClick()
            confirmStopNight()
            waitForText("Start night now", controller.get())
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Morning check-in").performScrollTo().performClick()
            compose.onNodeWithText("Just right").performClick()
            waitForText("Morning check-in: Just right")
            assertEquals(profileBefore, graph.repository.profiles.value.first { it.id == profileBefore.id })
            assertTrue(compose.onAllNodes(hasText("Review Middle suggestion")).fetchSemanticsNodes().isEmpty())
        } finally { controller.destroy() }
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
        compose.onNodeWithText(s(R.string.tonight_end_night)).performScrollTo().performClick()
        // Ending a running night asks first.
        confirmStopNight()
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
        compose.onNode(hasText(s(R.string.guard_title)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
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
