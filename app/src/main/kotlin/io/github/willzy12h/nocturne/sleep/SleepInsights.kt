package io.github.willzy12h.nocturne.sleep

import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.platform.SleepStage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** One sleep session from another app in Health Connect. A session with no stages is one "sleeping" stage. */
data class SleepNight(val start: Instant, val end: Instant, val stages: List<SleepStage>) {
    companion object {
        fun of(start: Instant, end: Instant, stages: List<SleepStage>): SleepNight =
            SleepNight(start, end, stages.filter { it.end > it.start }.sortedBy { it.start }
                .ifEmpty { listOf(SleepStage(start, end, SleepInsights.SLEEPING)) })
    }
}

/**
 * One night on the Sleep page: what Health Connect recorded, what the BedJet ran, or both. Either may be
 * missing; a night with no BedJet run still shows its sleep, with the BedJet shown as off.
 */
data class SleepDay(val date: LocalDate, val sleep: SleepNight?, val record: NightRecord?, val plan: Plan?) {
    val start: Instant get() = sleep?.start ?: plan?.start?.toInstant() ?: ZonedDateTime.parse(record!!.startedAt).toInstant()
    val end: Instant get() = sleep?.end ?: plan?.wake?.toInstant() ?: ZonedDateTime.parse(record!!.wakeAt).toInstant()
}

/** The four figures for one night, all whole minutes. */
data class SleepFigures(
    val inBedMinutes: Int,
    val asleepMinutes: Int,
    /** From getting into bed to the first sleep; null when the night has no sleep at all. */
    val fellAsleepMinutes: Int?,
    /** Times awake between first falling asleep and last waking up. */
    val wakeCount: Int,
    val longestWakeMinutes: Int,
    val longestWakeAt: Instant?,
    /** Asleep as a share of time in bed, 0–100. */
    val efficiencyPercent: Int,
    /** Minutes per Health Connect stage code, awake and out of bed together under [SleepInsights.AWAKE]. */
    val stageMinutes: Map<Int, Int>,
)

/** A night's stage minutes set against the average of the other nights in range. */
data class StageComparison(val stage: Int, val minutes: Int, val averageMinutes: Int) {
    val delta: Int get() = minutes - averageMinutes
}

/** Averages over the nights in range that have sleep data. */
data class SleepAverages(val nights: Int, val asleepMinutes: Int, val usualBedtimeMinute: Int?, val stageMinutes: Map<Int, Int>)

/** A wake that began shortly after the BedJet changed step, and how often that happened recently. */
data class SleepNoticed(val wokeAt: Instant, val minutesAfter: Int, val mode: Mode, val temperature: Int, val nights: Int, val ofNights: Int)

/**
 * Pure arithmetic behind the Sleep page. It reports what the data shows and never infers a cause; the page's
 * wording says so. Health Connect stage codes: 1 awake, 2 sleeping, 3 out of bed, 4 light, 5 deep, 6 REM.
 */
object SleepInsights {
    const val AWAKE = 1
    const val SLEEPING = 2
    const val OUT_OF_BED = 3
    const val LIGHT = 4
    const val DEEP = 5
    const val REM = 6

    /** A wake this soon after a step change counts towards a pattern. */
    const val NOTICE_WINDOW_MINUTES = 10L
    /** A pattern is mentioned only when it repeats on at least this many of the recent nights. */
    const val NOTICE_MINIMUM_NIGHTS = 2
    const val RECENT_NIGHTS = 7

    fun isAsleep(stage: Int) = stage == SLEEPING || stage == LIGHT || stage == DEEP || stage == REM
    fun isAwake(stage: Int) = stage == AWAKE || stage == OUT_OF_BED

    private fun minutes(from: Instant, to: Instant) = Duration.between(from, to).toMinutes().coerceAtLeast(0).toInt()

    fun figures(night: SleepNight): SleepFigures {
        val stages = night.stages.sortedBy { it.start }
        val inBed = minutes(night.start, night.end)
        val asleep = stages.filter { isAsleep(it.stage) }.sumOf { minutes(it.start, it.end) }
        val firstSleep = stages.firstOrNull { isAsleep(it.stage) }
        val lastSleep = stages.lastOrNull { isAsleep(it.stage) }
        // Awake spells between the first and last sleep; touching awake stages are one spell.
        val spells = mutableListOf<Pair<Instant, Instant>>()
        if (firstSleep != null && lastSleep != null) {
            stages.filter { isAwake(it.stage) && it.start >= firstSleep.end && it.end <= lastSleep.start }.forEach { stage ->
                val last = spells.lastOrNull()
                if (last != null && Duration.between(last.second, stage.start).toMinutes() < 1) spells[spells.lastIndex] = last.first to stage.end
                else spells += stage.start to stage.end
            }
        }
        val longest = spells.maxByOrNull { Duration.between(it.first, it.second) }
        val totals = stages.groupBy { if (isAwake(it.stage)) AWAKE else it.stage }
            .mapValues { (_, list) -> list.sumOf { minutes(it.start, it.end) } }.filterValues { it > 0 }
        return SleepFigures(
            inBedMinutes = inBed,
            asleepMinutes = asleep,
            fellAsleepMinutes = firstSleep?.let { minutes(night.start, it.start) },
            wakeCount = spells.size,
            longestWakeMinutes = longest?.let { minutes(it.first, it.second) } ?: 0,
            longestWakeAt = longest?.first,
            efficiencyPercent = if (inBed == 0) 0 else (asleep * 100f / inBed).roundToInt().coerceIn(0, 100),
            stageMinutes = totals,
        )
    }

    /**
     * Joins Health Connect sessions with BedJet nights by overlap, newest first. Each BedJet night joins the
     * session it overlaps most; whatever is left on either side becomes a night of its own.
     */
    fun join(nights: List<SleepNight>, records: List<NightRecord>, zone: ZoneId, planOf: (NightRecord) -> Plan?): List<SleepDay> {
        val timed = records.mapNotNull { record ->
            runCatching { Triple(record, ZonedDateTime.parse(record.startedAt).toInstant(), ZonedDateTime.parse(record.wakeAt).toInstant()) }.getOrNull()
        }
        val unused = timed.toMutableList()
        val days = nights.sortedBy { it.start }.map { night ->
            val match = unused.filter { (_, start, end) -> start < night.end && end > night.start }
                .maxByOrNull { (_, start, end) -> Duration.between(maxOf(start, night.start), minOf(end, night.end)) }
            match?.let(unused::remove)
            SleepDay(night.end.atZone(zone).toLocalDate(), night, match?.first, match?.first?.let(planOf))
        } + unused.map { (record, _, end) -> SleepDay(end.atZone(zone).toLocalDate(), null, record, planOf(record)) }
        return days.sortedByDescending { it.start }
    }

    /** The nights whose wake date falls in the last [days] days up to [today]. */
    fun inRange(all: List<SleepDay>, today: LocalDate, days: Int): List<SleepDay> =
        all.filter { !it.date.isAfter(today) && it.date.isAfter(today.minusDays(days.toLong())) }

    fun averages(days: List<SleepDay>, zone: ZoneId): SleepAverages {
        val figures = days.mapNotNull { it.sleep }.map(::figures)
        if (figures.isEmpty()) return SleepAverages(0, 0, null, emptyMap())
        val stages = figures.flatMap { it.stageMinutes.keys }.toSet()
            .associateWith { code -> (figures.sumOf { it.stageMinutes[code] ?: 0 } / figures.size.toFloat()).roundToInt() }
        // Bedtimes after noon count as the evening before, so 23:30 and 00:30 average to midnight, not noon.
        val bedtimes = days.mapNotNull { it.sleep?.start?.atZone(zone) }.map { (it.hour * 60 + it.minute).let { m -> if (m < 12 * 60) m + 1440 else m } }
        return SleepAverages(
            nights = figures.size,
            asleepMinutes = (figures.sumOf { it.asleepMinutes } / figures.size.toFloat()).roundToInt(),
            usualBedtimeMinute = bedtimes.sorted()[bedtimes.size / 2] % 1440,
            stageMinutes = stages,
        )
    }

    /** [day]'s stages against the average of up to [RECENT_NIGHTS] other nights before it. */
    fun compare(day: SleepDay, all: List<SleepDay>, zone: ZoneId): List<StageComparison> {
        val sleep = day.sleep ?: return emptyList()
        val others = all.filter { it.sleep != null && it.start < day.start }.sortedByDescending { it.start }.take(RECENT_NIGHTS)
        if (others.isEmpty()) return emptyList()
        val average = averages(others, zone).stageMinutes
        val mine = figures(sleep).stageMinutes
        return listOf(AWAKE, REM, LIGHT, SLEEPING, DEEP).filter { it in mine || it in average }
            .map { StageComparison(it, mine[it] ?: 0, average[it] ?: 0) }
    }

    /** Wakes on [day] that began within [NOTICE_WINDOW_MINUTES] of a BedJet step change; the first of each step. */
    private fun wakesAfterSteps(day: SleepDay): List<SleepNoticed> {
        val sleep = day.sleep ?: return emptyList()
        val plan = day.plan ?: return emptyList()
        val figures = figures(sleep)
        if (figures.wakeCount == 0) return emptyList()
        val firstSleep = sleep.stages.first { isAsleep(it.stage) }.start
        val wakes = sleep.stages.filter { isAwake(it.stage) && it.start > firstSleep }.map { it.start }
        val changes = plan.steps.drop(1).filter { it.minutes > 0 && it.step.mode != Mode.WAIT }
        return changes.mapNotNull { change ->
            val at = change.startsAt.toInstant()
            wakes.firstOrNull { !it.isBefore(at) && Duration.between(at, it).toMinutes() <= NOTICE_WINDOW_MINUTES }?.let { woke ->
                SleepNoticed(woke, Duration.between(at, woke).toMinutes().toInt(), change.step.mode, change.step.temperature, 0, 0)
            }
        }
    }

    /**
     * A pattern worth pointing out on [day]: it woke soon after a step change, and that also happened on other
     * recent nights. Returns null when it happened only once; a single night is not a pattern.
     */
    fun noticed(day: SleepDay, all: List<SleepDay>): SleepNoticed? {
        val first = wakesAfterSteps(day).firstOrNull() ?: return null
        val recent = all.filter { it.sleep != null && it.plan != null && !it.start.isAfter(day.start) }
            .sortedByDescending { it.start }.take(RECENT_NIGHTS)
        val count = recent.count { wakesAfterSteps(it).isNotEmpty() }
        return if (count >= NOTICE_MINIMUM_NIGHTS) first.copy(nights = count, ofNights = recent.size) else null
    }
}
