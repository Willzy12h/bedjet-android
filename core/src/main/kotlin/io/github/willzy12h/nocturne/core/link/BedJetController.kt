package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.CommandSafety
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
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
        validateSlot(slot)
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
        validateSlot(slot)
        validateProgram(program)
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
     * [onConfirmed] receives the original receipt that confirmed the requested first step, so the command owner
     * can reconcile later observations across its durable publication. [beforePrepare] may read fresh status after clock sync
     * and before start preparation.
     * [beforeStart] can reject an expired request after clock sync/status preparation, immediately before sending
     * the start command. If it throws, this scope cancels the confirmation waiter without issuing a start.
     */
    suspend fun startProgram(slot: Int, onStartAttempt: () -> Unit = {},
        beforePrepare: suspend () -> Unit = {}, onConfirmed: (SourcedStatus) -> Unit = {},
        beforeStart: () -> Unit = {}): Unit = coroutineScope {
        validateSlot(slot)
        val programme = StoredProgram("", programSteps(slot))
        validateProgram(programme)
        val firstStep = programme.steps.firstOrNull() ?: throw BedJetError.BadResponse()
        syncClock()
        beforePrepare()
        // Only reports that arrive after this count: a program already running would otherwise look like this one
        // starting, and a refusal that follows would be missed. The link forgets the progress it last saw first.
        try {
            link.prepareForStart()
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(START_CONFIRMATION_MS) {
                    link.sourcedStatus.drop(1).first { report ->
                        val status = report?.status ?: return@first false
                        status.extra?.notification in REFUSALS || matchesFirstStep(status, firstStep, requireProgress = true)
                    }
                }
            }
            beforeStart()
            link.sendGuarded(Codec.startProgram(slot)) {
                validateProgram(programme)
                beforeStart()
                // This runs at the transport write boundary, after the final temporal guard.
                onStartAttempt()
            }
            val confirmed = reply.await() ?: throw BedJetError.Timeout()
            var outcome = confirmed
            if (outcome.status.extra?.notification !in REFUSALS) {
                // A refusal can follow the first report; give it a moment.
                outcome = withTimeoutOrNull(REFUSAL_GRACE_MS) {
                    link.sourcedStatus.first { it?.status?.extra?.notification in REFUSALS }
                } ?: outcome
            }
            when (outcome.status.extra?.notification) {
                Codec.Notification.PROGRAM_TOO_LONG -> { acknowledge(); throw BedJetError.ProgramTooLong() }
                Codec.Notification.PROGRAM_NEEDS_CLOCK -> { acknowledge(); throw BedJetError.ClockNotSet() }
            }
            // A successful first report cannot overrule a newer Off/manual change received during the grace.
            // A head without progress may still contradict the mode/controls; it cannot replace the full receipt.
            val latest = link.latestSourcedStatus()
            if (latest != null && latest.receiptOrder >= confirmed.receiptOrder &&
                (!latest.isFrom(confirmed.source) || !matchesFirstStep(latest.status, firstStep, requireProgress = false)))
                throw BedJetError.BadResponse()
            onConfirmed(confirmed)
        } finally {
            link.cancelPreparedStart()
        }
    }

    private fun matchesFirstStep(status: DeviceStatus, step: ProgramStep, requireProgress: Boolean): Boolean =
        status.mode == step.mode && (!step.mode.usesFan || status.fanStep == step.fanStep) &&
            (!step.mode.usesTemperature || status.targetTemperature == step.temperature) &&
            (status.extra?.programStep == 1 || (!requireProgress && status.extra == null))

    suspend fun syncClock() {
        val time = now()
        link.send(Codec.setClock(time.hour, time.minute))
    }

    suspend fun setMode(mode: Mode) {
        Codec.setMode(mode)?.let { link.send(it) }
    }

    suspend fun setTemperature(halfDegrees: Int) {
        val status = currentStatus()
        requireSafe(CommandSafety.controls(status.mode, halfDegrees, status.fanStep))
        if (!status.mode.usesFan || status.minTemperature > status.maxTemperature ||
            halfDegrees !in status.minTemperature..status.maxTemperature)
            throw BedJetError.InvalidProgram("The requested temperature is outside the current control range.")
        if (status.remainingSeconds > ProgramStep.longestMinutes(status.mode, halfDegrees, status.fanStep) * 60)
            throw BedJetError.ProgramTooLong()
        link.send(Codec.setTemperature(halfDegrees))
    }
    suspend fun setFan(step: Int) {
        val status = currentStatus()
        requireSafe(CommandSafety.controls(status.mode, status.targetTemperature, step))
        // Standby fan preferences support the non-heating setup check; no mode is started here.
        if (status.mode.usesFan && status.remainingSeconds >
            ProgramStep.longestMinutes(status.mode, status.targetTemperature, step) * 60)
            throw BedJetError.ProgramTooLong()
        link.send(Codec.setFan(step))
    }
    suspend fun setRuntime(minutes: Int) {
        if (minutes !in 1..CommandSafety.manualMaximum(currentStatus())) throw BedJetError.ProgramTooLong()
        link.send(Codec.setRuntime(minutes))
    }
    suspend fun setLed(on: Boolean) = link.send(Codec.setLed(on))
    suspend fun setMuted(muted: Boolean) = link.send(Codec.setMuted(muted))
    suspend fun acknowledge() = link.send(Codec.acknowledgeNotification)
    suspend fun stop() = setMode(Mode.STANDBY)

    /** Runs a mode right now with a timer, as a snooze, extend or widget button does. Takes over from any program. */
    suspend fun runManual(mode: Mode, temperature: Int, fanStep: Int, minutes: Int) {
        // Validate the complete request before changing mode: no partially applied unsafe run.
        requireSafe(CommandSafety.manual(mode, temperature, fanStep, minutes))
        setMode(mode)
        if (mode.usesTemperature) link.send(Codec.setTemperature(temperature))
        if (mode.usesFan) link.send(Codec.setFan(fanStep))
        // Do not validate against the previous mode's cached status during this owned batch.
        link.send(Codec.setRuntime(minutes))
    }

    fun validateProgram(program: StoredProgram) = requireSafe(CommandSafety.programme(program, now().toLocalTime()))

    private fun currentStatus(): DeviceStatus {
        if (link.state.value != LinkState.READY) throw BedJetError.NotConnected()
        return link.latestSourcedStatus()?.status ?: throw BedJetError.NotConnected()
    }
    private fun validateSlot(slot: Int) {
        if (slot !in 0 until Limits.SLOTS) throw BedJetError.InvalidProgram("Choose a valid BedJet slot.")
    }
    private fun requireSafe(problem: CommandSafety.Problem?) {
        if (problem == CommandSafety.Problem.DURATION) throw BedJetError.ProgramTooLong()
        if (problem != null) throw BedJetError.InvalidProgram("The request exceeds the BedJet safety limits.")
    }

    private companion object {
        const val START_CONFIRMATION_MS = 10_000L
        const val REFUSAL_GRACE_MS = 1_500L
        val REFUSALS = setOf(Codec.Notification.PROGRAM_TOO_LONG, Codec.Notification.PROGRAM_NEEDS_CLOCK)
    }
}
