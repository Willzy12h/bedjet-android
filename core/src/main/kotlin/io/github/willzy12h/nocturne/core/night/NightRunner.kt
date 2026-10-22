package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.PlanException
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.RecordedStep
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SlotBackup
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Starts, re-times and interrupts nights on a BedJet. Everything here is what the buttons, widgets and alarms do. */
class NightRunner(private val controller: BedJetController, private val now: () -> ZonedDateTime) {

    fun prepareStart(profile: Profile, alarm: ZonedDateTime?, slot: Int, deviceId: String,
        expected: NightIdentity?, current: ActiveNight?, override: TonightOverride? = null,
        settings: Settings = Settings(),
        alarmOrigin: String? = null): Preparation {
        if (current?.identity != expected) return Preparation.Stale(expected, current?.identity)
        val time = now()
        val preview = NightPreview.forStartNow(profile, time, alarm, override, settings, alarmOrigin)
        val plan = preview.plan ?: return Preparation.Refused(preview.refusalReason.orEmpty())
        if (slot !in 0..2) return Preparation.Refused("Choose BedJet slot 1 to 3.")
        val wake = preview.wake
        val night = ActiveNight("night-${UUID.randomUUID()}", profile.id, time.toString(), wake.time.toString(),
            wake.fromAlarm, slot, plan.wakeUpStart.toString(), plan.end.toString(), deviceId, profile, wake.origin,
            applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED)
        val request = PendingChange(UUID.randomUUID().toString(), ChangeKind.START, night.wakeAt, time.toString(), time.plusMinutes(1).toString(), expected)
        return Preparation.Ready(PreparedChange(plan, night, current, request))
    }

    /** Prepare while disconnected if needed; [apply] checks the authoritative identity again. */
    fun prepareRetime(night: ActiveNight, expected: NightIdentity, alarm: ZonedDateTime?,
        deadline: ZonedDateTime = now().plusMinutes(1), liveProfile: Profile? = null,
        settings: Settings = Settings(),
        alarmOrigin: String? = null): Preparation {
        if (night.identity != expected) return Preparation.Stale(expected, night.identity)
        val profile = night.profile ?: liveProfile ?: return Preparation.Refused("The saved profile is unavailable.")
        if (night.wakeOrigin == WakeOrigin.OVERRIDE) return Preparation.Refused("Tonight's wake override is unchanged.")
        val start = ZonedDateTime.parse(night.startedAt)
        val accepted = profile.wakeSource.useAlarm && AlarmPolicy(settings).accept(alarm, alarmOrigin, start)
        val wake = if (accepted) alarm!! else WakeResolver.nextOccurrence(profile.wakeSource.defaultWakeMinute, start.plus(WakeResolver.MIN_NIGHT))
        if (wake.isEqual(ZonedDateTime.parse(night.wakeAt))) return Preparation.Refused("Wake-up unchanged.")
        return prepareRewrite(night, expected, profile, wake, if (accepted) WakeOrigin.ALARM else WakeOrigin.PROFILE_DEFAULT, ChangeKind.RETIME, deadline)
    }

    /** Explicit update-tonight, preserving the agreed wake and attribution unless [newWake] is supplied. */
    fun reapply(night: ActiveNight, editedProfile: Profile, expected: NightIdentity,
        newWake: ZonedDateTime? = null): Preparation = prepareRewrite(night, expected, editedProfile,
        newWake ?: ZonedDateTime.parse(night.wakeAt), if (newWake == null) night.wakeOrigin else WakeOrigin.OVERRIDE,
        ChangeKind.REAPPLY, now().plusMinutes(1))

    private fun prepareRewrite(night: ActiveNight, expected: NightIdentity, profile: Profile, wake: ZonedDateTime,
        origin: WakeOrigin, kind: ChangeKind, deadline: ZonedDateTime): Preparation {
        val time = now()
        val request = PendingChange(UUID.randomUUID().toString(), kind, wake.toString(), time.toString(), deadline.toString(), expected)
        when (val check = Eligibility.check(night, time, request)) {
            is Eligibility.Stale -> return Preparation.Stale(check.expected, check.actual)
            is Eligibility.Refused -> return Preparation.Refused(check.reason)
            Eligibility.Allowed -> Unit
        }
        val length = Duration.between(ZonedDateTime.parse(night.startedAt), wake)
        if (length < WakeResolver.MIN_NIGHT || length > WakeResolver.MAX_NIGHT || !wake.isAfter(time)) return Preparation.Refused("A night must last 1–16 hours. Set a tonight-only wake time.")
        return try {
            val original = night.activePlan(profile) ?: return Preparation.Refused("The applied programme is unavailable.")
            val plan = if (kind == ChangeKind.REAPPLY) {
                var elapsed = Duration.between(ZonedDateTime.parse(night.startedAt), time).toMinutes().toInt()
                val remaining = profile.start.mapNotNull { phase ->
                    val skipped = minOf(elapsed.coerceAtLeast(0), phase.minutes)
                    elapsed -= skipped
                    if (phase.minutes == skipped || (phase.mode == Mode.TURBO && skipped > 0)) null else phase.copy(minutes = phase.minutes - skipped)
                }
                PlanCompiler.compile(profile.copy(start = remaining), time, wake)
            } else PlanCompiler.replan(profile, original, time, wake) ?: return Preparation.Refused("Tonight's wake-up has already started.")
            val revision = night.revision + 1
            val intended = night.copy(profile = profile, profileId = profile.id, wakeAt = wake.toString(),
                wakeFromAlarm = origin == WakeOrigin.ALARM, wakeOrigin = origin, revision = revision,
                wakeUpStart = plan.wakeUpStart.toString(), endsAt = plan.end.toString(), applied = AppliedProgram.from(plan, revision),
                confidence = Confidence.CONFIRMED, pending = null)
            Preparation.Ready(PreparedChange(plan, intended, night, request))
        } catch (e: PlanException) { Preparation.Refused(e.message.orEmpty()) }
    }

    /** Resume is guarded by both no active night and the command version captured at stop/replacement. */
    fun resume(offer: ResumeOffer, now: ZonedDateTime, commandVersion: Long): Preparation {
        if (commandVersion != offer.commandVersion) return Preparation.Stale(offer.night.identity, null)
        if (!now.isBefore(ZonedDateTime.parse(offer.expiresAt)) || !now.isBefore(ZonedDateTime.parse(offer.night.wakeAt))) return Preparation.Refused("The resume offer has expired.")
        val profile = offer.night.profile ?: return Preparation.Refused("The saved profile is unavailable.")
        return try {
            val steps = mutableListOf<PlannedStep>()
            var cursor = now.withSecond(0).withNano(0)
            for (s in offer.remaining) {
                val start = ZonedDateTime.parse(s.startsAt)
                val end = start.plusMinutes(s.minutes.toLong())
                if (!end.isAfter(cursor) || (s.mode == Mode.TURBO && !start.isAfter(now))) continue
                if (start.isAfter(cursor)) {
                    val gap = Duration.between(cursor, start).toMinutes().toInt()
                    for (step in PlanCompiler.phaseSteps(Phase(Mode.WAIT, 80, 5, gap), profile.limits, profile.turboChain)) {
                        steps += PlannedStep(s.block, step, cursor, step.durationMinutes)
                        cursor = cursor.plusMinutes(step.durationMinutes.toLong())
                    }
                }
                val minutes = Duration.between(cursor, end).toMinutes().toInt()
                if (minutes > 0) {
                    val step = ProgramStep(s.mode, s.fanPercent / 5 - 1, s.temperature, minutes / 60, minutes % 60)
                    steps += PlannedStep(s.block, step, cursor, minutes)
                    cursor = end
                }
            }
            val wake = ZonedDateTime.parse(offer.night.wakeAt)
            if (cursor.isBefore(wake)) {
                val gap = Duration.between(cursor, wake).toMinutes().toInt()
                for (step in PlanCompiler.phaseSteps(Phase(Mode.WAIT, 80, 5, gap), profile.limits, profile.turboChain)) {
                    steps += PlannedStep(io.github.willzy12h.nocturne.core.plan.Block.WAKE, step, cursor, step.durationMinutes)
                    cursor = cursor.plusMinutes(step.durationMinutes.toLong())
                }
            }
            if (steps.isEmpty() || steps.size > 32) return Preparation.Refused("The remaining programme must contain 1–32 steps.")
            val plan = Plan(now.withSecond(0).withNano(0), ZonedDateTime.parse(offer.night.wakeAt), steps)
            val revision = offer.night.revision + 1
            val intended = offer.night.copy(revision = revision, applied = AppliedProgram.from(plan, revision),
                wakeUpStart = plan.wakeUpStart.toString(), endsAt = plan.end.toString(), confidence = Confidence.CONFIRMED, actualEnd = null, pending = null)
            // The offer may outlive tonight's wake; execution must satisfy both boundaries too.
            val deadline = minOf(ZonedDateTime.parse(offer.expiresAt), wake)
            val request = PendingChange(UUID.randomUUID().toString(), ChangeKind.RESUME, intended.wakeAt, now.toString(), deadline.toString(), null)
            Preparation.Ready(PreparedChange(plan, intended, null, request, offer.commandVersion))
        } catch (e: PlanException) { Preparation.Refused(e.message.orEmpty()) }
    }

    /** Caller owns lock and connection. [current] is read after acquisition. [publish] must durably save
     * PENDING before returning; [beforeWrite] durably backs up the original slot. A partial write is UNKNOWN.
     * Task 4 supplies repository callbacks; no callback is invoked for stale/expired requests. */
    suspend fun apply(change: PreparedChange, current: () -> ActiveNight?,
        commandVersion: () -> Long = { 0 }, publish: suspend (ActiveNight) -> Unit = {},
        beforeWrite: suspend () -> Unit = {}): NightMutation {
        if (change.expectedCommand != null && change.expectedCommand != commandVersion()) return NightMutation.Stale(change.request.expected, current()?.identity)
        when (val check = Eligibility.check(current(), now(), change.request)) {
            is Eligibility.Stale -> return NightMutation.Stale(check.expected, check.actual)
            is Eligibility.Refused -> return NightMutation.Refused(check.reason)
            Eligibility.Allowed -> Unit
        }
        val pending = change.pendingNight
        publish(pending)
        var writeMayHaveHappened = false
        try {
            beforeWrite()
            val latest = current()
            val expectedIdentity = change.request.expected
            // A callback may publish this request's pending start state; no other identity is accepted.
            val ownPendingStart = expectedIdentity == null && latest?.recordId == change.intended.recordId && latest.pending?.requestId == change.request.requestId
            if (latest?.identity != expectedIdentity && !ownPendingStart) return NightMutation.Stale(expectedIdentity, latest?.identity)
            if (change.expectedCommand != null && change.expectedCommand != commandVersion()) return NightMutation.Stale(expectedIdentity, latest?.identity)
            // Backing up/persisting may have consumed the remaining request lifetime.
            checkExecutionTime(change)
            writeMayHaveHappened = true
            controller.store(StoredProgram(change.intended.profile!!.name, change.plan.programSteps), change.intended.slot)
            checkExecutionTime(change)
            controller.startProgram(change.intended.slot) { checkExecutionTime(change) }
            publish(change.intended)
            return NightMutation.Applied(change.plan, change.intended)
        } catch (e: Exception) {
            val failed = pending.copy(confidence = if (writeMayHaveHappened) Confidence.UNKNOWN else Confidence.FAILED)
            withContext(NonCancellable) { publish(failed) }
            if (e is CancellationException) throw e
            return NightMutation.Failed(failed, e)
        }
    }

    /** Repeated after every pre-start suspension; the same strict boundary applies at the final send. */
    private fun checkExecutionTime(change: PreparedChange) {
        val time = now()
        if (!time.isBefore(ZonedDateTime.parse(change.request.deadline))) throw PlanException("This request has expired.")
        if ((change.request.kind == ChangeKind.RETIME || change.request.kind == ChangeKind.REAPPLY) &&
            change.previous != null && !time.isBefore(ZonedDateTime.parse(change.previous.wakeUpStart))) {
            throw PlanException("Tonight's wake-up has already started.")
        }
    }

}
