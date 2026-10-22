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

data class StartResult(val plan: Plan, val night: ActiveNight, val record: NightRecord, val slotBackup: SlotBackup?)
data class RetimeResult(val plan: Plan, val night: ActiveNight, val event: HistoryEvent)

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
            val request = PendingChange(UUID.randomUUID().toString(), ChangeKind.RESUME, intended.wakeAt, now.toString(), offer.expiresAt, null)
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
            if (!now().isBefore(ZonedDateTime.parse(change.request.deadline))) throw PlanException("This request has expired.")
            if ((change.request.kind == ChangeKind.RETIME || change.request.kind == ChangeKind.REAPPLY) && change.previous != null && !now().isBefore(ZonedDateTime.parse(change.previous.wakeUpStart))) throw PlanException("Tonight's wake-up has already started.")
            writeMayHaveHappened = true
            controller.store(StoredProgram(change.intended.profile!!.name, change.plan.programSteps), change.intended.slot)
            controller.startProgram(change.intended.slot)
            publish(change.intended)
            return NightMutation.Applied(change.plan, change.intended)
        } catch (e: Exception) {
            val failed = pending.copy(confidence = if (writeMayHaveHappened) Confidence.UNKNOWN else Confidence.FAILED)
            withContext(NonCancellable) { publish(failed) }
            if (e is CancellationException) throw e
            return NightMutation.Failed(failed, e)
        }
    }

    /**
     * Interim compatibility adapter: does not bind repository identity or durably publish state.
     * Task 4 must replace production use with [prepareStart] and [apply] under its command lock.
     * Plans tonight around [alarm] (or the profile's wake time), writes it to [slot] of the BedJet named [deviceId] and
     * starts it.
     */
    suspend fun start(
        profile: Profile,
        alarm: ZonedDateTime?,
        slot: Int,
        alreadyBackedUp: List<SlotBackup>,
        deviceId: String = Migrations.UNKNOWN_DEVICE,
        override: TonightOverride? = null,
    ): StartResult {
        val time = now()
        val prepared = prepareStart(profile, alarm, slot, deviceId, null, null, override)
        val change = (prepared as? Preparation.Ready)?.change
            ?: throw PlanException((prepared as? Preparation.Refused)?.reason ?: "The night changed.")
        var backup: SlotBackup? = null
        val outcome = apply(change, { null }, beforeWrite = { backup = backupIfNeeded(deviceId, slot, alreadyBackedUp) })
        val applied = when (outcome) {
            is NightMutation.Applied -> outcome
            is NightMutation.Failed -> throw outcome.cause
            is NightMutation.Refused -> throw PlanException(outcome.reason)
            is NightMutation.Stale -> throw PlanException("The night changed.")
        }
        val plan = applied.plan
        val night = applied.night
        val wake = WakeChoice(ZonedDateTime.parse(night.wakeAt), night.wakeFromAlarm, night.wakeOrigin)
        val source = when (wake.origin) {
            WakeOrigin.ALARM -> "alarm"
            WakeOrigin.PROFILE_DEFAULT -> "default wake time"
            WakeOrigin.OVERRIDE -> "tonight-only wake time"
        }
        val record = NightRecord(
            id = night.recordId, profileId = profile.id, profileName = profile.name,
            startedAt = night.startedAt, wakeAt = night.wakeAt, wakeFromAlarm = wake.fromAlarm,
            steps = recordedSteps(plan),
            events = listOf(HistoryEvent(time.toString(), EventKind.STARTED, "Started ${profile.name}, waking at ${wake.time.toLocalTime()} ($source)")),
            deviceId = deviceId, revisions = listOf(night.applied),
        )
        return StartResult(plan, night, record, backup)
    }

    /**
     * Interim compatibility adapter: [prepareRetime] / [reapply] plus [apply] provide the authoritative API.
     * Follows an alarm that changed during the night. Returns null when nothing needs to change: the wake time is the
     * same, the wake-up has already begun, or the fallback wake time has already passed. With [force] the rest of the
     * night is rewritten even when the wake time hasn't changed, to apply edits made to the running profile.
     */
    suspend fun retime(profile: Profile, night: ActiveNight, alarm: ZonedDateTime?, force: Boolean = false): RetimeResult? {
        val prepared = if (force) reapply(night, profile, night.identity)
            else prepareRetime(night, night.identity, alarm, liveProfile = profile)
        val change = (prepared as? Preparation.Ready)?.change ?: return null
        return when (val result = apply(change, { night })) {
            is NightMutation.Applied -> RetimeResult(result.plan, result.night, HistoryEvent(now().toString(), EventKind.RETIMED,
                if (force) "Profile changes applied to the rest of the night" else "Wake-up moved to ${ZonedDateTime.parse(result.night.wakeAt).toLocalTime()}"))
            is NightMutation.Failed -> throw result.cause
            else -> null
        }
    }

    /** Interim unowned adapter for Task 4 migration. Runs a manual action held to the profile's limits. */
    suspend fun quickAction(preset: ActionPreset, limits: ProfileLimits) {
        val temperature = PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits)
        val fanStep = PlanCompiler.fanStepFor(preset.fanPercent, limits)
        controller.runManual(preset.mode, temperature, fanStep, preset.minutes)
    }

    /** Interim unowned adapter for Task 4 migration. Turbo for its 10 minutes then Heat at the hottest the limits allow, written to [slot] so the BedJet chains them. */
    suspend fun boost(
        minutes: Int,
        limits: ProfileLimits,
        slot: Int,
        alreadyBackedUp: List<SlotBackup> = emptyList(),
        deviceId: String = Migrations.UNKNOWN_DEVICE,
    ): SlotBackup? {
        val steps = PlanCompiler.phaseSteps(Phase(Mode.TURBO, 86, 100, minutes), limits, TurboChain.TURBO_THEN_HEAT)
        val backup = backupIfNeeded(deviceId, slot, alreadyBackedUp)
        controller.store(StoredProgram("Boost", steps), slot)
        controller.startProgram(slot)
        return backup
    }

    /** Interim unowned adapter; Task 4 owns expected-identity checks for manual commands and Off. */
    suspend fun stop() = controller.stop()

    /** Keeps a copy of a program the app didn't write, the first time a slot of this device is overwritten. */
    private suspend fun backupIfNeeded(deviceId: String, slot: Int, alreadyBackedUp: List<SlotBackup>): SlotBackup? {
        if (alreadyBackedUp.any { it.deviceId == deviceId && it.slot == slot }) return null
        val existing = controller.storedPrograms().getOrNull(slot) ?: return null
        return SlotBackup(deviceId, slot, existing, now().toString())
    }

    private fun recordedSteps(plan: Plan) = plan.steps.map {
        RecordedStep(it.block, it.step.mode, it.step.temperature, (it.step.fanStep + 1) * 5, it.startsAt.toString(), it.minutes)
    }
}
