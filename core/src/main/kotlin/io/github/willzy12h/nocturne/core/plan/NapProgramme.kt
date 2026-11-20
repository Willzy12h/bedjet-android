package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Serializable
data class NapPreset(
    val durationMinutes: Int = 90,
    val opening: List<Phase> = listOf(Phase(Mode.TURBO, 86, 100, 10), Phase(Mode.HEAT, 70, 30, 30)),
    val untilWake: Phase? = Phase(Mode.COOL, 44, 40, 1),
    val afterWake: List<Phase> = listOf(Phase(Mode.COOL, 44, 80, 10)),
    val useProfileOpening: Boolean = false,
)

object NapProgramme {
    const val MAX_MINUTES = Limits.STEPS_PER_PROGRAM * Limits.MAX_STEP_MINUTES
    private val previewStart = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)

    /** Shared by backup import and the editor. Profile openings depend on the profile chosen at start. */
    fun validate(preset: NapPreset): String? = try {
        checkFields(preset)
        if (!preset.useProfileOpening) compile(preset, previewStart, null, null)
        null
    } catch (e: PlanException) { e.message }

    fun plan(preset: NapPreset, start: ZonedDateTime, wake: ZonedDateTime? = null, selectedProfile: Profile? = null): Plan {
        checkFields(preset)
        return compile(preset, start, wake, selectedProfile)
    }

    private fun checkFields(preset: NapPreset) {
        if (preset.durationMinutes !in 1..MAX_MINUTES) throw PlanException("Choose a nap duration within the BedJet’s programme limits.")
        if (preset.opening.size > Limits.STEPS_PER_PROGRAM || preset.afterWake.size > Limits.STEPS_PER_PROGRAM)
            throw PlanException("A BedJet programme holds up to 32 steps.")
        if (preset.untilWake?.mode in setOf(Mode.TURBO, Mode.STANDBY, Mode.WAIT))
            throw PlanException("Choose Heat, Long heat, Cool or Dry for the stage that runs until wake.")
        for (phase in preset.opening + listOfNotNull(preset.untilWake) + preset.afterWake) {
            if (phase.mode == Mode.TURBO && (phase.temperature != 86 || phase.fanPercent != 100 || phase.minutes > Limits.TURBO_MINUTES))
                throw PlanException("Turbo runs at 43 °C and 100% fan for up to ten minutes.")
            if (phase.minutes !in 1..MAX_MINUTES || phase.fanPercent !in 5..100 || phase.fanPercent % 5 != 0 ||
                phase.mode == Mode.STANDBY || (phase.mode.usesFan && phase.temperature !in phase.mode.temperatureRange) ||
                (!phase.mode.usesFan && phase.temperature !in 38..86))
                throw PlanException("A nap stage has settings outside the BedJet’s limits.")
        }
    }

    private fun compile(preset: NapPreset, start: ZonedDateTime, wake: ZonedDateTime?, selectedProfile: Profile?): Plan {
        val requestedWake = wake ?: start.plusMinutes(preset.durationMinutes.toLong())
        // Absolute choices use the BedJet’s whole-minute clock. Relative durations keep the START seconds.
        val planStart = if (wake == null) start else start.withSecond(wake.second).withNano(wake.nano).let {
            if (it.isAfter(start)) it.minusMinutes(1) else it
        }
        if (wake != null && Duration.between(start, wake) < Duration.ofMinutes(1))
            throw PlanException("Choose a nap wake time at least a minute ahead.")
        val available = Duration.between(planStart, requestedWake).toMinutes()
        if (available !in 1..MAX_MINUTES.toLong()) throw PlanException("Choose a future nap wake time within the BedJet’s programme limits.")
        val profile = if (preset.useProfileOpening) selectedProfile ?: throw PlanException("Choose a regular profile with an opening stage.")
            else null
        val limits = profile?.limits ?: ProfileLimits()
        val chain = profile?.turboChain ?: TurboChain.TURBO_THEN_HEAT
        var cursor = planStart
        val steps = mutableListOf<PlannedStep>()
        var previousHeat: Phase? = null
        var heatMinutes = 0
        fun append(block: Block, phase: Phase) {
            // A continuous nap Heat stage cannot bypass the cut-off by splitting into adjacent identical steps.
            if (profile == null && phase.mode == Mode.HEAT) {
                val sameControls = previousHeat?.let { it.fanPercent == phase.fanPercent && it.temperature == phase.temperature } == true
                heatMinutes = if (sameControls) heatMinutes + phase.minutes else phase.minutes
                if (heatMinutes > ProgramStep.longestMinutes(Mode.HEAT, phase.temperature, phase.fanPercent / 5 - 1))
                    throw PlanException("Shorten the Heat stage or choose cooler air within the BedJet’s Heat time limit.")
                previousHeat = phase
            } else previousHeat = null
            if (phase.minutes !in 1..MAX_MINUTES) throw PlanException("A nap stage exceeds the BedJet’s programme limits.")
            for (step in PlanCompiler.phaseSteps(phase, limits, chain)) {
                if (steps.size == Limits.STEPS_PER_PROGRAM) throw PlanException("A BedJet programme holds up to 32 steps.")
                steps += PlannedStep(block, step, cursor, step.durationMinutes)
                cursor = cursor.plusMinutes(step.durationMinutes.toLong())
            }
        }
        var remaining = available.toInt()
        for (opening in profile?.start ?: preset.opening) {
            if (remaining == 0) break
            val take = minOf(remaining, opening.minutes)
            append(Block.START, opening.copy(minutes = take))
            remaining -= take
        }
        if (profile == null && remaining > 0) {
            if (preset.untilWake != null) append(Block.MIDDLE, preset.untilWake.copy(minutes = remaining))
            else if (preset.afterWake.isNotEmpty()) throw PlanException("Add an until-wake stage so post-wake airflow can start at the chosen time.")
        }
        if (steps.isEmpty()) throw PlanException("Add an opening or until-wake stage before starting this nap.")
        val actualWake = cursor
        val last = steps.last()
        if (wake != null && last.block == Block.MIDDLE && last.minutes > PlanCompiler.MIN_CLOCK_TIME_STEP) {
            val clock = planStart.toLocalTime().plusMinutes(available)
            steps[steps.lastIndex] = last.copy(step = last.step.copy(hours = clock.hour, minutes = clock.minute, endsAtClockTime = true))
        }
        if (profile == null) preset.afterWake.forEach { append(Block.AFTER_WAKE, it) }
        return Plan(planStart, actualWake, steps)
    }

    fun snapshot(name: String, preset: NapPreset, selectedProfile: Profile?): Profile {
        val opening = selectedProfile.takeIf { preset.useProfileOpening }
        val steady = preset.untilWake ?: Phase(Mode.COOL, 44, 40, 1)
        return Profile(id = "nocturne-quick-nap", name = name, start = opening?.start ?: preset.opening,
            middle = Middle(steady.mode, steady.fanPercent, steady.fanPercent, steady.temperature, steady.temperature),
            wake = emptyList(), afterWake = if (opening == null) preset.afterWake else emptyList(),
            limits = opening?.limits ?: ProfileLimits(), turboChain = opening?.turboChain ?: TurboChain.TURBO_THEN_HEAT,
            wakeSource = WakeSource(useAlarm = false))
    }
}
