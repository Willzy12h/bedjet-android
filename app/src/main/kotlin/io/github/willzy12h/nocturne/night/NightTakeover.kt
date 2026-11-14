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

// Part of [NightActions], split out of NightActions.kt: detecting a physical-remote takeover of the tracked programme
// or manual run (IR-01, IR-08), and recording it.

/** A step boundary is not a takeover: the unit's report may lag the planned boundary briefly. */
private const val TAKEOVER_GRACE_S = 20L
/** A manual run this close to its end may stop by itself; that is not a takeover. */
private const val MANUAL_TIMER_SLACK_S = 90L

/** An automatic or programme-preserving rewrite never silently undoes a physical-remote change (IR-01).
 * Reads the BedJet first. Null means it still runs the tracked night and the rewrite may go ahead. */
internal suspend fun NightActions.takeoverBeforeRewrite(n: ActiveNight): CommandOutcome? {
    if (n.confidence != Confidence.CONFIRMED || n.trackingManual) return null
    if (unreconciledForCurrentNight()?.concerns(n, repo.settings.value.deviceId()) == true)
        return reconcileProgrammeOutcome(CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_takeover), notificationHandled = true))
    // A local CONFIRMED night is not evidence that the BedJet still runs our programme: a remote Off or
    // manual change may have happened while disconnected.
    if (matchesCurrentProgramme(n, freshStatus(), graph.now())) return null
    recordTakeover(n)
    return CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_takeover), notificationHandled = true)
}

internal suspend fun NightActions.recordTakeover(n: ActiveNight) {
    publishNight(n.copy(confidence = Confidence.UNKNOWN))
    repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_takeover)) }
    repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
    NightAlarms.cancelRetry(context)
    Notifications.showInfo(context, text(R.string.runtime_takeover_title), text(R.string.runtime_takeover))
}

internal fun NightActions.matchesCurrentProgramme(night: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
    if (!status.isRunning) return false
    val plan = night.activePlan(repo.profile(night.profileId)) ?: return false
    val current = plan.stepAt(at) ?: return false
    val extra = status.extra ?: return false
    if (extra.programStep == 0) return false
    fun matches(index: Int): Boolean {
        val step = plan.steps.getOrNull(index)?.step ?: return false
        // A step that does not use the fan (Wait) says nothing about it, as with temperature.
        return extra.programStep == index + 1 && status.mode == step.mode && (!step.mode.usesFan || status.fanStep == step.fanStep) &&
            (!status.mode.usesTemperature || status.targetTemperature == step.temperature)
    }
    if (matches(current.first)) return true
    // During a step transition only the immediately previous programme step is plausible.
    // A different mode or temperature is still a physical takeover, even in this grace period.
    return at.isBefore(current.second.startsAt.plusSeconds(TAKEOVER_GRACE_S)) && matches(current.first - 1)
}

/** Whether [status] shows the BedJet running something other than [n]'s one manual step, outside the run's first
 * moments and its last minute and a half, when it may already have stopped by itself. */
internal fun NightActions.contradictsManual(n: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
    val step = n.applied.programSteps.singleOrNull() ?: return false
    val began = n.applied.start.takeIf { it.isNotEmpty() }?.let(ZonedDateTime::parse) ?: return false
    if (at.isBefore(began.plusSeconds(TAKEOVER_GRACE_S)) || !at.isBefore(ZonedDateTime.parse(n.endsAt).minusSeconds(MANUAL_TIMER_SLACK_S))) return false
    return !status.isRunning || status.mode != step.mode || (step.mode.usesTemperature && status.targetTemperature != step.temperature)
}
