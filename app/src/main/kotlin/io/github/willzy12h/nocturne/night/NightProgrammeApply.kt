package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.SlotBackupsUnreadable
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZonedDateTime
import io.github.willzy12h.nocturne.night.NightActions.RetimeTakeover

// Part of [NightActions], split out of NightActions.kt: applying a prepared programme change to the BedJet and
// settling it, including the history revision it records and the durable proof that survives a later local failure.

internal suspend fun NightActions.apply(preparation: Preparation, message: Int,
    validate: () -> Unit = {}, afterWrite: suspend () -> Unit = {}): CommandOutcome {
    val change = when (preparation) {
        is Preparation.Ready -> preparation.change
        is Preparation.Refused -> return if (preparation.unchanged) local(R.string.runtime_unchanged)
            else CommandOutcome(OutcomeKind.REFUSED, preparation.refusalText(context))
        is Preparation.Stale -> return stale()
    }
    val pendingRetimeId = repo.runtime.value.retime?.id
    return try { applyPrepared(change, message, afterWrite, validate)
    } catch (e: CancellationException) {
        confirmedPreparedProgramme(change)?.let { confirmed ->
            withContext(NonCancellable) {
                try {
                    withTimeout(8_000) {
                        try {
                            // Publication can have completed before clearing an older ending marker failed.
                            publishNight(confirmed)
                            recordConfirmedProgramme(change, confirmed, message)
                        } finally {
                            settleConfirmedProgramme(pendingRetimeId, confirmed, reason = null,
                                resumeGeneration = change.expectedCommand.takeIf { change.request.kind == ChangeKind.RESUME }, change = change)
                        }
                    }
                } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            }
        }
        throw e
    } catch (e: Exception) {
        val confirmed = confirmedPreparedProgramme(change) ?: throw e
        // The exact prepared programme is durably confirmed. A later local save failure cannot undo that proof.
        val detail = text(R.string.runtime_programme_record_incomplete, e.message ?: text(R.string.runtime_failed))
        settleConfirmedProgramme(pendingRetimeId, confirmed, detail,
            resumeGeneration = change.expectedCommand.takeIf { change.request.kind == ChangeKind.RESUME }, change = change)
        Notifications.showInfo(context, text(message, confirmed.profile!!.name, clock(confirmed.wakeAt)), detail)
        CommandOutcome(OutcomeKind.CONFIRMED, detail, notificationHandled = true)
    }
}

private fun NightActions.confirmedPreparedProgramme(change: PreparedChange): ActiveNight? = repo.activeNight.value?.takeIf {
    change.request.kind in setOf(ChangeKind.START, ChangeKind.REAPPLY, ChangeKind.RESUME, ChangeKind.TEMPORARY) &&
        it == change.intended && it.confidence == Confidence.CONFIRMED && !it.trackingManual && !hasUnreconciledProgramme(it)
}

/** A revision and its event share one durable history write, so interrupted settlement can repeat safely. */
private suspend fun NightActions.recordConfirmedProgramme(change: PreparedChange, night: ActiveNight, message: Int) {
    change.previous?.takeIf { it.recordId != night.recordId }?.let { previous ->
        val replaced = text(R.string.runtime_replaced)
        val last = repo.history.value.firstOrNull { it.id == previous.recordId }?.events?.lastOrNull()
        if (last?.kind != EventKind.STOPPED || last.detail != replaced)
            repo.addEvent(previous.recordId, event(EventKind.STOPPED, replaced))
        // A Resume already settled before cancellation must not recreate the offer it consumed.
        if (change.request.kind != ChangeKind.RESUME || repo.runtime.value.generation == change.expectedCommand) {
            val offer = io.github.willzy12h.nocturne.core.night.stopTracking(previous, replaced, graph.now(), repo.runtime.value.generation).resumeOffer
            repo.updateRuntime { it.copy(resumeOffer = offer) }
        }
    }
    val existing = repo.history.value.firstOrNull { it.id == night.recordId }
    val record = existing ?: NightRecord(night.recordId, night.profileId, night.profile!!.name, night.startedAt,
        night.wakeAt, night.wakeFromAlarm, night.applied.steps, deviceId = night.deviceId, isTestRun = night.isTestRun)
    val revised = NightMutation.Applied(change.plan, night).recordRevision(record)
    val started = existing == null || change.request.kind == ChangeKind.START
    val events = if (night.applied in record.revisions) record.events else record.events +
        event(if (started) EventKind.STARTED else if (change.request.kind == ChangeKind.TEMPORARY) EventKind.TEMPORARY else EventKind.RETIMED,
            programmeDetail(change, night, message))
    val continuous = change.request.kind == ChangeKind.START ||
        (record.bedJetRunConfirmed && change.previous?.confidence == Confidence.CONFIRMED &&
            change.previous?.trackingManual == false && events.none {
                it.kind in setOf(EventKind.ERROR, EventKind.STOPPED, EventKind.REMOTE_CHANGE, EventKind.RECOVERED)
            })
    repo.addRecord(revised.copy(events = events, bedJetRunConfirmed = continuous && !repo.settings.value.demoMode &&
        !night.isTestRun && !io.github.willzy12h.nocturne.core.link.DeviceId.isDemo(night.deviceId) &&
            night.deviceId.startsWith("ble:") && night.confidence == Confidence.CONFIRMED && night.pending == null))
}

private fun NightActions.temporaryShortened(change: PreparedChange): Boolean {
    if (change.request.kind != ChangeKind.RETIME) return false
    val old = change.previous?.temporary?.until?.let(ZonedDateTime::parse) ?: return false
    val new = change.intended.temporary?.until?.let(ZonedDateTime::parse)
    return if (new == null) old.isAfter(change.plan.start) else new.isBefore(old)
}

private fun NightActions.programmeDetail(change: PreparedChange, night: ActiveNight, message: Int): String =
    text(message, night.profile!!.name, clock(night.wakeAt)) +
        if (temporaryShortened(change)) " " + text(R.string.runtime_temporary_shortened) else ""

private suspend fun NightActions.applyPrepared(change: PreparedChange, message: Int, afterWrite: suspend () -> Unit,
    validate: () -> Unit): CommandOutcome {
    var confirmedPublicationFailure: Exception? = null
    val result = graph.runner().apply(change, { repo.activeNight.value }, { repo.runtime.value.generation },
        publish = {
            try { publishNight(it) } catch (e: Exception) {
                if (it != change.intended || repo.activeNight.value != it) throw e
                // The confirmed snapshot is already durable. Defer a later local failure (including
                // cancellation) beyond the core's hardware-failure catch so it cannot undo that proof.
                confirmedPublicationFailure = e
            }
            // IR-23: a first start's record exists before the first write, with the steps it is to run.
            // The pending night of a fresh start carries no applied steps; its intended snapshot does.
            val intended = it.pending?.intended
            if (it.confidence == Confidence.PENDING && it.pending?.kind == ChangeKind.START && intended != null &&
                repo.history.value.none { record -> record.id == it.recordId }) {
                repo.addRecord(NightRecord(intended.recordId, intended.profileId, intended.profile!!.name, intended.startedAt,
                    intended.wakeAt, intended.wakeFromAlarm, intended.applied.steps, deviceId = intended.deviceId,
                    isTestRun = intended.isTestRun))
            }
            if (it.confidence != Confidence.CONFIRMED) Notifications.cancelQuiet(context)
        }, validate = validate, onConfirmed = { programmeConfirmed(change.intended, it) },
        beforeWrite = { backup(change.intended.deviceId, change.intended.slot) }, afterWrite = afterWrite)
    confirmedPublicationFailure?.let { throw it }
    val outcome = when (result) {
        is NightMutation.Applied -> {
            val n = result.night
            recordConfirmedProgramme(change, n, message)
            // IR-06: a confirmed write of this BedJet slot settles an earlier write of it still in question.
            repo.updateRuntime { it.withTemporaryAlarmHandoff(change).copy(retime = null, reason = null, wakeShown = null, manualWakeShown = false,
                generation = if (change.request.kind == ChangeKind.RESUME) it.generation + 1 else it.generation,
                resumeOffer = if (change.request.kind == ChangeKind.RESUME) null else it.resumeOffer,
                slotWrite = it.slotWrite?.takeUnless { w -> w.deviceId == n.deviceId && w.slot == n.slot }) }
            NightAlarms.schedule(context, n)
            NightAlarms.cancelRetry(context)
            if (repo.runtime.value.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
            Notifications.cancelWakeUp(context)
            refreshQuietNotification()
            if (message != R.string.runtime_resumed && change.previous?.recordId != n.recordId &&
                repo.runtime.value.resumeOffer != null) Notifications.showResume(context)
            if (message == R.string.runtime_retimed) Notifications.showInfo(context, text(R.string.runtime_moved_title, clock(n.wakeAt)),
                text(R.string.runtime_moved_body, clock(n.wakeAt), clock(change.previous!!.wakeAt)) +
                    if (temporaryShortened(change)) " " + text(R.string.runtime_temporary_shortened) else "")
            CommandOutcome(OutcomeKind.CONFIRMED, programmeDetail(change, n, message))
        }
        is NightMutation.Failed -> {
            if (result.nothingWritten && retimeExpired(change)) {
                // The backup/read can outlive the request. The old night is unchanged and no restart was sent.
                expireRetimeFor(change.intended.recordId)
                local(R.string.runtime_retime_expired)
            } else if (result.cause is SlotBackupsUnreadable) refused(R.string.runtime_slot_backups_unreadable)
            else if (result.cause is RetimeTakeover) {
                val reason = text(R.string.runtime_takeover)
                repo.updateRuntime { it.copy(reason = reason) }
                repo.addEvent(change.intended.recordId, event(EventKind.REMOTE_CHANGE, reason))
                Notifications.showInfo(context, text(R.string.runtime_takeover_title), reason)
                CommandOutcome(OutcomeKind.UNKNOWN, reason, notificationHandled = true)
            } else {
                val reason = result.cause.planMessage(context)
                repo.updateRuntime { it.copy(reason = reason) }
                if (change.request.kind == ChangeKind.START)
                    repo.addEvent(change.intended.recordId, event(EventKind.ERROR, reason.orEmpty()))
                else recordError(reason.orEmpty())
                // The final check before writing ruled the change out for good (the request expired, its wake-up
                // has begun): nothing was written and trying again cannot help, so it is refused, not retried. A
                // check only this moment failed (a clock-time step about to end, the offset changing) stays a
                // failure, which a re-time tries again within its deadline. Either way the reason and the
                // night's record keep the error.
                if (result.nothingWritten && result.cause is PlanException && result.cause !is RetryablePlanException)
                    CommandOutcome(OutcomeKind.REFUSED, reason.orEmpty())
                // IR-05: nothing written means the night shown is the one still running, whatever its confidence.
                else CommandOutcome(if (!result.nothingWritten && result.night.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                    reason ?: text(R.string.runtime_failed))
            }
        }
        is NightMutation.Refused -> if (retimeExpired(change)) {
            expireRetimeFor(change.intended.recordId)
            local(R.string.runtime_retime_expired)
        } else CommandOutcome(OutcomeKind.REFUSED, result.reason)
        is NightMutation.Stale -> stale()
    }
    // Every branch, including a late originals barrier, agrees with this request's durable FAILED publication.
    return reconcileProgrammeOutcome(if (result is NightMutation.Failed && result.night.confidence == Confidence.FAILED &&
        result.night.pending?.requestId == change.request.requestId) outcome.copy(kind = OutcomeKind.FAILED) else outcome)
}

private fun NightActions.retimeExpired(change: PreparedChange): Boolean = change.request.kind == ChangeKind.RETIME &&
    !graph.now().isBefore(ZonedDateTime.parse(change.request.deadline))

internal suspend fun NightActions.settleConfirmedProgramme(pendingId: String?, confirmed: ActiveNight, reason: String?,
    resumeGeneration: Long? = null, change: PreparedChange? = null) {
    try {
        repo.updateRuntime { it.withTemporaryAlarmHandoff(change).copy(retime = it.retime?.takeUnless { request -> request.id == pendingId },
            generation = if (it.generation == resumeGeneration) it.generation + 1 else it.generation,
            resumeOffer = if (it.generation == resumeGeneration) null else it.resumeOffer,
            reason = reason, wakeShown = null, manualWakeShown = false,
            slotWrite = it.slotWrite?.takeUnless { write -> write.deviceId == confirmed.deviceId && write.slot == confirmed.slot }) }
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) {
        // The warning retains the original local error; this best-effort save is not a new hardware outcome.
    } finally {
        NightAlarms.schedule(context, confirmed)
        settleRetimeAlarm(pendingId)
        Notifications.cancelWakeUp(context)
        refreshQuietNotification()
    }
}
