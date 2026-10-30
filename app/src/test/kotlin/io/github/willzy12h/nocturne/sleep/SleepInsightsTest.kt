package io.github.willzy12h.nocturne.sleep

import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.platform.SleepStage
import io.github.willzy12h.nocturne.sleep.SleepInsights.AWAKE
import io.github.willzy12h.nocturne.sleep.SleepInsights.DEEP
import io.github.willzy12h.nocturne.sleep.SleepInsights.LIGHT
import io.github.willzy12h.nocturne.sleep.SleepInsights.REM
import io.github.willzy12h.nocturne.sleep.SleepInsights.SLEEPING
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SleepInsightsTest {
    private val zone = ZoneId.of("Europe/London")
    private val bed = ZonedDateTime.of(2026, 10, 20, 23, 0, 0, 0, zone)

    /** Stages laid end to end from [start]: pairs of (stage, minutes). */
    private fun night(start: ZonedDateTime, vararg parts: Pair<Int, Int>): SleepNight {
        var at = start.toInstant()
        val stages = parts.map { (stage, minutes) -> SleepStage(at, at.plusSeconds(minutes * 60L), stage).also { at = it.end } }
        return SleepNight(start.toInstant(), at, stages)
    }

    private fun record(id: String, start: ZonedDateTime, end: ZonedDateTime) =
        NightRecord(id, "p", "Winter warm", start.toString(), end.toString(), false)

    private fun plan(start: ZonedDateTime, vararg steps: Pair<Int, Int>): Plan {
        var at = start
        return Plan(start, start.plusHours(8), steps.map { (temperature, minutes) ->
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.HEAT, 9, temperature, 0, minutes), at, minutes).also { at = at.plusMinutes(minutes.toLong()) }
        })
    }

    @Test fun figuresCountSleepWakesAndTheLongestWake() {
        val figures = SleepInsights.figures(night(bed, AWAKE to 15, LIGHT to 60, AWAKE to 5, DEEP to 60, AWAKE to 3, AWAKE to 9, REM to 30, AWAKE to 10))
        assertEquals(192, figures.inBedMinutes)
        assertEquals(150, figures.asleepMinutes)
        assertEquals(15, figures.fellAsleepMinutes)
        // The two touching awake stages are one spell; the awake before sleep and after the last sleep are not wakes.
        assertEquals(2, figures.wakeCount)
        assertEquals(12, figures.longestWakeMinutes)
        assertEquals(bed.plusMinutes(140).toInstant(), figures.longestWakeAt)
        assertEquals(78, figures.efficiencyPercent)
        assertEquals(mapOf(AWAKE to 42, LIGHT to 60, DEEP to 60, REM to 30), figures.stageMinutes)
    }

    @Test fun aSessionWithoutStagesIsOneSleepingStage() {
        val night = SleepNight.of(bed.toInstant(), bed.plusHours(7).toInstant(), emptyList())
        val figures = SleepInsights.figures(night)
        assertEquals(420, figures.asleepMinutes)
        assertEquals(0, figures.fellAsleepMinutes)
        assertEquals(0, figures.wakeCount)
        assertEquals(mapOf(SLEEPING to 420), figures.stageMinutes)
    }

    @Test fun aNightWithNoSleepHasNoFellAsleepTime() {
        val figures = SleepInsights.figures(night(bed, AWAKE to 30))
        assertNull(figures.fellAsleepMinutes)
        assertEquals(0, figures.efficiencyPercent)
    }

    @Test fun joinPairsByOverlapAndKeepsNightsWithOnlyOneSide() {
        val sleep = night(bed, LIGHT to 400)
        val other = night(bed.minusDays(1), LIGHT to 400)
        val matched = record("a", bed.minusMinutes(20), bed.plusHours(7))
        val bedJetOnly = record("b", bed.minusDays(3), bed.minusDays(3).plusHours(8))
        val days = SleepInsights.join(listOf(sleep, other), listOf(matched, bedJetOnly), zone) { null }
        assertEquals(listOf(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 18)), days.map { it.date })
        assertEquals("a", days[0].record?.id)
        // No BedJet run: Health Connect's night stays, with no record.
        assertNull(days[1].record)
        assertNotNull(days[1].sleep)
        // No Health Connect data: the BedJet night stays, with no sleep.
        assertEquals("b", days[2].record?.id)
        assertNull(days[2].sleep)
    }

    @Test fun inRangeKeepsTheLastDaysUpToToday() {
        val days = (0..40).map { SleepDay(LocalDate.of(2026, 10, 30).minusDays(it.toLong()), night(bed, LIGHT to 60), null, null) }
        assertEquals(7, SleepInsights.inRange(days, LocalDate.of(2026, 10, 30), 7).size)
        assertEquals(30, SleepInsights.inRange(days, LocalDate.of(2026, 10, 30), 30).size)
    }

    @Test fun usualBedtimeAveragesAcrossMidnight() {
        val days = listOf(bed.withHour(23).withMinute(30), bed.plusDays(1).withHour(0).withMinute(30), bed.plusDays(2).withHour(0).withMinute(0))
            .map { SleepDay(it.toLocalDate(), night(it, LIGHT to 400), null, null) }
        assertEquals(0, SleepInsights.averages(days, zone).usualBedtimeMinute)
        assertEquals(400, SleepInsights.averages(days, zone).asleepMinutes)
    }

    @Test fun compareSetsTheNightAgainstEarlierNights() {
        val earlier = (1..3).map { SleepDay(bed.minusDays(it.toLong()).toLocalDate(), night(bed.minusDays(it.toLong()), LIGHT to 200, DEEP to 60), null, null) }
        val tonight = SleepDay(bed.toLocalDate(), night(bed, LIGHT to 240, DEEP to 30), null, null)
        val comparison = SleepInsights.compare(tonight, earlier + tonight, zone).associateBy { it.stage }
        assertEquals(40, comparison.getValue(LIGHT).delta)
        assertEquals(-30, comparison.getValue(DEEP).delta)
    }

    @Test fun aWakeAfterAStepChangeIsNoticedOnlyWhenItRepeats() {
        // Step change at 60 min; the wake starts 2 min after it.
        fun day(daysBack: Long, wake: Boolean): SleepDay {
            val start = bed.minusDays(daysBack)
            val sleep = if (wake) night(start, LIGHT to 62, AWAKE to 5, DEEP to 100) else night(start, LIGHT to 62, DEEP to 105)
            return SleepDay(start.toLocalDate(), sleep, record("r$daysBack", start, start.plusHours(8)), plan(start, 66 to 60, 60 to 120))
        }
        val once = listOf(day(0, true), day(1, false), day(2, false))
        assertNull(SleepInsights.noticed(once[0], once))
        val twice = listOf(day(0, true), day(1, false), day(2, true))
        val noticed = SleepInsights.noticed(twice[0], twice)!!
        assertEquals(2, noticed.minutesAfter)
        assertEquals(60, noticed.temperature)
        assertEquals(2, noticed.nights)
        assertEquals(3, noticed.ofNights)
    }

    @Test fun demoNightsAreStableAndPlausible() {
        val first = DemoSleep.nights(LocalDate.of(2026, 10, 30), 7, zone)
        assertEquals(first, DemoSleep.nights(LocalDate.of(2026, 10, 30), 7, zone))
        first.forEach { night ->
            val figures = SleepInsights.figures(night)
            assert(figures.asleepMinutes in 240..600) { "asleep ${figures.asleepMinutes}" }
        }
    }
}
