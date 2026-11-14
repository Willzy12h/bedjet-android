package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import io.github.willzy12h.nocturne.night.NightActions.StatusObservation
import io.github.willzy12h.nocturne.night.NightActions.ProgrammeBoundary
import io.github.willzy12h.nocturne.night.NightActions.ProgrammeReports
import io.github.willzy12h.nocturne.night.NightActions.ProgrammeObservation

// Part of [NightActions], split out of NightActions.kt: fresh reads of the BedJet and observing its status reports
// against the confirmed programme, in receipt order, including reports that arrive while a command owns the BedJet.

internal suspend fun NightActions.freshStatus(requireProgress: Boolean = true): DeviceStatus = freshReport(requireProgress).status

/**
 * The chosen BedJet's next report. Commands reconnect first, under whole-command ownership, because an existing
 * READY status may predate the operation. A read-only background read passes [reconnect] false: the connection
 * it holds streams reports by itself, so a report received after this call is just as fresh.
 */
internal suspend fun NightActions.freshReport(requireProgress: Boolean = true, reconnect: Boolean = true): SourcedStatus {
    val target = repo.settings.value.deviceId()
    // Default links stamp their cached snapshot lazily; observe it before the read's receipt boundary.
    graph.link.latestSourcedStatus()
    val receivedAfter = StatusReceiptOrder.next()
    if (reconnect) {
        graph.link.disconnect()
        graph.link.connect()
    }
    // Only the chosen BedJet's own report counts: one from a session still on another unit says nothing about it.
    // A status that never arrives is a failed attempt with nothing written. Only this wait's own timeout is turned
    // into that: a cancellation from outside, such as the owner's budget running out, still cancels.
    return withTimeoutOrNull(freshStatusMs) {
        graph.link.sourcedStatus.filterNotNull().first {
            it.receiptOrder > receivedAfter && it.isFrom(target) && (!requireProgress || it.status.extra != null)
        }
    } ?: throw BedJetError.Timeout()
}

internal suspend fun NightActions.observeStatusLocked(observed: StatusObservation, receivedDuringProgramme: Boolean = false) {
    val (status, target, receivedAt, source, receipt) = observed
    // A report from another BedJet is never this target's state.
    if (target != repo.settings.value.deviceId() || !DeviceId.same(source, target) ||
        (!receivedDuringProgramme && graph.link.state.value != LinkState.READY)) return
    val n = repo.activeNight.value ?: return
    if (finishRequestedEnd(status, receipt)) return
    if (!admitProgrammeReceipt(observed, n)) return
    // Off is established by the head itself; no optional programme-progress tail can make it running.
    if (n.confidence == Confidence.CONFIRMED && !n.trackingManual && !status.isRunning) {
        recordTakeover(n)
        return
    }
    val extra = status.extra ?: return
    if (extra.notification != 0 && lastNotice != (n.recordId to extra.notification)) {
        lastNotice = n.recordId to extra.notification
        repo.addEvent(n.recordId, event(EventKind.UNIT_NOTICE, text(R.string.runtime_unit_notice, extra.notification)))
    } else if (extra.notification == 0) lastNotice = null
    if (n.confidence != Confidence.CONFIRMED) return
    // IR-08: a kept manual run is watched against its own step.
    if (n.trackingManual) { if (contradictsManual(n, status, receivedAt)) recordTakeover(n); return }
    if (matchesCurrentProgramme(n, status, receivedAt)) return
    recordTakeover(n)
}

/** Upload intermediates precede this actual, matching start receipt and can never be replayed as takeover. */
internal fun NightActions.programmeConfirmed(night: ActiveNight, report: SourcedStatus) {
    if (!report.isFrom(night.deviceId)) throw BedJetError.NotConnected()
    synchronized(statusOwnership) {
        val boundary = ProgrammeBoundary(night.identity, night.deviceId, report.receiptOrder)
        confirmedProgrammeBoundary = boundary
        reconciledProgrammeReceipt = report.receiptOrder
        ownedProgrammeReports = ProgrammeReports(boundary)
        // A new packet may have arrived between the controller's return and this callback.
        graph.link.latestSourcedStatus()?.let { latest ->
            queueProgrammeReport(StatusObservation(latest.status, night.deviceId, graph.now(),
                latest.source ?: night.deviceId, receiptOf(latest)))
        }
    }
}

/** Called only under statusOwnership. There is one latest eligible receipt, not a queue of upload packets. */
internal fun NightActions.queueProgrammeReport(observed: StatusObservation) {
    val reports = ownedProgrammeReports ?: return
    val receipt = observed.receipt ?: return
    val boundary = reports.boundary
    val confirmedAt = boundary.receipt ?: return
    if (observed.target != boundary.target || !DeviceId.same(observed.source, boundary.target) ||
        receipt.epoch != statusEpoch || receipt.sequence <= confirmedAt || receipt.sequence < reconciledProgrammeReceipt) return
    val previous = reports.latest
    val previousSequence = previous?.receipt?.sequence ?: Long.MIN_VALUE
    if (receipt.sequence > previousSequence || (receipt.sequence == previousSequence && observed.status.extra != null))
        reports.latest = observed
}

/** Keep receipt order after a queue drains. Equal receipts may carry fresh progress over cached head metadata. */
private fun NightActions.admitProgrammeReceipt(observed: StatusObservation, night: ActiveNight): Boolean = synchronized(statusOwnership) {
    val boundary = confirmedProgrammeBoundary
    val receipt = observed.receipt
    if (boundary?.identity != night.identity || boundary.target != observed.target || receipt?.epoch != statusEpoch) true
    else if (receipt.sequence <= (boundary.receipt ?: Long.MIN_VALUE) || receipt.sequence < reconciledProgrammeReceipt) false
    else { reconciledProgrammeReceipt = receipt.sequence; true }
}

private fun NightActions.pendingProgrammeObservation(): ProgrammeObservation? = ownedProgrammeReports?.let { reports ->
    reports.latest?.let { ProgrammeObservation(reports.boundary, it) }
}

internal fun ProgrammeObservation.concerns(night: ActiveNight?, target: String?): Boolean =
    boundary.identity == night?.identity && boundary.target == target && night?.deviceId == target

internal fun NightActions.hasUnreconciledProgramme(night: ActiveNight?): Boolean =
    unreconciledProgramme.value?.concerns(night, repo.settings.value.deviceId()) == true

internal fun NightActions.unreconciledForCurrentNight(): ProgrammeObservation? {
    val observed = unreconciledProgramme.value ?: return null
    // Demo has separate disposable state. A real receipt is retained while that state is displayed.
    if (observed.boundary.target != repo.settings.value.deviceId()) return null
    if (observed.concerns(repo.activeNight.value, repo.settings.value.deviceId())) return observed
    unreconciledProgramme.compareAndSet(observed, null)
    return null
}

private fun NightActions.retainProgrammeUncertainty(observed: ProgrammeObservation) {
    val night = repo.activeNight.value ?: return
    // Demo publication is in memory and must not replace a real session's pending local save.
    if (repo.isDemoSession || !observed.concerns(night, repo.settings.value.deviceId()) ||
        night.confidence != Confidence.CONFIRMED || night.trackingManual) return
    val report = observed.report
    if (!admitProgrammeReceipt(report, night)) return
    if ((report.status.extra != null || !report.status.isRunning) &&
        !matchesCurrentProgramme(night, report.status, report.receivedAt)) {
        unreconciledProgramme.value = observed
    }
}

internal suspend fun NightActions.retryUnreconciledProgramme(): Boolean {
    val observed = unreconciledForCurrentNight() ?: return true
    return try { withTimeoutOrNull(8_000) { observeProgrammeLocked(observed); true } ?: false }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { false } // Retain the receipt and visible UNKNOWN until a later local save can finish.
}

internal suspend fun NightActions.observeProgrammeLocked(observed: ProgrammeObservation) {
    val night = repo.activeNight.value
    if (night == null || !observed.concerns(night, repo.settings.value.deviceId())) {
        if (observed.boundary.target == repo.settings.value.deviceId()) unreconciledProgramme.compareAndSet(observed, null)
        return
    }
    if (!admitProgrammeReceipt(observed.report, night)) {
        unreconciledProgramme.compareAndSet(observed, null)
        return
    }
    retainProgrammeUncertainty(observed)
    if (hasUnreconciledProgramme(repo.activeNight.value)) {
        Notifications.cancelQuiet(context)
        Notifications.cancelWakeUp(context)
    }
    // This receipt was already admitted for this night and target. A later disconnect cannot undo it.
    observeStatusLocked(observed.report, receivedDuringProgramme = true)
    unreconciledProgramme.compareAndSet(observed, null)
}

private suspend fun NightActions.reconcileProgrammeReports() {
    while (true) {
        val observed = unreconciledForCurrentNight()
            ?: synchronized(statusOwnership) { pendingProgrammeObservation() } ?: return
        observeProgrammeLocked(observed)
        synchronized(statusOwnership) {
            ownedProgrammeReports?.takeIf { it.boundary == observed.boundary && it.latest === observed.report }?.latest = null
        }
    }
}

internal suspend fun NightActions.reconcileProgrammeOutcome(outcome: CommandOutcome): CommandOutcome {
    try { reconcileProgrammeReports() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        if (unreconciledForCurrentNight() == null) throw e
        return outcome.copy(kind = OutcomeKind.UNKNOWN, notificationHandled = false,
            message = text(R.string.runtime_takeover) + " " + text(R.string.runtime_save_failed, e.message ?: text(R.string.runtime_failed)))
    }
    val boundary = synchronized(statusOwnership) { ownedProgrammeReports?.boundary }
    val night = repo.activeNight.value
    return if (outcome.kind == OutcomeKind.CONFIRMED && boundary?.identity == night?.identity &&
        night?.confidence == Confidence.UNKNOWN) outcome.copy(kind = OutcomeKind.UNKNOWN, message = text(R.string.runtime_takeover),
            notificationHandled = true) else outcome
}

/** No report can be queued between the final empty check and unlock. Cancellation retains the same handoff. */
internal suspend fun NightActions.releaseProgrammeReports(owner: Any, remainingMs: Long) {
    var released = false
    try {
        if (remainingMs > 0) withContext(NonCancellable) { withTimeout(remainingMs) {
            while (true) {
                reconcileProgrammeReports()
                val complete = synchronized(statusOwnership) {
                    if (ownedProgrammeReports?.latest != null) false else {
                        ownedProgrammeReports = null
                        released = true
                        lock.unlock(owner)
                        true
                    }
                }
                if (complete) break
            }
        } }
    } catch (_: Exception) {
        // A storage delay must not consume another owner budget; deliver the original observation below.
    } finally {
        val pending = if (released) null else synchronized(statusOwnership) {
                val observed = pendingProgrammeObservation()
                // Admission must see known uncertainty before the next owner resumes from unlock.
                try { observed?.let(::retainProgrammeUncertainty) }
                finally {
                    ownedProgrammeReports = null
                    if (lock.holdsLock(owner)) lock.unlock(owner)
                }
                observed
            }
        if (pending != null) {
            graph.scope.launch {
                try { lock.withLock { withTimeout(8_000) { observeProgrammeLocked(pending) } } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The original receipt and UNKNOWN remain for a later observation. */ }
            }
        }
    }
}
