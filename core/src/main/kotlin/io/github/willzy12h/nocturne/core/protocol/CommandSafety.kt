package io.github.willzy12h.nocturne.core.protocol

import java.time.LocalTime

/** Write policy, deliberately separate from lossless decoding of captured originals. */
object CommandSafety {
    enum class Problem { STRUCTURE, TEMPERATURE, FAN, DURATION }

    fun controls(mode: Mode, temperature: Int, fanStep: Int): Problem? = when {
        fanStep !in 0..19 -> Problem.FAN
        temperature !in 0..255 -> Problem.TEMPERATURE
        mode.usesFan && temperature !in mode.temperatureRange -> Problem.TEMPERATURE
        else -> null
    }

    fun manual(mode: Mode, temperature: Int, fanStep: Int, minutes: Int): Problem? =
        controls(mode, temperature, fanStep) ?: when {
            !mode.usesFan -> Problem.STRUCTURE
            minutes !in 1..ProgramStep.longestMinutes(mode, temperature, fanStep) -> Problem.DURATION
            else -> null
        }

    fun stepFields(step: ProgramStep): Problem? = controls(step.mode, step.temperature, step.fanStep) ?: when {
        step.minutes !in 0..59 -> Problem.STRUCTURE
        step.hours !in (if (step.endsAtClockTime) 0..23 else 0..11) -> Problem.STRUCTURE
        !step.endsAtClockTime && step.durationMinutes !in 1..step.longestMinutes -> Problem.DURATION
        else -> null
    }

    /** Evaluate each wall-clock end at its actual sequential start, not as an unbounded exemption. */
    fun programme(program: StoredProgram, startsAt: LocalTime): Problem? {
        if (program.steps.size !in 1..Limits.STEPS_PER_PROGRAM) return Problem.STRUCTURE
        var cursor = startsAt.toSecondOfDay()
        for (step in program.steps) {
            stepFields(step)?.let { return it }
            val seconds = if (step.endsAtClockTime) {
                Math.floorMod((step.hours * 60 + step.minutes) * 60 - cursor, 86_400).let {
                    if (it == 0) 86_400 else it
                }
            } else step.durationMinutes * 60
            if (seconds > step.longestMinutes * 60) return Problem.DURATION
            cursor = (cursor + seconds) % 86_400
        }
        return null
    }

    fun manualMaximum(status: DeviceStatus): Int {
        if (!status.mode.usesFan || controls(status.mode, status.targetTemperature, status.fanStep) != null) return 0
        return minOf(status.maxRuntimeMinutes.coerceAtLeast(0),
            ProgramStep.longestMinutes(status.mode, status.targetTemperature, status.fanStep))
    }
}
