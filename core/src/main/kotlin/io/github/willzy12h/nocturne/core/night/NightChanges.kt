package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.*
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZonedDateTime

sealed interface Preparation {
    data class Ready(val change: PreparedChange): Preparation
    data class Stale(val expected: NightIdentity?, val actual: NightIdentity?): Preparation
    data class Refused(val reason: String): Preparation
}
/** No hardware work has happened. Persist [pendingNight] before executing this change. */
data class PreparedChange(val plan: Plan, val intended: ActiveNight, val previous: ActiveNight?, val request: PendingChange,
    val expectedCommand: Long? = null) {
    val pendingNight: ActiveNight get() = (previous ?: intended.copy(applied = AppliedProgram())).copy(
        confidence = Confidence.PENDING, pending = request,
    )
}
sealed interface NightMutation {
    data class Applied(val plan: Plan, val night: ActiveNight): NightMutation {
        /** Idempotent append; the original steps remain the first revision for old readers. */
        fun recordRevision(record: NightRecord): NightRecord = record.copy(
            revisions = record.revisions.ifEmpty {
                if (record.steps.isEmpty()) emptyList() else listOf(AppliedProgram(record.steps, record.startedAt, record.wakeAt))
            }.filterNot { it.revision == night.applied.revision } + night.applied,
            wakeAt = night.wakeAt, wakeFromAlarm = night.wakeFromAlarm,
        )
    }
    data class Stale(val expected: NightIdentity?, val actual: NightIdentity?): NightMutation
    data class Refused(val reason: String): NightMutation
    data class Failed(val night: ActiveNight, val cause: Exception): NightMutation
}
object Eligibility {
    sealed interface Result
    data object Allowed: Result
    data class Stale(val expected: NightIdentity?, val actual: NightIdentity?): Result
    data class Refused(val reason: String): Result
    /** Call after acquiring the command lock and connection, immediately before a write. */
    fun check(night: ActiveNight?, now: ZonedDateTime, request: PendingChange): Result {
        if (night?.identity != request.expected) return Stale(request.expected, night?.identity)
        if (!now.isBefore(ZonedDateTime.parse(request.deadline))) return Refused("This request has expired.")
        if (request.kind == ChangeKind.RETIME || request.kind == ChangeKind.REAPPLY) {
            if (night == null || night.actualEnd != null) return Refused("This night has ended.")
            if (!now.isBefore(ZonedDateTime.parse(night.wakeUpStart))) return Refused("Tonight's wake-up has already started.")
            val legacyFallback = night.profile == null && night.applied.steps.isEmpty() && night.pending == null
            if ((night.confidence == Confidence.UNKNOWN && !legacyFallback) || night.confidence == Confidence.FAILED) {
                return Refused("The BedJet programme must be checked before changing this night.")
            }
        }
        return Allowed
    }
}
@Serializable data class ResumeOffer(val night: ActiveNight, val remaining: List<RecordedStep>, val stoppedAt: String,
    val expiresAt: String, val commandVersion: Long)
data class LocalTransition(val night: ActiveNight, val event: HistoryEvent, val resumeOffer: ResumeOffer?) {
    fun settingsAfter(settings: Settings): Settings = settings.clearWakeOverride()
}
/** Local only: callers persist this transition and the offer under the same command lock. */
fun stopTracking(night: ActiveNight, reason: String, at: ZonedDateTime, commandVersion: Long = 0): LocalTransition {
    val remaining = night.applied.steps.mapNotNull { s ->
        val start = ZonedDateTime.parse(s.startsAt)
        val end = start.plusMinutes(s.minutes.toLong())
        when {
            !end.isAfter(at) -> null
            s.mode == Mode.TURBO && !start.isAfter(at) -> null
            start.isBefore(at) -> s.copy(startsAt = at.toString(), minutes = Duration.between(at, end).toMinutes().toInt())
            else -> s
        }
    }.filter { it.minutes > 0 }
    val offer = if (night.profile != null && at.isBefore(ZonedDateTime.parse(night.wakeAt))) {
        ResumeOffer(night, remaining, at.toString(), at.plusMinutes(15).toString(), commandVersion)
    } else null
    return LocalTransition(night.copy(actualEnd = at.toString(), pending = null), HistoryEvent(at.toString(), EventKind.STOPPED, reason), offer)
}
fun expire(night: ActiveNight, at: ZonedDateTime): LocalTransition? {
    if (night.actualEnd != null || at.isBefore(ZonedDateTime.parse(night.endsAt))) return null
    return LocalTransition(night.copy(actualEnd = at.toString(), pending = null), HistoryEvent(at.toString(), EventKind.ENDED, "Night ended"), null)
}
