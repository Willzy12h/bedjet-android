package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** A temporary interval replaces elapsed time; it never moves the underlying night later. */
internal object TemporaryProgramme {
    data class Composition(val plan: Plan, val temporary: TemporaryOverride?)
    class Refusal(val reason: TemporaryRefusal) : Exception()

    fun prepare(profile: Profile, base: AppliedProgram, now: ZonedDateTime, preset: ActionPreset): Composition {
        val original = base.plan() ?: throw Refusal(TemporaryRefusal.MISSING_SNAPSHOT)
        val start = now.truncatedTo(ChronoUnit.MINUTES)
        val prefix = mutableListOf<PlannedStep>()
        append(prefix, Block.TEMPORARY, Phase(preset.mode, preset.temperature, preset.fanPercent, preset.minutes),
            start, profile, TurboChain.TURBO_THEN_HEAT)
        return compose(profile, base, original, start, prefix, now)
    }

    /** Re-time the base first, then retain only the original temporary interval that can still precede wake-up. */
    fun replan(profile: Profile, base: Plan, original: TemporaryOverride, now: ZonedDateTime, revision: Int): Composition {
        val start = base.start
        val anchor = base.steps.lastOrNull { it.block == Block.MIDDLE && it.minutes >= PlanCompiler.MIN_CLOCK_TIME_STEP }
        val until = anchor?.let { minOf(ZonedDateTime.parse(original.until), it.endsAt.minusMinutes(PlanCompiler.MIN_CLOCK_TIME_STEP.toLong())) }
        if (until == null || !until.isAfter(now) || !until.isAfter(start)) return Composition(base, null)
        val prefix = mutableListOf<PlannedStep>()
        var cursor = start
        for (s in original.steps) {
            val began = ZonedDateTime.parse(s.startsAt)
            val end = minOf(began.plusMinutes(s.minutes.toLong()), until)
            val from = maxOf(start, began)
            if (!end.isAfter(from)) continue
            if (from.isAfter(cursor)) appendPause(prefix, Block.TEMPORARY, cursor, from, profile)
            val minutes = Duration.between(from, end).toMinutes().toInt()
            append(prefix, Block.TEMPORARY, Phase(s.mode, s.temperature, s.fanPercent, minutes), from, profile,
                TurboChain.TURBO_THEN_HEAT)
            cursor = end
        }
        if (prefix.isEmpty()) return Composition(base, null)
        return compose(profile, AppliedProgram.from(base, revision), base, start, prefix, now)
    }

    private fun compose(profile: Profile, base: AppliedProgram, original: Plan, start: ZonedDateTime,
        prefix: List<PlannedStep>, now: ZonedDateTime): Composition {
        val until = prefix.last().endsAt
        if (!until.isBefore(original.wakeUpStart)) throw Refusal(TemporaryRefusal.TOO_CLOSE_TO_WAKE)
        val steps = prefix.toMutableList()
        var cursor = until
        for (s in original.steps) {
            // Restarting a partly elapsed original Turbo would give it another run. Keep its gap, not its heat.
            if (!s.endsAt.isAfter(cursor) || (s.step.mode == Mode.TURBO && s.startsAt.isBefore(until))) continue
            val from = maxOf(cursor, s.startsAt)
            if (from.isAfter(cursor)) appendPause(steps, s.block, cursor, from, profile)
            val minutes = Duration.between(from, s.endsAt).toMinutes().toInt()
            append(steps, s.block, Phase(s.step.mode, s.step.temperature, (s.step.fanStep + 1) * 5, minutes),
                from, profile, profile.turboChain)
            cursor = s.endsAt
        }
        if (cursor.isBefore(original.end)) appendPause(steps,
            if (cursor.isBefore(original.wake)) Block.WAKE else Block.AFTER_WAKE, cursor, original.end, profile)

        // The remaining clock anchor absorbs preparation/upload time without pushing the saved wake later.
        val anchorIndex = steps.indexOfLast { it.block == Block.MIDDLE && it.minutes >= PlanCompiler.MIN_CLOCK_TIME_STEP }
        if (anchorIndex < 0) throw Refusal(TemporaryRefusal.TOO_CLOSE_TO_WAKE)
        val anchor = steps[anchorIndex]
        if (!now.isBefore(anchor.endsAt.minusMinutes(PlanCompiler.MIN_CLOCK_TIME_STEP.toLong())))
            throw Refusal(TemporaryRefusal.TOO_CLOSE_TO_WAKE)
        val endOnUnitClock = start.toLocalTime().plusMinutes(Duration.between(start, anchor.endsAt).toMinutes())
        steps[anchorIndex] = anchor.copy(step = anchor.step.copy(hours = endOnUnitClock.hour,
            minutes = endOnUnitClock.minute, endsAtClockTime = true))
        if (steps.size > Limits.STEPS_PER_PROGRAM) throw Refusal(TemporaryRefusal.TOO_MANY_STEPS)
        val plan = Plan(start, original.wake, steps)
        val expanded = AppliedProgram.from(Plan(start, original.wake, prefix)).steps
        return Composition(plan, TemporaryOverride(base, expanded, until.toString()))
    }

    private fun appendPause(out: MutableList<PlannedStep>, block: Block, from: ZonedDateTime,
        until: ZonedDateTime, profile: Profile) = append(out, block,
        Phase(Mode.WAIT, 80, 5, Duration.between(from, until).toMinutes().toInt()), from, profile, profile.turboChain)

    private fun append(out: MutableList<PlannedStep>, block: Block, phase: Phase, from: ZonedDateTime,
        profile: Profile, turbo: TurboChain) {
        var cursor = from
        for (step in PlanCompiler.phaseSteps(phase, profile.limits, turbo)) {
            out += PlannedStep(block, step, cursor, step.durationMinutes)
            cursor = cursor.plusMinutes(step.durationMinutes.toLong())
        }
    }
}
