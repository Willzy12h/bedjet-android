package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The only owner of complete BedJet transactions, target changes and local night transitions.
 * Callers own the coroutine lifecycle: foreground UI, bounded service, or cancellable JobService. */
class NightActions(private val graph: Graph) {
    private class RetimeTakeover : Exception("The BedJet programme changed during retime upload.")
    internal val repo get() = graph.repository
    private val context get() = graph.context
    private val lock = Mutex()
    private val _busy = MutableStateFlow(BusyState())
    val busy: StateFlow<BusyState> = _busy.asStateFlow()
    private val _outcomes = MutableSharedFlow<CommandOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<CommandOutcome> = _outcomes.asSharedFlow()
    val status: StateFlow<NightStatus> = combine(repo.activeNight, repo.runtime) { n, r -> NightStatus(if ((r.retime != null || r.alarmInbox != null) && n?.confidence == Confidence.CONFIRMED) Confidence.PENDING else if (r.slotWrite?.confidence in setOf(Confidence.PENDING, Confidence.FAILED, Confidence.UNKNOWN)) r.slotWrite?.confidence else n?.confidence, r.reason) }
        .stateIn(graph.scope, SharingStarted.Eagerly, NightStatus(repo.activeNight.value?.confidence, repo.runtime.value.reason))

    enum class Command { START_NIGHT, END_NIGHT, OFF, TURBO, BOOST, COOL, WARM, RETIME, RETRY_RETIME, REAPPLY, SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, RESUME, STOP_TRACKING, KEEP_TRACKING }
    fun capture(command: Command, profileId: String? = null, requireIdentity: Boolean = false) =
        CommandRequest(command, profileId, repo.settings.value.deviceId(), repo.activeNight.value?.identity, requireIdentity)

    /** Fresh explicit Off follows a queued Start. Only old notification/widget identities are rejected. */
    suspend fun perform(command: Command, profileId: String? = null, replacementConfirmed: Boolean = false): String =
        execute(capture(command, profileId, requireIdentity = replacementConfirmed).copy(replacementConfirmed = replacementConfirmed)).message
    suspend fun execute(request: CommandRequest): CommandOutcome = owned(label(request.command), request.deviceId,
        minimumRemainingMs = if (request.command in setOf(Command.START_NIGHT, Command.RETIME, Command.RETRY_RETIME,
                Command.REAPPLY, Command.RESUME, Command.BOOST)) 30_000 else 0) {
        if (request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
        if (ReplacementPolicy.decide(request.command, repo.activeNight.value).needsConfirmation && !request.replacementConfirmed)
            return@owned CommandOutcome(OutcomeKind.CONFIRMATION_REQUIRED, text(R.string.runtime_replace_confirmation))
        when (request.command) {
            Command.STOP_TRACKING -> stopTrackingLocked()
            Command.KEEP_TRACKING -> keepTrackingLocked()
            Command.RETRY_RETIME -> { consumeAlarmObservation(); retryRetimeLocked() }
            Command.RETIME -> { consumeAlarmObservation(); val queued = queueRetimeLocked(); if (queued.kind == OutcomeKind.LOCAL) retryRetimeLocked() else queued }
            else -> connected { run(request) }
        }
    }
    /** No FGS, detached launch or retry. Job cancellation propagates after durable uncertainty is saved. */
    suspend fun performJobOwned(request: CommandRequest, eligible: suspend (DeviceStatus) -> Boolean): CommandOutcome =
        owned(label(request.command), request.deviceId, minimumRemainingMs = 30_000) {
            if (request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
            connected {
                val fresh = freshStatus()
                if (repo.activeNight.value != null || !eligible(fresh)) return@connected refused(R.string.runtime_ineligible)
                run(request)
            }
        }

    /** A read-only compatibility route. Production writes use named owned methods below. */
    internal suspend fun <T> exclusive(work: suspend () -> T): T = lock.withLock {
        try { withTimeout(OPERATION_MS) { graph.sessions.withConnection { work() } } }
        finally { drainLocalSafely() }
    }

    private suspend fun owned(label: String, target: String?, minimumRemainingMs: Long = 0,
        work: suspend () -> CommandOutcome): CommandOutcome {
        _busy.update { it.copy(queuedCount = it.queuedCount + 1) }
        var acquired = false
        val owner = Any()
        val startedAt = System.nanoTime()
        try {
            return withTimeout(OPERATION_MS) {
                lock.lock(owner)
                acquired = true
                _busy.update { it.copy(runningLabel = label, queuedCount = it.queuedCount - 1) }
                val submitted = currentCoroutineContext()[SubmissionTarget]
                val remainingMs = OPERATION_MS - (System.nanoTime() - startedAt) / 1_000_000
                val result = if (minimumRemainingMs > 0 && remainingMs < minimumRemainingMs)
                    CommandOutcome(OutcomeKind.REFUSED, text(R.string.runtime_waited_too_long))
                else if (target != repo.settings.value.deviceId() ||
                    (submitted != null && submitted.deviceId != repo.settings.value.deviceId())) stale(R.string.runtime_target_changed) else work()
                _outcomes.emit(result)
                result
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            recordError("$label: ${e.message.orEmpty()}")
            return CommandOutcome(if (repo.activeNight.value?.confidence == Confidence.UNKNOWN || repo.runtime.value.slotWrite?.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                e.message ?: text(R.string.runtime_failed)).also { _outcomes.emit(it) }
        } finally {
            // Ownership is tested even if timeout raced with resumption after lock.lock().
            _busy.update { if (acquired) it.copy(runningLabel = null) else it.copy(queuedCount = (it.queuedCount - 1).coerceAtLeast(0)) }
            if (lock.holdsLock(owner)) {
                try { drainLocalSafely() } finally { lock.unlock(owner) }
            }
        }
    }
    private suspend fun <T> connected(work: suspend () -> T): T {
        check(repo.settings.value.deviceId() != null) { text(R.string.runtime_choose_target) }
        return graph.sessions.withConnection { work() }
    }
    private suspend fun advanceGeneration() {
        repo.updateRuntime { it.copy(generation = it.generation + 1, resumeOffer = null, reason = null) }
    }
    private suspend fun run(request: CommandRequest): CommandOutcome {
        if (request.deviceId != repo.settings.value.deviceId()) return stale(R.string.runtime_target_changed)
        val command = request.command
        if (command == Command.RESUME) return resumeLocked()
        if (command == Command.START_NIGHT) {
            val profile = if (request.profileId == null) repo.tonightsProfile() else repo.profile(request.profileId)
            if (profile == null) return refused(if (request.profileId == null) R.string.runtime_add_profile else R.string.runtime_profile_deleted)
            advanceGeneration()
            val s = repo.settings.value
            val old = repo.activeNight.value
            return apply(graph.runner().prepareStart(profile, AlarmSource.next(context, s), s.workingSlot, request.deviceId!!,
                old?.identity, old, s.resolveWakeOverride(graph.now()), s), R.string.runtime_started)
        }
        if (command == Command.REAPPLY) {
            val night = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
            if (night.trackingManual) return refused(R.string.runtime_manual_tracking)
            val profile = repo.profile(night.profileId) ?: return refused(R.string.runtime_profile_deleted)
            advanceGeneration()
            return apply(graph.runner().reapply(night, profile, night.identity, repo.settings.value.resolveWakeOverride(graph.now())?.wakeAt?.let(ZonedDateTime::parse)), R.string.runtime_reapplied)
        }
        advanceGeneration()
        return when (command) {
            Command.OFF, Command.END_NIGHT -> manualChange(text(R.string.runtime_off), endsNight = true) { graph.controller().stop() }
            Command.TURBO -> quick(ActionPreset(Mode.TURBO, 86, 100, 10), text(R.string.runtime_turbo))
            Command.COOL -> quick(repo.settings.value.extendCool, text(R.string.runtime_cooling))
            Command.WARM -> quick(repo.settings.value.extendWarm, text(R.string.runtime_warming))
            Command.BOOST -> boostLocked()
            Command.SNOOZE_WARM -> timedManual(repo.settings.value.snoozeWarm, EventKind.SNOOZED, text(R.string.runtime_snooze_warm))
            Command.SNOOZE_COOL -> timedManual(repo.settings.value.snoozeCool, EventKind.SNOOZED, text(R.string.runtime_snooze_cool))
            Command.EXTEND_WARM -> timedManual(repo.settings.value.extendWarm, EventKind.EXTENDED, text(R.string.runtime_extend_warm))
            Command.EXTEND_COOL -> timedManual(repo.settings.value.extendCool, EventKind.EXTENDED, text(R.string.runtime_extend_cool))
            else -> refused(R.string.runtime_ineligible)
        }
    }
    private suspend fun apply(preparation: Preparation, message: Int,
        afterWrite: suspend () -> Unit = {}): CommandOutcome {
        val change = when (preparation) {
            is Preparation.Ready -> preparation.change
            is Preparation.Refused -> return CommandOutcome(OutcomeKind.REFUSED, preparation.reason)
            is Preparation.Stale -> return stale()
        }
        val result = graph.runner().apply(change, { repo.activeNight.value }, { repo.runtime.value.generation },
            publish = {
                publishNight(it)
                if (it.confidence == Confidence.PENDING && it.pending?.kind == ChangeKind.START &&
                    repo.history.value.none { record -> record.id == it.recordId }) {
                    repo.addRecord(NightRecord(it.recordId, it.profileId, it.profile!!.name, it.startedAt, it.wakeAt,
                        it.wakeFromAlarm, it.applied.steps, deviceId = it.deviceId))
                }
                if (it.confidence != Confidence.CONFIRMED) Notifications.cancelQuiet(context)
            }, beforeWrite = { backup(change.intended.deviceId, change.intended.slot) }, afterWrite = afterWrite)
        return when (result) {
            is NightMutation.Applied -> {
                if (change.previous != null && change.previous!!.recordId != result.night.recordId) {
                    repo.addEvent(change.previous!!.recordId, event(EventKind.STOPPED, text(R.string.runtime_replaced)))
                    val offer = io.github.willzy12h.nocturne.core.night.stopTracking(change.previous!!, text(R.string.runtime_replaced), graph.now(), repo.runtime.value.generation).resumeOffer
                    repo.updateRuntime { it.copy(resumeOffer = offer) }
                }
                val n = result.night
                val existing = repo.history.value.firstOrNull { it.id == n.recordId }
                val record = existing ?: NightRecord(n.recordId, n.profileId, n.profile!!.name, n.startedAt, n.wakeAt,
                    n.wakeFromAlarm, n.applied.steps, deviceId = n.deviceId)
                repo.addRecord(result.recordRevision(record))
                repo.addEvent(n.recordId, event(if (existing == null) EventKind.STARTED else EventKind.RETIMED, text(message, n.profile!!.name, clock(n.wakeAt))))
                repo.updateRuntime { it.copy(retime = null, reason = null, wakeShown = null, manualWakeShown = false) }
                NightAlarms.schedule(context, n)
                NightAlarms.cancelRetry(context)
                if (repo.runtime.value.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
                Notifications.cancelWakeUp(context)
                Notifications.showQuiet(context, n, repo.settings.value)
                if (message != R.string.runtime_resumed && change.previous?.recordId != n.recordId &&
                    repo.runtime.value.resumeOffer != null) Notifications.showResume(context)
                if (message == R.string.runtime_retimed) Notifications.showInfo(context, text(R.string.runtime_moved_title, clock(n.wakeAt)),
                    text(R.string.runtime_moved_body, clock(n.wakeAt), clock(change.previous!!.wakeAt)))
                CommandOutcome(OutcomeKind.CONFIRMED, text(message, n.profile!!.name, clock(n.wakeAt)))
            }
            is NightMutation.Failed -> {
                if (result.cause is RetimeTakeover) {
                    val reason = text(R.string.runtime_takeover)
                    repo.updateRuntime { it.copy(reason = reason) }
                    repo.addEvent(change.intended.recordId, event(EventKind.REMOTE_CHANGE, reason))
                    Notifications.showInfo(context, text(R.string.runtime_takeover_title), reason)
                    CommandOutcome(OutcomeKind.UNKNOWN, reason)
                } else {
                    repo.updateRuntime { it.copy(reason = result.cause.message) }
                    if (change.request.kind == ChangeKind.START)
                        repo.addEvent(change.intended.recordId, event(EventKind.ERROR, result.cause.message.orEmpty()))
                    else recordError(result.cause.message.orEmpty())
                    CommandOutcome(if (result.night.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                        result.cause.message ?: text(R.string.runtime_failed))
                }
            }
            is NightMutation.Refused -> CommandOutcome(OutcomeKind.REFUSED, result.reason)
            is NightMutation.Stale -> stale()
        }
    }
    internal suspend fun backup(device: String, slot: Int) {
        repo.originalsFor(device)
        val existing = repo.originalSlot(device, slot)
        val original = existing?.program ?: if (existing == null) graph.controller().storedPrograms()[slot] else null
        repo.recordOriginalSlot(device, slot, original)
    }
    private suspend fun quick(preset: ActionPreset, message: String) = manualChange(message, true) { runPreset(preset) }
    private suspend fun runPreset(preset: ActionPreset) {
        val limits = limits()
        graph.controller().runManual(preset.mode, PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits),
            PlanCompiler.fanStepFor(preset.fanPercent, limits), preset.minutes)
    }
    private suspend fun manualChange(message: String, endsNight: Boolean, affectsProgramme: Boolean = false, work: suspend () -> Unit): CommandOutcome {
        val night = repo.activeNight.value
        val pending = night?.copy(confidence = Confidence.PENDING, pending = PendingChange(UUID.randomUUID().toString(),
            ChangeKind.MANUAL, null, graph.now().toString(), graph.now().plusMinutes(1).toString(), night.identity))
        pending?.let { publishNight(it) }
        try { work() } catch (e: Exception) {
            withContext(NonCancellable) {
                pending?.let { publishNight(it.copy(confidence = Confidence.UNKNOWN)) }
                repo.updateRuntime { it.copy(reason = e.message) }
            }
            throw e
        }
        if (endsNight && night != null) finishLocked(night, EventKind.STOPPED, message)
        else if (affectsProgramme && night != null) {
            // The packet was accepted, but a temperature/fan/timer change is not proof
            // that the saved programme still describes the unit's running state.
            publishNight(night.copy(confidence = Confidence.UNKNOWN, pending = null, trackingManual = true))
            repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_manual_tracking)) }
            NightAlarms.cancelRetry(context)
            Notifications.cancelQuiet(context)
        }
        else if (night != null) publishNight(night)
        return CommandOutcome(OutcomeKind.ACCEPTED, message)
    }
    private suspend fun timedManual(preset: ActionPreset, kind: EventKind, label: String): CommandOutcome {
        val old = repo.activeNight.value
        val message = text(R.string.runtime_timed, label, preset.minutes)
        val result = manualChange(message, false) { runPreset(preset) }
        if (old != null) {
            val until = graph.now().plusMinutes(preset.minutes.toLong())
            val bounds = limits()
            val step = ProgramStep(preset.mode, PlanCompiler.fanStepFor(preset.fanPercent, bounds),
                PlanCompiler.temperatureFor(preset.mode, preset.temperature, bounds), preset.minutes / 60, preset.minutes % 60)
            val plan = Plan(graph.now(), ZonedDateTime.parse(old.wakeAt), listOf(PlannedStep(Block.MIDDLE, step, graph.now(), preset.minutes)))
            val n = old.copy(endsAt = until.toString(), revision = old.revision + 1, confidence = Confidence.UNKNOWN,
                pending = null, trackingManual = true, applied = AppliedProgram.from(plan, old.revision + 1))
            publishNight(n)
            repo.history.value.firstOrNull { it.id == n.recordId }?.let { repo.addRecord(NightMutation.Applied(plan, n).recordRevision(it)) }
            repo.addEvent(n.recordId, event(kind, message))
            repo.updateRuntime { it.copy(retime = null, wakeShown = n.identity, manualWakeShown = true,
                reason = text(R.string.runtime_manual_tracking)) }
            NightAlarms.schedule(context, n)
            Notifications.showWakeUp(context, n, repo.settings.value, message, logNotice = false)
        }
        return result
    }
    private suspend fun boostLocked(): CommandOutcome {
        val s = repo.settings.value
        val steps = PlanCompiler.phaseSteps(Phase(Mode.TURBO, 86, 100, s.boostMinutes), limits(), TurboChain.TURBO_THEN_HEAT)
        val result = writeSlotLocked(s.workingSlot, StoredProgram("Boost", steps), text(R.string.runtime_boost, s.boostMinutes), start = true)
        if (result.succeeded) repo.activeNight.value?.let { finishLocked(it, EventKind.STOPPED, result.message) }
        return result
    }
    suspend fun finish(night: ActiveNight, kind: EventKind, detail: String) = lock.withLock {
        if (repo.activeNight.value?.identity == night.identity) finishLocked(night, kind, detail)
    }
    private suspend fun finishLocked(night: ActiveNight, kind: EventKind, detail: String) {
        val transition = io.github.willzy12h.nocturne.core.night.stopTracking(night, detail, graph.now(), repo.runtime.value.generation)
        repo.updateRuntime { it.copy(ending = PendingEnd(night.identity, event(kind, detail)), retime = null,
            resumeOffer = if (kind == EventKind.ENDED) null else transition.resumeOffer, reason = null,
            wakeShown = null, manualWakeShown = false) }
        completeLocalEnd()
        transition.resumeOffer?.takeIf { kind != EventKind.ENDED }?.let { Notifications.showResume(context) }
    }
    /** Complete an idempotent local journal after a crash between the separate durable files. */
    private suspend fun completeLocalEnd() {
        val end = repo.runtime.value.ending ?: return
        val active = repo.activeNight.value
        if (active == null || active.identity == end.identity) {
            val events = repo.history.value.firstOrNull { it.id == end.identity.nightId }?.events.orEmpty()
            if (end.event !in events) repo.addEvent(end.identity.nightId, end.event)
            publishNight(null)
            repo.updateSettings { it.clearWakeOverride() }
            NightAlarms.cancel(context)
            Notifications.cancelWakeUp(context)
            Notifications.cancelQuiet(context)
        }
        repo.updateRuntime { it.copy(ending = null) }
    }
    private suspend fun stopTrackingLocked(): CommandOutcome {
        val n = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
        advanceGeneration()
        finishLocked(n, EventKind.STOPPED, text(R.string.runtime_stop_tracking))
        return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_stop_tracking))
    }
    suspend fun expire(expected: NightIdentity? = repo.activeNight.value?.identity): Boolean = lock.withLock { expireLocked(expected) }
    private suspend fun expireLocked(expected: NightIdentity?): Boolean {
        val n = repo.activeNight.value ?: return false
        if (n.identity != expected || io.github.willzy12h.nocturne.core.night.expire(n, graph.now()) == null) return false
        advanceGeneration()
        finishLocked(n, EventKind.ENDED, text(R.string.runtime_ended)); return true
    }
    val retimePending: Boolean get() = repo.runtime.value.retime != null || repo.runtime.value.alarmInbox != null
    suspend fun retryPendingRetime(): String? = if (!retimePending) null else owned(label(Command.RETIME), repo.settings.value.deviceId(), 30_000) { consumeAlarmObservation(); retryRetimeLocked() }.message
    /** A coalesced observation inbox, not a second command owner. This only uses the repository writer.
     * The receiver can submit while a whole hardware transaction holds the coordinator mutex. */
    suspend fun submitAlarmChange(): CommandOutcome {
        val night = repo.activeNight.value
        if (night == null) {
            withTimeout(8_000) { repo.updateRuntime { if (repo.activeNight.value == null) it.copy(alarmInbox = null, retime = null) else it } }
            if (!retimePending) NightAlarms.cancelRetry(context)
            return refused(R.string.runtime_no_night)
        }
        if (!graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))) {
            repo.updateRuntime { it.copy(alarmInbox = null, retime = null) }
            NightAlarms.cancelRetry(context)
            return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_unchanged))
        }
        val device = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
        val observation = AlarmObservation(UUID.randomUUID().toString(), night.recordId, device, graph.now().toString())
        NightAlarms.scheduleObservation(context, graph.now())
        var expired = false
        withTimeout(8_000) {
            repo.updateRuntime {
                // This expires only submission metadata, never active state or a hardware transaction.
                expired = repo.activeNight.value?.identity == night.identity && !graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))
                if (expired) it.copy(alarmInbox = null, retime = null)
                else it.copy(alarmInbox = observation)
            }
        }
        if (expired) {
            NightAlarms.cancelRetry(context)
            return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_unchanged))
        }
        return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_pending))
    }
    suspend fun recordPendingRetime(): CommandOutcome = submitAlarmChange()
    private suspend fun consumeAlarmObservation() {
        val observed = repo.runtime.value.alarmInbox ?: return
        val night = repo.activeNight.value
        if (night?.recordId == observed.nightId && repo.settings.value.deviceId() == observed.deviceId) {
            if (!graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))) {
                repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_retime_expired)) }
            } else queueRetimeLocked()
        }
        repo.updateRuntime { if (it.alarmInbox?.id == observed.id) it.copy(alarmInbox = null) else it }
    }
    private fun hasUnfinishedReplacement(night: ActiveNight): Boolean =
        repo.runtime.value.slotWrite?.let { it.startRequested && it.displaced == night.identity } == true
    private suspend fun queueRetimeLocked(): CommandOutcome {
        val n = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
        if (hasUnfinishedReplacement(n) || n.trackingManual || n.confidence != Confidence.CONFIRMED || n.wakeOrigin == WakeOrigin.OVERRIDE) return refused(R.string.runtime_unchanged)
        val alarm = AlarmSource.describe(context, repo.settings.value)
        if (alarm?.counts == false) return refused(R.string.runtime_unchanged)
        val deadline = ZonedDateTime.parse(n.wakeUpStart)
        if (!graph.now().isBefore(deadline)) return refused(R.string.runtime_retime_expired)
        val prepared = graph.runner().prepareRetime(n, n.identity, alarm?.time, deadline, repo.profile(n.profileId), repo.settings.value, alarm?.creator)
        if (prepared !is Preparation.Ready) {
            repo.updateRuntime { it.copy(retime = null) }; NightAlarms.cancelRetry(context)
            return if (prepared is Preparation.Refused) CommandOutcome(OutcomeKind.REFUSED, prepared.reason) else stale()
        }
        val pending = PendingRetime(UUID.randomUUID().toString(), n.identity, repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target),
            alarm?.time?.toString(), alarm?.creator, deadline.toString())
        repo.updateRuntime { it.copy(retime = pending, reason = text(R.string.runtime_retime_pending)) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_pending))
    }
    private suspend fun retryRetimeLocked(): CommandOutcome {
        val pending = repo.runtime.value.retime ?: return if (repo.runtime.value.reason == text(R.string.runtime_retime_expired)) refused(R.string.runtime_retime_expired) else refused(if (repo.activeNight.value == null) R.string.runtime_no_night else R.string.runtime_unchanged)
        val n = repo.activeNight.value
        if (!graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
            repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_retime_expired)) }
            NightAlarms.cancelRetry(context)
            Notifications.showError(context, text(R.string.runtime_retime_expired), text(R.string.runtime_retime_expired_detail))
            return refused(R.string.runtime_retime_expired)
        }
        if (n?.identity != pending.expected || hasUnfinishedReplacement(n) || n.trackingManual || n.confidence != Confidence.CONFIRMED || pending.deviceId != repo.settings.value.deviceId()) {
            repo.updateRuntime { it.copy(retime = null) }; NightAlarms.cancelRetry(context); return stale()
        }
        return try {
            connected {
                // A remote Off or manual change may have happened while disconnected. A local
                // CONFIRMED night is not evidence that the BedJet is still running our programme.
                val fresh = freshStatus()
                if (!matchesCurrentProgramme(n, fresh, graph.now())) {
                    publishNight(n.copy(confidence = Confidence.UNKNOWN))
                    repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_takeover)) }
                    repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
                    NightAlarms.cancelRetry(context)
                    Notifications.showInfo(context, text(R.string.runtime_takeover_title), text(R.string.runtime_takeover))
                    return@connected CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_takeover))
                }
                val result = apply(graph.runner().prepareRetime(n, pending.expected, pending.alarmAt?.let(ZonedDateTime::parse),
                    ZonedDateTime.parse(pending.deadline), repo.profile(n.profileId), repo.settings.value, pending.alarmOrigin),
                    R.string.runtime_retimed, afterWrite = {
                        // A physical Off can arrive during the upload while status observation is held
                        // behind transaction ownership. Reconnect to demand a fresh hardware report.
                        if (!matchesCurrentProgramme(n, freshStatus(), graph.now())) throw RetimeTakeover()
                    })
                if (!result.succeeded) { repo.updateRuntime { it.copy(retime = null, reason = result.message) }; NightAlarms.cancelRetry(context) }
                result
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val current = repo.activeNight.value
            if (current?.identity != pending.expected || current.confidence in setOf(Confidence.UNKNOWN, Confidence.FAILED)) {
                repo.updateRuntime { it.copy(retime = null, reason = e.message) }
                NightAlarms.cancelRetry(context)
                return CommandOutcome(if (current?.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                    e.message ?: text(R.string.runtime_failed))
            }
            repo.updateRuntime { it.copy(reason = text(R.string.runtime_retime_pending)) }
            NightAlarms.scheduleRetry(context, pending, graph.now())
            recordError(e.message.orEmpty())
            CommandOutcome(OutcomeKind.FAILED, text(R.string.runtime_retime_pending))
        }
    }
    private suspend fun resumeLocked(): CommandOutcome {
        val offer = repo.runtime.value.resumeOffer ?: return refused(R.string.runtime_resume_expired)
        if (offer.night.deviceId != repo.settings.value.deviceId()) return stale()
        val initial = graph.runner().resume(offer, graph.now(), repo.runtime.value.generation)
        val current = repo.activeNight.value
        val prepared = if (initial is Preparation.Ready && current != null) Preparation.Ready(initial.change.copy(
            previous = current, request = initial.change.request.copy(expected = current.identity))) else initial
        val result = apply(prepared, R.string.runtime_resumed)
        if (result.succeeded) advanceGeneration()
        return result
    }
    private suspend fun keepTrackingLocked(): CommandOutcome = connected {
        val night = repo.activeNight.value ?: return@connected refused(R.string.runtime_no_night)
        if (night.confidence != Confidence.UNKNOWN) return@connected refused(R.string.runtime_ineligible)
        val fresh = freshStatus()
        if (!fresh.isRunning) return@connected refused(R.string.runtime_reconcile_needed)
        advanceGeneration()
        val minutes = ((fresh.remainingSeconds + 59) / 60).coerceAtLeast(1)
        val step = ProgramStep(fresh.mode, fresh.fanStep, fresh.targetTemperature, minutes / 60, minutes % 60)
        val plan = Plan(graph.now(), ZonedDateTime.parse(night.wakeAt), listOf(PlannedStep(Block.MIDDLE, step, graph.now(), minutes)))
        val updated = night.copy(confidence = Confidence.CONFIRMED, pending = null, trackingManual = true,
            revision = night.revision + 1, endsAt = graph.now().plusSeconds(fresh.remainingSeconds.toLong()).toString(),
            applied = AppliedProgram.from(plan, night.revision + 1))
        publishNight(updated)
        repo.history.value.firstOrNull { it.id == night.recordId }?.let { repo.addRecord(NightMutation.Applied(plan, updated).recordRevision(it)) }
        repo.updateRuntime { it.copy(retime = null, reason = null,
            wakeShown = if (it.manualWakeShown && it.wakeShown == night.identity) updated.identity else it.wakeShown) }
        repo.addEvent(night.recordId, event(EventKind.RECOVERED, text(R.string.runtime_manual_tracking)))
        NightAlarms.schedule(context, updated)
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_manual_tracking))
    }
    private suspend fun freshStatus(): DeviceStatus {
        // Reconnect under whole-command ownership. Existing READY status may predate this operation.
        graph.link.disconnect()
        graph.link.connect()
        return withTimeout(10_000) { graph.link.status.filterNotNull().first { it.extra != null } }
    }
    /** Actual received wake/end signals only. Startup never invents historical deliveries. */
    suspend fun submitLocalDelivery(action: String, expected: NightIdentity?) {
        if (expected == null || action !in setOf(NightAlarms.ACTION_WAKE_UP, NightAlarms.ACTION_NIGHT_END)) return
        if (repo.activeNight.value?.identity != expected) return
        NightAlarms.scheduleLocalRetry(context)
        withTimeout(8_000) {
            val received = LocalDelivery(UUID.randomUUID().toString(), action, expected, graph.now().toString())
            repo.updateRuntime {
                if (repo.activeNight.value?.identity != expected) it
                else it.copy(localDeliveries = it.localDeliveries.filter { old -> old.action != action } + received)
            }
        }
        NightAlarms.scheduleLocalRetry(context)
        drainLocalDeliveries()
    }
    /** Never wait behind hardware ownership in a receiver. Durable retry and owner-finally handle contention. */
    suspend fun drainLocalDeliveries() {
        if (repo.runtime.value.localDeliveries.isEmpty()) return
        NightAlarms.scheduleLocalRetry(context)
        val owner = Any()
        if (!lock.tryLock(owner)) return
        try { consumeLocalDeliveriesLocked() } finally { lock.unlock(owner) }
    }
    private suspend fun drainLocalSafely() {
        if (repo.runtime.value.localDeliveries.isEmpty()) return
        try { withContext(NonCancellable) { withTimeout(8_000) { consumeLocalDeliveriesLocked() } } }
        catch (_: Exception) { NightAlarms.scheduleLocalRetry(context) }
    }
    private suspend fun consumeLocalDeliveriesLocked() {
        for (delivery in repo.runtime.value.localDeliveries) {
            if (delivery.action == NightAlarms.ACTION_WAKE_UP) wakeUpLocked(delivery.expected)
            else if (delivery.action == NightAlarms.ACTION_NIGHT_END) expireLocked(delivery.expected)
            repo.updateRuntime { it.copy(localDeliveries = it.localDeliveries.filterNot { item -> item.id == delivery.id }) }
        }
        if (repo.runtime.value.localDeliveries.isEmpty()) NightAlarms.cancelLocalRetry(context)
        else NightAlarms.scheduleLocalRetry(context)
    }
    suspend fun reconcile() {
        lock.withLock {
            completeLocalEnd()
            completeWakeSourceChange()
            val displaced = repo.runtime.value.slotWrite?.takeIf { it.startRequested }?.displaced
            repo.activeNight.value?.takeIf { it.identity == displaced }?.let { previous ->
                publishNight(previous.copy(confidence = Confidence.UNKNOWN))
                repo.updateRuntime { it.copy(reason = text(R.string.runtime_interrupted), retime = null) }
                Notifications.cancelQuiet(context)
            }
            val n = repo.activeNight.value
            // A process died with an uncompleted intention. Never replay a possibly partial upload.
            if (n?.confidence == Confidence.PENDING) {
                publishNight(n.copy(confidence = Confidence.UNKNOWN))
                repo.updateRuntime { it.copy(reason = text(R.string.runtime_interrupted), retime = null) }
            }
            repo.runtime.value.slotWrite?.takeIf { it.confidence == Confidence.PENDING }?.let { slot ->
                repo.updateRuntime { it.copy(slotWrite = slot.copy(confidence = Confidence.UNKNOWN), reason = text(R.string.runtime_interrupted)) }
            }
            repo.runtime.value.resumeOffer?.takeIf { !graph.now().isBefore(ZonedDateTime.parse(it.expiresAt)) }?.let {
                repo.updateRuntime { it.copy(resumeOffer = null) }
            }
        }
        drainLocalDeliveries()
        expire()
        repo.activeNight.value?.let { NightAlarms.schedule(context, it); Notifications.showQuiet(context, it, repo.settings.value) }
        repo.runtime.value.retime?.let { NightAlarms.scheduleRetry(context, it, graph.now()) }
        if (repo.runtime.value.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
    }
    suspend fun wakeUp(expected: NightIdentity?) = lock.withLock { wakeUpLocked(expected) }
    private suspend fun wakeUpLocked(expected: NightIdentity?) {
        val n = repo.activeNight.value ?: return
        if (n.identity != expected || n.trackingManual || n.confidence != Confidence.CONFIRMED || repo.runtime.value.wakeShown == n.identity ||
            graph.now().isBefore(ZonedDateTime.parse(n.wakeUpStart)) || !graph.now().isBefore(ZonedDateTime.parse(n.endsAt))) return
        repo.updateRuntime { it.copy(wakeShown = n.identity, manualWakeShown = false) }
        Notifications.showWakeUp(context, n, repo.settings.value)
    }
    suspend fun observeStatus(status: DeviceStatus, target: String?, receivedAt: ZonedDateTime = graph.now(), source: String? = target) {
        // Intermediate reports during our own upload/manual packets must not become a takeover after queuing.
        if (lock.isLocked) return
        lock.withLock {
            // [source] is the unit that reported [status]; one from another BedJet is never this target's state.
            if (target != repo.settings.value.deviceId() || !DeviceId.same(source, target) ||
                graph.link.state.value != LinkState.READY) return@withLock
            val n = repo.activeNight.value ?: return@withLock
            val extra = status.extra ?: return@withLock
            if (extra.notification != 0 && lastNotice != (n.recordId to extra.notification)) {
                lastNotice = n.recordId to extra.notification
                repo.addEvent(n.recordId, event(EventKind.UNIT_NOTICE, text(R.string.runtime_unit_notice, extra.notification)))
            } else if (extra.notification == 0) lastNotice = null
            if (n.confidence != Confidence.CONFIRMED || n.trackingManual) return@withLock
            if (matchesCurrentProgramme(n, status, receivedAt)) return@withLock
            publishNight(n.copy(confidence = Confidence.UNKNOWN))
            repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_takeover)) }
            repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
            NightAlarms.cancelRetry(context)
            Notifications.showInfo(context, text(R.string.runtime_takeover_title), text(R.string.runtime_takeover))
        }
    }
    private fun matchesCurrentProgramme(night: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
        if (!status.isRunning) return false
        val plan = night.activePlan(repo.profile(night.profileId)) ?: return false
        val current = plan.stepAt(at) ?: return false
        val extra = status.extra ?: return false
        if (extra.programStep == 0) return false
        fun matches(index: Int): Boolean {
            val step = plan.steps.getOrNull(index)?.step ?: return false
            return extra.programStep == index + 1 && status.mode == step.mode && status.fanStep == step.fanStep &&
                (!status.mode.usesTemperature || status.targetTemperature == step.temperature)
        }
        if (matches(current.first)) return true
        // During a step transition only the immediately previous programme step is plausible.
        // A different mode or temperature is still a physical takeover, even in this grace period.
        return at.isBefore(current.second.startsAt.plusSeconds(20)) && matches(current.first - 1)
    }
    private var lastNotice: Pair<String, Int>? = null
    suspend fun pin(profileId: String, slot: Int, expectedTarget: String? = null,
        expectedProfile: Profile? = null, expectedWorkingSlot: Int? = null,
        previewedAt: ZonedDateTime? = null, expectedProgram: StoredProgram? = null): CommandOutcome =
        owned(text(R.string.runtime_pin_label), repo.settings.value.deviceId()) {
        fun previewExpired(): Boolean = previewedAt?.let {
            Duration.between(it.toInstant(), graph.now().toInstant()).let { age -> age.isNegative || age >= Duration.ofMinutes(1) }
        } ?: false
        if (expectedTarget != null && repo.settings.value.deviceId() != expectedTarget) return@owned stale()
        if (expectedWorkingSlot != null && repo.settings.value.workingSlot != expectedWorkingSlot) return@owned stale()
        if (expectedProfile != null && repo.profile(profileId) != expectedProfile) return@owned stale()
        if (previewExpired()) return@owned stale(R.string.pin_preview_expired)
        if (repo.activeNight.value != null) return@owned refused(R.string.runtime_restore_active)
        if (slot !in 0..2 || slot == repo.settings.value.workingSlot) return@owned refused(R.string.runtime_pin_slot)
        val profile = repo.profile(profileId) ?: return@owned refused(R.string.runtime_profile_deleted)
        connected {
            if (freshStatus().extra?.programStep != 0) return@connected refused(R.string.runtime_slot_running)
            val preview = NightPreview.forStartNow(profile, graph.now(), null, settings = repo.settings.value)
            val plan = preview.plan ?: return@connected CommandOutcome(OutcomeKind.REFUSED, preview.refusalReason.orEmpty())
            val steps = plan.steps.map { it.step.copy(hours = it.minutes / 60, minutes = it.minutes % 60, endsAtClockTime = false) }
            val program = StoredProgram(profile.name, steps)
            if (previewExpired() || (expectedProgram != null && program != expectedProgram))
                return@connected stale(R.string.pin_preview_expired)
            advanceGeneration()
            writeSlotLocked(slot, program, text(R.string.runtime_pinned, slot + 1))
        }
    }
    suspend fun restoreOriginal(slot: Int): CommandOutcome = owned(text(R.string.runtime_restore_label), repo.settings.value.deviceId()) {
        if (!ReplacementPolicy.permitsSlotRestore(repo.activeNight.value)) return@owned refused(R.string.runtime_restore_active)
        if (slot !in 0..2) return@owned refused(R.string.runtime_pin_slot)
        val target = repo.settings.value.deviceId() ?: return@owned refused(R.string.runtime_choose_target)
        repo.originalsFor(target)
        val original = repo.originalSlot(target, slot) ?: return@owned refused(R.string.runtime_no_original)
        connected {
            if (freshStatus().extra?.programStep != 0) return@connected refused(R.string.runtime_slot_running)
            advanceGeneration()
            writeSlotLocked(slot, original.program, text(R.string.runtime_restored, slot + 1))
        }
    }
    private suspend fun writeSlotLocked(slot: Int, program: StoredProgram?, label: String, start: Boolean = false): CommandOutcome {
        val target = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
        val intent = SlotWrite(target, slot, program, label = label, startRequested = start,
            displaced = if (start) repo.activeNight.value?.identity else null)
        repo.updateRuntime { it.copy(slotWrite = intent) }
        var writing = false
        try {
            backup(target, slot)
            writing = true
            if (program != null) graph.controller().store(program, slot) else {
                graph.link.send(Codec.deleteProgram(slot))
                if (graph.controller().storedPrograms()[slot] != null) throw BedJetError.VerifyFailed()
            }
            if (start) graph.controller().startProgram(slot)
            repo.updateRuntime { it.copy(slotWrite = intent.copy(confidence = Confidence.CONFIRMED)) }
            return CommandOutcome(OutcomeKind.CONFIRMED, label)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val confidence = if (writing) Confidence.UNKNOWN else Confidence.FAILED
                // No slot write happened after a backup failure. Keep the error message,
                // but do not leave a false uncertain slot intent blocking future automation.
                repo.updateRuntime { it.copy(slotWrite = if (writing) intent.copy(confidence = confidence) else null, reason = e.message) }
                if (start) repo.activeNight.value?.let { publishNight(it.copy(confidence = confidence)) }
            }
            throw e
        }
    }
    suspend fun initialise() {
        lock.withLock {
            if (repo.settings.value.targetDevice == null) graph.bleLink.rememberedAddress()?.let { address ->
                repo.updateSettings { it.copy(targetDevice = DeviceId.ble(address)) }
                repo.addNotice(LoggedNotice(graph.now().toString(), NoticeKind.INFO, text(R.string.runtime_target_saved), text(R.string.runtime_adopted)))
            }
        }
        reconcile()
    }
    suspend fun setWakeOverride(wake: ZonedDateTime?, expected: NightIdentity? = repo.activeNight.value?.identity): CommandOutcome =
        owned(text(R.string.runtime_override_label), repo.settings.value.deviceId()) {
        val n = repo.activeNight.value
        if (n?.identity != expected) return@owned stale()
        if (n != null && (n.confidence != Confidence.CONFIRMED || n.trackingManual)) return@owned refused(R.string.runtime_reconcile_needed)
        if (n == null) {
            repo.updateSettings { if (wake == null) it.clearWakeOverride() else it.setWakeOverride(wake, graph.now()) }
            return@owned CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_settings_saved))
        }
        val profile = n.profile ?: repo.profile(n.profileId) ?: return@owned refused(R.string.runtime_profile_deleted)
        val alarm = AlarmSource.describe(context, repo.settings.value)
        if (wake == null) {
            val fallback = NightPreview.forStartNow(profile, ZonedDateTime.parse(n.startedAt), alarm?.time,
                settings = repo.settings.value, alarmOrigin = alarm?.creator).wake
            if (fallback.time.isEqual(ZonedDateTime.parse(n.wakeAt))) {
                advanceGeneration()
                repo.updateRuntime { it.copy(wakeSourceChange = WakeSourceChange(n.identity, fallback.origin)) }
                completeWakeSourceChange()
                return@owned CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_override_cleared))
            }
        }
        val prepared = if (wake != null) graph.runner().reapply(n, profile, n.identity, wake)
            else graph.runner().prepareRetime(n.copy(wakeOrigin = WakeOrigin.PROFILE_DEFAULT), n.identity,
                alarm?.time, liveProfile = profile, settings = repo.settings.value, alarmOrigin = alarm?.creator)
        if (prepared is Preparation.Refused) return@owned CommandOutcome(OutcomeKind.REFUSED, prepared.reason)
        connected {
            advanceGeneration()
            repo.updateSettings { if (wake == null) it.clearWakeOverride() else it.setWakeOverride(wake, graph.now()) }
            apply(prepared, R.string.runtime_reapplied)
        }
    }
    private suspend fun completeWakeSourceChange() {
        val change = repo.runtime.value.wakeSourceChange ?: return
        val n = repo.activeNight.value
        if (n?.identity == change.expected) {
            repo.updateSettings { it.clearWakeOverride() }
            publishNight(n.copy(wakeOrigin = change.origin, wakeFromAlarm = change.origin == WakeOrigin.ALARM))
        }
        repo.updateRuntime { if (it.wakeSourceChange == change) it.copy(wakeSourceChange = null) else it }
    }
    suspend fun updateSettings(change: (Settings) -> Settings): CommandOutcome = owned(text(R.string.runtime_target_label), repo.settings.value.deviceId()) {
        val old = repo.settings.value
        val updated = change(old)
        if (updated.deviceId() != old.deviceId() || updated.targetDevice != old.targetDevice) {
            if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
            advanceGeneration(); graph.link.disconnect()
        }
        repo.updateSettings { updated }
        refreshQuietNotification()
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_settings_saved))
    }
    suspend fun selectTarget(deviceId: String?, useRealDevice: Boolean = false): CommandOutcome = owned(text(R.string.runtime_target_label), repo.settings.value.deviceId()) {
        if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
        if (deviceId != null && DeviceId.address(deviceId)?.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) != true) return@owned refused(R.string.runtime_invalid_target)
        advanceGeneration()
        graph.link.disconnect()
        // Setup must not expose a real target while Demo is still selected, or vice versa.
        repo.updateSettings { it.copy(targetDevice = deviceId, demoMode = if (useRealDevice) false else it.demoMode,
            setupFanCheckedDevice = it.setupFanCheckedDevice.takeIf { checked -> deviceId != null && checked == deviceId }) }
        if (deviceId == null) graph.bleLink.forget()
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_target_saved))
    }
    suspend fun setDemoMode(enabled: Boolean): CommandOutcome = owned(text(R.string.runtime_demo_label), repo.settings.value.deviceId()) {
        if (repo.activeNight.value != null && enabled != repo.settings.value.demoMode) return@owned refused(R.string.runtime_active_target)
        advanceGeneration()
        graph.link.disconnect()
        repo.updateSettings { it.copy(demoMode = enabled) }
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_demo_saved))
    }
    suspend fun forgetTarget(): CommandOutcome = selectTarget(null)
    suspend fun setupFanCheck(): CommandOutcome = owned(text(R.string.runtime_fan_check), repo.settings.value.deviceId()) {
        if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
        connected {
            val initial = freshStatus()
            if (initial.isRunning || initial.extra?.programStep != 0) return@connected refused(R.string.runtime_fan_refused)
            val changed = if (initial.fanStep < 19) initial.fanStep + 1 else initial.fanStep - 1
            try { graph.controller().setFan(changed) }
            finally { withContext(NonCancellable) { withTimeout(5_000) { graph.controller().setFan(initial.fanStep) } } }
            repo.settings.value.takeIf { !it.demoMode }?.targetDevice?.let { target ->
                repo.updateSettings { it.copy(setupFanCheckedDevice = target) }
            }
            CommandOutcome(OutcomeKind.ACCEPTED, text(R.string.runtime_fan_checked))
        }
    }
    suspend fun releaseForOtherPhone(untilMs: Long? = null, declineIfBusy: Boolean = false): CommandOutcome {
        if (declineIfBusy && lock.isLocked) return refused(R.string.runtime_busy)
        return owned(text(R.string.runtime_release_label), repo.settings.value.deviceId()) {
            // Handover invalidates queued commands, but an existing recovery reason and
            // short-lived resume offer remain meaningful when this phone releases the link.
            repo.updateRuntime { it.copy(generation = it.generation + 1) }
            graph.sessions.suspendAutoConnect(untilMs).join()
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_released))
        }
    }
    suspend fun takeBack(): CommandOutcome = owned(text(R.string.runtime_take_back), repo.settings.value.deviceId()) {
        graph.sessions.resumeAutoConnect()
        connected {
            val status = freshStatus()
            // Uncertain hardware is never replayed. The user can keep a fresh manual run or stop tracking.
            repo.activeNight.value?.let { n ->
                val step = n.activePlan(repo.profile(n.profileId))?.stepAt(graph.now())
                if (step == null || status.extra?.programStep != step.first + 1 || status.mode != step.second.step.mode ||
                    (status.mode.usesTemperature && status.targetTemperature != step.second.step.temperature)) {
                    publishNight(n.copy(confidence = Confidence.UNKNOWN))
                    repo.updateRuntime { it.copy(reason = text(R.string.runtime_takeover), retime = null) }
                    repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
                }
            }
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_taken_back))
        }
    }
    suspend fun remote(label: String, endsNight: Boolean = false, affectsProgramme: Boolean = false,
        target: String? = repo.settings.value.deviceId(), expected: NightIdentity? = repo.activeNight.value?.identity,
        work: suspend (BedJetController) -> Unit): CommandOutcome = owned(label, target) {
        if (endsNight && expected != repo.activeNight.value?.identity) return@owned stale()
        connected { advanceGeneration(); manualChange(label, endsNight, affectsProgramme) { work(graph.controller()) } }
    }
    suspend fun exportDiagnostics(uri: android.net.Uri) {
        val snapshot = diagnostics()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(snapshot.toByteArray()) }
                ?: throw java.io.IOException(text(R.string.runtime_export_failed))
        }
    }
    fun diagnostics(): String = kotlinx.serialization.json.buildJsonObject {
        put("version", kotlinx.serialization.json.JsonPrimitive(io.github.willzy12h.nocturne.BuildConfig.VERSION_NAME))
        put("deviceModel", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL))
        put("linkState", kotlinx.serialization.json.JsonPrimitive(graph.link.state.value.name))
        put("settings", BackupCodec.json.encodeToJsonElement(Settings.serializer(), repo.settings.value))
        put("activeNight", repo.activeNight.value?.let { BackupCodec.json.encodeToJsonElement(ActiveNight.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
        put("events", BackupCodec.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(HistoryEvent.serializer()), repo.history.value.flatMap { it.events }.sortedByDescending { it.at }.take(50)))
        put("notices", BackupCodec.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LoggedNotice.serializer()), repo.notices.value.take(50)))
    }.toString()

    private suspend fun publishNight(night: ActiveNight?) {
        repo.setActiveNight(night)
        refreshQuietNotification()
    }
    internal fun refreshQuietNotification() {
        val night = repo.activeNight.value
        if (night == null) Notifications.cancelQuiet(context)
        else Notifications.showQuiet(context, night, repo.settings.value)
    }
    private fun event(kind: EventKind, detail: String) = HistoryEvent(graph.now().toString(), kind, detail)
    private suspend fun recordError(detail: String) { repo.activeNight.value?.let { repo.addEvent(it.recordId, event(EventKind.ERROR, detail)) } }
    private fun limits(): ProfileLimits = (repo.activeNight.value?.let { it.profile ?: repo.profile(it.profileId) } ?: repo.tonightsProfile())?.limits ?: ProfileLimits()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun refused(id: Int) = CommandOutcome(OutcomeKind.REFUSED, text(id))
    private fun stale(id: Int = R.string.runtime_stale) = CommandOutcome(OutcomeKind.STALE, text(id))
    private fun label(command: Command) = commandLabel(context, command)
    private fun clock(iso: String) = ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("HH:mm"))
    companion object {
        const val OPERATION_MS = 90_000L
        fun commandLabel(context: android.content.Context, command: Command): String = context.getString(when (command) {
            Command.START_NIGHT -> R.string.runtime_command_start_night
            Command.END_NIGHT -> R.string.runtime_command_end_night
            Command.OFF -> R.string.runtime_command_off
            Command.TURBO -> R.string.runtime_command_turbo
            Command.BOOST -> R.string.runtime_command_boost
            Command.COOL -> R.string.runtime_command_cool
            Command.WARM -> R.string.runtime_command_warm
            Command.RETIME -> R.string.runtime_command_retime
            Command.RETRY_RETIME -> R.string.runtime_command_retry_retime
            Command.REAPPLY -> R.string.runtime_command_reapply
            Command.SNOOZE_WARM -> R.string.runtime_command_snooze_warm
            Command.SNOOZE_COOL -> R.string.runtime_command_snooze_cool
            Command.EXTEND_WARM -> R.string.runtime_command_extend_warm
            Command.EXTEND_COOL -> R.string.runtime_command_extend_cool
            Command.RESUME -> R.string.runtime_command_resume
            Command.STOP_TRACKING -> R.string.runtime_command_stop_tracking
            Command.KEEP_TRACKING -> R.string.runtime_command_keep_tracking
        })
    }
}
