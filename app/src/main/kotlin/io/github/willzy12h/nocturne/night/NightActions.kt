package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.SlotBackupsUnreadable
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
    /** Monotonic milliseconds for the admission check; tests move it by hand. */
    internal var elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }
    private val _busy = MutableStateFlow(BusyState())
    val busy: StateFlow<BusyState> = _busy.asStateFlow()
    private val _outcomes = MutableSharedFlow<CommandOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<CommandOutcome> = _outcomes.asSharedFlow()
    /** A slot write colours the status only while it is in question and concerns the tracked night, or, with no night,
     * only when a night could depend on it; an unknown pin of another slot is reported by its own outcome (IR-06). */
    val status: StateFlow<NightStatus> = combine(repo.activeNight, repo.runtime, repo.settings) { n, r, s ->
        val slot = r.unresolvedSlotWrite?.takeIf { if (n == null) it.affectsNights(s.workingSlot) else it.displaced == n.identity }
        NightStatus(if ((r.retime != null || r.alarmInbox != null) && n?.confidence == Confidence.CONFIRMED) Confidence.PENDING
            else slot?.confidence ?: n?.confidence, r.reason) }
        .stateIn(graph.scope, SharingStarted.Eagerly, NightStatus(repo.activeNight.value?.confidence, repo.runtime.value.reason))

    enum class Command { START_NIGHT, END_NIGHT, OFF, TURBO, BOOST, COOL, WARM, RETIME, RETRY_RETIME, REAPPLY, SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, RESUME, STOP_TRACKING, KEEP_TRACKING }
    fun capture(command: Command, profileId: String? = null, requireIdentity: Boolean = false) =
        CommandRequest(command, profileId, repo.settings.value.deviceId(), repo.activeNight.value?.identity, requireIdentity)

    /** Off resolves the captured target even when a notification/widget carries an older night identity. */
    suspend fun perform(command: Command, profileId: String? = null, replacementConfirmed: Boolean = false): String =
        execute(capture(command, profileId, requireIdentity = replacementConfirmed).copy(replacementConfirmed = replacementConfirmed)).message
    /** Every command that may reach the BedJet is admitted only with [MIN_BUDGET_MS] left; Stop tracking is local (IR-11). */
    suspend fun execute(request: CommandRequest): CommandOutcome = owned(label(request.command), request.deviceId,
        minimumRemainingMs = if (request.command != Command.STOP_TRACKING) MIN_BUDGET_MS else 0,
        retimeWork = request.command == Command.RETIME || request.command == Command.RETRY_RETIME) {
        if (request.command != Command.OFF && request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
        if (ReplacementPolicy.decide(request.command, repo.activeNight.value).needsConfirmation && !request.replacementConfirmed)
            return@owned CommandOutcome(OutcomeKind.CONFIRMATION_REQUIRED, text(R.string.runtime_replace_confirmation))
        when (request.command) {
            Command.STOP_TRACKING -> stopTrackingLocked()
            Command.KEEP_TRACKING -> keepTrackingLocked()
            Command.RETRY_RETIME -> { consumeAlarmObservation(); retryRetimeLocked() }
            Command.RETIME -> {
                consumeAlarmObservation()
                val previous = repo.runtime.value.retime?.id
                val queued = queueRetimeLocked()
                // A quiet no-op must not execute an older pending request merely because its outcome is LOCAL.
                if (queued.kind == OutcomeKind.LOCAL && repo.runtime.value.retime?.id?.let { it != previous } == true)
                    retryRetimeLocked() else queued
            }
            else -> slotWriteRefusal(request.command) ?: connected { run(request) }
        }
    }
    /** No FGS, detached launch or retry. Job cancellation propagates after durable uncertainty is saved. */
    suspend fun performJobOwned(request: CommandRequest, eligible: suspend (DeviceStatus) -> Boolean): CommandOutcome =
        owned(label(request.command), request.deviceId, minimumRemainingMs = MIN_BUDGET_MS) {
            if (request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
            slotWriteRefusal(request.command)?.let { return@owned it }
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

    private suspend fun owned(label: String, target: String?, minimumRemainingMs: Long = 0, retimeWork: Boolean = false,
        work: suspend () -> CommandOutcome): CommandOutcome {
        _busy.update { it.copy(queuedCount = it.queuedCount + 1) }
        var acquired = false
        val owner = Any()
        val startedAt = elapsedMs()
        try {
            return withTimeout(OPERATION_MS) {
                lock.lock(owner)
                acquired = true
                _busy.update { it.copy(runningLabel = label, queuedCount = it.queuedCount - 1) }
                val submitted = currentCoroutineContext()[SubmissionTarget]
                val remainingMs = OPERATION_MS - (elapsedMs() - startedAt)
                val result = if (minimumRemainingMs > 0 && remainingMs < minimumRemainingMs) waitedTooLong(retimeWork)
                else if (target != repo.settings.value.deviceId() ||
                    (submitted != null && submitted.deviceId != repo.settings.value.deviceId())) stale(R.string.runtime_target_changed) else work()
                _outcomes.emit(result)
                result
            }
        } catch (e: CancellationException) { throw e
        } catch (e: SlotBackupsUnreadable) {
            // Thrown before any slot write; the night's confidence is left as it was (IR-03).
            return refused(R.string.runtime_slot_backups_unreadable).also { _outcomes.emit(it) }
        } catch (e: Exception) {
            recordError("$label: ${e.message.orEmpty()}")
            // A slot write reports its own outcome; an old one in question says nothing about this failure (IR-06).
            return CommandOutcome(if (repo.activeNight.value?.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                e.message ?: text(R.string.runtime_failed)).also { _outcomes.emit(it) }
        } finally {
            // Ownership is tested even if timeout raced with resumption after lock.lock().
            _busy.update { if (acquired) it.copy(runningLabel = null) else it.copy(queuedCount = (it.queuedCount - 1).coerceAtLeast(0)) }
            if (lock.holdsLock(owner)) {
                try { drainLocalSafely() } finally { lock.unlock(owner) }
            }
        }
    }
    /** Only a refused re-time attempt may have consumed its alarm. Other queued taps leave that retry's time alone. */
    private fun waitedTooLong(retimeWork: Boolean): CommandOutcome {
        if (retimeWork) {
            repo.runtime.value.retime?.let { NightAlarms.scheduleRetry(context, it, graph.now()) }
            if (repo.runtime.value.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
        }
        return refused(R.string.runtime_waited_too_long)
    }
    /** IR-03: while the saved originals are unreadable, a slot with none is not written, and nothing is published or
     * sent first. Repository.recordOriginalSlot stays the final barrier. */
    private fun slotBackupsRefusal(slot: Int?): CommandOutcome? {
        val target = repo.settings.value.deviceId() ?: return null
        return if (slot != null && repo.originalsUnreadableFor(target, slot)) refused(R.string.runtime_slot_backups_unreadable) else null
    }
    /** [slotBackupsRefusal] for the commands that write a slot, checked before connecting. */
    private fun slotWriteRefusal(command: Command): CommandOutcome? = slotBackupsRefusal(when (command) {
        Command.START_NIGHT, Command.BOOST -> repo.settings.value.workingSlot
        Command.REAPPLY -> repo.activeNight.value?.slot
        Command.RESUME -> repo.runtime.value.resumeOffer?.night?.slot
        else -> null
    })
    private suspend fun <T> connected(work: suspend () -> T): T {
        check(repo.settings.value.deviceId() != null) { text(R.string.runtime_choose_target) }
        return graph.sessions.withConnection { work() }
    }
    /** Explicit programme replacement/reapply/end and target changes supersede the Resume choice. Manual changes
     * and auxiliaries preserve a valid offer, including one for the programme displaced by the current night. */
    private suspend fun advanceGeneration(preserveOffer: Boolean = false) {
        repo.updateRuntime { it.copy(generation = it.generation + 1, reason = null,
            resumeOffer = validResumeOffer(it)?.takeIf { preserveOffer }
                ?.copy(commandVersion = it.generation + 1)) }
    }
    private fun validResumeOffer(runtime: RuntimeState): ResumeOffer? = runtime.resumeOffer?.takeIf {
        it.commandVersion == runtime.generation && it.night.deviceId == repo.settings.value.deviceId() &&
            graph.now().isBefore(ZonedDateTime.parse(it.expiresAt)) && graph.now().isBefore(ZonedDateTime.parse(it.night.wakeAt))
    }
    /** [night] leaves its programme for a manual run: offer to resume the programme, as Stop tracking would. A night
     * already manual keeps the offer it has, since its own steps are not a programme to resume. */
    private suspend fun offerProgrammeResume(night: ActiveNight) {
        if (night.trackingManual) return
        val offer = io.github.willzy12h.nocturne.core.night.stopTracking(night, text(R.string.runtime_manual_tracking), graph.now(),
            repo.runtime.value.generation).resumeOffer
        // A manual adjustment does not supersede an earlier valid programme choice or renew its expiry.
        repo.updateRuntime { it.copy(resumeOffer = validResumeOffer(it) ?: offer) }
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
            takeoverBeforeRewrite(night)?.let { return it }
            advanceGeneration()
            return apply(graph.runner().reapply(night, profile, night.identity, repo.settings.value.resolveWakeOverride(graph.now())?.wakeAt?.let(ZonedDateTime::parse)), R.string.runtime_reapplied)
        }
        advanceGeneration(preserveOffer = command in TIMED_MANUAL)
        return when (command) {
            Command.OFF, Command.END_NIGHT -> manualChange(text(R.string.runtime_off), endsNight = true, confirms = { it.mode == Mode.STANDBY },
                unconfirmed = text(R.string.runtime_off_unconfirmed)) { graph.controller().stop() }
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
            is Preparation.Refused -> return if (preparation.unchanged) local(R.string.runtime_unchanged)
                else CommandOutcome(OutcomeKind.REFUSED, preparation.reason)
            is Preparation.Stale -> return stale()
        }
        val result = graph.runner().apply(change, { repo.activeNight.value }, { repo.runtime.value.generation },
            publish = {
                publishNight(it)
                // IR-23: a first start's record exists before the first write, with the steps it is to run.
                // The pending night of a fresh start carries no applied steps; its intended snapshot does.
                val intended = it.pending?.intended
                if (it.confidence == Confidence.PENDING && it.pending?.kind == ChangeKind.START && intended != null &&
                    repo.history.value.none { record -> record.id == it.recordId }) {
                    repo.addRecord(NightRecord(intended.recordId, intended.profileId, intended.profile!!.name, intended.startedAt,
                        intended.wakeAt, intended.wakeFromAlarm, intended.applied.steps, deviceId = intended.deviceId))
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
                // A first start's record was made before its write, so it already exists here; it still started.
                val started = existing == null || change.request.kind == ChangeKind.START
                repo.addEvent(n.recordId, event(if (started) EventKind.STARTED else EventKind.RETIMED, text(message, n.profile!!.name, clock(n.wakeAt))))
                // IR-06: a confirmed write of this BedJet slot settles an earlier write of it still in question.
                repo.updateRuntime { it.copy(retime = null, reason = null, wakeShown = null, manualWakeShown = false,
                    slotWrite = it.slotWrite?.takeUnless { w -> w.deviceId == n.deviceId && w.slot == n.slot }) }
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
                if (result.nothingWritten && retimeExpired(change)) {
                    // The backup/read can outlive the request. The old night is unchanged and no restart was sent.
                    expireRetimeFor(change.intended.recordId)
                    local(R.string.runtime_retime_expired)
                } else if (result.cause is SlotBackupsUnreadable) refused(R.string.runtime_slot_backups_unreadable)
                else if (result.cause is RetimeTakeover) {
                    val reason = text(R.string.runtime_takeover)
                    repo.updateRuntime { it.copy(reason = reason) }
                    repo.addEvent(change.intended.recordId, event(EventKind.REMOTE_CHANGE, reason))
                    Notifications.showInfo(context, text(R.string.runtime_takeover_title), reason)
                    CommandOutcome(OutcomeKind.UNKNOWN, reason, notificationHandled = true)
                } else {
                    repo.updateRuntime { it.copy(reason = result.cause.message) }
                    if (change.request.kind == ChangeKind.START)
                        repo.addEvent(change.intended.recordId, event(EventKind.ERROR, result.cause.message.orEmpty()))
                    else recordError(result.cause.message.orEmpty())
                    // The final check before writing ruled the change out for good (the request expired, its wake-up
                    // has begun): nothing was written and trying again cannot help, so it is refused, not retried. A
                    // check only this moment failed (a clock-time step about to end, the offset changing) stays a
                    // failure, which a re-time tries again within its deadline. Either way the reason and the
                    // night's record keep the error.
                    if (result.night.confidence == Confidence.FAILED && result.night.pending?.requestId == change.request.requestId)
                        // A first start already published this request as FAILED; its outcome must say the same.
                        CommandOutcome(OutcomeKind.FAILED, result.cause.message ?: text(R.string.runtime_failed))
                    else if (result.nothingWritten && result.cause is PlanException && result.cause !is RetryablePlanException)
                        CommandOutcome(OutcomeKind.REFUSED, result.cause.message.orEmpty())
                    // IR-05: nothing written means the night shown is the one still running, whatever its confidence.
                    else CommandOutcome(if (!result.nothingWritten && result.night.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                        result.cause.message ?: text(R.string.runtime_failed))
                }
            }
            is NightMutation.Refused -> if (retimeExpired(change)) {
                expireRetimeFor(change.intended.recordId)
                local(R.string.runtime_retime_expired)
            } else CommandOutcome(OutcomeKind.REFUSED, result.reason)
            is NightMutation.Stale -> stale()
        }
    }
    private fun retimeExpired(change: PreparedChange): Boolean = change.request.kind == ChangeKind.RETIME &&
        !graph.now().isBefore(ZonedDateTime.parse(change.request.deadline))
    internal suspend fun backup(device: String, slot: Int) {
        repo.originalsFor(device)
        val existing = repo.originalSlot(device, slot)
        val original = existing?.program ?: if (existing == null) graph.controller().storedPrograms()[slot] else null
        repo.recordOriginalSlot(device, slot, original)
    }
    private suspend fun quick(preset: ActionPreset, message: String) = manualChange(message, true, confirms = runs(preset)) { runPreset(preset) }
    private suspend fun runPreset(preset: ActionPreset) {
        val limits = limits()
        graph.controller().runManual(preset.mode, PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits),
            PlanCompiler.fanStepFor(preset.fanPercent, limits), preset.minutes)
    }
    /** Whether a status shows [preset] running, as [runPreset] sends it: its mode, and the temperature and fan it uses. */
    private fun runs(preset: ActionPreset): (DeviceStatus) -> Boolean {
        val limits = limits()
        val temperature = PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits).coerceIn(preset.mode.temperatureRange)
        val fan = PlanCompiler.fanStepFor(preset.fanPercent, limits)
        return { s -> s.mode == preset.mode && (!preset.mode.usesTemperature || s.targetTemperature == temperature) &&
            (!preset.mode.usesFan || s.fanStep == fan) }
    }
    /**
     * Runs [send] and says whether the BedJet then showed what was sent: a report from the target received after
     * [send] began that [matches], or, if none comes within [confirmMs], one fresh read. A packet the link accepted is
     * not proof that the unit acted on it, so nothing is reported as done on the write alone. Null [matches] confirms
     * nothing.
     */
    private suspend fun sentAndConfirmed(matches: ((DeviceStatus) -> Boolean)?, send: suspend () -> Unit): Boolean = coroutineScope {
        val target = repo.settings.value.deviceId()
        val reports = Channel<DeviceStatus>(64, BufferOverflow.DROP_OLDEST)
        // Subscribed before anything is sent; the report already current is dropped.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.link.sourcedStatus.drop(1).filterNotNull().collect { if (it.isFrom(target)) reports.trySend(it.status) }
        }
        try {
            send()
            if (matches == null) false
            else withTimeoutOrNull(confirmMs) { reports.receiveAsFlow().first { matches(it) } } != null || freshlyShows(matches)
        } finally { watcher.cancel() }
    }
    /** One fresh read of the target; a read that fails confirms nothing. */
    private suspend fun freshlyShows(matches: (DeviceStatus) -> Boolean): Boolean =
        try { matches(freshStatus()) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    /** How long [sentAndConfirmed] waits for a matching report before reading the BedJet afresh; tests shorten it. */
    internal var confirmMs = 5_000L
    /**
     * A manual change: [work] is sent, and the outcome is CONFIRMED only when the BedJet's own report [confirms] it.
     * Otherwise the outcome says it was sent and is waiting for the BedJet ([unconfirmed]).
     *
     * [endsNight]: the tracked night ends only on that confirmation. Without it the night is kept, marked UNKNOWN with
     * [unconfirmed] as its reason and an ERROR event, so it never reads as running a programme, nor as ended, on a
     * write the unit did not report. Its ending intention survives interruption, so a later target Standby can close
     * the same night as STOPPED. Off stays available to send again, and Stop tracking ends it locally.
     */
    private suspend fun manualChange(message: String, endsNight: Boolean, affectsProgramme: Boolean = false,
        confirms: ((DeviceStatus) -> Boolean)?, unconfirmed: String = text(R.string.runtime_sent_unconfirmed, message),
        work: suspend () -> Unit): CommandOutcome {
        val night = repo.activeNight.value
        val requestedAt = graph.now()
        val pending = night?.copy(confidence = Confidence.PENDING, pending = PendingChange(UUID.randomUUID().toString(),
            ChangeKind.MANUAL, null, requestedAt.toString(), requestedAt.plusMinutes(1).toString(), night.identity))
        pending?.let { publishNight(it) }
        if (endsNight && night != null) {
            // Persist before the write: a lost acknowledgement or process death cannot erase the ending intention.
            // This records intent, not successful delivery; only the target's stopped state completes it.
            repo.updateRuntime { it.copy(endRequested = night.identity, endRequestedLabel = message,
                endRequestedAt = requestedAt.toString(), retime = null, alarmInbox = null) }
            NightAlarms.cancelRetry(context)
        }
        val confirmed = sentAndConfirmed(confirms) {
            try { work() } catch (e: Exception) {
                withContext(NonCancellable) {
                    pending?.let { publishNight(it.copy(confidence = Confidence.UNKNOWN)) }
                    repo.updateRuntime { it.copy(reason = e.message) }
                }
                throw e
            }
        }
        if (endsNight && night != null) {
            if (confirmed) finishLocked(night, EventKind.STOPPED, message)
            else {
                publishNight(night.copy(confidence = Confidence.UNKNOWN, pending = null))
                repo.updateRuntime { it.copy(retime = null, reason = unconfirmed) }
                repo.addEvent(night.recordId, event(EventKind.ERROR, unconfirmed))
                NightAlarms.cancelRetry(context)
                Notifications.cancelQuiet(context)
                return CommandOutcome(OutcomeKind.UNKNOWN, unconfirmed)
            }
        }
        else if (affectsProgramme && night != null) {
            // The packet was accepted, but a temperature/fan/timer change is not proof
            // that the saved programme still describes the unit's running state.
            publishNight(night.copy(confidence = Confidence.UNKNOWN, pending = null, trackingManual = true))
            repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_manual_tracking)) }
            offerProgrammeResume(night)
            NightAlarms.cancelRetry(context)
            Notifications.cancelQuiet(context)
        }
        else if (night != null) publishNight(night)
        return if (confirmed) CommandOutcome(OutcomeKind.CONFIRMED, message) else CommandOutcome(OutcomeKind.ACCEPTED, unconfirmed)
    }
    private suspend fun timedManual(preset: ActionPreset, kind: EventKind, label: String): CommandOutcome {
        val old = repo.activeNight.value
        val message = text(R.string.runtime_timed, label, preset.minutes)
        val result = manualChange(message, false, confirms = runs(preset)) { runPreset(preset) }
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
            offerProgrammeResume(old)
            NightAlarms.schedule(context, n)
            Notifications.showWakeUp(context, n, repo.settings.value, result.message, logNotice = false)
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
        // IR-06: a slot write made in this night, or one that displaced it, ends with it.
        repo.updateRuntime { it.copy(ending = PendingEnd(night.identity, event(kind, detail)),
            endRequested = null, endRequestedLabel = null, endRequestedAt = null, retime = null,
            resumeOffer = if (kind == EventKind.ENDED) null else transition.resumeOffer, reason = null,
            wakeShown = null, manualWakeShown = false, slotWrite = it.slotWrite?.takeUnless { w -> w.belongsTo(night.recordId) }) }
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
        // The old programme end cannot convert an unconfirmed explicit ending command into natural completion.
        if (repo.runtime.value.endRequested == n.identity) return false
        if (n.identity != expected || io.github.willzy12h.nocturne.core.night.expire(n, graph.now()) == null) return false
        advanceGeneration()
        finishLocked(n, EventKind.ENDED, text(R.string.runtime_ended)); return true
    }
    val retimePending: Boolean get() = repo.runtime.value.retime != null || repo.runtime.value.alarmInbox != null
    suspend fun retryPendingRetime(): String? = if (!retimePending) null else owned(label(Command.RETIME), repo.settings.value.deviceId(), MIN_BUDGET_MS,
        retimeWork = true) { consumeAlarmObservation(); retryRetimeLocked() }.message
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
            expireRetimeFor(night.recordId)
            return local(R.string.runtime_unchanged)
        }
        val device = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
        val observation = AlarmObservation(UUID.randomUUID().toString(), night.recordId, device, graph.now().toString())
        NightAlarms.scheduleObservation(context, graph.now())
        var expired = false
        withTimeout(8_000) {
            repo.updateRuntime {
                // This expires only submission metadata, never active state or a hardware transaction.
                expired = repo.activeNight.value?.identity == night.identity && !graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))
                if (expired) it // Preserve pending evidence until the single expiry owner consumes it below.
                else it.copy(alarmInbox = observation)
            }
        }
        if (expired) {
            expireRetimeFor(night.recordId)
            return local(R.string.runtime_unchanged)
        }
        return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_pending))
    }
    suspend fun recordPendingRetime(): CommandOutcome = submitAlarmChange()
    private suspend fun consumeAlarmObservation() {
        val observed = repo.runtime.value.alarmInbox ?: return
        val night = repo.activeNight.value
        if (night?.recordId == observed.nightId && repo.settings.value.deviceId() == observed.deviceId) {
            if (!graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))) {
                expireRetimeFor(night.recordId)
            } else queueRetimeLocked()
        }
        repo.updateRuntime { if (it.alarmInbox?.id == observed.id) it.copy(alarmInbox = null) else it }
    }
    /** Atomically consume the real pending change; repeated observations/retries cannot post duplicate expiry notices. */
    private suspend fun expireRetimeFor(nightId: String) {
        var expired = false
        repo.updateRuntime { runtime ->
            val active = repo.activeNight.value?.takeIf { it.recordId == nightId }
            // An alarm observation does not own an in-flight re-time. START may already have been sent;
            // only that command can decide whether it completed or expired without a write.
            if (active?.confidence == Confidence.PENDING &&
                active.pending?.kind == ChangeKind.RETIME) return@updateRuntime runtime
            val pending = runtime.retime?.takeIf { it.expected.nightId == nightId }
            expired = pending != null && active?.identity == pending.expected && active.confidence == Confidence.CONFIRMED
            val observed = runtime.alarmInbox?.nightId == nightId
            // Publication can win this race: an old revision's request says nothing about a new or uncertain start.
            val reason = when {
                expired || (pending == null && observed && active?.confidence == Confidence.CONFIRMED) -> text(R.string.runtime_retime_expired)
                pending != null && active?.confidence == Confidence.CONFIRMED && active.identity != pending.expected -> null
                else -> runtime.reason
            }
            runtime.copy(retime = runtime.retime?.takeUnless { it.expected.nightId == nightId },
                alarmInbox = runtime.alarmInbox?.takeUnless { it.nightId == nightId },
                reason = reason)
        }
        if (!retimePending) NightAlarms.cancelRetry(context)
        if (expired) Notifications.showError(context, text(R.string.runtime_retime_expired), text(R.string.runtime_retime_expired_detail))
    }
    /** A Boost that displaced [night] and may have run. An old FAILED entry wrote nothing (IR-06). */
    private fun hasUnfinishedReplacement(night: ActiveNight): Boolean =
        repo.runtime.value.slotWrite?.let { it.startRequested && it.displaced == night.identity && it.confidence != Confidence.FAILED } == true
    private suspend fun queueRetimeLocked(): CommandOutcome {
        val n = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
        if (n.wakeOrigin == WakeOrigin.OVERRIDE) return local(R.string.runtime_unchanged)
        val alarm = AlarmSource.describe(context, repo.settings.value)
        if (alarm?.counts == false) return local(R.string.runtime_unchanged)
        if (hasUnfinishedReplacement(n) || n.confidence != Confidence.CONFIRMED) return refused(R.string.runtime_reconcile_needed)
        if (n.trackingManual) return refused(R.string.runtime_manual_tracking)
        val deadline = ZonedDateTime.parse(n.wakeUpStart)
        if (!graph.now().isBefore(deadline)) return local(R.string.runtime_retime_expired)
        val prepared = graph.runner().prepareRetime(n, n.identity, alarm?.time, deadline, repo.profile(n.profileId), repo.settings.value, alarm?.creator)
        if (prepared !is Preparation.Ready) {
            val droppedPending = retimePending
            val outcome = if (prepared is Preparation.Refused) {
                if (prepared.unchanged) local(R.string.runtime_unchanged) else CommandOutcome(OutcomeKind.REFUSED, prepared.reason)
            } else stale()
            val failedPending = droppedPending && !outcome.succeeded && outcome.kind != OutcomeKind.STALE
            repo.updateRuntime { it.copy(retime = null, reason = if (failedPending) outcome.message else it.reason) }
            NightAlarms.cancelRetry(context)
            // A newer observation may refuse and consume an older pending change before the retry runs.
            return if (droppedPending) notifyRetimeFailure(outcome) else outcome
        }
        val pending = PendingRetime(UUID.randomUUID().toString(), n.identity, repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target),
            alarm?.time?.toString(), alarm?.creator, deadline.toString())
        repo.updateRuntime { it.copy(retime = pending, reason = text(R.string.runtime_retime_pending)) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        return CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_retime_pending))
    }
    private suspend fun retryRetimeLocked(): CommandOutcome {
        val pending = repo.runtime.value.retime ?: return when {
            repo.runtime.value.reason == text(R.string.runtime_retime_expired) -> local(R.string.runtime_retime_expired)
            repo.activeNight.value == null -> refused(R.string.runtime_no_night)
            repo.activeNight.value?.let { it.confidence != Confidence.CONFIRMED || hasUnfinishedReplacement(it) } == true -> refused(R.string.runtime_reconcile_needed)
            repo.activeNight.value?.trackingManual == true -> refused(R.string.runtime_manual_tracking)
            else -> local(R.string.runtime_unchanged)
        }
        val n = repo.activeNight.value
        if (!graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
            expireRetimeFor(pending.expected.nightId)
            return local(R.string.runtime_retime_expired)
        }
        if (n?.identity != pending.expected || hasUnfinishedReplacement(n) || n.trackingManual || n.confidence != Confidence.CONFIRMED || pending.deviceId != repo.settings.value.deviceId()) {
            repo.updateRuntime { it.copy(retime = null) }; NightAlarms.cancelRetry(context); return stale()
        }
        slotBackupsRefusal(n.slot)?.let { refusal ->
            repo.updateRuntime { it.copy(retime = null, reason = refusal.message) }; NightAlarms.cancelRetry(context)
            // A background retry has no screen to show the refusal, so the wake-up change isn't dropped silently (IR-03).
            return notifyRetimeFailure(refusal)
        }
        return try {
            connected {
                // A remote Off or manual change may have happened while disconnected. A local
                // CONFIRMED night is not evidence that the BedJet is still running our programme.
                takeoverBeforeRewrite(n)?.let { return@connected it }
                val preparation = graph.runner().prepareRetime(n, pending.expected, pending.alarmAt?.let(ZonedDateTime::parse),
                    ZonedDateTime.parse(pending.deadline), repo.profile(n.profileId), repo.settings.value, pending.alarmOrigin)
                if (!graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
                    expireRetimeFor(pending.expected.nightId)
                    return@connected local(R.string.runtime_retime_expired)
                }
                val result = apply(preparation,
                    R.string.runtime_retimed, afterWrite = {
                        // A physical Off can arrive during the upload while status observation is held
                        // behind transaction ownership. Reconnect to demand a fresh hardware report.
                        if (!matchesCurrentProgramme(n, freshStatus(), graph.now())) throw RetimeTakeover()
                    })
                val current = repo.activeNight.value
                if (result.kind == OutcomeKind.FAILED && current?.identity == pending.expected && current.confidence == Confidence.CONFIRMED) {
                    // IR-05: it failed before writing and the night runs on unchanged, so the change is tried
                    // again (never past its deadline, checked above), as after a failed connection.
                    repo.updateRuntime { it.copy(reason = result.message) }
                    NightAlarms.scheduleRetry(context, pending, graph.now())
                } else if (result.kind == OutcomeKind.LOCAL) {
                    repo.updateRuntime { if (it.retime?.id == pending.id) it.copy(retime = null, reason = null) else it }
                    if (!retimePending) NightAlarms.cancelRetry(context)
                } else if (!result.succeeded) {
                    repo.updateRuntime { it.copy(retime = null, reason = result.message) }
                    NightAlarms.cancelRetry(context)
                    return@connected notifyRetimeFailure(result)
                }
                result
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val current = repo.activeNight.value
            if (current?.identity == pending.expected && current.confidence == Confidence.CONFIRMED &&
                !graph.now().isBefore(ZonedDateTime.parse(pending.deadline))) {
                expireRetimeFor(pending.expected.nightId)
                return local(R.string.runtime_retime_expired)
            }
            if (current?.identity != pending.expected || current.confidence in setOf(Confidence.UNKNOWN, Confidence.FAILED)) {
                repo.updateRuntime { it.copy(retime = null, reason = e.message) }
                NightAlarms.cancelRetry(context)
                return notifyRetimeFailure(CommandOutcome(if (current?.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                    e.message ?: text(R.string.runtime_failed)))
            }
            repo.updateRuntime { it.copy(reason = text(R.string.runtime_retime_pending)) }
            NightAlarms.scheduleRetry(context, pending, graph.now())
            recordError(e.message.orEmpty())
            CommandOutcome(OutcomeKind.FAILED, text(R.string.runtime_retime_pending))
        }
    }
    /** Terminal re-time failures own their notice; routine retries and already-reported outcomes stay quiet. */
    private fun notifyRetimeFailure(outcome: CommandOutcome): CommandOutcome {
        if (outcome.succeeded || outcome.kind == OutcomeKind.STALE || outcome.notificationHandled) return outcome
        val unknown = outcome.kind == OutcomeKind.UNKNOWN || repo.activeNight.value?.confidence == Confidence.UNKNOWN
        val title = if (!unknown && outcome.kind == OutcomeKind.REFUSED) R.string.runtime_retime_refused else R.string.runtime_retime_failed
        val detail = if (unknown) text(R.string.runtime_retime_unknown_detail, outcome.message) else outcome.message
        Notifications.showError(context, text(title), detail)
        return outcome.copy(notificationHandled = true)
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
        advanceGeneration(preserveOffer = true)
        val minutes = ((fresh.remainingSeconds + 59) / 60).coerceAtLeast(1)
        val step = ProgramStep(fresh.mode, fresh.fanStep, fresh.targetTemperature, minutes / 60, minutes % 60)
        val plan = Plan(graph.now(), ZonedDateTime.parse(night.wakeAt), listOf(PlannedStep(Block.MIDDLE, step, graph.now(), minutes)))
        val updated = night.copy(confidence = Confidence.CONFIRMED, pending = null, trackingManual = true,
            revision = night.revision + 1, endsAt = graph.now().plusSeconds(fresh.remainingSeconds.toLong()).toString(),
            applied = AppliedProgram.from(plan, night.revision + 1))
        publishNight(updated)
        repo.history.value.firstOrNull { it.id == night.recordId }?.let { repo.addRecord(NightMutation.Applied(plan, updated).recordRevision(it)) }
        // Keeping a fresh run acknowledges a slot write that displaced or interrupted this night (IR-06).
        repo.updateRuntime { it.copy(retime = null, reason = null,
            wakeShown = if (it.manualWakeShown && it.wakeShown == night.identity) updated.identity else it.wakeShown,
            slotWrite = it.slotWrite?.takeUnless { w -> w.belongsTo(night.recordId) }) }
        repo.addEvent(night.recordId, event(EventKind.RECOVERED, text(R.string.runtime_manual_tracking)))
        offerProgrammeResume(night)
        NightAlarms.schedule(context, updated)
        // A snooze's wake-up notification still on screen now names this run's own end, not the old wake (P5).
        if (repo.runtime.value.let { it.manualWakeShown && it.wakeShown == updated.identity })
            Notifications.showWakeUp(context, updated, repo.settings.value, logNotice = false)
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_manual_tracking))
    }
    private suspend fun freshStatus(requireProgress: Boolean = true): DeviceStatus {
        // Reconnect under whole-command ownership. Existing READY status may predate this operation.
        val target = repo.settings.value.deviceId()
        graph.link.disconnect()
        graph.link.connect()
        // Only the chosen BedJet's own report counts: one from a session still on another unit says nothing about it.
        // A status that never arrives is a failed attempt with nothing written. Only this wait's own timeout is turned
        // into that: a cancellation from outside, such as the owner's budget running out, still cancels.
        return withTimeoutOrNull(freshStatusMs) {
            graph.link.sourcedStatus.filterNotNull().first { it.isFrom(target) && (!requireProgress || it.status.extra != null) }.status
        } ?: throw BedJetError.Timeout()
    }
    /** How long [freshStatus] waits for the target's report with program progress; tests shorten it. */
    internal var freshStatusMs = 10_000L
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
            // Only an interrupted replacement: under way (PENDING) or written but not yet finished (CONFIRMED). A write
            // that failed first changed nothing, and an UNKNOWN one already marked its night (IR-06).
            val displaced = repo.runtime.value.slotWrite?.takeIf { it.startRequested &&
                (it.confidence == Confidence.PENDING || it.confidence == Confidence.CONFIRMED) }?.displaced
            repo.activeNight.value?.takeIf { it.identity == displaced && it.confidence != Confidence.UNKNOWN }?.let { previous ->
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
            repo.runtime.value.endRequested?.let { expected ->
                if (repo.activeNight.value?.identity != expected) repo.updateRuntime {
                    it.copy(endRequested = null, endRequestedLabel = null, endRequestedAt = null) }
                else try {
                    // Standby is in the head; no programme-progress tail is needed to confirm an ending command.
                    connected { finishRequestedEnd(freshStatus(requireProgress = false)) }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { /* Keep the durable intention and UNKNOWN state until a target report arrives. */ }
            }
            repo.runtime.value.resumeOffer?.takeIf { !graph.now().isBefore(ZonedDateTime.parse(it.expiresAt)) }?.let {
                repo.updateRuntime { it.copy(resumeOffer = null) }
            }
        }
        drainLocalDeliveries()
        expire()
        repo.activeNight.value?.let { NightAlarms.schedule(context, it); Notifications.showQuiet(context, it, repo.settings.value, wakeChangePending()) }
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
            if (finishRequestedEnd(status, receivedAt)) return@withLock
            val extra = status.extra ?: return@withLock
            if (extra.notification != 0 && lastNotice != (n.recordId to extra.notification)) {
                lastNotice = n.recordId to extra.notification
                repo.addEvent(n.recordId, event(EventKind.UNIT_NOTICE, text(R.string.runtime_unit_notice, extra.notification)))
            } else if (extra.notification == 0) lastNotice = null
            if (n.confidence != Confidence.CONFIRMED) return@withLock
            // IR-08: a kept manual run is watched against its own step.
            if (n.trackingManual) { if (contradictsManual(n, status, receivedAt)) recordTakeover(n); return@withLock }
            if (matchesCurrentProgramme(n, status, receivedAt)) return@withLock
            recordTakeover(n)
        }
    }
    /** Called only with a report attributed to the selected target, under command ownership. */
    private suspend fun finishRequestedEnd(status: DeviceStatus, receivedAt: ZonedDateTime = graph.now()): Boolean {
        val night = repo.activeNight.value ?: return false
        val request = repo.runtime.value
        if (request.endRequested != night.identity || status.isRunning ||
            request.endRequestedAt?.let { receivedAt.isBefore(ZonedDateTime.parse(it)) } == true) return false
        finishLocked(night, EventKind.STOPPED, request.endRequestedLabel ?: text(R.string.runtime_stopped))
        return true
    }
    /** An automatic or programme-preserving rewrite never silently undoes a physical-remote change (IR-01).
     * Reads the BedJet first. Null means it still runs the tracked night and the rewrite may go ahead. */
    private suspend fun takeoverBeforeRewrite(n: ActiveNight): CommandOutcome? {
        if (n.confidence != Confidence.CONFIRMED || n.trackingManual) return null
        // A local CONFIRMED night is not evidence that the BedJet still runs our programme: a remote Off or
        // manual change may have happened while disconnected.
        if (matchesCurrentProgramme(n, freshStatus(), graph.now())) return null
        recordTakeover(n)
        return CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_takeover), notificationHandled = true)
    }
    private suspend fun recordTakeover(n: ActiveNight) {
        publishNight(n.copy(confidence = Confidence.UNKNOWN))
        repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_takeover)) }
        repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
        NightAlarms.cancelRetry(context)
        Notifications.showInfo(context, text(R.string.runtime_takeover_title), text(R.string.runtime_takeover))
    }
    private fun matchesCurrentProgramme(night: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
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
    private fun contradictsManual(n: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
        val step = n.applied.programSteps.singleOrNull() ?: return false
        val began = n.applied.start.takeIf { it.isNotEmpty() }?.let(ZonedDateTime::parse) ?: return false
        if (at.isBefore(began.plusSeconds(TAKEOVER_GRACE_S)) || !at.isBefore(ZonedDateTime.parse(n.endsAt).minusSeconds(MANUAL_TIMER_SLACK_S))) return false
        return !status.isRunning || status.mode != step.mode || (step.mode.usesTemperature && status.targetTemperature != step.temperature)
    }
    private var lastNotice: Pair<String, Int>? = null
    suspend fun pin(profileId: String, slot: Int, expectedTarget: String? = null,
        expectedProfile: Profile? = null, expectedWorkingSlot: Int? = null,
        previewedAt: ZonedDateTime? = null, expectedProgram: StoredProgram? = null): CommandOutcome =
        owned(text(R.string.runtime_pin_label), repo.settings.value.deviceId(), MIN_BUDGET_MS) {
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
        slotBackupsRefusal(slot)?.let { return@owned it }
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
    suspend fun restoreOriginal(slot: Int): CommandOutcome = owned(text(R.string.runtime_restore_label), repo.settings.value.deviceId(), MIN_BUDGET_MS) {
        if (!ReplacementPolicy.permitsSlotRestore(repo.activeNight.value)) return@owned refused(R.string.runtime_restore_active)
        if (slot !in 0..2) return@owned refused(R.string.runtime_pin_slot)
        val target = repo.settings.value.deviceId() ?: return@owned refused(R.string.runtime_choose_target)
        repo.originalsFor(target)
        val original = repo.originalSlot(target, slot) ?: return@owned refused(R.string.runtime_no_original)
        // The validator lets a saved original hold any temperature the codec carries, as a BedJet recorded it. One
        // from a backup file or snapshot may have been edited, so heating/cooling steps must stay in range,
        // including Turbo's fixed temperature. Standby and Wait ignore their placeholder temperature byte.
        if (original.imported && original.program?.steps.orEmpty().any {
                it.mode != Mode.STANDBY && it.mode != Mode.WAIT && it.temperature !in it.mode.temperatureRange
            })
            return@owned refused(R.string.runtime_restore_imported_out_of_range)
        connected {
            if (freshStatus().extra?.programStep != 0) return@connected refused(R.string.runtime_slot_running)
            advanceGeneration()
            writeSlotLocked(slot, original.program, text(R.string.runtime_restored, slot + 1))
        }
    }
    private suspend fun writeSlotLocked(slot: Int, program: StoredProgram?, label: String, start: Boolean = false): CommandOutcome {
        val target = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
        val earlier = repo.runtime.value.slotWrite
        val night = repo.activeNight.value
        val intent = SlotWrite(target, slot, program, label = label, startRequested = start,
            displaced = if (start) night?.identity else null, nightId = night?.recordId)
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
                if (!writing) {
                    // IR-05/IR-06: nothing reached the BedJet after a backup failure. Keep the error message, but leave
                    // the journal and the running night as they were: no false uncertain intent, no FAILED night.
                    repo.updateRuntime { it.copy(slotWrite = earlier?.takeIf { w -> w.confidence == Confidence.PENDING || w.confidence == Confidence.UNKNOWN },
                        reason = e.message) }
                } else {
                    repo.updateRuntime { it.copy(slotWrite = intent.copy(confidence = Confidence.UNKNOWN), reason = e.message) }
                    if (start) repo.activeNight.value?.let { publishNight(it.copy(confidence = Confidence.UNKNOWN)) }
                }
            }
            if (e is CancellationException) throw e
            if (e is SlotBackupsUnreadable) return refused(R.string.runtime_slot_backups_unreadable)
            // The write reports its own outcome: a write that may have reached the BedJet is UNKNOWN (IR-06).
            recordError("$label: ${e.message.orEmpty()}")
            return CommandOutcome(if (writing) OutcomeKind.UNKNOWN else OutcomeKind.FAILED, e.message ?: text(R.string.runtime_failed))
        }
    }
    /** The user has checked the BedJet: a slot write in question no longer holds anything back (IR-06). Local only. */
    suspend fun acknowledgeSlotWrite(): CommandOutcome = lock.withLock {
        repo.updateRuntime { it.copy(slotWrite = null) }
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_slot_write_acknowledged))
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
        owned(text(R.string.runtime_override_label), repo.settings.value.deviceId(), MIN_BUDGET_MS) {
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
        slotBackupsRefusal(n.slot)?.let { return@owned it }
        connected {
            // Checked before the override is saved, so a refused override is not stored (IR-01).
            takeoverBeforeRewrite(n)?.let { return@connected it }
            advanceGeneration()
            // Saved only once the BedJet runs the rewrite, so a failed or uncertain one never shows the new time as
            // tonight's while the night keeps the old one.
            apply(prepared, R.string.runtime_reapplied).also { result ->
                if (result.kind == OutcomeKind.CONFIRMED)
                    repo.updateSettings { if (wake == null) it.clearWakeOverride() else it.setWakeOverride(wake, graph.now()) }
            }
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
        CommandOutcome(OutcomeKind.LOCAL, text(if (deviceId == null) R.string.runtime_target_forgotten else R.string.runtime_target_saved))
    }
    suspend fun setDemoMode(enabled: Boolean): CommandOutcome = owned(text(R.string.runtime_demo_label), repo.settings.value.deviceId()) {
        if (repo.activeNight.value != null && enabled != repo.settings.value.demoMode) return@owned refused(R.string.runtime_active_target)
        advanceGeneration()
        graph.link.disconnect()
        repo.updateSettings { it.copy(demoMode = enabled) }
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_demo_saved))
    }
    suspend fun forgetTarget(): CommandOutcome = selectTarget(null)
    suspend fun setupFanCheck(): CommandOutcome = owned(text(R.string.runtime_fan_check), repo.settings.value.deviceId(), MIN_BUDGET_MS) {
        if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
        connected {
            val initial = freshStatus()
            if (initial.isRunning || initial.extra?.programStep != 0) return@connected refused(R.string.runtime_fan_refused)
            val changed = if (initial.fanStep < 19) initial.fanStep + 1 else initial.fanStep - 1
            var seen = false
            try { seen = sentAndConfirmed({ it.fanStep == changed }) { graph.controller().setFan(changed) } }
            finally { withContext(NonCancellable) { withTimeout(5_000) { graph.controller().setFan(initial.fanStep) } } }
            // The restore is read back too: the check says the fan is as it was only when the BedJet reports it so.
            if (!freshlyShows { it.fanStep == initial.fanStep }) return@connected CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_fan_restore_unconfirmed))
            if (!seen) return@connected CommandOutcome(OutcomeKind.FAILED, text(R.string.runtime_fan_change_unseen))
            repo.settings.value.takeIf { !it.demoMode }?.targetDevice?.let { target ->
                repo.updateSettings { it.copy(setupFanCheckedDevice = target) }
            }
            CommandOutcome(OutcomeKind.CONFIRMED, text(R.string.runtime_fan_checked))
        }
    }
    suspend fun releaseForOtherPhone(untilMs: Long? = null, declineIfBusy: Boolean = false): CommandOutcome {
        if (declineIfBusy && lock.isLocked) return refused(R.string.runtime_busy)
        return owned(text(R.string.runtime_release_label), repo.settings.value.deviceId()) {
            // Handover invalidates queued commands, but an existing recovery reason and
            // short-lived resume offer remain meaningful when this phone releases the link.
            repo.updateRuntime { it.copy(generation = it.generation + 1,
                resumeOffer = it.resumeOffer?.copy(commandVersion = it.generation + 1)) }
            graph.sessions.suspendAutoConnect(untilMs).join()
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_released))
        }
    }
    suspend fun takeBack(): CommandOutcome = owned(text(R.string.runtime_take_back), repo.settings.value.deviceId(), MIN_BUDGET_MS) {
        graph.sessions.resumeAutoConnect()
        connected {
            val pendingEnd = repo.runtime.value.endRequested?.let { it == repo.activeNight.value?.identity } == true
            val status = freshStatus(requireProgress = !pendingEnd)
            if (finishRequestedEnd(status)) return@connected CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_taken_back))
            // Uncertain hardware is never replayed. The user can keep a fresh manual run or stop tracking.
            repo.activeNight.value?.let { n ->
                // The same comparison as takeover detection: a kept manual run against its own step (IR-08).
                val runs = if (n.trackingManual) !contradictsManual(n, status, graph.now()) else matchesCurrentProgramme(n, status, graph.now())
                if (!runs) {
                    recordTakeover(n)
                    // The outcome says what the check found, not only that it ran.
                    return@connected CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_taken_back_takeover))
                }
            }
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_taken_back))
        }
    }
    /** A Remote-screen change. [confirms] says which BedJet report shows it took effect; without one it is only sent. */
    suspend fun remote(label: String, endsNight: Boolean = false, affectsProgramme: Boolean = false,
        target: String? = repo.settings.value.deviceId(), expected: NightIdentity? = repo.activeNight.value?.identity,
        confirms: ((DeviceStatus) -> Boolean)? = null,
        work: suspend (BedJetController) -> Unit): CommandOutcome = owned(label, target, MIN_BUDGET_MS) {
        if (endsNight && confirms == null) return@owned refused(R.string.runtime_remote_confirmation_required)
        // The tap's night, captured then: a change queued behind a Start or re-time never converts another night (IR-09).
        if ((endsNight || affectsProgramme) && expected != repo.activeNight.value?.identity) return@owned stale()
        connected {
            advanceGeneration(preserveOffer = !endsNight)
            manualChange(label, endsNight, affectsProgramme, confirms) { work(graph.controller()) }
        }
    }
    /** [open] is how the file is opened; tests make it fail. A file that can't be opened throws, so nothing claims it saved. */
    suspend fun exportDiagnostics(uri: android.net.Uri,
        open: (android.net.Uri) -> java.io.OutputStream? = { context.contentResolver.openOutputStream(it, "wt") }) {
        val snapshot = diagnostics()
        withContext(Dispatchers.IO) {
            open(uri)?.use { it.write(snapshot.toByteArray()) }
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
        // A refused/pre-write-failed replacement keeps the same night and its ending intention. A different
        // published identity supersedes it; a leftover marker after a crash is also checked by reconcile.
        if (repo.runtime.value.endRequested?.let { it != night?.identity } == true)
            repo.updateRuntime { if (it.endRequested == night?.identity) it
                else it.copy(endRequested = null, endRequestedLabel = null, endRequestedAt = null) }
        refreshQuietNotification()
    }
    internal fun refreshQuietNotification() {
        val night = repo.activeNight.value
        if (night == null) Notifications.cancelQuiet(context)
        else Notifications.showQuiet(context, night, repo.settings.value, wakeChangePending())
    }
    /** A wake-up change is waiting for the BedJet, as [status] reports PENDING for it. */
    private fun wakeChangePending(): Boolean = repo.runtime.value.let { it.retime != null || it.alarmInbox != null }
    private fun event(kind: EventKind, detail: String) = HistoryEvent(graph.now().toString(), kind, detail)
    private suspend fun recordError(detail: String) { repo.activeNight.value?.let { repo.addEvent(it.recordId, event(EventKind.ERROR, detail)) } }
    private fun limits(): ProfileLimits = (repo.activeNight.value?.let { it.profile ?: repo.profile(it.profileId) } ?: repo.tonightsProfile())?.limits ?: ProfileLimits()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun local(id: Int) = CommandOutcome(OutcomeKind.LOCAL, text(id))
    private fun refused(id: Int) = CommandOutcome(OutcomeKind.REFUSED, text(id))
    private fun stale(id: Int = R.string.runtime_stale) = CommandOutcome(OutcomeKind.STALE, text(id))
    private fun label(command: Command) = commandLabel(context, command)
    private fun clock(iso: String) = ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("HH:mm"))
    companion object {
        const val OPERATION_MS = 90_000L
        /** The least of [OPERATION_MS] a hardware request must have left once it holds the lock (IR-11). */
        const val MIN_BUDGET_MS = 30_000L
        private val TIMED_MANUAL = setOf(Command.SNOOZE_WARM, Command.SNOOZE_COOL, Command.EXTEND_WARM, Command.EXTEND_COOL)
        /** A step boundary is not a takeover: the unit's report may lag the planned boundary briefly. */
        private const val TAKEOVER_GRACE_S = 20L
        /** A manual run this close to its end may stop by itself; that is not a takeover. */
        private const val MANUAL_TIMER_SLACK_S = 90L
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
