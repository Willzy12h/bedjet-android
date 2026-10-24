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
}
