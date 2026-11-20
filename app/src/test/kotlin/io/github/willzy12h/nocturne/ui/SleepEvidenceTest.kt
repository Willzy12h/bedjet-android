package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.willzy12h.nocturne.platform.SleepStage
import io.github.willzy12h.nocturne.sleep.SleepDay
import io.github.willzy12h.nocturne.sleep.SleepInsights
import io.github.willzy12h.nocturne.sleep.SleepNight
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepEvidenceTest {
    @get:Rule val compose = createComposeRule()
    private val start = Instant.parse("2026-11-20T09:00:00Z")

    @Test fun unknownAndAwakeInBedIntervalsAreAccessibleWithoutInventingDeviceEvidence() {
        val stages = listOf(0, 7, 99).mapIndexed { index, code ->
            SleepStage(start.plusSeconds(index * 600L), start.plusSeconds((index + 1) * 600L), code)
        }
        val night = SleepNight.of(start, stages.last().end, stages, "one")
        val day = SleepDay(start.atZone(ZoneOffset.UTC).toLocalDate(), night, null, null)
        compose.setContent { NocturneTheme { SleepNightChart(day, ZoneOffset.UTC, false, null) } }
        for (label in listOf("Stage unknown", "Awake in bed", "Stage unknown")) {
            val node = compose.onNodeWithContentDescription("BedJet plan", substring = true)
            val next = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Next interval" }
            compose.runOnUiThread { assertTrue(next.action()) }
            compose.onNodeWithTag("sleep-scrub").assertTextContains(label, substring = true)
                .assertTextContains("No BedJet record", substring = true)
        }
        assertEquals(0, SleepInsights.figures(night).asleepMinutes)
        assertEquals(10, SleepInsights.figures(night).stageMinutes[SleepInsights.AWAKE])
    }

    @Test fun trackerIntervalAndObservedStandbyRemainIndependent() {
        val zone = ZoneOffset.UTC
        val nap = io.github.willzy12h.nocturne.core.store.NightRecord("nap", "p", "Afternoon", start.toString(),
            start.plusSeconds(3600).toString(), false, isNap = true, standbyObservedAt = start.plusSeconds(900).toString())
        val tracker = SleepNight.of(start, start.plusSeconds(2700), emptyList(), "tracker")
        val day = SleepDay(start.atZone(zone).toLocalDate(), tracker, nap, null)
        compose.setContent { NocturneTheme {
            androidx.compose.foundation.layout.Column {
                SleepNightPicker(day, zone, null, null) {}
                SleepNightChart(day, zone, false, null)
            }
        } }
        compose.onNodeWithText("BedJet nap", substring = true).assertExists()
        compose.onNodeWithText("Tracker session ${Format.clock(start.atZone(zone))} to ${Format.clock(tracker.end.atZone(zone))}").assertExists()
        compose.onNodeWithText("BedJet reported standby at ${Format.dayClock(start.plusSeconds(900).atZone(zone))}.").assertExists()
    }

    @Test fun plannedCompletionWithoutAStandbyReportOnlyShowsTheLastReading() {
        val zone = ZoneOffset.UTC
        val reading = start.plusSeconds(900)
        val end = start.plusSeconds(3600)
        val record = io.github.willzy12h.nocturne.core.store.NightRecord("night", "p", "Overnight", start.toString(),
            end.toString(), false, confirmedCompletionAt = end.toString(),
            temperatures = listOf(io.github.willzy12h.nocturne.core.store.TempSample(reading.toString(), 20, 24)))
        val day = SleepDay(start.atZone(zone).toLocalDate(), null, record, null)
        compose.setContent { NocturneTheme { SleepNightChart(day, zone, false, null) } }
        compose.onNodeWithText("Last BedJet reading at ${Format.dayClock(reading.atZone(zone))}. Stop time unknown.").assertExists()
        compose.onNodeWithText("BedJet reported standby", substring = true).assertDoesNotExist()
    }

    @Test fun selectionSurvivesRefreshAndDistinguishesSessionsOnOneDate() {
        val day = start.atZone(ZoneOffset.UTC).toLocalDate()
        val first = SleepNight.of(start, start.plusSeconds(1800), emptyList(), "morning")
        val second = SleepNight.of(start.plusSeconds(3600), start.plusSeconds(5400), emptyList(), "nap")
        val one = SleepDay(day, first, null, null)
        val two = SleepDay(day, second, null, null)
        assertNotEquals(one.selectionId, two.selectionId)
        assertEquals(two.selectionId, two.copy(sleep = second.copy(end = second.end.plusSeconds(60))).selectionId)
    }
}
