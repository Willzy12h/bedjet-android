package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.RuntimeState
import io.github.willzy12h.nocturne.night.SlotWrite
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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

/** P5: Tonight's notices, busy state and change feedback in the real app, time fixed before composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TonightCompletenessTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK, simpleView = false) }
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
    private fun waitFor(text: String, substring: Boolean = false) =
        waitUntil { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun anUnconfirmedSlotWriteIsShownOnTonightAndCanBeMarkedAsChecked() {
        val graph = graph
        compose.runOnUiThread { runBlocking {
            graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite("demo", 0, null, Confidence.UNKNOWN, "Keep on slot 1")) }
        } }
        waitFor("Keep on slot 1", substring = true)
        compose.onNodeWithText(s(R.string.slot_write_checked)).performScrollTo().performClick()
        waitUntil { graph.repository.runtime.value.slotWrite == null }
        waitFor(s(R.string.runtime_slot_write_acknowledged))
        assertTrue(compose.onAllNodes(hasText(s(R.string.slot_write_checked))).fetchSemanticsNodes().isEmpty())
    }

    private val model get() = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]

    /** Everything the snackbar is asked to show, in order. */
    private fun recordMessages(): MutableList<String> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val model = model
        compose.runOnUiThread { graph.scope.launch { model.messages.collect { seen += it } } }
        return seen
    }
    private fun idle(ms: Long) { repeat((ms / 50).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }; compose.waitForIdle() }

    @Test fun theRunningCommandAndQueuedCountAreShownWhileTheyWait() {
        val graph = graph
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.runOnUiThread {
            graph.scope.launch { graph.actions.remote("Lights test") { gate.await() } }
            graph.scope.launch { graph.actions.remote("Second") { } }
            graph.scope.launch { graph.actions.remote("Third") { } }
        }
        waitUntil { graph.actions.busy.value.runningLabel == "Lights test" && graph.actions.busy.value.queuedCount == 2 }
        waitFor(s(R.string.busy_running, "Lights test"), substring = true)
        waitFor(compose.activity.resources.getQuantityString(R.plurals.busy_queued, 2, 2), substring = true)
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        gate.complete(Unit)
        waitUntil { graph.actions.busy.value == io.github.willzy12h.nocturne.night.BusyState() }
        waitUntil { compose.onAllNodes(hasText(s(R.string.busy_running, "Lights test"), substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun aTapOnTonightIsReportedExactlyOnce() {
        val seen = recordMessages()
        compose.onNodeWithText("Off").performScrollTo().performClick()
        waitUntil { seen.isNotEmpty() }
        idle(2_000)
        assertEquals(seen.toString(), 1, seen.size)
    }

    @Test fun anOutcomeFromOutsideTheAppIsShownOnceWhileItIsOpen() {
        val graph = graph
        val seen = recordMessages()
        var outcome: io.github.willzy12h.nocturne.night.CommandOutcome? = null
        // As a widget, shortcut or alarm runs it: straight through the command owner, not the screen.
        compose.runOnUiThread { graph.scope.launch {
            outcome = graph.actions.execute(graph.actions.capture(io.github.willzy12h.nocturne.night.NightActions.Command.OFF))
        } }
        waitUntil { outcome != null && seen.isNotEmpty() }
        idle(2_000)
        assertEquals(listOf(outcome!!.message), seen.toList())
        waitFor(outcome!!.message)
    }

    @Test fun aSavedSettingIsNotAnnounced() {
        val seen = recordMessages()
        val model = model
        compose.runOnUiThread { model.updateSettings { it.copy(fahrenheit = true) } }
        waitUntil { graph.repository.settings.value.fahrenheit }
        idle(1_000)
        assertEquals(emptyList<String>(), seen.toList())
    }

    @Test fun stopTrackingSaysItDoesNotTurnTheBedJetOff() {
        compose.onNodeWithText("Start night now").performScrollTo().performClick()
        waitUntil { graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED && graph.actions.busy.value.runningLabel == null }
        compose.onNodeWithText("Stop tracking").performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.stop_tracking_text)).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("removes", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
