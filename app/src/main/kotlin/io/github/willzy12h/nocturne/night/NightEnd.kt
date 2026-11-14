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
import java.time.ZonedDateTime
import io.github.willzy12h.nocturne.night.NightActions.StatusReceipt

// Part of [NightActions], split out of NightActions.kt: ending a night (Stop tracking, a confirmed Off, natural
// expiry) and the local wake-up/night-end alarm deliveries that drive the end and the wake-up notification.

internal suspend fun NightActions.finishLocked(night: ActiveNight, kind: EventKind, detail: String,
    end: PendingEnd = PendingEnd(night.identity, event(kind, detail), isTestRun = night.isTestRun,
        confirmedCompletionAt = confirmedCompletion(night, kind))) {
    val transition = io.github.willzy12h.nocturne.core.night.stopTracking(night, detail, graph.now(), repo.runtime.value.generation)
    // IR-06: a slot write made in this night, or one that displaced it, ends with it.
    repo.updateRuntime { it.copy(ending = end,
        endRequested = null, endRequestedLabel = null, endRequestedAt = null, retime = null,
        endRequestedEpoch = null, endRequestedSequence = null,
        resumeOffer = when {
            kind == EventKind.ENDED -> null
            night.trackingManual -> validResumeOffer(it)
            else -> transition.resumeOffer
        }, reason = null,
        wakeShown = null, manualWakeShown = false, slotWrite = it.slotWrite?.takeUnless { w -> w.belongsTo(night.recordId) }) }
    completeLocalEnd()
    repo.runtime.value.resumeOffer?.let { Notifications.showResume(context) }
}

/** A clock or an ENDED label alone cannot turn uncertain tracking into a confirmed completed programme. */
private fun NightActions.confirmedCompletion(night: ActiveNight, kind: EventKind): String? = night.endsAt.takeIf {
    kind == EventKind.ENDED && night.confidence == Confidence.CONFIRMED && night.pending == null &&
        !night.trackingManual && !hasUnreconciledProgramme(night) && !night.isTestRun && !repo.settings.value.demoMode &&
        !io.github.willzy12h.nocturne.core.link.DeviceId.isDemo(night.deviceId) &&
        repo.runtime.value.retime == null && repo.runtime.value.alarmInbox == null &&
        !graph.now().isBefore(ZonedDateTime.parse(night.endsAt)) &&
        repo.history.value.any { it.id == night.recordId && it.bedJetRunConfirmed }
}

/** Complete an idempotent local journal after a crash between the separate durable files. */
internal suspend fun NightActions.completeLocalEnd() {
    val end = repo.runtime.value.ending ?: return
    val active = repo.activeNight.value
    if (active == null || active.identity == end.identity) {
        repo.completeHistoryRecord(end.identity.nightId, end.event, end.confirmedCompletionAt)
        publishNight(null)
        if (!end.isTestRun && active?.isTestRun != true &&
            repo.history.value.none { it.id == end.identity.nightId && it.isTestRun })
            repo.updateSettings { it.clearWakeOverride() }
        NightAlarms.cancel(context)
        Notifications.cancelWakeUp(context)
        Notifications.cancelQuiet(context)
    }
    repo.updateRuntime { it.copy(ending = null) }
}

internal suspend fun NightActions.stopTrackingLocked(): CommandOutcome {
    val n = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
    advanceGeneration(preserveOffer = n.trackingManual)
    finishLocked(n, EventKind.STOPPED, text(R.string.runtime_stop_tracking))
    return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_stop_tracking))
}

internal suspend fun NightActions.expireLocked(expected: NightIdentity?): Boolean {
    val n = repo.activeNight.value ?: return false
    // The old programme end cannot convert an unconfirmed explicit ending command into natural completion.
    if (repo.runtime.value.endRequested == n.identity) return false
    if (n.identity != expected || io.github.willzy12h.nocturne.core.night.expire(n, graph.now()) == null) return false
    advanceGeneration()
    finishLocked(n, EventKind.ENDED, text(R.string.runtime_ended)); return true
}

internal suspend fun NightActions.drainLocalSafely(remainingMs: Long = 8_000) {
    if (repo.runtime.value.localDeliveries.isEmpty()) return
    if (remainingMs <= 0) { NightAlarms.scheduleLocalRetry(context); return }
    try { withContext(NonCancellable) { withTimeout(minOf(8_000L, remainingMs)) { consumeLocalDeliveriesLocked() } } }
    catch (_: Exception) { NightAlarms.scheduleLocalRetry(context) }
}

internal suspend fun NightActions.consumeLocalDeliveriesLocked() {
    for (delivery in repo.runtime.value.localDeliveries) {
        if (delivery.action == NightAlarms.ACTION_WAKE_UP) wakeUpLocked(delivery.expected)
        else if (delivery.action == NightAlarms.ACTION_NIGHT_END) expireLocked(delivery.expected)
        repo.updateRuntime { it.copy(localDeliveries = it.localDeliveries.filterNot { item -> item.id == delivery.id }) }
    }
    if (repo.runtime.value.localDeliveries.isEmpty()) NightAlarms.cancelLocalRetry(context)
    else NightAlarms.scheduleLocalRetry(context)
}

internal suspend fun NightActions.wakeUpLocked(expected: NightIdentity?) {
    val n = repo.activeNight.value ?: return
    if (n.identity != expected || n.trackingManual || n.confidence != Confidence.CONFIRMED || repo.runtime.value.wakeShown == n.identity ||
        hasUnreconciledProgramme(n) ||
        graph.now().isBefore(ZonedDateTime.parse(n.wakeUpStart)) || !graph.now().isBefore(ZonedDateTime.parse(n.endsAt))) return
    repo.updateRuntime { it.copy(wakeShown = n.identity, manualWakeShown = false) }
    Notifications.showWakeUp(context, n, repo.settings.value)
}

/** Receipt order never depends on wall time. Restored/legacy markers require a new report in this runtime. */
internal suspend fun NightActions.finishRequestedEnd(status: DeviceStatus, receipt: StatusReceipt?): Boolean {
    val night = repo.activeNight.value ?: return false
    val request = repo.runtime.value
    val requestedSequence = request.endRequestedSequence?.takeIf { request.endRequestedEpoch == statusEpoch }
    if (request.endRequested != night.identity || status.isRunning || receipt == null || receipt.epoch != statusEpoch ||
        receipt.sequence <= maxOf(observationStartedSequence, requestedSequence ?: observationStartedSequence)) return false
    finishLocked(night, EventKind.STOPPED, request.endRequestedLabel ?: text(R.string.runtime_stopped))
    return true
}
