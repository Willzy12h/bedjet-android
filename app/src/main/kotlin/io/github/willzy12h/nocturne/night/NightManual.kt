package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.CommandSafety
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.ZonedDateTime
import java.util.UUID

// Part of [NightActions], split out of NightActions.kt: manual runs (Turbo, Cool, Warm, snooze/extend, Boost, Remote
// changes) and how each is confirmed from the BedJet's own reports, plus keeping a fresh manual run tracked.

/** [night] leaves its programme for a manual run: offer to resume the programme, as Stop tracking would. A night
 * already manual keeps the offer it has, since its own steps are not a programme to resume. */
private suspend fun NightActions.offerProgrammeResume(night: ActiveNight) {
    if (night.trackingManual) return
    val offer = io.github.willzy12h.nocturne.core.night.stopTracking(night, text(R.string.runtime_manual_tracking), graph.now(),
        repo.runtime.value.generation).resumeOffer
    // The newest programme actually displaced wins; its own 15-minute window starts at this adjustment.
    repo.updateRuntime { it.copy(resumeOffer = offer) }
}

internal suspend fun NightActions.quick(preset: ActionPreset, message: String): CommandOutcome {
    if (!safePreset(preset)) return refused(R.string.runtime_command_out_of_range)
    var sentAt = elapsedMs()
    return manualChange(message, true, confirms = runs(preset) { sentAt }) {
        sentAt = elapsedMs()
        runPreset(preset)
    }
}

private fun NightActions.safePreset(preset: ActionPreset): Boolean {
    val bounds = limits()
    return CommandSafety.manual(preset.mode, PlanCompiler.temperatureFor(preset.mode, preset.temperature, bounds),
        PlanCompiler.fanStepFor(preset.fanPercent, bounds), preset.minutes) == null
}

private suspend fun NightActions.runPreset(preset: ActionPreset) {
    val limits = limits()
    graph.controller().runManual(preset.mode, PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits),
        PlanCompiler.fanStepFor(preset.fanPercent, limits), preset.minutes)
}

/** A complete manual run: controls, no programme step, and a timer consistent with monotonic time since sending. */
internal fun NightActions.runs(preset: ActionPreset, sentAt: () -> Long): (DeviceStatus) -> Boolean {
    val limits = limits()
    val temperature = PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits).coerceIn(preset.mode.temperatureRange)
    val fan = PlanCompiler.fanStepFor(preset.fanPercent, limits)
    return { s ->
        val elapsedSeconds = (elapsedMs() - sentAt()).coerceAtLeast(0) / 1_000
        val remaining = (preset.minutes * 60L - elapsedSeconds).coerceAtLeast(0)
        s.extra?.programStep == 0 && s.mode == preset.mode &&
            (!preset.mode.usesTemperature || s.targetTemperature == temperature) &&
            (!preset.mode.usesFan || s.fanStep == fan) && kotlin.math.abs(s.remainingSeconds - remaining) <= 120
    }
}

/**
 * Always runs [send]. Only complete-state commands may use an already matching snapshot. Other commands need
 * a matching receipt after [send] began or, after [confirmMs], one fresh read. An accepted write alone does not
 * prove a change took effect. Null [matches] confirms nothing.
 */
internal suspend fun NightActions.sentAndConfirmed(matches: ((DeviceStatus) -> Boolean)?, allowCurrentStatus: Boolean = false,
    send: suspend () -> Unit): Boolean = coroutineScope {
    val target = repo.settings.value.deviceId()
    val alreadyMatches = allowCurrentStatus && matches != null && graph.link.latestStatusFrom(target)?.let(matches) == true
    val reports = Channel<SourcedStatus>(64, BufferOverflow.DROP_OLDEST)
    // Subscribe first, then capture the send boundary; delayed older reports retain their earlier receipt.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        graph.link.sourcedStatus.filterNotNull().collect { if (it.isFrom(target)) reports.trySend(it) }
    }
    try {
        val sentAfter = StatusReceiptOrder.next()
        send()
        if (matches == null) false
        else alreadyMatches || withTimeoutOrNull(confirmMs) {
            reports.receiveAsFlow().first { it.receiptOrder > sentAfter && matches(it.status) }
        } != null || freshlyShows(matches)
    } finally { watcher.cancel() }
}

/** One fresh read of the target; a read that fails confirms nothing. */
internal suspend fun NightActions.freshlyShows(matches: (DeviceStatus) -> Boolean): Boolean =
    try { matches(freshStatus()) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

/**
 * A manual change: [work] is sent, and the outcome is CONFIRMED only when the BedJet's own report [confirms] it.
 * Otherwise the outcome says it was sent and is waiting for the BedJet ([unconfirmed]).
 *
 * [endsNight]: the tracked night ends only on that confirmation. Without it the night is kept, marked UNKNOWN with
 * [unconfirmed] as its reason and an ERROR event, so it never reads as running a programme, nor as ended, on a
 * write the unit did not report. Its ending intention survives interruption, so a later target Standby can close
 * the same night as STOPPED. Off stays available to send again, and Stop tracking ends it locally.
 */
internal suspend fun NightActions.manualChange(message: String, endsNight: Boolean, affectsProgramme: Boolean = false,
    confirms: ((DeviceStatus) -> Boolean)?, allowCurrentStatus: Boolean = false,
    unconfirmed: String = text(R.string.runtime_sent_unconfirmed, message),
    work: suspend () -> Unit): CommandOutcome {
    val night = repo.activeNight.value
    val requestedAt = graph.now()
    val requestedReceipt = statusReceipt()
    val pending = night?.copy(confidence = Confidence.PENDING, pending = PendingChange(UUID.randomUUID().toString(),
        ChangeKind.MANUAL, null, requestedAt.toString(), requestedAt.plusMinutes(1).toString(), night.identity))
    var writeMayHaveHappened = false
    var confirmedEnd: PendingEnd? = null
    try {
        pending?.let { publishNight(it) }
        if (endsNight && night != null) {
            // Persist before the write: a lost acknowledgement or process death cannot erase the ending intention.
            // This records intent, not successful delivery; only the target's stopped state completes it.
            repo.updateRuntime { it.copy(endRequested = night.identity, endRequestedLabel = message,
                endRequestedAt = requestedAt.toString(), endRequestedEpoch = requestedReceipt.epoch,
                endRequestedSequence = requestedReceipt.sequence, retime = null, alarmInbox = null) }
            NightAlarms.cancelRetry(context)
        }
        val confirmed = sentAndConfirmed(confirms, allowCurrentStatus) {
            writeMayHaveHappened = true
            work()
        }
        if (endsNight && night != null) {
            if (confirmed) {
                val end = PendingEnd(night.identity, event(EventKind.STOPPED, message), isTestRun = night.isTestRun)
                confirmedEnd = end
                finishLocked(night, EventKind.STOPPED, message, end)
            }
            else {
                publishNight(night.copy(confidence = Confidence.UNKNOWN, pending = null))
                repo.updateRuntime { it.copy(retime = null, reason = unconfirmed) }
                repo.addEvent(night.recordId, event(EventKind.ERROR, unconfirmed))
                NightAlarms.cancelRetry(context)
                Notifications.cancelQuiet(context)
                return CommandOutcome(OutcomeKind.UNKNOWN, unconfirmed)
            }
        }
        else if (affectsProgramme && night != null) {
            // The packet was accepted, but a temperature/fan/timer change is not proof
            // that the saved programme still describes the unit's running state.
            publishNight(night.copy(confidence = Confidence.UNKNOWN, pending = null, trackingManual = true))
            repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_manual_tracking)) }
            offerProgrammeResume(night)
            NightAlarms.cancelRetry(context)
            Notifications.cancelQuiet(context)
        }
        else if (night != null) publishNight(night)
        return if (confirmed) CommandOutcome(OutcomeKind.CONFIRMED, message) else CommandOutcome(OutcomeKind.ACCEPTED, unconfirmed)
    } catch (e: Exception) {
        val reason = when (e) {
            is TimeoutCancellationException -> text(R.string.runtime_operation_timeout)
            is CancellationException -> text(R.string.runtime_interrupted)
            else -> e.planMessage(context) ?: text(R.string.runtime_failed)
        }
        try {
            // Confirmation and its fresh fallback belong to the transaction too. Repair only this still-pending
            // request; a published confirmation, ending or newer request must never be put back to UNKNOWN.
            withContext(NonCancellable) { withTimeout(8_000) {
                val current = repo.activeNight.value
                val ownsPending = pending != null && current?.identity == pending.identity &&
                    current.confidence == Confidence.PENDING && current.pending?.requestId == pending.pending?.requestId
                if (confirmedEnd != null && repo.runtime.value.ending == confirmedEnd && (ownsPending || current == null)) {
                    // The matching report and its durable ending already won; finish that exact journal locally.
                    completeLocalEnd()
                    repo.runtime.value.resumeOffer?.let { Notifications.showResume(context) }
                } else if (ownsPending) {
                    publishNight(if (writeMayHaveHappened) current!!.copy(confidence = Confidence.UNKNOWN, pending = null) else night)
                    // Publishing the same night retains any valid Off intention and its receipt boundary.
                    repo.updateRuntime { it.copy(reason = reason) }
                } else if (night == null && current == null) repo.updateRuntime { it.copy(reason = reason) }
            } }
        } catch (cleanup: Exception) { if (cleanup !== e) e.addSuppressed(cleanup) }
        throw e
    }
}

internal suspend fun NightActions.timedManual(preset: ActionPreset, kind: EventKind, label: String): CommandOutcome {
    if (!safePreset(preset)) return refused(R.string.runtime_command_out_of_range)
    val old = repo.activeNight.value
    val message = text(R.string.runtime_timed, label, preset.minutes)
    var sentAt = elapsedMs()
    val result = manualChange(message, false, affectsProgramme = true, confirms = runs(preset) { sentAt }) {
        sentAt = elapsedMs()
        runPreset(preset)
    }
    if (old != null) {
        val until = graph.now().plusMinutes(preset.minutes.toLong())
        val bounds = limits()
        val step = ProgramStep(preset.mode, PlanCompiler.fanStepFor(preset.fanPercent, bounds),
            PlanCompiler.temperatureFor(preset.mode, preset.temperature, bounds), preset.minutes / 60, preset.minutes % 60)
        val plan = Plan(graph.now(), ZonedDateTime.parse(old.wakeAt), listOf(PlannedStep(Block.MIDDLE, step, graph.now(), preset.minutes)))
        val n = old.copy(endsAt = until.toString(), revision = old.revision + 1, confidence = Confidence.UNKNOWN,
            pending = null, trackingManual = true, applied = AppliedProgram.from(plan, old.revision + 1))
        publishNight(n)
        repo.history.value.firstOrNull { it.id == n.recordId }?.let { repo.addRecord(NightMutation.Applied(plan, n).recordRevision(it)) }
        repo.addEvent(n.recordId, event(kind, message))
        repo.updateRuntime { it.copy(retime = null, wakeShown = n.identity, manualWakeShown = true,
            reason = text(R.string.runtime_manual_tracking)) }
        NightAlarms.schedule(context, n)
        Notifications.showWakeUp(context, n, repo.settings.value, result.message, logNotice = false)
    }
    return result
}

internal suspend fun NightActions.boostLocked(captured: ActionPreset? = null): CommandOutcome {
    val s = repo.settings.value
    val preset = captured ?: ActionPreset(Mode.TURBO, 86, 100, s.boostMinutes)
    val steps = PlanCompiler.phaseSteps(Phase(preset.mode, preset.temperature, preset.fanPercent, preset.minutes), limits(), TurboChain.TURBO_THEN_HEAT)
    val result = writeSlotLocked(s.workingSlot, StoredProgram("Boost", steps), boostDescription(context, preset.minutes), start = true)
    if (result.succeeded) repo.activeNight.value?.let { finishLocked(it, EventKind.STOPPED, result.message) }
    return result
}

internal suspend fun NightActions.keepTrackingLocked(): CommandOutcome = connected {
    val night = repo.activeNight.value ?: return@connected refused(R.string.runtime_no_night)
    if (night.confidence != Confidence.UNKNOWN) return@connected refused(R.string.runtime_ineligible)
    val fresh = freshStatus()
    if (!fresh.isRunning) return@connected refused(R.string.runtime_reconcile_needed)
    advanceGeneration(preserveOffer = true)
    val minutes = ((fresh.remainingSeconds + 59) / 60).coerceAtLeast(1)
    val step = ProgramStep(fresh.mode, fresh.fanStep, fresh.targetTemperature, minutes / 60, minutes % 60)
    val plan = Plan(graph.now(), ZonedDateTime.parse(night.wakeAt), listOf(PlannedStep(Block.MIDDLE, step, graph.now(), minutes)))
    val updated = night.copy(confidence = Confidence.CONFIRMED, pending = null, trackingManual = true,
        revision = night.revision + 1, endsAt = graph.now().plusSeconds(fresh.remainingSeconds.toLong()).toString(),
        applied = AppliedProgram.from(plan, night.revision + 1))
    publishNight(updated)
    repo.history.value.firstOrNull { it.id == night.recordId }?.let { repo.addRecord(NightMutation.Applied(plan, updated).recordRevision(it)) }
    // Keeping a fresh run acknowledges a slot write that displaced or interrupted this night (IR-06).
    repo.updateRuntime { it.copy(retime = null, reason = null,
        wakeShown = if (it.manualWakeShown && it.wakeShown == night.identity) updated.identity else it.wakeShown,
        slotWrite = it.slotWrite?.takeUnless { w -> w.belongsTo(night.recordId) }) }
    repo.addEvent(night.recordId, event(EventKind.RECOVERED, text(R.string.runtime_manual_tracking)))
    offerProgrammeResume(night)
    NightAlarms.schedule(context, updated)
    // A snooze's wake-up notification still on screen now names this run's own end, not the old wake (P5).
    if (repo.runtime.value.let { it.manualWakeShown && it.wakeShown == updated.identity })
        Notifications.showWakeUp(context, updated, repo.settings.value, logNotice = false)
    CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_manual_tracking))
}
