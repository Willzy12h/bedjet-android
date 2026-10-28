package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.RuntimeState
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
import java.time.ZonedDateTime

/** P5 U10/F01/U1: next-night edits, pending versus confirmed wake, the tonight-only wake and its labels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WakeFeedbackTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK, simpleView = false, tonightOverride = null) }
                graph.repository.setActiveNight(null)
                graph.repository.updateRuntime { RuntimeState() }
            }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        condition()
    }
    private fun has(text: String, substring: Boolean = false) = compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String, substring: Boolean = false) = waitUntil { has(text, substring) }

    private fun setOverride(at: ZonedDateTime) {
        val graph = graph
        val now = graph.now()
        compose.runOnUiThread { runBlocking { graph.repository.updateSettings { it.setWakeOverride(at, now) } } }
    }
    private fun startNight() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitUntil {
            graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED && graph.actions.busy.value.runningLabel == null
        }
    }
    private fun look(theme: ThemeMode, simple: Boolean) {
        val graph = graph
        compose.runOnUiThread { runBlocking { graph.repository.updateSettings { it.copy(themeMode = theme, simpleView = simple) } } }
        compose.waitForIdle()
    }

    @Test fun theNoAlarmNoteDoesNotContradictATonightOnlyWake() {
        waitFor("Start night now")
        setOverride(graph.now().plusHours(8))
        waitFor(s(R.string.gpt12_wake_override), substring = true)
        assertFalse(has("No alarm set, so the profile's wake time is used."))
    }

    @Test fun theTonightOnlyWakeSaysWhenItLapses() {
        val wake = graph.now().plusHours(8)
        setOverride(wake)
        waitFor(s(R.string.override_expiry, Format.clock(wake), wake.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))))
    }

    @Test fun aRunningNightOnATonightOnlyWakeIsLabelledSoInEveryLook() {
        setOverride(graph.now().plusHours(8))
        startNight()
        assertEquals(WakeOrigin.OVERRIDE, graph.repository.activeNight.value!!.wakeOrigin)
        assertFalse("an unedited profile has no next-night note", has("start with the next night", substring = true))
        for ((theme, simple) in listOf(ThemeMode.DARK to false, ThemeMode.LIGHT to false, ThemeMode.DARK to true, ThemeMode.LIGHT to true)) {
            look(theme, simple)
            waitFor(s(R.string.wake_origin_override))
            assertFalse("$theme simple=$simple", has(s(R.string.wake_origin_profile)))
            assertFalse("$theme simple=$simple", has("No alarm set", substring = true))
        }
    }

    @Test fun aPendingWakeChangeShowsTheRequestedTimeBesideThePlannedOne() {
        startNight()
        val graph = graph
        val night = graph.repository.activeNight.value!!
        val requested = ZonedDateTime.parse(night.wakeAt).minusMinutes(30)
        compose.runOnUiThread { runBlocking {
            graph.repository.setActiveNight(night.copy(confidence = Confidence.PENDING,
                pending = PendingChange("r1", ChangeKind.RETIME, requested.toString(), graph.now().toString(), graph.now().plusMinutes(10).toString(), night.identity)))
        } }
        waitFor(s(R.string.wake_requested, Format.clock(requested)))
        compose.onNodeWithTag("tonight-fixed-wake")
            .assertTextContains(s(R.string.gpt12_wake_profile_fallback))
            .assertTextContains(Format.clock(night.wakeAt))
    }

    @Test fun anEditSavedForTheNextNightSaysSoOnTonight() {
        startNight()
        val graph = graph
        val running = graph.repository.activeNight.value!!
        val edited = graph.repository.profiles.value.first { it.id == running.profileId }.copy(name = "Edited for later")
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { model.saveProfileForNextNight(edited) }
        waitFor(s(R.string.next_night_saved))
        waitFor(s(R.string.tonight_edits_next_night, "Edited for later"))
        assertEquals(running, graph.repository.activeNight.value)
    }

    @Test fun theNextNightButtonSavesWithoutTouchingTonight() {
        startNight()
        val graph = graph
        val running = graph.repository.activeNight.value!!
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithTag("profile-open-${running.profileId}").performScrollTo().performClick()
        waitFor("Edit profile")
        compose.onNode(hasSetTextAction()).performTextReplacement("Next night name")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Next night").performClick()
        waitFor(s(R.string.next_night_saved))
        assertEquals("Next night name", graph.repository.profiles.value.first { it.id == running.profileId }.name)
        assertEquals(running.identity, graph.repository.activeNight.value?.identity)
    }

    @Test fun discardingAnUnsavedNewProfileAddsNothing() {
        val before = graph.repository.profiles.value
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithTag("profiles-new", useUnmergedTree = true).performClick()
        compose.onNode(hasText("Summer Cool") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        waitFor("Edit profile")
        compose.onNode(hasSetTextAction()).performTextReplacement("Never saved")
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        waitUntil { compose.onAllNodesWithTag("profiles-new", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, graph.repository.profiles.value)
        assertFalse(has("Never saved"))
    }

    @Test fun anUpdateTonightUnderWayDoesNotSayTheEditsWaitForTheNextNight() {
        startNight()
        val graph = graph
        val night = graph.repository.activeNight.value!!
        val edited = graph.repository.profiles.value.first { it.id == night.profileId }.copy(name = "Being applied")
        compose.runOnUiThread { runBlocking {
            graph.repository.saveProfile(edited)
            graph.repository.setActiveNight(night.copy(confidence = Confidence.PENDING,
                pending = PendingChange("r2", ChangeKind.REAPPLY, null, graph.now().toString(), graph.now().plusMinutes(10).toString(), night.identity)))
        } }
        waitFor("Change pending", substring = true)
        assertFalse(has(s(R.string.tonight_edits_next_night, "Being applied")))
        compose.runOnUiThread { runBlocking { graph.repository.setActiveNight(night) } }
        waitFor(s(R.string.tonight_edits_next_night, "Being applied"))
    }
}
