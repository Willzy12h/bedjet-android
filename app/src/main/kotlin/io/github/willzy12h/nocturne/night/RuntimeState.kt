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
    /** An explicit ending command was attempted; a later target Standby can finish this exact night as STOPPED. */
    val endRequested: NightIdentity? = null,
    /** The command label and request time stay with the identity; older journals contain only the identity. */
    val endRequestedLabel: String? = null,
    val endRequestedAt: String? = null,
    /** Receipt boundary within its process; wall time above is retained for older journals, not used for ordering. */
    val endRequestedEpoch: String? = null,
    val endRequestedSequence: Long? = null,
    val wakeSourceChange: WakeSourceChange? = null,
    val resumeOffer: ResumeOffer? = null,
    val retime: PendingRetime? = null,
    val slotWrite: SlotWrite? = null,
    val reason: String? = null,
    val wakeShown: NightIdentity? = null,
    val manualWakeShown: Boolean = false,
) {
    /**
     * IR-06: the slot write still in question, PENDING (under way, or interrupted by a restart) or UNKNOWN (perhaps
     * partly accepted). A write that failed before touching the BedJet leaves no entry, and an old FAILED entry from an
     * earlier journal is ignored. The entry is settled by a later successful write of the same BedJet slot, by the end
     * of the night it was made in or displaced, by keeping a fresh run of that night, or when the user marks it checked.
     */
    val unresolvedSlotWrite: SlotWrite? get() = slotWrite?.takeIf { it.confidence == Confidence.PENDING || it.confidence == Confidence.UNKNOWN }
}
@Serializable data class PendingRetime(val id: String, val expected: NightIdentity, val deviceId: String,
    val alarmAt: String?, val alarmOrigin: String?, val deadline: String)
@Serializable data class SlotWrite(val deviceId: String, val slot: Int, val intended: StoredProgram?,
    val confidence: Confidence = Confidence.PENDING, val label: String,
    val startRequested: Boolean = false, val displaced: NightIdentity? = null,
    /** The night tracked when the write was made; its end settles the write (IR-06). */
    val nightId: String? = null) {
    fun belongsTo(recordId: String): Boolean = nightId == recordId || displaced?.nightId == recordId
    /** Whether a night or start could depend on this write: it started a programme, or wrote the working slot that
     * nights are written to and started from. A pin or restore of another slot does not (IR-06). */
    fun affectsNights(workingSlot: Int): Boolean = startRequested || slot == workingSlot
}

/** The entry that requested work; transient command metadata, never part of the runtime journal or backups. */
enum class CommandOrigin { APP, WIDGET, SHORTCUT, NOTIFICATION, REMINDER, ALARM, CHARGER, SETTINGS, PEER }
internal fun defaultCommandOrigin(command: NightActions.Command): CommandOrigin = when (command) {
    NightActions.Command.RETIME, NightActions.Command.RETRY_RETIME -> CommandOrigin.ALARM
    else -> CommandOrigin.APP
}

data class CommandRequest(val command: NightActions.Command, val profileId: String?, val deviceId: String?,
    val expected: NightIdentity?, val requireIdentity: Boolean = false, val replacementConfirmed: Boolean = false,
    val origin: CommandOrigin = CommandOrigin.APP)
enum class OutcomeKind { CONFIRMED, ACCEPTED, LOCAL, REFUSED, STALE, FAILED, UNKNOWN, CONFIRMATION_REQUIRED }
data class CommandOutcome(val kind: OutcomeKind, val message: String,
    /** The outcome already has its own notice; service delivery must not post another one. */
    val notificationHandled: Boolean = false, val origin: CommandOrigin = CommandOrigin.APP) {
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

/** Typed entry metadata retained when a service takes ownership of the caller's work. */
internal class CommandOriginContext(val origin: CommandOrigin) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<CommandOriginContext>
}

/** A queued retry may admit newer work. Cancellation belongs to the identity and notice it actually handled. */
internal class RetimeExecution : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    var identity: NightIdentity? = null
    var reportedNotice: String? = null
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RetimeExecution>
}

@Serializable data class LocalDelivery(val id: String, val action: String, val expected: NightIdentity, val receivedAt: String)

@Serializable data class WakeSourceChange(val expected: NightIdentity, val origin: WakeOrigin)
