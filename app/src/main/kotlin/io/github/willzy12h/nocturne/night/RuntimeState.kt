package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.serialization.Serializable

/** Local runtime journal, intentionally excluded from portable profile backups. */
@Serializable data class RuntimeState(
    val generation: Long = 0,
    val localDeliveries: List<LocalDelivery> = emptyList(),
    val alarmInbox: AlarmObservation? = null,
    val ending: PendingEnd? = null,
    val wakeSourceChange: WakeSourceChange? = null,
    val resumeOffer: ResumeOffer? = null,
    val retime: PendingRetime? = null,
    val slotWrite: SlotWrite? = null,
    val reason: String? = null,
    val wakeShown: NightIdentity? = null,
)
@Serializable data class PendingRetime(val id: String, val expected: NightIdentity, val deviceId: String,
    val alarmAt: String?, val alarmOrigin: String?, val deadline: String)
@Serializable data class SlotWrite(val deviceId: String, val slot: Int, val intended: StoredProgram?,
    val confidence: Confidence = Confidence.PENDING, val label: String,
    val startRequested: Boolean = false, val displaced: NightIdentity? = null)

data class CommandRequest(val command: NightActions.Command, val profileId: String?, val deviceId: String?,
    val expected: NightIdentity?, val requireIdentity: Boolean = false, val replacementConfirmed: Boolean = false)
enum class OutcomeKind { CONFIRMED, ACCEPTED, LOCAL, REFUSED, STALE, FAILED, UNKNOWN, CONFIRMATION_REQUIRED }
/** [notified] means the runtime already posted the user-facing notice, so callers do not add a generic failure. */
data class CommandOutcome(val kind: OutcomeKind, val message: String, val notified: Boolean = false) {
    val succeeded: Boolean get() = kind in setOf(OutcomeKind.CONFIRMED, OutcomeKind.ACCEPTED, OutcomeKind.LOCAL)
}
data class BusyState(val runningLabel: String? = null, val queuedCount: Int = 0)
data class NightStatus(val confidence: Confidence?, val reason: String?)

/** One replacement decision shared by every entry point. Off is always executable. */
object ReplacementPolicy {
    /** Restore never replaces a tracked night; first end it or stop tracking explicitly. */
    fun permitsSlotRestore(active: ActiveNight?): Boolean = active == null
    data class Decision(val replaces: Boolean, val needsConfirmation: Boolean)
    fun decide(command: NightActions.Command, active: ActiveNight?): Decision {
        val replaces = active != null && command in setOf(NightActions.Command.START_NIGHT, NightActions.Command.OFF,
            NightActions.Command.END_NIGHT, NightActions.Command.TURBO, NightActions.Command.BOOST,
            NightActions.Command.COOL, NightActions.Command.WARM, NightActions.Command.RESUME)
        return Decision(replaces, replaces && command != NightActions.Command.OFF && command != NightActions.Command.END_NIGHT)
    }
}

@Serializable data class AlarmObservation(val id: String, val nightId: String, val deviceId: String, val observedAt: String)

@Serializable data class PendingEnd(val identity: NightIdentity, val event: io.github.willzy12h.nocturne.core.store.HistoryEvent)

/** Submission target carried across the service handoff and checked under command ownership. */
internal class SubmissionTarget(val deviceId: String?) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SubmissionTarget>
}

@Serializable data class LocalDelivery(val id: String, val action: String, val expected: NightIdentity, val receivedAt: String)

@Serializable data class WakeSourceChange(val expected: NightIdentity, val origin: WakeOrigin)
