package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

/** One stretch of the night. Temperatures are half-degrees Celsius, as the BedJet stores them. */
@Serializable
data class Phase(val mode: Mode, val temperature: Int, val fanPercent: Int, val minutes: Int)

/** The part of the night that stretches to fill the time, moving fan and temperature in [rampSteps] equal steps. */
@Serializable
data class Middle(
    val mode: Mode,
    val fanStart: Int,
    val fanEnd: Int,
    val tempStart: Int,
    val tempEnd: Int,
    val rampSteps: Int = 1,
    /** The Quiet middle fan shape; null for Steady or Ease, which [fanStart] and [fanEnd] describe. When set,
     * [fanStart] and [fanEnd] are kept at its settle and rise speeds, so an older app reads it as an Ease. */
    val quietFan: QuietFan? = null,
) {
    val fanShape: FanShape get() = when {
        quietFan != null -> FanShape.QUIET_MIDDLE
        fanStart == fanEnd -> FanShape.STEADY
        else -> FanShape.EASE
    }

    /** Every fan speed this Middle sets. */
    val fanSpeeds: List<Int> get() = listOf(fanStart, fanEnd) + listOfNotNull(quietFan).flatMap { listOf(it.settle, it.asleep, it.rise) }

    /** This Middle with every fan speed moved by [delta] per cent; timings are left alone. */
    fun shiftFan(delta: Int): Middle = copy(
        fanStart = fanStart + delta,
        fanEnd = fanEnd + delta,
        quietFan = quietFan?.let { it.copy(settle = it.settle + delta, asleep = it.asleep + delta, rise = it.rise + delta) },
    )
}

/** How the fan moves through the Middle, chosen apart from the temperature. */
enum class FanShape {
    /** One speed all night. */
    STEADY,
    /** Moves from [Middle.fanStart] to [Middle.fanEnd] across the temperature steps. */
    EASE,
    /** [QuietFan.settle] while falling asleep, [QuietFan.asleep] through the night, [QuietFan.rise] before the wake-up. */
    QUIET_MIDDLE,
}

/** Fan speeds (per cent) for the Quiet middle shape. The rise keeps its minutes first when the Middle is short. */
@Serializable
data class QuietFan(val settle: Int, val settleMinutes: Int, val asleep: Int, val rise: Int, val riseMinutes: Int)

/** Bounds every phase, ramp step, snooze and extend is held to, on top of each mode's own range. */
@Serializable
data class ProfileLimits(
    val minFan: Int = 5,
    val maxFan: Int = 100,
    val minTemp: Int = 38,
    val maxTemp: Int = 86,
)

@Serializable
data class WakeSource(
    /** Take the next alarm from the clock app when there is one in the night's window. */
    val useAlarm: Boolean = true,
    /** Minutes after midnight, used when there is no alarm. */
    val defaultWakeMinute: Int = 7 * 60,
)

@Serializable
enum class Season { NONE, SUMMER, WINTER, SPRING_AUTUMN }

/** What a Turbo phase longer than the BedJet's 10 minutes becomes after the first 10. */
@Serializable
enum class TurboChain { TURBO_THEN_HEAT, REPEAT_TURBO }

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val season: Season = Season.NONE,
    val start: List<Phase>,
    val middle: Middle,
    val wake: List<Phase>,
    val afterWake: List<Phase> = emptyList(),
    val limits: ProfileLimits = ProfileLimits(),
    val wakeSource: WakeSource = WakeSource(),
    /** Minutes after midnight; for previews and auto-start. */
    val bedtimeMinute: Int = 22 * 60 + 30,
    /** ISO days of week (1 = Monday) this is the default profile for. */
    val days: Set<Int> = emptySet(),
    val turboChain: TurboChain = TurboChain.TURBO_THEN_HEAT,
)

@Serializable
enum class Block { START, MIDDLE, WAKE, AFTER_WAKE, TEMPORARY }

data class PlannedStep(val block: Block, val step: ProgramStep, val startsAt: ZonedDateTime, val minutes: Int) {
    val endsAt: ZonedDateTime get() = startsAt.plusMinutes(minutes.toLong())
}

data class Plan(val start: ZonedDateTime, val wake: ZonedDateTime, val steps: List<PlannedStep>) {
    val end: ZonedDateTime get() = steps.lastOrNull()?.endsAt ?: start
    val programSteps: List<ProgramStep> get() = steps.map { it.step }

    /** When the wake-up begins: the first Wake-up step, or the wake time when there is none. */
    val wakeUpStart: ZonedDateTime get() = steps.firstOrNull { it.block == Block.WAKE }?.startsAt ?: wake

    /** The step running at [time], with its index, or null before the start and after the end. */
    fun stepAt(time: ZonedDateTime): Pair<Int, PlannedStep>? =
        steps.withIndex().firstOrNull { !time.isBefore(it.value.startsAt) && time.isBefore(it.value.endsAt) }
            ?.let { it.index to it.value }
}

open class PlanException(message: String) : Exception(message)
/** A plan ruled out only by this moment (a clock-time step about to end, the clock offset changing): one compiled
 * afresh can still apply, so the change is worth trying again, unlike an expired request or a begun wake-up. */
class RetryablePlanException(message: String) : PlanException(message)
