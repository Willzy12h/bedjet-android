package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.RecordedStep
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

@Serializable enum class WakeOrigin { ALARM, PROFILE_DEFAULT, OVERRIDE }
@Serializable enum class Confidence { CONFIRMED, PENDING, FAILED, UNKNOWN }
@Serializable enum class ChangeKind { START, RETIME, REAPPLY, RESUME, STOP, MANUAL }
@Serializable data class NightIdentity(val nightId: String, val revision: Int)
@Serializable data class PendingChange(
    val requestId: String, val kind: ChangeKind, val requestedWake: String?,
    val requestedAt: String, val deadline: String, val expected: NightIdentity? = null,
)

/** One confirmed write, including the clock-time flags actually sent to the unit. */
@Serializable data class AppliedProgram(
    val steps: List<RecordedStep> = emptyList(),
    val start: String = "", val wake: String = "", val revision: Int = 0,
    val programSteps: List<ProgramStep> = emptyList(),
) {
    fun plan(): Plan? {
        if (start.isEmpty() || wake.isEmpty()) return null
        return Plan(ZonedDateTime.parse(start), ZonedDateTime.parse(wake), steps.mapIndexed { i, s ->
            PlannedStep(s.block, programSteps.getOrNull(i) ?: ProgramStep(s.mode, s.fanPercent / 5 - 1,
                s.temperature, s.minutes / 60, s.minutes % 60), ZonedDateTime.parse(s.startsAt), s.minutes)
        })
    }
    companion object {
        fun from(plan: Plan, revision: Int = 0) = AppliedProgram(plan.steps.map {
            RecordedStep(it.block, it.step.mode, it.step.temperature, (it.step.fanStep + 1) * 5, it.startsAt.toString(), it.minutes)
        }, plan.start.toString(), plan.wake.toString(), revision, plan.programSteps)
    }
}

/** v1 JSON remains readable. Missing snapshots are explicitly marked as a live-profile fallback. */
@Serializable data class ActiveNight(
    val recordId: String, val profileId: String, val startedAt: String, val wakeAt: String,
    val wakeFromAlarm: Boolean, val slot: Int, val wakeUpStart: String, val endsAt: String,
    val deviceId: String = Migrations.UNKNOWN_DEVICE,
    val profile: Profile? = null,
    val wakeOrigin: WakeOrigin = if (wakeFromAlarm) WakeOrigin.ALARM else WakeOrigin.PROFILE_DEFAULT,
    val revision: Int = 0,
    val applied: AppliedProgram = AppliedProgram(),
    val confidence: Confidence = Confidence.UNKNOWN,
    val pending: PendingChange? = null,
    val actualEnd: String? = null,
) {
    val identity: NightIdentity get() = NightIdentity(recordId, revision)
    val usesLiveProfileFallback: Boolean get() = profile == null
    fun activePlan(liveProfile: Profile? = null): Plan? = applied.plan() ?: (profile ?: liveProfile)?.let {
        PlanCompiler.compile(it, ZonedDateTime.parse(startedAt), ZonedDateTime.parse(wakeAt))
    }
}
