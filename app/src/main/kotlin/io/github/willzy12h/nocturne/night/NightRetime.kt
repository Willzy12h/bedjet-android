package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.BluetoothCommandException
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZonedDateTime
import java.util.UUID
import io.github.willzy12h.nocturne.night.NightActions.RetimeTakeover
import io.github.willzy12h.nocturne.night.NightActions.RetimeExpiry
import io.github.willzy12h.nocturne.night.NightActions.TerminalRetime

// Part of [NightActions], split out of NightActions.kt: re-timing the wake-up after an alarm change. The alarm
// observation inbox, the pending re-time journal and its retries, expiry at wake-up, and terminal failure notices.

internal fun NightActions.retimeWasExpired(night: ActiveNight): Boolean = lastRetimeExpiry?.let {
    night.confidence == Confidence.CONFIRMED && it.expected == night.identity && it.deviceId == night.deviceId &&
        it.deviceId == repo.settings.value.deviceId() && it.generation == repo.runtime.value.generation
} == true

/** Cancel only the settled request's delivery; a separately saved observation still needs its own retry. */
internal fun NightActions.settleRetimeAlarm(pendingId: String?) {
    NightAlarms.cancelRetry(context)
    val runtime = repo.runtime.value
    val newer = runtime.retime?.takeUnless { it.id == pendingId }
    if (newer != null) NightAlarms.scheduleRetry(context, newer, graph.now())
    else if (runtime.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
}

internal suspend fun NightActions.submitAlarmChangeLocal(): CommandOutcome {
    val night = repo.activeNight.value
    if (night == null) {
        withTimeout(8_000) { repo.updateRuntime { if (repo.activeNight.value == null) it.copy(alarmInbox = null, retime = null) else it } }
        if (!retimePending) NightAlarms.cancelRetry(context)
        return refused(R.string.runtime_no_night)
    }
    if (!graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))) {
        expireRetimeFor(night.recordId)
        return local(R.string.runtime_unchanged)
    }
    val device = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
    val observation = AlarmObservation(UUID.randomUUID().toString(), night.recordId, device, graph.now().toString())
    NightAlarms.scheduleObservation(context, graph.now())
    var expired = false
    withTimeout(8_000) {
        repo.updateRuntime {
            // This expires only submission metadata, never active state or a hardware transaction.
            expired = repo.activeNight.value?.identity == night.identity && !graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))
            if (expired) it // Preserve pending evidence until the single expiry owner consumes it below.
            else it.copy(alarmInbox = observation)
        }
    }
    if (expired) {
        expireRetimeFor(night.recordId)
        return local(R.string.runtime_unchanged)
    }
    return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_pending))
}

internal suspend fun NightActions.consumeAlarmObservation(): CommandOutcome? {
    val observed = repo.runtime.value.alarmInbox ?: return null
    val night = repo.activeNight.value
    val previousPendingId = repo.runtime.value.retime?.id
    var expiry: RetimeExpiry? = null
    val outcome = if (night?.recordId == observed.nightId && repo.settings.value.deviceId() == observed.deviceId) {
        if (!graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))) {
            expiry = expireRetimeFor(night.recordId)
            expiry?.let { local(R.string.runtime_retime_expired) }
                ?: if (previousPendingId == null) local(R.string.runtime_unchanged) else null
        } else queueRetimeLocked(observed.id)
    } else null
    val preparedNewRequest = repo.runtime.value.retime?.id?.let { it != previousPendingId } == true
    repo.updateRuntime { if (it.alarmInbox?.id == observed.id) it.copy(alarmInbox = null) else it }
    if (!retimePending) NightAlarms.cancelRetry(context)
    // A quiet observation must not fall through to an unsafe-night refusal or execute an older pending change.
    // Keep terminal outcomes too, so an explicit service retry cannot report the same refusal twice.
    return outcome?.takeIf { it.notificationHandled || expiry != null || (it.kind == OutcomeKind.LOCAL && !preparedNewRequest) }
}

private fun NightActions.unchangedAlarmObservation(night: ActiveNight): Boolean {
    if (night.wakeOrigin == WakeOrigin.OVERRIDE) return true
    val alarm = AlarmSource.describe(context, repo.settings.value)
    if (alarm?.counts == false) return true
    val prepared = graph.runner().prepareRetime(night, night.identity, alarm?.time,
        ZonedDateTime.parse(night.wakeUpStart), repo.profile(night.profileId), repo.settings.value, alarm?.creator)
    return prepared is Preparation.Refused && prepared.unchanged
}

/** Atomically consume the real pending change; repeated observations/retries cannot post duplicate expiry notices. */
internal suspend fun NightActions.expireRetimeFor(nightId: String): RetimeExpiry? {
    var expired = false
    var disposition: RetimeExpiry? = null
    var expiredRequest: PendingRetime? = null
    var expiredObservation: AlarmObservation? = null
    var expiredAt: String? = null
    var expiryDetail: String? = null
    var consumedRuntime: RuntimeState? = null
    var consumedPublished = false
    return try {
        repo.updateRuntime { runtime ->
            val active = repo.activeNight.value?.takeIf { it.recordId == nightId }
            // An alarm observation does not own an in-flight re-time. START may already have been sent;
            // only that command can decide whether it completed or expired without a write.
            if (active?.confidence == Confidence.PENDING &&
                active.pending?.kind == ChangeKind.RETIME) return@updateRuntime runtime
            val pending = runtime.retime?.takeIf { it.expected.nightId == nightId }
            expired = pending != null && active?.identity == pending.expected && active.confidence == Confidence.CONFIRMED
            if (expired) {
                expiredRequest = pending
                expiredObservation = runtime.alarmInbox?.takeIf { it.nightId == nightId }
                expiredAt = active!!.wakeUpStart
                expiryDetail = retimeWarningDetail(text(R.string.runtime_retime_expired_detail),
                    requestedRetimeWake(pending), pending!!.deviceId)
            }
            val observed = runtime.alarmInbox?.nightId == nightId
            // An unchanged observation never requested a rewrite, so crossing wake-up cannot give it an expiry.
            if (expired || (pending == null && observed && active?.confidence == Confidence.CONFIRMED &&
                    !unchangedAlarmObservation(active)))
                disposition = RetimeExpiry(active!!.identity, active.deviceId, runtime.generation)
            // Publication can win this race: an old revision's request says nothing about a new or uncertain start.
            val reason = when {
                disposition != null -> text(R.string.runtime_retime_expired)
                pending != null && active?.confidence == Confidence.CONFIRMED && active.identity != pending.expected -> null
                else -> runtime.reason
            }
            runtime.copy(retime = runtime.retime?.takeUnless { it.expected.nightId == nightId },
                alarmInbox = runtime.alarmInbox?.takeUnless { it.nightId == nightId },
                reason = reason).also { if (expired) consumedRuntime = it }
        }
        consumedPublished = true
        disposition?.let { lastRetimeExpiry = it }
        if (!retimePending) NightAlarms.cancelRetry(context)
        if (expired) {
            // The night and its wake boundary identify this expiry across a later local retry.
            val failure = retimeFailureEvent(OutcomeKind.LOCAL, R.string.runtime_retime_expired,
                expiryDetail!!, expiredAt!!)
            if (failure !in repo.history.value.firstOrNull { it.id == nightId }?.events.orEmpty()) repo.addEvent(nightId, failure)
            Notifications.showRetimeError(context, text(R.string.runtime_retime_expired), expiryDetail!!, expiredRequest!!.deviceId)
            currentCoroutineContext()[RetimeExecution]?.reportedNotice = text(R.string.runtime_retime_expired)
        }
        disposition
    } catch (e: Exception) {
        val request = expiredRequest
        val consumed = consumedRuntime
        // Repository may publish the clear before prompt cancellation prevents its withContext return.
        if (request != null && consumed != null && (consumedPublished || repo.runtime.value === consumed)) {
            val failure = retimeFailureEvent(OutcomeKind.LOCAL, R.string.runtime_retime_expired,
                expiryDetail!!, expiredAt!!)
            if (failure !in repo.history.value.firstOrNull { it.id == nightId }?.events.orEmpty()) {
                try {
                    // A local receiver can own this expiry. Allow only a short repair tail after cancellation.
                    withContext(NonCancellable) { withTimeout(500) {
                        var restored = false
                        repo.updateRuntime { runtime ->
                            val active = repo.activeNight.value
                            // Never overwrite newer work or return this expired request to another night/target.
                            if (runtime.retime != null || runtime.generation != consumed.generation || active?.identity != request.expected ||
                                active.confidence != Confidence.CONFIRMED || active.deviceId != request.deviceId ||
                                repo.settings.value.deviceId() != request.deviceId) runtime
                            else {
                                restored = true
                                runtime.copy(retime = request, alarmInbox = runtime.alarmInbox ?: expiredObservation)
                            }
                        }
                        if (restored) NightAlarms.scheduleObservation(context, graph.now())
                    } }
                } catch (cleanup: Exception) { if (cleanup !== e) e.addSuppressed(cleanup) }
            }
        }
        throw e
    }
}

/** A Boost that displaced [night] and may have run. An old FAILED entry wrote nothing (IR-06). */
internal fun NightActions.hasUnfinishedReplacement(night: ActiveNight): Boolean =
    repo.runtime.value.slotWrite?.let { it.startRequested && it.displaced == night.identity && it.confidence != Confidence.FAILED } == true

internal suspend fun NightActions.queueRetimeLocked(observationId: String? = null): CommandOutcome {
    val n = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
    if (n.wakeOrigin == WakeOrigin.OVERRIDE) return local(R.string.runtime_unchanged)
    val alarm = AlarmSource.describe(context, repo.settings.value)
    if (alarm?.counts == false) return local(R.string.runtime_unchanged)
    val deadline = ZonedDateTime.parse(n.wakeUpStart)
    if (!graph.now().isBefore(deadline)) return local(R.string.runtime_retime_expired)
    val prepared = graph.runner().prepareRetime(n, n.identity, alarm?.time, deadline, repo.profile(n.profileId), repo.settings.value, alarm?.creator)
    // An unchanged resolved wake asks for no hardware change, even while existing tracking needs checking.
    if (prepared is Preparation.Refused && prepared.unchanged) return dropRetimeLocked(local(R.string.runtime_unchanged), observationId)
    if (hasUnfinishedReplacement(n) || n.confidence != Confidence.CONFIRMED) return dropRetimeLocked(refused(R.string.runtime_reconcile_needed), observationId)
    if (n.trackingManual) return dropRetimeLocked(refused(R.string.runtime_manual_tracking), observationId)
    if (prepared !is Preparation.Ready) {
        val outcome = if (prepared is Preparation.Refused) CommandOutcome(OutcomeKind.REFUSED, prepared.refusalText(context)) else stale()
        return dropRetimeLocked(outcome, observationId)
    }
    val pending = PendingRetime(UUID.randomUUID().toString(), n.identity, repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target),
        alarm?.time?.toString(), alarm?.creator, deadline.toString(), prepared.change.intended.wakeAt)
    repo.updateRuntime { it.copy(retime = pending, reason = text(R.string.runtime_retime_pending)) }
    if (repo.runtime.value.retime?.id == pending.id) lastRetimeExpiry = null
    NightAlarms.scheduleRetry(context, pending, graph.now())
    return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_pending))
}

private suspend fun NightActions.dropRetimeLocked(outcome: CommandOutcome, observationId: String?): CommandOutcome {
    if (!retimePending) return outcome
    val failedPending = !outcome.succeeded && outcome.kind != OutcomeKind.STALE
    // A qualifying alarm observation is a requested change even before preparation. Early manual/reconcile
    // refusals therefore own the same terminal notice as preparation refusals; ignored alarms stay quiet.
    return notifyRetimeFailure(outcome, requestId = observationId, requestedWake = requestedRetimeWake(null)) { detail ->
        repo.updateRuntime { it.copy(retime = null, reason = if (failedPending) detail else it.reason) }
        if (!retimePending) NightAlarms.cancelRetry(context)
    }
}

internal suspend fun NightActions.retryRetimeLocked(): CommandOutcome {
    val execution = currentCoroutineContext()[RetimeExecution]
    val pending = repo.runtime.value.retime ?: return when {
        repo.activeNight.value == null -> refused(R.string.runtime_no_night)
        repo.activeNight.value?.let { it.confidence != Confidence.CONFIRMED || hasUnfinishedReplacement(it) } == true -> refused(R.string.runtime_reconcile_needed)
        repo.activeNight.value?.trackingManual == true -> refused(R.string.runtime_manual_tracking)
        repo.activeNight.value?.let(::retimeWasExpired) == true -> local(R.string.runtime_retime_expired)
        else -> local(R.string.runtime_unchanged)
    }
    val n = repo.activeNight.value
    if (!graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
        expireRetimeFor(pending.expected.nightId)
        return local(R.string.runtime_retime_expired)
    }
    if (n?.identity != pending.expected || hasUnfinishedReplacement(n) || n.trackingManual || n.confidence != Confidence.CONFIRMED || pending.deviceId != repo.settings.value.deviceId()) {
        val superseded = n != null && n.confidence == Confidence.CONFIRMED && !n.trackingManual &&
            n.identity != pending.expected && !hasUnfinishedReplacement(n)
        try {
            withTimeout(8_000) {
                repo.updateRuntime { if (it.retime?.id == pending.id) it.copy(retime = null) else it }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (!superseded) throw e
            // The old request cannot run against this confirmed revision. Failure to clear its journal is
            // local bookkeeping, not evidence that the current programme or another re-time failed.
            return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_cleanup_pending, e.message.orEmpty()))
        } finally { settleRetimeAlarm(pending.id) }
        return stale()
    }
    slotBackupsRefusal(n.slot)?.let { refusal ->
        // A background retry has no screen to show the refusal, so the wake-up change isn't dropped silently (IR-03).
        return notifyRetimeFailure(refusal, pending.expected.nightId, pending.id) { detail ->
            repo.updateRuntime { it.copy(retime = null, reason = detail) }; NightAlarms.cancelRetry(context)
        }
    }
    execution?.identity = pending.expected
    execution?.requestedWakeAt = requestedRetimeWake(pending)
    var preparedChange: PreparedChange? = null
    fun requestedWake() = preparedChange?.intended?.wakeAt ?: requestedRetimeWake(pending)
    return try {
        connected {
            // A remote Off or manual change may have happened while disconnected. A local
            // CONFIRMED night is not evidence that the BedJet is still running our programme.
            takeoverBeforeRewrite(n)?.let {
                if (it.notificationHandled) execution?.reportedNotice = it.message
                return@connected it
            }
            val preparation = graph.runner().prepareRetime(n, pending.expected, pending.alarmAt?.let(ZonedDateTime::parse),
                ZonedDateTime.parse(pending.deadline), repo.profile(n.profileId), repo.settings.value, pending.alarmOrigin)
            preparedChange = (preparation as? Preparation.Ready)?.change
            preparedChange?.let { change ->
                // Source settings may have changed while the request waited. Every later warning must name
                // the wake in this actual upload, including service timeout and process-recovery paths.
                execution?.requestedWakeAt = change.intended.wakeAt
                if (pending.requestedWakeAt != change.intended.wakeAt) repo.updateRuntime { runtime ->
                    runtime.retime?.takeIf { it.id == pending.id }?.let { current ->
                        runtime.copy(retime = current.copy(requestedWakeAt = change.intended.wakeAt))
                    } ?: runtime
                }
            }
            if (!graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
                expireRetimeFor(pending.expected.nightId)
                return@connected local(R.string.runtime_retime_expired)
            }
            val result = apply(preparation,
                R.string.runtime_retimed, afterWrite = {
                    // A physical Off can arrive during the upload while status observation is held
                    // behind transaction ownership. Reconnect to demand a fresh hardware report.
                    if (!matchesCurrentProgramme(n, freshStatus(), graph.now())) throw RetimeTakeover()
                })
            if (result.notificationHandled) execution?.reportedNotice = result.message
            val current = repo.activeNight.value
            if (result.kind == OutcomeKind.FAILED && current?.identity == pending.expected && current.confidence == Confidence.CONFIRMED) {
                // IR-05: it failed before writing and the night runs on unchanged, so the change is tried
                // again (never past its deadline, checked above), as after a failed connection.
                repo.updateRuntime { it.copy(reason = result.message) }
                NightAlarms.scheduleRetry(context, pending, graph.now())
            } else if (result.kind == OutcomeKind.LOCAL) {
                repo.updateRuntime { if (it.retime?.id == pending.id) it.copy(retime = null, reason = null) else it }
                if (!retimePending) NightAlarms.cancelRetry(context)
            } else if (!result.succeeded) {
                return@connected notifyRetimeFailure(result, pending.expected.nightId, pending.id, requestedWake()) { detail ->
                    repo.updateRuntime { it.copy(retime = null, reason = detail) }
                    NightAlarms.cancelRetry(context)
                }
            }
            result
        }
    } catch (e: CancellationException) {
        val current = repo.activeNight.value
        val change = preparedChange
        if (e is TimeoutCancellationException && current != null && current.identity == change?.intended?.identity &&
            current.confidence == Confidence.CONFIRMED && !hasUnreconciledProgramme(current)) {
            withContext(NonCancellable) {
                try {
                    withTimeout(8_000) {
                        settleConfirmedRetime(pending, current, text(R.string.runtime_retime_record_incomplete,
                            text(R.string.runtime_operation_timeout)))
                    }
                } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                val detail = text(R.string.runtime_retime_timeout_current_confirmed)
                Notifications.showInfo(context, text(R.string.runtime_retime_timeout_current_wake, clock(current.wakeAt)), detail)
                execution?.reportedNotice = detail
            }
        } else if (e is TimeoutCancellationException && change != null && current?.identity == pending.expected &&
            current.confidence == Confidence.UNKNOWN && current.pending?.requestId == change.request.requestId) {
            // Only this request's possible write is terminal. A confirmed no-write timeout retains its
            // journal even past the deadline, so the observation/expiry path still owns its one notice.
            withContext(NonCancellable) {
                try {
                    withTimeout(8_000) {
                        if (execution?.reportedNotice == null && lastTerminalRetime?.requestId != pending.id) recordRetimeFailure(pending.expected.nightId,
                            OutcomeKind.UNKNOWN, R.string.runtime_retime_timeout,
                            retimeWarningDetail(text(R.string.runtime_retime_unknown_detail, text(R.string.runtime_retime_timeout_detail)),
                                requestedWake(), pending.deviceId))
                        repo.updateRuntime { if (it.retime?.id == pending.id)
                            it.copy(retime = null, reason = execution?.reportedNotice ?: text(R.string.runtime_retime_timeout_detail)) else it }
                    }
                } catch (cleanup: Exception) { e.addSuppressed(cleanup)
                } finally { settleRetimeAlarm(pending.id) }
            }
        }
        throw e
    } catch (e: Exception) {
        val current = repo.activeNight.value
        if (hasUnreconciledProgramme(current))
            return reconcileProgrammeOutcome(CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_takeover)))
        // Publication of this prepared revision proves the BedJet confirmed it. A later local save failure
        // cannot turn that into a failed change, nor credit some unrelated CONFIRMED revision to this request.
        if (current != null && current.identity == preparedChange?.intended?.identity && current.confidence == Confidence.CONFIRMED) {
            val detail = text(R.string.runtime_retime_record_incomplete, e.message ?: text(R.string.runtime_failed))
            settleConfirmedRetime(pending, current, detail)
            Notifications.showInfo(context, text(R.string.runtime_moved_title, clock(current.wakeAt)), detail)
            return CommandOutcome(OutcomeKind.CONFIRMED, detail, notificationHandled = true)
        }
        if (e is BluetoothCommandException && current?.identity == pending.expected && current.confidence == Confidence.CONFIRMED) {
            // The confirmed revision is unchanged: no re-time write began. Refuse this attempt rather
            // than silently replaying it when Bluetooth returns. A newer observation keeps its delivery.
            try {
                repo.updateRuntime { if (it.retime?.id == pending.id)
                    it.copy(retime = null, reason = e.planMessage(context)) else it }
            } finally { settleRetimeAlarm(pending.id) }
            // The owner supplies the exact FAILED reason, origin and deduplicated Bluetooth notice.
            throw e
        }
        if (current?.identity == pending.expected && current.confidence == Confidence.CONFIRMED &&
            !graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
            expireRetimeFor(pending.expected.nightId)
            return local(R.string.runtime_retime_expired)
        }
        if (current?.identity != pending.expected || current.confidence in setOf(Confidence.UNKNOWN, Confidence.FAILED)) {
            return notifyRetimeFailure(CommandOutcome(if (current?.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                e.message ?: text(R.string.runtime_failed)), pending.expected.nightId, pending.id, requestedWake()) { detail ->
                repo.updateRuntime { it.copy(retime = null, reason = detail) }
                NightAlarms.cancelRetry(context)
            }
        }
        repo.updateRuntime { it.copy(reason = text(R.string.runtime_retime_pending)) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        recordError(e.message.orEmpty())
        CommandOutcome(OutcomeKind.FAILED, text(R.string.runtime_retime_pending))
    }
}

/** The active-night file already records the confirmed revision. Even persistent journal failure must not
 * undo that proof or keep scheduling the old request. A newer observation retains its separate delivery. */
private suspend fun NightActions.settleConfirmedRetime(pending: PendingRetime, confirmed: ActiveNight, reason: String) {
    settleConfirmedProgramme(pending.id, confirmed, reason)
}

private fun NightActions.retimeFailureEvent(kind: OutcomeKind, title: Int, detail: String, at: String = graph.now().toString()) =
    HistoryEvent(at, EventKind.ERROR, "${text(title)} [${kind.name}]: $detail")

private suspend fun NightActions.recordRetimeFailure(recordId: String?, kind: OutcomeKind, title: Int, detail: String) {
    recordId?.let { repo.addEvent(it, retimeFailureEvent(kind, title, detail)) }
}

/** History owns the typed terminal reason before clearing the retry or posting its dismissible notice. */
private suspend fun NightActions.notifyRetimeFailure(outcome: CommandOutcome, recordId: String? = repo.activeNight.value?.recordId,
    requestId: String? = null, requestedWake: String? = requestedRetimeWake(),
    settle: suspend (String) -> Unit = {}): CommandOutcome {
    val execution = currentCoroutineContext()[RetimeExecution]
    val retained = lastTerminalRetime?.takeIf { requestId != null && it.requestId == requestId }
    val terminal = retained?.outcome ?: outcome
    if (terminal.succeeded || terminal.kind == OutcomeKind.STALE || terminal.notificationHandled || execution?.reportedNotice != null) {
        val result = if (execution?.reportedNotice != null) terminal.copy(notificationHandled = true) else terminal
        val detail = retained?.notice ?: execution?.reportedNotice ?: result.message
        if (result.notificationHandled && requestId != null) lastTerminalRetime = TerminalRetime(requestId, result, detail)
        settle(detail)
        return result
    }
    val unknown = terminal.kind == OutcomeKind.UNKNOWN || repo.activeNight.value?.confidence == Confidence.UNKNOWN
    val title = if (!unknown && terminal.kind == OutcomeKind.REFUSED) R.string.runtime_retime_refused else R.string.runtime_retime_failed
    val target = repo.runtime.value.retime?.deviceId ?: repo.activeNight.value?.deviceId ?: repo.settings.value.deviceId()
    val detail = retimeWarningDetail(if (unknown) text(R.string.runtime_retime_unknown_detail, terminal.message) else terminal.message,
        requestedWake, target)
    recordRetimeFailure(recordId, terminal.kind, title, detail)
    val handled = terminal.copy(notificationHandled = true)
    try { settle(detail) } finally {
        // A later cleanup exception still belongs to this terminal result, including its saved history.
        if (requestId != null) lastTerminalRetime = TerminalRetime(requestId, handled, detail)
        Notifications.showRetimeError(context, text(title), detail, target)
        execution?.reportedNotice = detail
    }
    return handled
}

internal fun NightActions.requestedRetimeWake(pending: PendingRetime? = repo.runtime.value.retime): String? {
    pending?.requestedWakeAt?.let { return it }
    val night = repo.activeNight.value ?: return pending?.alarmAt
    // An older uncertain write is not the alarm observation now being refused.
    val profile = night.profile ?: repo.profile(night.profileId) ?: return pending?.alarmAt
    val alarm = if (pending == null) AlarmSource.describe(context, repo.settings.value) else null
    return NightPreview.forStartNow(profile, ZonedDateTime.parse(night.startedAt),
        pending?.alarmAt?.let(ZonedDateTime::parse) ?: alarm?.time, settings = repo.settings.value,
        alarmOrigin = pending?.alarmOrigin ?: alarm?.creator).wake.time.toString()
}
