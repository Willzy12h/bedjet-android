package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.PlanException
import io.github.willzy12h.nocturne.core.plan.RetryablePlanException
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.Limits
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
import java.time.DateTimeException
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Starts, re-times and interrupts nights on a BedJet. Everything here is what the buttons, widgets and alarms do. */
class NightRunner(private val controller: BedJetController, private val now: () -> ZonedDateTime) {
    private class StaleApplication(val expected: NightIdentity?, val actual: NightIdentity?) : Exception()

    /** Prepare one complete on-device programme; returning to the night needs no later phone command. */
    fun prepareTemporary(night: ActiveNight, expected: NightIdentity, preset: ActionPreset): Preparation {
        if (night.identity != expected) return Preparation.Stale(expected, night.identity)
        fun refused(reason: TemporaryRefusal) = Preparation.Refused("", temporaryRefusal = reason)
        if (night.actualEnd != null) return refused(TemporaryRefusal.ENDED)
        if (night.confidence != Confidence.CONFIRMED || night.pending != null) return refused(TemporaryRefusal.NOT_CONFIRMED)
        if (night.trackingManual) return refused(TemporaryRefusal.MANUAL)
        val profile = night.profile ?: return refused(TemporaryRefusal.MISSING_SNAPSHOT)
        val base = night.temporary?.base ?: night.applied
        if (!hasTemporarySnapshot(night, base)) return refused(TemporaryRefusal.MISSING_SNAPSHOT)
        if (preset.mode in setOf(Mode.STANDBY, Mode.WAIT) || preset.minutes !in 1..120 ||
            (preset.mode == Mode.TURBO && preset.minutes > 30) || preset.fanPercent !in 5..100 || preset.temperature !in 38..86)
            return refused(TemporaryRefusal.INVALID_PRESET)
        val time = now()
        if (!time.isBefore(ZonedDateTime.parse(night.wakeUpStart))) return refused(TemporaryRefusal.TOO_CLOSE_TO_WAKE)
        return try {
            val composed = TemporaryProgramme.prepare(profile, base, time, preset)
            val revision = night.revision + 1
            val intended = night.copy(revision = revision, applied = AppliedProgram.from(composed.plan, revision),
                temporary = composed.temporary, confidence = Confidence.CONFIRMED, pending = null,
                wakeUpStart = composed.plan.wakeUpStart.toString(), endsAt = composed.plan.end.toString())
            val request = PendingChange(UUID.randomUUID().toString(), ChangeKind.TEMPORARY, night.wakeAt,
                time.toString(), time.plusMinutes(1).toString(), expected)
            Preparation.Ready(PreparedChange(composed.plan, intended, night, request))
        } catch (e: TemporaryProgramme.Refusal) { refused(e.reason)
        } catch (_: PlanException) { refused(TemporaryRefusal.PROFILE_LIMITS) }
    }

    /** Runtime JSON can be syntactically valid while its dates are not. A pure preview must fail closed. */
    private fun hasTemporarySnapshot(night: ActiveNight, base: AppliedProgram): Boolean = try {
        val snapshots = listOf(night.applied, base)
        if (snapshots.any { it.steps.isEmpty() || it.steps.size > Limits.STEPS_PER_PROGRAM || it.programSteps.size != it.steps.size }) false
        else {
            listOf(night.startedAt, night.wakeAt, night.wakeUpStart, night.endsAt).forEach { ZonedDateTime.parse(it) }
            val plansAvailable = snapshots.all { it.plan() != null }
            val temporaryDatesAvailable = night.temporary?.let { temporary ->
                if (temporary.steps.isEmpty() || temporary.steps.size > Limits.STEPS_PER_PROGRAM) false
                else {
                    ZonedDateTime.parse(temporary.until)
                    temporary.steps.forEach { ZonedDateTime.parse(it.startsAt).plusMinutes(it.minutes.toLong()) }
                    true
                }
            } ?: true
            plansAvailable && temporaryDatesAvailable
        }
    } catch (_: DateTimeException) { false }

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
        if (night.wakeOrigin == WakeOrigin.OVERRIDE) return Preparation.Refused("Tonight's wake override is unchanged.", unchanged = true)
        val start = ZonedDateTime.parse(night.startedAt)
        val accepted = profile.wakeSource.useAlarm && AlarmPolicy(settings).accept(alarm, alarmOrigin, start)
        val wake = if (accepted) alarm!! else WakeResolver.nextOccurrence(profile.wakeSource.defaultWakeMinute, start.plus(WakeResolver.MIN_NIGHT))
        if (wake.isEqual(ZonedDateTime.parse(night.wakeAt))) return Preparation.Refused("Wake-up unchanged.", unchanged = true)
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
            val original = night.temporary?.base?.plan() ?: night.activePlan(profile)
                ?: return Preparation.Refused("The applied programme is unavailable.")
            val base = if (kind == ChangeKind.REAPPLY) {
                var elapsed = Duration.between(ZonedDateTime.parse(night.startedAt), time).toMinutes().toInt()
                val remaining = profile.start.mapNotNull { phase ->
                    val skipped = minOf(elapsed.coerceAtLeast(0), phase.minutes)
                    elapsed -= skipped
                    if (phase.minutes == skipped || (phase.mode == Mode.TURBO && skipped > 0)) null else phase.copy(minutes = phase.minutes - skipped)
                }
                PlanCompiler.compile(profile.copy(start = remaining), time, wake)
            } else PlanCompiler.replan(profile, original, time, wake) ?: return Preparation.Refused("Tonight's wake-up has already started.")
            val revision = night.revision + 1
            val composed = night.temporary?.takeIf { time.isBefore(ZonedDateTime.parse(it.until)) }
                ?.let { TemporaryProgramme.replan(profile, base, it, time, revision) }
                ?: TemporaryProgramme.Composition(base, null)
            val plan = composed.plan
            val intended = night.copy(profile = profile, profileId = profile.id, wakeAt = wake.toString(),
                wakeFromAlarm = origin == WakeOrigin.ALARM, wakeOrigin = origin, revision = revision,
                wakeUpStart = plan.wakeUpStart.toString(), endsAt = plan.end.toString(), applied = AppliedProgram.from(plan, revision),
                confidence = Confidence.CONFIRMED, pending = null, temporary = composed.temporary)
            Preparation.Ready(PreparedChange(plan, intended, night, request))
        } catch (e: TemporaryProgramme.Refusal) { Preparation.Refused("", temporaryRefusal = e.reason)
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
                wakeUpStart = plan.wakeUpStart.toString(), endsAt = plan.end.toString(), confidence = Confidence.CONFIRMED,
                actualEnd = null, pending = null, temporary = null)
            // The offer may outlive tonight's wake; execution must satisfy both boundaries too.
            val deadline = minOf(ZonedDateTime.parse(offer.expiresAt), wake)
            val request = PendingChange(UUID.randomUUID().toString(), ChangeKind.RESUME, intended.wakeAt, now.toString(), deadline.toString(), null)
            Preparation.Ready(PreparedChange(plan, intended, null, request, offer.commandVersion))
        } catch (e: PlanException) { Preparation.Refused(e.message.orEmpty()) }
    }

    /** Caller owns lock and connection. [current] is read after acquisition. [publish] must durably save
     * PENDING before returning; [beforeWrite] durably backs up the original slot. [afterWrite]
     * may verify hardware after upload and before START. A partial write is UNKNOWN.
     * Task 4 supplies repository callbacks; no callback is invoked for stale/expired requests. */
    suspend fun apply(change: PreparedChange, current: () -> ActiveNight?,
        commandVersion: () -> Long = { 0 }, publish: suspend (ActiveNight) -> Unit = {},
        beforeWrite: suspend () -> Unit = {}, afterWrite: suspend () -> Unit = {}): NightMutation {
        if (change.expectedCommand != null && change.expectedCommand != commandVersion()) return NightMutation.Stale(change.request.expected, current()?.identity)
        when (val check = Eligibility.check(current(), now(), change.request)) {
            is Eligibility.Stale -> return NightMutation.Stale(check.expected, check.actual)
            is Eligibility.Refused -> return NightMutation.Refused(check.reason)
            Eligibility.Allowed -> Unit
        }
        // What is running now; a failure before any write publishes it back unchanged, whatever its confidence (IR-05).
        val before = current()
        val pending = change.pendingNight
        var writeMayHaveHappened = false
        var startMayHaveHappened = false
        fun ownsPending(night: ActiveNight?): Boolean = night?.identity == pending.identity &&
            night.confidence == Confidence.PENDING && night.pending?.requestId == change.request.requestId
        fun checkOwnership() {
            val latest = current()
            // The unchanged snapshot also supports read-only owners; a different request never does.
            if ((latest != before && !ownsPending(latest)) ||
                (change.expectedCommand != null && change.expectedCommand != commandVersion()))
                throw StaleApplication(change.request.expected, latest?.identity)
        }
        try {
            // Publication can commit and then throw cancellation while returning from the durable writer.
            publish(pending)
            beforeWrite()
            checkOwnership()
            // Backing up/persisting may have consumed the remaining request lifetime.
            checkExecutionTime(change)
            writeMayHaveHappened = true
            controller.store(StoredProgram(change.intended.profile!!.name, change.plan.programSteps), change.intended.slot)
            checkOwnership()
            checkExecutionTime(change)
            controller.startProgram(change.intended.slot, onStartAttempt = { startMayHaveHappened = true },
                beforePrepare = { afterWrite(); checkOwnership(); checkExecutionTime(change) }) {
                checkOwnership()
                checkExecutionTime(change)
            }
            publish(change.intended)
            return NightMutation.Applied(change.plan, change.intended)
        } catch (e: Exception) {
            // A committed confirmation stays authoritative even when returning from its writer was cancelled.
            if (current() == change.intended && change.intended.confidence == Confidence.CONFIRMED) {
                if (e is CancellationException) throw e
                return NightMutation.Applied(change.plan, change.intended)
            }
            // Restore a known pre-write state, otherwise retain this exact request's uncertain intention.
            val failed = if (!writeMayHaveHappened && before != null) before else pending.copy(confidence =
                if (writeMayHaveHappened && (change.request.kind != ChangeKind.START || change.previous != null ||
                        startMayHaveHappened || e is CancellationException)) Confidence.UNKNOWN else Confidence.FAILED)
            try {
                withContext(NonCancellable) { withTimeout(8_000) {
                    val latest = current()
                    if (ownsPending(latest) || (!writeMayHaveHappened && latest == before)) publish(failed)
                } }
            } catch (cleanup: Exception) { if (cleanup !== e) e.addSuppressed(cleanup) }
            if (e is CancellationException) throw e
            if (e is StaleApplication) return NightMutation.Stale(e.expected, e.actual)
            return NightMutation.Failed(failed, e, nothingWritten = !writeMayHaveHappened)
        }
    }

    /** Repeated after every pre-start suspension; the same strict boundary applies at the final send. */
    private fun checkExecutionTime(change: PreparedChange) {
        val time = now()
        if (!time.isBefore(ZonedDateTime.parse(change.request.deadline))) throw PlanException("This request has expired.")
        val rewrite = change.request.kind in setOf(ChangeKind.RETIME, ChangeKind.REAPPLY, ChangeKind.TEMPORARY)
        if (rewrite &&
            change.previous != null && !time.isBefore(ZonedDateTime.parse(change.previous.wakeUpStart))) {
            throw PlanException("Tonight's wake-up has already started.")
        }
        // IR-10: the new plan's own wake-up start too, which is earlier than the old one when the alarm moved earlier.
        // Start and Resume are left out: a start is compiled moments before, and a resume may continue a wake-up.
        if (rewrite &&
            !time.isBefore(change.plan.wakeUpStart)) throw PlanException("Tonight's wake-up has already started.")
        // A fresh preparation may continue Heat or the underlying night, but this elapsed prefix cannot restart.
        if (change.plan.steps.firstOrNull { it.block == Block.TEMPORARY }?.let { !time.isBefore(it.endsAt) } == true)
            throw RetryablePlanException("This request has expired.")
        // Clock-time BedJet steps wrap to tomorrow once their programmed end has passed.
        // Recheck the new plan after uploads and clock sync, including a safe write margin.
        if (change.plan.steps.any { it.step.endsAtClockTime && !time.isBefore(it.endsAt.minusMinutes(PlanCompiler.MIN_CLOCK_TIME_STEP.toLong())) }) {
            throw RetryablePlanException("The new programme's clock-time step is too close to ending.")
        }
        if (change.plan.steps.any { it.step.endsAtClockTime } && time.offset != change.plan.start.offset) {
            throw RetryablePlanException("The clock offset changed while preparing the programme. Try again.")
        }
    }

}
