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
        compose.onNodeWithText("Standby observed at ${Format.dayClock(start.plusSeconds(900).atZone(zone))}.").assertExists()
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
        compose.onNodeWithText("Standby observed", substring = true).assertDoesNotExist()
    }

    @Test fun theBedJetRowOpensItsOwnRecordOnTheSameDateAsASeparateTrackerSession() {
        val zone = ZoneOffset.UTC
        val date = start.atZone(zone).toLocalDate()
        val tracker = SleepNight.of(start, start.plusSeconds(3600), listOf(SleepStage(start, start.plusSeconds(3600), SleepInsights.LIGHT)))
        val overnight = SleepDay(date, tracker, null, null)
        val run = io.github.willzy12h.nocturne.core.store.NightRecord("nap", "p", "Quick nap",
            start.plusSeconds(7200).toString(), start.plusSeconds(10800).toString(), false, isNap = true,
            lastRunningObservedAt = start.plusSeconds(9000).toString())
        val bedJet = SleepDay(date, null, run, null)
        var chosen: SleepDay? = null
        compose.setContent { NocturneTheme { SleepTrendCard(listOf(overnight, bedJet), date, 7, overnight, zone) { chosen = it } } }
        compose.onNodeWithText("From 1 tracked night").assertExists()
        compose.onNodeWithTag("sleep-trend-bars").performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, centerY)) }
        compose.runOnIdle { assertEquals(overnight, chosen) }
        compose.onNodeWithTag("sleep-bedjet-use").performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, centerY)) }
        compose.runOnIdle { assertEquals(bedJet, chosen) }
        compose.onNodeWithText("Outlines mark incomplete records.", substring = true).assertExists()
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
