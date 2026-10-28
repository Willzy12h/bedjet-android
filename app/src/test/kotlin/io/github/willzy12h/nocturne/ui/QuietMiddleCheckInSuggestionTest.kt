package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.QuietFan
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
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

/** P5 F09: a Cool Middle is offered a validated fan change in History, applied only when the person accepts it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class QuietMiddleCheckInSuggestionTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val cool = Presets.summerCool.copy(id = "cool-mine", name = "Cool mine",
        middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 45, tempStart = 44, tempEnd = 44, rampSteps = 3, quietFan = QuietFan(settle = 50, settleMinutes = 30, asleep = 25, rise = 45, riseMinutes = 40)))
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-23T09:00:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.setActiveNight(null)
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) }
                graph.repository.saveProfile(cool)
                graph.repository.addRecord(NightRecord("cool-night", cool.id, cool.name, "2026-10-22T22:30Z", "2026-10-23T07:00Z", false,
                    events = listOf(HistoryEvent("2026-10-23T07:30Z", EventKind.ENDED, "Night ended"))))
            }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun quietMiddleCheckInShowsAndAppliesAllThreeFanChangesOnlyAfterConfirmation() {
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText(s(R.string.checkin_button)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.checkin_too_hot)).performClick()
        waitFor("All fan speeds +10%")
        compose.onNodeWithText("Settle: 50% → 60% · asleep: 25% → 35% · rise: 45% → 55%").assertExists()
        assertEquals(cool.middle, graph.repository.profile(cool.id)!!.middle)
        compose.onNodeWithText(s(R.string.checkin_apply)).performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            graph.repository.profile(cool.id)!!.middle.quietFan?.asleep == 35
        }
        val applied = graph.repository.profile(cool.id)!!.middle
        assertEquals(cool.middle.shiftFan(10), applied)
    }
}
