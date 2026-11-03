package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.SLEEP_SECTIONS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xhdpi")
class SleepPageTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT)
    @get:Rule val rules = ui.rules

    @Test fun hiddenSectionsKeepTheirPlaceWhenTheShownOnesMove() {
        val saved = listOf("trend", "nights", "noticed", "figures", "history")
        assertEquals(listOf("figures", "nights", "noticed", "trend", "history"),
            mergeSectionOrder(saved, listOf("figures", "nights", "trend", "history")))
        assertEquals(saved, mergeSectionOrder(saved, listOf("trend", "nights", "figures", "history")))
    }

    @Test fun theDemoSleepPageShowsEverySectionAndTheRangeSwitch() {
        ui.tab("Sleep")
        ui.compose.onNodeWithTag("sleep-range").assertExists()
        ui.compose.onNodeWithTag("sleep-trend").assertExists()
        val list = ui.compose.onNodeWithTag("sleep-list")
        for (id in listOf("stages", "figures", "timeline", "history")) {
            list.performScrollToKey(id)
            ui.compose.onNodeWithTag("sleep-section-$id").assertExists()
        }
    }

    @Test fun theRangeSwitchIsSaved() {
        ui.tab("Sleep")
        ui.compose.onNode(hasText("30 days") and hasClickAction() and hasAnyAncestor(hasTestTag("sleep-range"))).performClick()
        ui.waitUntil { ui.graph.repository.settings.value.sleepRangeDays == 30 }
    }

    @Test fun movingASectionWithTheAccessibilityActionSavesTheOrder() {
        ui.tab("Sleep")
        val actions = ui.compose.onNodeWithTag("sleep-section-trend").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        ui.compose.runOnUiThread { actions.first { it.label == "Move section down" }.action() }
        ui.waitUntil { ui.graph.repository.settings.value.normalisedSleepOrder().indexOf("trend") == 1 }
        assertEquals(SLEEP_SECTIONS.toSet(), ui.graph.repository.settings.value.normalisedSleepOrder().toSet())
    }

    @Test fun shorteningTheKeepPeriodAsksFirstThenDeletesOlderNights() {
        val now = ui.graph.now()
        ui.compose.runOnUiThread { runBlocking {
            // Demo mode never changes saved nights, so these controls only work with a real BedJet chosen.
            ui.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
            ui.graph.repository.addRecord(NightRecord("old", "p", "Old night", now.minusDays(20).toString(), now.minusDays(20).plusHours(8).toString(), false))
            ui.graph.repository.addRecord(NightRecord("new", "p", "New night", now.minusDays(2).toString(), now.minusDays(2).plusHours(8).toString(), false))
        } }
        ui.tab("History")
        ui.compose.onNodeWithTag("sleep-history-row").performScrollTo().performClick()
        ui.compose.onNodeWithTag("sleep-local-only").assertExists()
        ui.compose.onNodeWithTag("sleep-keep-7").performScrollTo().performClick()
        ui.compose.onNodeWithText("1 older night will be deleted from this phone", substring = true).assertExists()
        assertEquals(2, ui.graph.repository.history.value.size)
        ui.compose.onNodeWithTag("sleep-shrink-confirm").performClick()
        ui.waitUntil { ui.graph.repository.history.value.map { it.id } == listOf("new") }
        assertEquals(7, ui.graph.repository.settings.value.historyKeepDays)
    }

    @Test fun deletingSleepHistoryAsksFirst() {
        val now = ui.graph.now()
        ui.compose.runOnUiThread { runBlocking {
            ui.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
            ui.graph.repository.addRecord(NightRecord("a", "p", "A night", now.minusDays(2).toString(), now.minusDays(2).plusHours(8).toString(), false))
        } }
        ui.tab("History")
        ui.compose.onNodeWithTag("sleep-history-row").performScrollTo().performClick()
        ui.compose.onNodeWithTag("sleep-delete").performScrollTo().performClick()
        assertEquals(1, ui.graph.repository.history.value.size)
        ui.compose.onNodeWithTag("sleep-delete-confirm").performClick()
        ui.waitUntil { ui.graph.repository.history.value.isEmpty() }
    }

    @Test fun lastNightOnTonightOpensTheSleepPage() {
        val card = ui.compose.onNodeWithTag("last-night").performScrollTo()
        card.assertHasClickAction()
        ui.compose.onNodeWithTag("last-night-week", useUnmergedTree = true).assertExists()
        card.performClick()
        ui.compose.onNodeWithTag("sleep-range").assertExists()
    }

    @Test fun lastNightIsOnlyTheNightThatEndedThisMorningOrYesterday() {
        val today = java.time.LocalDate.of(2026, 10, 30)
        val zone = java.time.ZoneId.of("Europe/London")
        val night = io.github.willzy12h.nocturne.sleep.SleepNight(today.atStartOfDay(zone).toInstant(), today.atStartOfDay(zone).plusHours(7).toInstant(), emptyList())
        fun day(date: java.time.LocalDate) = io.github.willzy12h.nocturne.sleep.SleepDay(date, night, null, null)
        assertEquals(today, lastNight(listOf(day(today), day(today.minusDays(1))), today)?.date)
        assertEquals(today.minusDays(1), lastNight(listOf(day(today.minusDays(1))), today)?.date)
        assertNull(lastNight(listOf(day(today.minusDays(2))), today))
    }
}
