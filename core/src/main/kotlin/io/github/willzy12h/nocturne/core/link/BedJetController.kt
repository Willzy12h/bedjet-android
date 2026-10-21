package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/** Everything the app asks of a BedJet 3, in terms of the bytes in [Codec]. */
class BedJetController(private val link: BedJetLink, private val now: () -> ZonedDateTime) {

    suspend fun deviceName(): String = Codec.parseText(link.query(Codec.Query.DEVICE_NAME))

    suspend fun firmware(): String = Codec.parseText(link.query(Codec.Query.FIRMWARE))

    /** What is saved on the unit, one entry per slot; null where the slot is empty. */
    suspend fun storedPrograms(): List<StoredProgram?> {
        val names = Codec.parseNames(link.query(Codec.Query.PROGRAM_NAMES))
        return (0 until Limits.SLOTS).map { slot ->
            val name = names.getOrNull(slot) ?: return@map null
            programSteps(slot).takeIf { it.isNotEmpty() }?.let { StoredProgram(name, it) }
        }
    }

    suspend fun programSteps(slot: Int): List<ProgramStep> {
        val steps = mutableListOf<ProgramStep>()
        var index = 0
        while (true) {
            val fragment = Codec.parseFragment(link.query(Codec.Query.fragment(slot, index)), index)
                ?: throw BedJetError.BadResponse()
            steps += fragment.steps
            index++
            if (steps.size >= fragment.total || fragment.steps.isEmpty()) return steps
        }
    }

    /** Saves a program into a slot, replacing whatever was there, and confirms the unit kept it. */
    suspend fun store(program: StoredProgram, slot: Int) {
        if (program.steps.isEmpty()) throw BedJetError.InvalidProgram("Add at least one phase first.")
        if (program.steps.size > Limits.STEPS_PER_PROGRAM) {
            throw BedJetError.InvalidProgram("A BedJet night can hold up to ${Limits.STEPS_PER_PROGRAM} steps.")
        }
        if (!program.steps.all { it.fitsUnitLimits }) throw BedJetError.ProgramTooLong()
        for (command in Codec.writeProgram(slot, program.steps)) link.send(command)
        link.send(Codec.nameProgram(slot, program.name))
        if (programSteps(slot) != program.steps) throw BedJetError.VerifyFailed()
    }

    /**
     * Sets the unit's clock and starts a slot, then waits for the unit to report the first step running.
     * A unit that can't run the program stays off and raises a notification instead, which is cleared here.
     */
    suspend fun startProgram(slot: Int) {
        syncClock()
        link.send(Codec.startProgram(slot))
        val outcome = withTimeoutOrNull(START_CONFIRMATION_MS) {
            link.status.first { status ->
                val extra = status?.extra ?: return@first false
                extra.programStep > 0 || extra.notification in REFUSALS
            }
        }
        when (outcome?.extra?.notification) {
            Codec.Notification.PROGRAM_TOO_LONG -> { acknowledge(); throw BedJetError.ProgramTooLong() }
            Codec.Notification.PROGRAM_NEEDS_CLOCK -> { acknowledge(); throw BedJetError.ClockNotSet() }
        }
        if (outcome == null) throw BedJetError.Timeout()
    }

    suspend fun syncClock() {
        val time = now()
        link.send(Codec.setClock(time.hour, time.minute))
    }

    suspend fun setMode(mode: Mode) {
        Codec.setMode(mode)?.let { link.send(it) }
    }

    suspend fun setTemperature(halfDegrees: Int) = link.send(Codec.setTemperature(halfDegrees))
    suspend fun setFan(step: Int) = link.send(Codec.setFan(step))
    suspend fun setRuntime(minutes: Int) = link.send(Codec.setRuntime(minutes))
    suspend fun setLed(on: Boolean) = link.send(Codec.setLed(on))
    suspend fun setMuted(muted: Boolean) = link.send(Codec.setMuted(muted))
    suspend fun acknowledge() = link.send(Codec.acknowledgeNotification)
    suspend fun stop() = setMode(Mode.STANDBY)

    /** Runs a mode right now with a timer, as a snooze, extend or widget button does. Takes over from any program. */
    suspend fun runManual(mode: Mode, temperature: Int, fanStep: Int, minutes: Int) {
        setMode(mode)
        if (mode.usesTemperature) setTemperature(temperature.coerceIn(mode.temperatureRange))
        if (mode.usesFan) setFan(fanStep)
        // The unit resets the timer when the mode changes, so the timer goes last.
        setRuntime(minutes)
    }

    private companion object {
        const val START_CONFIRMATION_MS = 10_000L
        val REFUSALS = setOf(Codec.Notification.PROGRAM_TOO_LONG, Codec.Notification.PROGRAM_NEEDS_CLOCK)
    }
}
