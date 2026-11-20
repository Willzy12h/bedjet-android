package io.github.willzy12h.nocturne.sleep

import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.platform.SleepStage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** A session window from Health Connect. Only reported stages establish time asleep. */
data class SleepNight(val start: Instant, val end: Instant, val stages: List<SleepStage>, val sourceId: String? = null) {
    val hasRecordedSleep: Boolean get() = stages.any {
        SleepInsights.isAsleep(it.stage) && Duration.between(it.start, it.end).toMinutes() > 0
    }

    companion object {
        fun of(start: Instant, end: Instant, stages: List<SleepStage>, sourceId: String? = null): SleepNight =
            SleepNight(start, end, stages.mapNotNull { stage ->
                val from = maxOf(start, stage.start)
                val to = minOf(end, stage.end)
                if (to > from) stage.copy(start = from, end = to) else null
            }.sortedBy { it.start }, sourceId)
    }
}

/**
 * One night on the Sleep page: what Health Connect recorded, what the BedJet ran, or both. Either may be
 * missing; a night with no BedJet run still shows its sleep, without a BedJet plan.
 */
data class SleepDay(val date: LocalDate, val sleep: SleepNight?, val record: NightRecord?, val plan: Plan?) {
    val selectionId: String get() = sleep?.sourceId?.let { "health:$it" } ?: record?.let { "bedjet:${it.id}" } ?: "session:$start/$end"
    val start: Instant get() = sleep?.start ?: plan?.start?.toInstant() ?: ZonedDateTime.parse(record!!.startedAt).toInstant()
    val end: Instant get() = sleep?.end ?: savedInstant(record?.standbyObservedAt) ?: plan?.wake?.toInstant()
        ?: savedInstant(record?.wakeAt) ?: start
}

private fun savedInstant(text: String?): Instant? = text?.let { runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() }

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
 * wording says so. Unknown stages count towards recorded time in bed and never imply time asleep.
 */
object SleepInsights {
    const val UNKNOWN = 0
    const val AWAKE = 1
    const val SLEEPING = 2
    const val OUT_OF_BED = 3
    const val LIGHT = 4
    const val DEEP = 5
    const val REM = 6
    const val AWAKE_IN_BED = 7

    /** A wake this soon after a step change counts towards a pattern. */
    const val NOTICE_WINDOW_MINUTES = 10L
    /** A pattern is mentioned only when it repeats on at least this many of the recent nights. */
    const val NOTICE_MINIMUM_NIGHTS = 2
    const val RECENT_NIGHTS = 7

    fun isAsleep(stage: Int) = stage == SLEEPING || stage == LIGHT || stage == DEEP || stage == REM
    fun isAwake(stage: Int) = stage == AWAKE || stage == OUT_OF_BED || stage == AWAKE_IN_BED

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
        val totals = stages.groupBy {
            when {
                isAwake(it.stage) -> AWAKE
                isAsleep(it.stage) -> it.stage
                else -> UNKNOWN
            }
        }
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
            val start = savedInstant(record.startedAt) ?: return@mapNotNull null
            Triple(record, start, savedInstant(record.standbyObservedAt) ?: savedInstant(record.wakeAt) ?: start)
        }
        // Allocate the strongest overlap first. A short earlier nap must not claim an overnight run.
        val overlaps = nights.flatMap { night ->
            timed.mapNotNull { (record, start, end) ->
                val overlap = Duration.between(maxOf(start, night.start), minOf(end, night.end))
                if (overlap.isNegative || overlap.isZero) null else Triple(overlap, night, record)
            }
        }.sortedByDescending { it.first }
        val joined = mutableMapOf<SleepNight, NightRecord>()
        val used = mutableSetOf<String>()
        overlaps.forEach { (_, night, record) ->
            if (night !in joined && used.add(record.id)) joined[night] = record
        }
        val days = nights.map { night ->
            val record = joined[night]
            SleepDay(night.end.atZone(zone).toLocalDate(), night, record, record?.let(planOf))
        } + timed.filter { it.first.id !in used }.map { (record, start, _) ->
            val evidence = bedJetEvidence(record)
            val recorded = evidence.standby ?: evidence.lastRunning ?: evidence.lastReading ?: evidence.confirmedStart ?: start
            SleepDay(recorded.atZone(zone).toLocalDate(), null, record, planOf(record))
        }
        return days.sortedWith(compareByDescending<SleepDay> { it.date }.thenByDescending { it.start })
    }

    /** A date's default is the longest session with sleep stages, then a tracker window or BedJet record. */
    fun representative(sessions: List<SleepDay>, selected: SleepDay? = null): SleepDay? =
        selected?.takeIf { it.sleep?.hasRecordedSleep == true && it in sessions }
            ?: sessions.filter { it.sleep?.hasRecordedSleep == true }.maxByOrNull { Duration.between(it.start, it.end) }
            ?: selected?.takeIf { it.sleep != null && it in sessions }
            ?: sessions.firstOrNull { it.sleep != null }
            ?: sessions.firstOrNull()

    data class BedJetEvidence(val confirmedStart: Instant?, val standby: Instant?, val lastRunning: Instant?, val lastReading: Instant?) {
        /** Time between device confirmations, which may differ from actual continuous running time. */
        val minutesBetweenReports: Int? get() {
            val start = confirmedStart ?: return null
            val end = standby ?: return null
            return Duration.between(start, end).toMinutes().takeIf { end >= start }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        }
    }

    fun bedJetEvidence(record: NightRecord): BedJetEvidence {
        val confirmed = record.events.lastOrNull { it.kind == EventKind.STARTED && record.bedJetRunConfirmed }?.let { savedInstant(it.at) }
        val lowerBound = confirmed ?: savedInstant(record.startedAt)
        val running = savedInstant(record.lastRunningObservedAt)?.takeIf { lowerBound == null || it >= lowerBound }
        val standby = savedInstant(record.standbyObservedAt)?.takeIf {
            (lowerBound == null || it >= lowerBound) && (running == null || it >= running)
        }
        val reading = record.temperatures.mapNotNull { savedInstant(it.at) }
            .filter { lowerBound == null || it >= lowerBound }.maxOrNull()
        return BedJetEvidence(confirmed, standby, running, reading)
    }

    /** The nights whose wake date falls in the last [days] days up to [today]. */
    fun inRange(all: List<SleepDay>, today: LocalDate, days: Int): List<SleepDay> =
        all.filter { !it.date.isAfter(today) && it.date.isAfter(today.minusDays(days.toLong())) }

    /** One usable tracker session per date, so naps and multiple trackers don't add extra nights. */
    fun trackedNights(days: List<SleepDay>): List<SleepNight> =
        days.groupBy { it.date }.values.mapNotNull { representative(it)?.sleep?.takeIf(SleepNight::hasRecordedSleep) }

    fun averages(days: List<SleepDay>, zone: ZoneId): SleepAverages {
        val nights = trackedNights(days)
        val figures = nights.map(::figures)
        if (figures.isEmpty()) return SleepAverages(0, 0, null, emptyMap())
        val stages = figures.flatMap { it.stageMinutes.keys }.toSet()
            .associateWith { code -> (figures.sumOf { it.stageMinutes[code] ?: 0 } / figures.size.toFloat()).roundToInt() }
        // Bedtimes after noon count as the evening before, so 23:30 and 00:30 average to midnight, not noon.
        val bedtimes = nights.map { it.start.atZone(zone) }.map { (it.hour * 60 + it.minute).let { m -> if (m < 12 * 60) m + 1440 else m } }
        return SleepAverages(
            nights = figures.size,
            asleepMinutes = (figures.sumOf { it.asleepMinutes } / figures.size.toFloat()).roundToInt(),
            usualBedtimeMinute = bedtimes.sorted()[bedtimes.size / 2] % 1440,
            stageMinutes = stages,
        )
    }

    /** [day]'s stages against the average of up to [RECENT_NIGHTS] other nights before it. */
    fun compare(day: SleepDay, all: List<SleepDay>, zone: ZoneId): List<StageComparison> {
        val sleep = day.sleep?.takeIf(SleepNight::hasRecordedSleep) ?: return emptyList()
        val earlier = all.filter { it.date < day.date && it.sleep?.hasRecordedSleep == true }
        val dates = earlier.map { it.date }.distinct().sortedDescending().take(RECENT_NIGHTS)
        val average = averages(earlier.filter { it.date in dates }, zone).stageMinutes
        if (average.isEmpty()) return emptyList()
        val mine = figures(sleep).stageMinutes
        return listOf(AWAKE, REM, LIGHT, SLEEPING, DEEP, UNKNOWN).filter { it in mine || it in average }
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
