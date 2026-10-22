package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Turns a profile and a wake time into the steps the BedJet runs, following the unit's limits. */
object PlanCompiler {
    /** Shortest last Middle step that ends at a clock time rather than after a duration. */
    const val MIN_CLOCK_TIME_STEP = 10

    /**
     * Compiles a night from [start] to [wake]. Wake-up keeps priority over Start, and Start over Middle, when the
     * night is too short for all of them. The BedJet's clock is set when the program starts, so the last Middle step
     * ends at the wake-up start measured on that clock.
     */
    fun compile(profile: Profile, startAt: ZonedDateTime, wake: ZonedDateTime): Plan {
        if (Duration.between(startAt, wake) > WakeResolver.MAX_NIGHT) {
            throw PlanException("A night can last at most 16 hours. Set a tonight-only wake time.")
        }
        (profile.start + profile.wake + profile.afterWake).forEach { temperatureFor(it.mode, it.temperature, profile.limits) }
        temperatureFor(profile.middle.mode, profile.middle.tempStart, profile.limits)
        // The BedJet's clock is set to the minute, so the plan starts on that minute too.
        val start = startAt.truncatedTo(ChronoUnit.MINUTES)
        // Real minutes, not wall-clock minutes: a night across a clock change is an hour longer or shorter.
        val available = maxOf(0, Duration.between(start, wake).toMinutes().toInt())
        val wakePhases = trimFront(profile.wake, available)
        val startPhases = trimEnd(profile.start, available - wakePhases.sumOf { it.minutes })
        val middleMinutes = available - wakePhases.sumOf { it.minutes } - startPhases.sumOf { it.minutes }

        val blocks = buildList {
            startPhases.forEach { add(Block.START to it) }
            ramp(profile.middle, middleMinutes).forEach { add(Block.MIDDLE to it) }
            wakePhases.forEach { add(Block.WAKE to it) }
            profile.afterWake.forEach { add(Block.AFTER_WAKE to it) }
        }

        var elapsed = 0
        val steps = mutableListOf<PlannedStep>()
        for ((block, phase) in blocks) {
            for (step in phaseSteps(phase, profile.limits, profile.turboChain)) {
                steps += PlannedStep(block, step, start.plusMinutes(elapsed.toLong()), step.durationMinutes)
                elapsed += step.durationMinutes
            }
        }

        val lastMiddle = steps.indexOfLast { it.block == Block.MIDDLE }
        // A short last step could already be past its clock-time end by the time it begins (writing the night takes a
        // while), and the BedJet would then wait for that time tomorrow. Those run by duration instead.
        if (lastMiddle >= 0 && steps[lastMiddle].minutes >= MIN_CLOCK_TIME_STEP) {
            val planned = steps[lastMiddle]
            val endOnBedJetClock = start.toLocalTime().plusMinutes(Duration.between(start, planned.endsAt).toMinutes())
            steps[lastMiddle] = planned.copy(
                step = planned.step.copy(hours = endOnBedJetClock.hour, minutes = endOnBedJetClock.minute, endsAtClockTime = true),
            )
        }

        if (steps.size > Limits.STEPS_PER_PROGRAM) {
            throw PlanException(
                "This night needs ${steps.size} BedJet steps but it holds ${Limits.STEPS_PER_PROGRAM}. " +
                    "Use fewer phases or ramp steps, or shorter Heat phases.",
            )
        }
        return Plan(start, wake, steps)
    }

    /**
     * Recompiles the rest of a running night for a new wake time, from [now]. Returns null once the wake-up has
     * begun, because by then the person is waking and the plan is left alone.
     */
    fun replan(profile: Profile, plan: Plan, now: ZonedDateTime, newWake: ZonedDateTime): Plan? {
        if (!now.isBefore(plan.wakeUpStart)) return null
        val remainingStart = plan.steps.filter { it.block == Block.START }.mapNotNull { s ->
            if (!s.endsAt.isAfter(now) || (s.step.mode == Mode.TURBO && !s.startsAt.isAfter(now))) null
            else Phase(s.step.mode, s.step.temperature, (s.step.fanStep + 1) * 5,
                Duration.between(if (s.startsAt.isAfter(now)) s.startsAt else now, s.endsAt).toMinutes().toInt())
        }
        return compile(profile.copy(start = remainingStart), now, newWake)
    }

    // Phase shaping

    private fun ramp(middle: Middle, minutes: Int): List<Phase> {
        if (minutes <= 0) return emptyList()
        val count = middle.rampSteps.coerceIn(1, 8).coerceAtMost(minutes)
        return (0 until count).map { i ->
            val t = if (count == 1) 0.0 else i.toDouble() / (count - 1)
            val share = minutes / count + if (i >= count - minutes % count) 1 else 0
            Phase(
                mode = middle.mode,
                temperature = lerp(middle.tempStart, middle.tempEnd, t),
                fanPercent = lerp(middle.fanStart, middle.fanEnd, t),
                minutes = share,
            )
        }
    }

    /** One phase becomes one or more steps: clamped to the limits, then split where the unit would stop it. */
    fun phaseSteps(phase: Phase, limits: ProfileLimits, turboChain: TurboChain): List<ProgramStep> {
        if (phase.minutes <= 0) return emptyList()
        temperatureFor(phase.mode, phase.temperature, limits)
        val fanStep = fanStepFor(phase.fanPercent, limits)
        return when (phase.mode) {
            Mode.TURBO -> {
                val turbo = step(Mode.TURBO, fanStep, Mode.TURBO.temperatureRange.first, minOf(phase.minutes, Limits.TURBO_MINUTES))
                val rest = phase.minutes - turbo.durationMinutes
                when {
                    rest <= 0 -> listOf(turbo)
                    turboChain == TurboChain.REPEAT_TURBO ->
                        split(rest, Limits.TURBO_MINUTES).map { turbo.copy(hours = it / 60, minutes = it % 60) }.let { listOf(turbo) + it }
                    else -> listOf(turbo) + phaseSteps(Phase(Mode.HEAT, limits.maxTemp, phase.fanPercent, rest), limits, turboChain)
                }
            }
            else -> {
                val temperature = temperatureFor(phase.mode, phase.temperature, limits)
                val longest = ProgramStep.longestMinutes(phase.mode, temperature, fanStep)
                split(phase.minutes, longest).map { step(phase.mode, fanStep, temperature, it) }
            }
        }
    }

    /** Fan percent held to the limits, as the BedJet's 0..19 fan step. */
    fun fanStepFor(fanPercent: Int, limits: ProfileLimits): Int {
        val low = (maxOf(5, limits.minFan) + 4) / 5
        val high = minOf(100, limits.maxFan) / 5
        if (low > high) throw PlanException("No BedJet fan setting fits this profile's limits")
        return (fanPercent / 5.0).roundToInt().coerceIn(low, high) - 1
    }

    /** A target held to the limits and to what the BedJet accepts for the mode. */
    fun temperatureFor(mode: Mode, requested: Int, limits: ProfileLimits): Int {
        val range = mode.temperatureRange
        if (mode == Mode.TURBO && limits.maxTemp < 86) {
            throw PlanException("Turbo always runs at 43 °C, above this profile's limit")
        }
        if (mode == Mode.STANDBY || mode == Mode.WAIT) return range.first
        val low = maxOf(limits.minTemp, range.first)
        val high = minOf(limits.maxTemp, range.last)
        if (low > high) throw PlanException("${mode.name.lowercase().replace('_', ' ')} cannot honour this profile's temperature limits")
        return requested.coerceIn(low, high)
    }

    private fun step(mode: Mode, fanStep: Int, temperature: Int, minutes: Int) =
        ProgramStep(mode, fanStep, temperature, hours = minutes / 60, minutes = minutes % 60)

    /** Splits [minutes] into the fewest near-equal parts no longer than [longest]. */
    private fun split(minutes: Int, longest: Int): List<Int> {
        val parts = (minutes + longest - 1) / longest
        return (0 until parts).map { i -> minutes / parts + if (i >= parts - minutes % parts) 1 else 0 }
    }

    private fun trimFront(phases: List<Phase>, budget: Int): List<Phase> =
        trimEnd(phases.reversed(), budget).reversed()

    private fun trimEnd(phases: List<Phase>, budget: Int): List<Phase> {
        var left = maxOf(0, budget)
        return phases.mapNotNull { phase ->
            val kept = minOf(phase.minutes, left)
            left -= kept
            if (kept > 0) phase.copy(minutes = kept) else null
        }
    }

    private fun dropFront(phases: List<Phase>, minutes: Int): List<Phase> {
        var skip = maxOf(0, minutes)
        return phases.mapNotNull { phase ->
            val dropped = minOf(phase.minutes, skip)
            skip -= dropped
            if (phase.minutes - dropped > 0) phase.copy(minutes = phase.minutes - dropped) else null
        }
    }

    private fun lerp(from: Int, to: Int, t: Double): Int = (from + (to - from) * t).roundToInt()
}
