package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.RecordedStep
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
import java.time.ZonedDateTime

/** P5 U09: History shows a night's applied revisions and draws the curve from the one the BedJet last ran. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HistoryRevisionsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-23T09:00:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.setActiveNight(null)
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) }
            }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(GraphLifecycle()).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val original = listOf(
        RecordedStep(Block.MIDDLE, Mode.COOL, 44, 40, "2026-10-22T22:30Z", 480),
        RecordedStep(Block.WAKE, Mode.HEAT, 60, 30, "2026-10-23T06:30Z", 30))
    private val retimed = AppliedProgram(listOf(
        RecordedStep(Block.MIDDLE, Mode.COOL, 44, 40, "2026-10-23T01:00Z", 300),
        RecordedStep(Block.WAKE, Mode.HEAT, 60, 30, "2026-10-23T06:00Z", 30)),
        start = "2026-10-23T01:00Z", wake = "2026-10-23T06:30Z", revision = 1)
    private val record = NightRecord("n1", "p", "Mine", "2026-10-22T22:30Z", "2026-10-23T06:30Z", true, original,
        events = listOf(HistoryEvent("2026-10-22T22:30Z", EventKind.STARTED, "Started"),
            HistoryEvent("2026-10-23T01:00Z", EventKind.RETIMED, "Alarm moved to 06:30"),
            HistoryEvent("2026-10-23T06:30Z", EventKind.ENDED, "Ended")),
        revisions = listOf(AppliedProgram(original, "2026-10-22T22:30Z", "2026-10-23T07:00Z"), retimed))

    @Test fun theCurveIsTheLastAppliedRevision() {
        val plan = historyPlan(record)!!
        assertEquals(ZonedDateTime.parse("2026-10-23T06:30Z"), plan.wake)
        assertEquals(retimed.steps.map { it.startsAt }, plan.steps.map { it.startsAt.toString() })
    }

    @Test fun aNightWithoutRevisionsUsesItsRecordedSteps() {
        val plan = historyPlan(record.copy(revisions = emptyList()))!!
        assertEquals(original.size, plan.steps.size)
        assertEquals(ZonedDateTime.parse(record.wakeAt), plan.wake)
    }

    @Test fun anOpenedCardListsRevisionsAndNamesEventKinds() {
        val graph = graph
        compose.runOnUiThread { runBlocking { graph.repository.addRecord(record) } }
        compose.onNodeWithText("History").performClick()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); compose.onAllNodesWithText("Mine", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mine", substring = true).performClick()
        compose.onNodeWithText(s(R.string.history_revision, 1, "07:00", compose.activity.resources.getQuantityString(R.plurals.history_steps, 2, 2))).assertExists()
        compose.onNodeWithText(s(R.string.history_revision, 2, "06:30", compose.activity.resources.getQuantityString(R.plurals.history_steps, 2, 2))).assertExists()
        compose.onNodeWithText(s(R.string.history_curve_applied)).assertExists()
        compose.onNode(hasText(s(R.string.event_retimed), substring = true) and hasText("Alarm moved to 06:30", substring = true)).assertExists()
    }
    private fun openNight(night: NightRecord) {
        compose.runOnUiThread { runBlocking { graph.repository.addRecord(night) } }
        compose.onNodeWithText("History").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodesWithText(night.profileName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(night.profileName, substring = true).performClick()
    }

    private fun stepText(step: RecordedStep): String =
        if (step.mode.usesTemperature) s(R.string.history_step_temp, Format.clock(step.startsAt),
            s(if (step.block == Block.MIDDLE) R.string.block_middle else R.string.block_wake), Format.modeName(step.mode), Format.temp(step.temperature, false), step.fanPercent, Format.duration(step.minutes))
        else s(R.string.history_step, Format.clock(step.startsAt), s(if (step.block == Block.MIDDLE) R.string.block_middle else R.string.block_wake), Format.modeName(step.mode),
            step.fanPercent, Format.duration(step.minutes))

    @Test fun expandedStepsMatchTheLastAppliedCurveRatherThanOriginalProgramme() {
        openNight(record)
        retimed.steps.forEach { compose.onNodeWithText(stepText(it)).assertExists() }
        original.forEach { compose.onNodeWithText(stepText(it)).assertDoesNotExist() }
    }

    @Test fun expandedLegacyStepsMatchTheOriginalCurveWithoutRevisions() {
        openNight(record.copy(revisions = emptyList()))
        original.forEach { compose.onNodeWithText(stepText(it)).assertExists() }
        retimed.steps.forEach { compose.onNodeWithText(stepText(it)).assertDoesNotExist() }
    }

    @Test fun anUnreadableRevisionFallsBackToTheOriginalCurveAndExpandedSteps() {
        val night = record.copy(revisions = listOf(retimed.copy(start = "invalid")))
        assertEquals(original.map { it.startsAt }, historyPlan(night)!!.steps.map { it.startsAt.toString() })
        openNight(night)
        original.forEach { compose.onNodeWithText(stepText(it)).assertExists() }
        retimed.steps.forEach { compose.onNodeWithText(stepText(it)).assertDoesNotExist() }
    }

    @Test fun eachHistoryCardHasItsHealthOverlayBelowItsOwnCurve() {
        val target = "ble:AA:BB:CC:DD:EE:01"
        val first = record.copy(deviceId = target)
        val second = first.copy(id = "n2", profileName = "Second night", startedAt = "2026-10-21T22:30Z")
        compose.runOnUiThread { runBlocking {
            graph.sessions.suspendAutoConnect(untilMs = null)
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target,
                platform = it.platform.copy(healthSleepOverlay = true)) }
            graph.repository.addRecord(first)
            graph.repository.addRecord(second)
        } }
        compose.onNodeWithText("History").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodesWithText(record.profileName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        listOf(first, second).forEach { night ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(night.profileName, substring = true))
            val card = hasClickAction() and hasAnyDescendant(hasText(night.profileName, substring = true))
            val overlay = compose.onNode(hasText(s(R.string.feature_health_source)) and hasAnyAncestor(card), useUnmergedTree = true)
            overlay.performScrollTo().assertIsDisplayed()
            val curveInteraction = compose.onNode(hasContentDescription("Night plan", substring = true) and hasAnyAncestor(card), useUnmergedTree = true)
            val curve = curveInteraction.fetchSemanticsNode()
            val health = overlay.fetchSemanticsNode()
            fun cardId(node: androidx.compose.ui.semantics.SemanticsNode): Int = generateSequence(node) { it.parent }
                .first { it.config.contains(SemanticsActions.OnClick) }.id
            assertEquals("${night.id} overlay belongs to its curve's card", cardId(curve), cardId(health))
            assertTrue("${night.id} overlay must follow its own curve",
                overlay.getUnclippedBoundsInRoot().top >= curveInteraction.getUnclippedBoundsInRoot().bottom)
        }
    }

}
