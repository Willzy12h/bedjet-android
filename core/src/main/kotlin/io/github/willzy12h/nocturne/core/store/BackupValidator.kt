package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZonedDateTime

/**
 * Checks a backup before anything is replaced with it, so a damaged or hand-edited file can't leave the app holding
 * values the BedJet or the planner would refuse. Every problem is reported, each as a plain sentence.
 */
object BackupValidator {
    /** Bigger than any real backup: 60 nights of history come to well under 1 MB. */
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_PHASE_MINUTES = 720
    const val MAX_RAMP_STEPS = 8
    private val FAN = 5..100
    private val PHASE_MINUTES = 1..MAX_PHASE_MINUTES
    private val RAMP_STEPS = 1..MAX_RAMP_STEPS
    private val SLOTS = 0 until Limits.SLOTS
    private val BOOST_MINUTES = 10..30
    private val ACTION_MINUTES = 1..120
    private val MINUTE_OF_DAY = 0 until 24 * 60
    private val DAYS = 1..7
    private const val MAX_NOTIFICATION_ACTIONS = 3
    /** A programme step's fan: `(fanStep + 1) * 5` percent. */
    private val FAN_STEPS = 0..19
    private val BYTE = 0..255
    private val MINUTES = 0..59
    private val HOURS_OF_DAY = 0..23
    /** Every temperature any mode allows, Turbo's included. */
    private val allTemps = Mode.entries.minOf { it.temperatureRange.first }..Mode.entries.maxOf { it.temperatureRange.last }

    /**
     * Reads a backup file, refusing it once it passes [MAX_BYTES] without reading the rest or decoding any of it.
     * Throws [BackupException] when it is too big.
     */
    fun readWithinLimit(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw BackupException("This file is too big to be a Nocturne backup (over 5 MB).")
        }
        return out.toByteArray().decodeToString()
    }

    /** Every problem with [bundle]; empty when it is safe to restore. */
    fun validate(bundle: BackupBundle): List<String> = buildList {
        if (bundle.schema > BackupCodec.SCHEMA) add("This backup is from a newer version of Nocturne. Update the app first.")
        time("The backup's creation time", bundle.createdAt)

        bundle.profiles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("The profile id \"$it\" is used more than once.") }
        bundle.profiles.forEach { profile(it) }
        settings(bundle.settings)

        bundle.slotBackups.forEach { backup -> slotBackup(backup) }

        bundle.history.forEach { night ->
            val what = "The night \"${night.profileName}\""
            time("$what start time", night.startedAt)
            time("$what wake-up time", night.wakeAt)
            night.steps.forEach { time("$what step time", it.startsAt) }
            night.events.forEach { time("$what event time", it.at) }
            night.temperatures.forEach { time("$what temperature reading time", it.at) }
        }
    }

    private fun MutableList<String>.profile(profile: Profile) {
        val name = profile.name.ifBlank { profile.id }
        listOf("start" to profile.start, "wake-up" to profile.wake, "after wake-up" to profile.afterWake).forEach { (block, phases) ->
            phases.forEachIndexed { i, phase -> phase(phase, "$name: step ${i + 1} of the $block") }
        }
        val middle = profile.middle
        fan("$name: the middle's starting fan", middle.fanStart)
        fan("$name: the middle's ending fan", middle.fanEnd)
        temperature("$name: the middle's starting temperature", middle.mode, middle.tempStart)
        temperature("$name: the middle's ending temperature", middle.mode, middle.tempEnd)
        if (middle.rampSteps !in RAMP_STEPS) add("$name: the middle has ${middle.rampSteps} ramp steps; it must be 1 to $MAX_RAMP_STEPS.")
        val limits = profile.limits
        if (limits.minFan !in FAN || limits.maxFan !in FAN || limits.minFan > limits.maxFan) {
            add("$name: the fan limits ${limits.minFan}% to ${limits.maxFan}% must be in order, within 5 to 100%.")
        }
        if (limits.minTemp !in allTemps || limits.maxTemp !in allTemps || limits.minTemp > limits.maxTemp) {
            add("$name: the temperature limits ${celsius(limits.minTemp)} to ${celsius(limits.maxTemp)} must be in order, within ${celsius(allTemps.first)} to ${celsius(allTemps.last)}.")
        }
        if (profile.wakeSource.defaultWakeMinute !in MINUTE_OF_DAY) add("$name: the wake-up time of day is out of range.")
        if (profile.bedtimeMinute !in MINUTE_OF_DAY) add("$name: the bedtime time of day is out of range.")
        if (profile.days.any { it !in DAYS }) add("$name: the days of the week must be Monday to Sunday.")
    }

    private fun MutableList<String>.slotBackup(backup: SlotBackup) {
        val slot = backup.slot + 1
        if (backup.slot !in SLOTS) add("A saved slot is numbered $slot; it must be 1 to ${Limits.SLOTS}.")
        time("The time slot $slot was saved", backup.savedAt)
        val device = backup.deviceId
        if (device != DeviceId.DEMO && device != Migrations.UNKNOWN_DEVICE && DeviceId.address(device).isNullOrBlank()) {
            add("The original of slot $slot was saved for \"$device\", which isn't a BedJet this app knows.")
        }
        if (backup.empty != (backup.program == null)) {
            add("The original of slot $slot ${if (backup.empty) "is marked empty but has a programme" else "isn't marked empty but has no programme"}.")
        }
        backup.program?.let { program(it, "The programme saved from slot $slot") }
    }

    /** IR-16: a saved programme can be written back to a BedJet, so it must be within what the codec writes as it is. */
    private fun MutableList<String>.program(program: StoredProgram, what: String) {
        if (program.steps.size > Limits.STEPS_PER_PROGRAM) {
            add("$what has ${program.steps.size} steps; a BedJet holds up to ${Limits.STEPS_PER_PROGRAM}.")
        }
        program.steps.forEachIndexed { i, step ->
            val at = "$what, step ${i + 1}"
            if (step.fanStep !in FAN_STEPS) add("$at: the fan setting is ${step.fanStep}; it must be ${FAN_STEPS.first} to ${FAN_STEPS.last}.")
            if (step.mode.usesTemperature) temperature("$at: the temperature", step.mode, step.temperature)
            else if (step.temperature !in BYTE) add("$at: the temperature value ${step.temperature} is out of range.")
            if (step.minutes !in MINUTES) add("$at runs for ${step.minutes} minutes past the hour; it must be 0 to 59.")
            if (step.endsAtClockTime) {
                if (step.hours !in HOURS_OF_DAY) add("$at ends at hour ${step.hours}, which isn't a time of day.")
            } else if (step.hours < 0 || step.durationMinutes > Limits.MAX_STEP_MINUTES) {
                add("$at runs for ${step.hours} h ${step.minutes} min; a BedJet step must be shorter than 12 hours.")
            }
        }
    }

    private fun MutableList<String>.phase(phase: Phase, what: String) {
        if (phase.minutes !in PHASE_MINUTES) add("$what runs for ${phase.minutes} minutes; it must be 1 to $MAX_PHASE_MINUTES.")
        fan("$what: the fan", phase.fanPercent)
        temperature("$what: the temperature", phase.mode, phase.temperature)
    }

    private fun MutableList<String>.settings(settings: Settings) {
        addAll(settings.platform.validationErrors())
        if (settings.workingSlot !in SLOTS) add("The working slot is ${settings.workingSlot + 1}; it must be 1 to ${Limits.SLOTS}.")
        if (settings.boostMinutes !in BOOST_MINUTES) add("Boost lasts ${settings.boostMinutes} minutes; it must be 10 to 30.")
        if (settings.notificationActions.size > MAX_NOTIFICATION_ACTIONS) {
            add("The wake-up notification has ${settings.notificationActions.size} buttons; it can have up to $MAX_NOTIFICATION_ACTIONS.")
        }
        listOf(
            "Snooze warm" to settings.snoozeWarm, "Snooze cool" to settings.snoozeCool,
            "Extend warm" to settings.extendWarm, "Extend cool" to settings.extendCool,
        ).forEach { (what, preset) ->
            if (preset.minutes !in ACTION_MINUTES) add("$what runs for ${preset.minutes} minutes; it must be 1 to ${ACTION_MINUTES.last}.")
            fan("$what: the fan", preset.fanPercent)
            temperature("$what: the temperature", preset.mode, preset.temperature)
        }
    }

    private fun MutableList<String>.fan(what: String, percent: Int) {
        if (percent !in FAN) add("$what is $percent%; it must be 5 to 100%.")
    }

    /** Modes with a single fixed temperature (Turbo, Standby, Wait) ignore the stored one. */
    private fun MutableList<String>.temperature(what: String, mode: Mode, value: Int) {
        val range = mode.temperatureRange
        if (mode.usesTemperature && value !in range) {
            add("$what is ${celsius(value)}; ${modeName(mode)} allows ${celsius(range.first)} to ${celsius(range.last)}.")
        }
    }

    private fun MutableList<String>.time(what: String, value: String) {
        if (runCatching { ZonedDateTime.parse(value) }.isFailure) add("$what \"$value\" isn't a date and time.")
    }

    private fun celsius(halfDegrees: Int) = "${halfDegrees / 2}${if (halfDegrees % 2 != 0) ".5" else ""} °C"

    private fun modeName(mode: Mode) = mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
