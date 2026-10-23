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
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The only owner of complete BedJet transactions, target changes and local night transitions.
 * Callers own the coroutine lifecycle: foreground UI, bounded service, or cancellable JobService. */
class NightActions(private val graph: Graph) {
    internal val repo get() = graph.repository
    private val context get() = graph.context
    private val lock = Mutex()
    /** Monotonic milliseconds for the admission check; tests move it by hand. */
    internal var elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }
    private val _busy = MutableStateFlow(BusyState())
    val busy: StateFlow<BusyState> = _busy.asStateFlow()
    private val _outcomes = MutableSharedFlow<CommandOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<CommandOutcome> = _outcomes.asSharedFlow()
    /** A slot write colours the status only while it is in question and concerns the tracked night, or there is none (IR-06). */
    val status: StateFlow<NightStatus> = combine(repo.activeNight, repo.runtime) { n, r ->
        val slot = r.unresolvedSlotWrite?.takeIf { n == null || it.displaced == n.identity }
        NightStatus(if ((r.retime != null || r.alarmInbox != null) && n?.confidence == Confidence.CONFIRMED) Confidence.PENDING
            else slot?.confidence ?: n?.confidence, r.reason) }
        .stateIn(graph.scope, SharingStarted.Eagerly, NightStatus(repo.activeNight.value?.confidence, repo.runtime.value.reason))

    enum class Command { START_NIGHT, END_NIGHT, OFF, TURBO, BOOST, COOL, WARM, RETIME, RETRY_RETIME, REAPPLY, SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, RESUME, STOP_TRACKING, KEEP_TRACKING }
    fun capture(command: Command, profileId: String? = null, requireIdentity: Boolean = false) =
        CommandRequest(command, profileId, repo.settings.value.deviceId(), repo.activeNight.value?.identity, requireIdentity)

    /** Fresh explicit Off follows a queued Start. Only old notification/widget identities are rejected. */
    suspend fun perform(command: Command, profileId: String? = null, replacementConfirmed: Boolean = false): String =
        execute(capture(command, profileId, requireIdentity = replacementConfirmed).copy(replacementConfirmed = replacementConfirmed)).message
    suspend fun execute(request: CommandRequest): CommandOutcome = owned(label(request.command), request.deviceId,
        admit = request.command != Command.STOP_TRACKING) {
        if (request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
        if (ReplacementPolicy.decide(request.command, repo.activeNight.value).needsConfirmation && !request.replacementConfirmed)
            return@owned CommandOutcome(OutcomeKind.CONFIRMATION_REQUIRED, text(R.string.runtime_replace_confirmation))
        when (request.command) {
            Command.STOP_TRACKING -> stopTrackingLocked()
            Command.KEEP_TRACKING -> keepTrackingLocked()
            Command.RETRY_RETIME -> { consumeAlarmObservation(); retryRetimeLocked() }
            Command.RETIME -> { consumeAlarmObservation(); val queued = queueRetimeLocked(); if (queued.kind == OutcomeKind.LOCAL) retryRetimeLocked() else queued }
            else -> slotWriteRefusal(request.command) ?: connected { run(request) }
        }
    }
    /** No FGS, detached launch or retry. Job cancellation propagates after durable uncertainty is saved. */
    suspend fun performJobOwned(request: CommandRequest, eligible: suspend (DeviceStatus) -> Boolean): CommandOutcome =
        owned(label(request.command), request.deviceId) {
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

    /** [admit]: refuse, before any write, a request left with less than [MIN_BUDGET_MS] after waiting for the lock (IR-11). */
    private suspend fun owned(label: String, target: String?, admit: Boolean = true, work: suspend () -> CommandOutcome): CommandOutcome {
        _busy.update { it.copy(queuedCount = it.queuedCount + 1) }
        var acquired = false
        val owner = Any()
        val queued = elapsedMs()
        try {
            return withTimeout(OPERATION_MS) {
                lock.lock(owner)
                acquired = true
                _busy.update { it.copy(runningLabel = label, queuedCount = it.queuedCount - 1) }
                val submitted = currentCoroutineContext()[SubmissionTarget]
                val result = if (target != repo.settings.value.deviceId() ||
                    (submitted != null && submitted.deviceId != repo.settings.value.deviceId())) stale(R.string.runtime_target_changed)
                    // The budget includes the wait; too little left would be cut off partway through a write.
                    else if (admit && OPERATION_MS - (elapsedMs() - queued) < MIN_BUDGET_MS) waitedTooLong()
                    else work()
                _outcomes.emit(result)
                result
            }
        } catch (e: CancellationException) { throw e
        } catch (e: SlotBackupsUnreadable) {
            // Thrown before any slot write; the night's confidence is left as it was.
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
    /** Nothing changed; a pending wake-up change keeps its retry, since this attempt may have been the alarm's. */
    private fun waitedTooLong(): CommandOutcome {
        repo.runtime.value.retime?.let { NightAlarms.scheduleRetry(context, it, graph.now()) }
        if (repo.runtime.value.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
        return refused(R.string.runtime_waited_too_long)
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
            takeoverBeforeRewrite(night)?.let { return it }
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
    private suspend fun apply(preparation: Preparation, message: Int): CommandOutcome {
        val change = when (preparation) {
            is Preparation.Ready -> preparation.change
            is Preparation.Refused -> return CommandOutcome(OutcomeKind.REFUSED, preparation.reason)
            is Preparation.Stale -> return stale()
        }
        slotBackupsRefusal()?.let { return it }
        val result = graph.runner().apply(change, { repo.activeNight.value }, { repo.runtime.value.generation },
            publish = {
                // IR-23: a new night's record exists before the first write, so a failure or restart keeps its events.
                val intended = it.pending?.intended
                if (intended != null && it.confidence == Confidence.PENDING && repo.history.value.none { r -> r.id == it.recordId }) repo.addRecord(
                    NightRecord(intended.recordId, intended.profileId, intended.profile!!.name, intended.startedAt, intended.wakeAt,
                        intended.wakeFromAlarm, intended.applied.steps, deviceId = intended.deviceId))
                publishNight(it); if (it.confidence != Confidence.CONFIRMED) Notifications.cancelQuiet(context)
            }, beforeWrite = { backup(change.intended.deviceId, change.intended.slot) })
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
                val started = existing == null || change.request.kind == ChangeKind.START
                repo.addEvent(n.recordId, event(if (started) EventKind.STARTED else EventKind.RETIMED, text(message, n.profile!!.name, clock(n.wakeAt))))
                // IR-06: a confirmed write of this BedJet's slot settles an earlier write still in question.
                repo.updateRuntime { it.copy(retime = null, reason = null, wakeShown = null,
                    slotWrite = it.slotWrite?.takeUnless { w -> w.deviceId == n.deviceId }) }
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
                repo.updateRuntime { it.copy(reason = result.cause.message) }
                recordError(result.cause.message.orEmpty())
                // IR-05: nothing written means the night shown is the one still running, whatever its confidence.
                CommandOutcome(if (!result.nothingWritten && result.night.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                    result.cause.message ?: text(R.string.runtime_failed))
            }
            is NightMutation.Refused -> CommandOutcome(OutcomeKind.REFUSED, result.reason)
            is NightMutation.Stale -> stale()
        }
    }
    /** IR-03: while the saved slot originals can't be read, no slot is written and nothing is published first. */
    private fun slotBackupsRefusal(): CommandOutcome? =
        if (repo.slotBackupsUnreadable) refused(R.string.runtime_slot_backups_unreadable) else null
    /** [slotBackupsRefusal] for the commands that write a slot, checked before connecting. */
    private fun slotWriteRefusal(command: Command): CommandOutcome? =
        if (command in SLOT_WRITES) slotBackupsRefusal() else null
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
    /** Sends manual packets with [night] shown PENDING meanwhile. A failure leaves it UNKNOWN, since a packet may have landed. */
    private suspend fun sendManual(night: ActiveNight?, work: suspend () -> Unit) {
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
    }
    private suspend fun manualChange(message: String, endsNight: Boolean, work: suspend () -> Unit): CommandOutcome {
        val night = repo.activeNight.value
        sendManual(night, work)
        if (endsNight && night != null) finishLocked(night, EventKind.STOPPED, message)
        else if (night != null) publishNight(night)
        return CommandOutcome(OutcomeKind.ACCEPTED, message)
    }
    private suspend fun timedManual(preset: ActionPreset, kind: EventKind, label: String): CommandOutcome {
        val old = repo.activeNight.value
        val message = text(R.string.runtime_timed, label, preset.minutes)
        if (old == null) return manualChange(message, false) { runPreset(preset) }
        val bounds = limits()
        val step = ProgramStep(preset.mode, PlanCompiler.fanStepFor(preset.fanPercent, bounds),
            PlanCompiler.temperatureFor(preset.mode, preset.temperature, bounds), preset.minutes / 60, preset.minutes % 60)
        val n = reportsAfter({ sendManual(old) { runPreset(preset) } }) { reports -> trackManual(old, step, kind, message, reports) }
        val shown = manualMessage(n, message)
        Notifications.showWakeUp(context, n, repo.settings.value, shown, logNotice = false)
        return CommandOutcome(OutcomeKind.ACCEPTED, shown)
    }
    /**
     * IR-08/IR-09: the night now tracks the one manual [step], published PENDING until a status after the packets
     * shows it ([confirmsManual]); then CONFIRMED. A report that never comes leaves it PENDING, and [observeStatus]
     * confirms it later or, if the BedJet runs something else, records a takeover. Any pending re-time is dropped.
     */
    private suspend fun trackManual(old: ActiveNight, step: ProgramStep, kind: EventKind, message: String,
        reports: ReceiveChannel<SourcedStatus>): ActiveNight {
        val now = graph.now()
        val minutes = step.durationMinutes
        val plan = Plan(now, ZonedDateTime.parse(old.wakeAt), listOf(PlannedStep(Block.MIDDLE, step, now, minutes)))
        val n = old.copy(endsAt = now.plusMinutes(minutes.toLong()).toString(), revision = old.revision + 1, confidence = Confidence.PENDING,
            pending = null, trackingManual = true, applied = AppliedProgram.from(plan, old.revision + 1))
        publishNight(n)
        repo.history.value.firstOrNull { it.id == n.recordId }?.let { repo.addRecord(NightMutation.Applied(plan, n).recordRevision(it)) }
        repo.addEvent(n.recordId, event(kind, message))
        // A manual run never follows the alarm, so an alarm change waiting to be read is dropped with the re-time.
        repo.updateRuntime { it.copy(retime = null, alarmInbox = null, reason = null, wakeShown = n.identity) }
        NightAlarms.cancelRetry(context)
        NightAlarms.schedule(context, n)
        val target = repo.settings.value.deviceId()
        // Only a report received after the packets went out counts, never the one current before them.
        val seen = withTimeoutOrNull(MANUAL_CONFIRM_MS) {
            reports.receiveAsFlow().first { it.isFrom(target) && confirmsManual(n, it.status, graph.now()) }
        }
        if (seen == null || repo.activeNight.value?.identity != n.identity) return n
        return n.copy(confidence = Confidence.CONFIRMED).also { publishNight(it) }
    }
    /** Runs [send], then [then] with the reports the link received from the start of [send] on. */
    private suspend fun <T> reportsAfter(send: suspend () -> Unit, then: suspend (ReceiveChannel<SourcedStatus>) -> T): T = coroutineScope {
        val reports = Channel<SourcedStatus>(64, BufferOverflow.DROP_OLDEST)
        // Subscribed before anything is sent; the value already current is dropped.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.link.sourcedStatus.drop(1).filterNotNull().collect { reports.trySend(it) }
        }
        try { send(); then(reports) } finally { watcher.cancel() }
    }
    private fun manualMessage(n: ActiveNight, message: String) =
        if (n.confidence == Confidence.CONFIRMED) message else text(R.string.runtime_manual_pending, message)
    /** A manual night waiting for the BedJet's report of its run; unlike an interrupted command it has no pending change. */
    private fun awaitingReport(n: ActiveNight) = n.trackingManual && n.confidence == Confidence.PENDING && n.pending == null
    private fun manualStep(n: ActiveNight): ProgramStep? = n.applied.programSteps.singleOrNull()
    /** Whether [status] shows [n]'s manual run: its mode, temperature, fan and the time it has left. */
    private fun confirmsManual(n: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
        val step = manualStep(n) ?: return false
        val left = java.time.Duration.between(at, ZonedDateTime.parse(n.endsAt)).seconds
        return status.isRunning && status.mode == step.mode &&
            (!step.mode.usesTemperature || status.targetTemperature == step.temperature) &&
            (!step.mode.usesFan || status.fanStep == step.fanStep) &&
            kotlin.math.abs(status.remainingSeconds - left) <= MANUAL_TIMER_SLACK_S
    }
    /** Whether [status] shows the BedJet running something other than [n]'s manual run, outside its first and last moments. */
    private fun contradictsManual(n: ActiveNight, status: DeviceStatus, at: ZonedDateTime): Boolean {
        val step = manualStep(n) ?: return false
        val began = n.applied.start.takeIf { it.isNotEmpty() }?.let(ZonedDateTime::parse) ?: return false
        if (at.isBefore(began.plusSeconds(TAKEOVER_GRACE_S)) || !at.isBefore(ZonedDateTime.parse(n.endsAt).minusSeconds(MANUAL_TIMER_SLACK_S))) return false
        return !status.isRunning || status.mode != step.mode || (step.mode.usesTemperature && status.targetTemperature != step.temperature)
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
        repo.updateRuntime { it.copy(ending = PendingEnd(night.identity, event(kind, detail)), retime = null,
            resumeOffer = if (kind == EventKind.ENDED) null else transition.resumeOffer, reason = null, wakeShown = null,
            slotWrite = it.slotWrite?.takeUnless { w -> w.belongsTo(night.recordId) }) }
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
    suspend fun retryPendingRetime(): String? = if (!retimePending) null else owned(label(Command.RETIME), repo.settings.value.deviceId()) { consumeAlarmObservation(); retryRetimeLocked() }.message
    /** A coalesced observation inbox, not a second command owner. This only uses the repository writer.
     * The receiver can submit while a whole hardware transaction holds the coordinator mutex. */
    suspend fun submitAlarmChange(): CommandOutcome {
        val night = repo.activeNight.value
        if (night == null) {
            withTimeout(8_000) { repo.updateRuntime { if (repo.activeNight.value == null) it.copy(alarmInbox = null, retime = null) else it } }
            if (!retimePending) NightAlarms.cancelRetry(context)
            return refused(R.string.runtime_no_night)
        }
        val device = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
        val observation = AlarmObservation(UUID.randomUUID().toString(), night.recordId, device, graph.now().toString())
        NightAlarms.scheduleObservation(context, graph.now())
        var expired = false
        withTimeout(8_000) {
            repo.updateRuntime {
                // This expires only submission metadata, never active state or a hardware transaction.
                expired = repo.activeNight.value?.identity == night.identity && !graph.now().isBefore(ZonedDateTime.parse(night.wakeUpStart))
                if (expired) it.copy(alarmInbox = null, retime = null, reason = text(R.string.runtime_retime_expired))
                else it.copy(alarmInbox = observation)
            }
        }
        if (expired) {
            NightAlarms.cancelRetry(context)
            Notifications.showError(context, text(R.string.runtime_retime_expired), text(R.string.runtime_retime_expired_detail))
            return refused(R.string.runtime_retime_expired)
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
                Notifications.showError(context, text(R.string.runtime_retime_expired), text(R.string.runtime_retime_expired_detail))
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
        slotBackupsRefusal()?.let { refusal ->
            repo.updateRuntime { it.copy(retime = null, reason = refusal.message) }; NightAlarms.cancelRetry(context)
            // A background retry has no screen to show the refusal, so the wake-up change isn't dropped silently.
            Notifications.showError(context, text(R.string.runtime_retime_refused), refusal.message)
            return refusal
        }
        return try {
            connected {
                takeoverBeforeRewrite(n)?.let { return@connected it }
                val result = apply(graph.runner().prepareRetime(n, pending.expected, pending.alarmAt?.let(ZonedDateTime::parse),
                    ZonedDateTime.parse(pending.deadline), repo.profile(n.profileId), repo.settings.value, pending.alarmOrigin), R.string.runtime_retimed)
                val current = repo.activeNight.value
                if (result.kind == OutcomeKind.FAILED && current?.identity == pending.expected && current.confidence == Confidence.CONFIRMED) {
                    // IR-05: it failed before writing and the night runs on unchanged, so the change is tried again
                    // (never past its deadline), as after a failed connection.
                    repo.updateRuntime { it.copy(reason = result.message) }
                    NightAlarms.scheduleRetry(context, pending, graph.now())
                } else if (!result.succeeded) { repo.updateRuntime { it.copy(retime = null, reason = result.message) }; NightAlarms.cancelRetry(context) }
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
        // Keeping a fresh run acknowledges a slot write that displaced or interrupted this night (IR-06).
        repo.updateRuntime { it.copy(retime = null, reason = null, slotWrite = it.slotWrite?.takeUnless { w -> w.belongsTo(night.recordId) }) }
        repo.addEvent(night.recordId, event(EventKind.RECOVERED, text(R.string.runtime_manual_tracking)))
        NightAlarms.schedule(context, updated)
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_manual_tracking))
    }
    /** What the tracked night expects of the BedJet at [at]: programme step, mode and, for temperature modes,
     * target. With [grace], a report still on the previous step just after a boundary also matches. */
    private fun runsNight(n: ActiveNight, status: DeviceStatus, at: ZonedDateTime, grace: Boolean = false): Boolean {
        if (n.trackingManual) return !contradictsManual(n, status, at)
        val plan = n.activePlan(repo.profile(n.profileId)) ?: return false
        val (index, step) = plan.stepAt(at) ?: return false
        fun runs(i: Int, planned: PlannedStep) = status.extra?.programStep == i + 1 && status.mode == planned.step.mode &&
            (!status.mode.usesTemperature || status.targetTemperature == planned.step.temperature)
        return runs(index, step) ||
            (grace && index > 0 && at.isBefore(step.startsAt.plusSeconds(TAKEOVER_GRACE_S)) && runs(index - 1, plan.steps[index - 1]))
    }
    /** An automatic or programme-preserving rewrite never silently undoes a physical-remote change (IR-01).
     * Reads the BedJet first. Null means it still runs the tracked night and the rewrite may go ahead. */
    private suspend fun takeoverBeforeRewrite(n: ActiveNight): CommandOutcome? {
        if (n.confidence != Confidence.CONFIRMED || n.trackingManual) return null
        val status = freshStatus()
        if (runsNight(n, status, graph.now(), grace = true)) return null
        recordTakeover(n)
        return CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_takeover), notified = true)
    }
    private suspend fun recordTakeover(n: ActiveNight) {
        publishNight(n.copy(confidence = Confidence.UNKNOWN))
        repo.updateRuntime { it.copy(retime = null, reason = text(R.string.runtime_takeover)) }
        repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
        NightAlarms.cancelRetry(context)
        Notifications.showInfo(context, text(R.string.runtime_takeover_title), text(R.string.runtime_takeover))
    }
    private suspend fun freshStatus(): DeviceStatus {
        // Reconnect under whole-command ownership. Existing READY status may predate this operation.
        graph.link.disconnect()
        graph.link.connect()
        // A status that never arrives is a failed attempt with nothing written. Only this wait's own timeout is turned
        // into that: a cancellation from outside, such as the owner's budget running out, still cancels.
        return withTimeoutOrNull(freshStatusMs) { graph.link.status.filterNotNull().first { it.extra != null } } ?: throw BedJetError.Timeout()
    }
    /** How long [freshStatus] waits for a report with program progress; tests shorten it. */
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
            if (n?.confidence == Confidence.PENDING && !awaitingReport(n)) {
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
        repo.updateRuntime { it.copy(wakeShown = n.identity) }
        Notifications.showWakeUp(context, n, repo.settings.value)
    }
    suspend fun observeStatus(status: DeviceStatus, target: String?, receivedAt: ZonedDateTime = graph.now(), source: String? = target) {
        // Intermediate reports during our own upload/manual packets must not become a takeover after queuing.
        if (lock.isLocked) return
        lock.withLock {
            // [source] is the unit that reported [status]; one from another BedJet is never this target's state.
            if (target != repo.settings.value.deviceId() || !DeviceId.same(source, target) || graph.link.state.value != LinkState.READY) return@withLock
            val n = repo.activeNight.value ?: return@withLock
            val extra = status.extra ?: return@withLock
            if (extra.notification != 0 && lastNotice != (n.recordId to extra.notification)) {
                lastNotice = n.recordId to extra.notification
                repo.addEvent(n.recordId, event(EventKind.UNIT_NOTICE, text(R.string.runtime_unit_notice, extra.notification)))
            } else if (extra.notification == 0) lastNotice = null
            if (n.trackingManual) {
                // IR-08: a manual run is confirmed by its report and watched against its own step.
                if (awaitingReport(n) && confirmsManual(n, status, receivedAt)) publishNight(n.copy(confidence = Confidence.CONFIRMED))
                else if ((n.confidence == Confidence.CONFIRMED || awaitingReport(n)) && contradictsManual(n, status, receivedAt)) recordTakeover(n)
                return@withLock
            }
            if (n.confidence != Confidence.CONFIRMED) return@withLock
            val plan = n.activePlan(repo.profile(n.profileId)) ?: return@withLock
            val current = plan.stepAt(receivedAt) ?: return@withLock
            val grace = receivedAt.isBefore(current.second.startsAt.plusSeconds(TAKEOVER_GRACE_S))
            val contradicts = (extra.programStep == 0 && status.isRunning) ||
                (!grace && (extra.programStep != current.first + 1 || status.mode != current.second.step.mode ||
                    (status.mode.usesTemperature && status.targetTemperature != current.second.step.temperature)))
            if (!contradicts) return@withLock
            recordTakeover(n)
        }
    }
    private var lastNotice: Pair<String, Int>? = null
    suspend fun pin(profileId: String, slot: Int): CommandOutcome = owned(text(R.string.runtime_pin_label), repo.settings.value.deviceId()) {
        if (slot !in 0..2 || slot == repo.settings.value.workingSlot) return@owned refused(R.string.runtime_pin_slot)
        slotBackupsRefusal()?.let { return@owned it }
        val profile = repo.profile(profileId) ?: return@owned refused(R.string.runtime_profile_deleted)
        connected {
            if (freshStatus().extra?.programStep != 0) return@connected refused(R.string.runtime_slot_running)
            val preview = NightPreview.forStartNow(profile, graph.now(), null, settings = repo.settings.value)
            val plan = preview.plan ?: return@connected CommandOutcome(OutcomeKind.REFUSED, preview.refusalReason.orEmpty())
            val steps = plan.steps.map { it.step.copy(hours = it.minutes / 60, minutes = it.minutes % 60, endsAtClockTime = false) }
            advanceGeneration()
            writeSlotLocked(slot, StoredProgram(profile.name, steps), text(R.string.runtime_pinned, slot + 1))
        }
    }
    /**
     * Puts back what [slot] of the chosen BedJet held before the app first wrote it. A record from an imported
     * backup ([SlotBackup.fromBackup]) is never that BedJet's original; it is put back only when the person picks it
     * as [chosen], and the slot's real content is backed up first as for any slot write.
     */
    suspend fun restoreOriginal(slot: Int, chosen: SlotBackup? = null): CommandOutcome = owned(text(R.string.runtime_restore_label), repo.settings.value.deviceId()) {
        if (!ReplacementPolicy.permitsSlotRestore(repo.activeNight.value)) return@owned refused(R.string.runtime_restore_active)
        if (slot !in 0..2) return@owned refused(R.string.runtime_pin_slot)
        val target = repo.settings.value.deviceId() ?: return@owned refused(R.string.runtime_choose_target)
        slotBackupsRefusal()?.let { return@owned it }
        repo.originalsFor(target)
        val original = if (chosen?.fromBackup == true) {
            chosen.takeIf { it.slot == slot && it.program != null && it in repo.slotBackups.value } ?: return@owned refused(R.string.runtime_no_original)
        } else {
            repo.originalSlot(target, slot) ?: return@owned refused(R.string.runtime_no_original)
        }
        connected {
            if (freshStatus().extra?.programStep != 0) return@connected refused(R.string.runtime_slot_running)
            advanceGeneration()
            writeSlotLocked(slot, original.program, restoredMessage(original))
        }
    }
    /** IR-31: a programme an older version saved, or one from an imported backup, says so when it is put back. */
    private fun restoredMessage(original: SlotBackup): String = when {
        original.fromBackup -> text(R.string.runtime_restored_from_backup, original.slot + 1)
        original.uncertain -> text(R.string.runtime_restored_uncertain, original.slot + 1)
        else -> text(R.string.runtime_restored, original.slot + 1)
    }
    private suspend fun writeSlotLocked(slot: Int, program: StoredProgram?, label: String, start: Boolean = false): CommandOutcome {
        val target = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
        slotBackupsRefusal()?.let { return it }
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
            // IR-06: success settles any earlier write in question. A Boost keeps its CONFIRMED entry until the night
            // it displaced is finished, so a restart in between still marks that night interrupted.
            repo.updateRuntime { it.copy(slotWrite = if (start) intent.copy(confidence = Confidence.CONFIRMED) else null) }
            return CommandOutcome(OutcomeKind.CONFIRMED, label)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (!writing) {
                    // IR-05/IR-06: nothing reached the BedJet. The journal and the night are left as they were.
                    repo.updateRuntime { it.copy(slotWrite = earlier, reason = e.message) }
                } else {
                    repo.updateRuntime { it.copy(slotWrite = intent.copy(confidence = Confidence.UNKNOWN), reason = e.message) }
                    if (start) repo.activeNight.value?.let { publishNight(it.copy(confidence = Confidence.UNKNOWN)) }
                }
            }
            // Owned turns these into the IR-03 refusal; both were thrown before any write.
            if (e is CancellationException || e is SlotBackupsUnreadable) throw e
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
    suspend fun setWakeOverride(wake: ZonedDateTime?): CommandOutcome = owned(text(R.string.runtime_override_label), repo.settings.value.deviceId()) {
        val n = repo.activeNight.value
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
        slotBackupsRefusal()?.let { return@owned it }
        connected {
            takeoverBeforeRewrite(n)?.let { return@connected it }
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
    suspend fun updateSettings(change: (Settings) -> Settings): CommandOutcome = owned(text(R.string.runtime_target_label), repo.settings.value.deviceId(), admit = false) {
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
    suspend fun selectTarget(deviceId: String?): CommandOutcome = owned(text(R.string.runtime_target_label), repo.settings.value.deviceId(), admit = false) {
        if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
        if (deviceId != null && DeviceId.address(deviceId)?.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) != true) return@owned refused(R.string.runtime_invalid_target)
        advanceGeneration()
        graph.link.disconnect()
        repo.updateSettings { it.copy(targetDevice = deviceId) }
        if (deviceId == null) graph.bleLink.forget()
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_target_saved))
    }
    suspend fun setDemoMode(enabled: Boolean): CommandOutcome = owned(text(R.string.runtime_demo_label), repo.settings.value.deviceId(), admit = false) {
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
            CommandOutcome(OutcomeKind.ACCEPTED, text(R.string.runtime_fan_checked))
        }
    }
    suspend fun releaseForOtherPhone(untilMs: Long? = null, declineIfBusy: Boolean = false): CommandOutcome {
        if (declineIfBusy && lock.isLocked) return refused(R.string.runtime_busy)
        return owned(text(R.string.runtime_release_label), repo.settings.value.deviceId(), admit = false) {
            advanceGeneration()
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
                if (!runsNight(n, status, graph.now())) {
                    publishNight(n.copy(confidence = Confidence.UNKNOWN))
                    repo.updateRuntime { it.copy(reason = text(R.string.runtime_takeover), retime = null) }
                    repo.addEvent(n.recordId, event(EventKind.REMOTE_CHANGE, text(R.string.runtime_takeover)))
                }
            }
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_taken_back))
        }
    }
    suspend fun remote(label: String, endsNight: Boolean = false,
        target: String? = repo.settings.value.deviceId(), expected: NightIdentity? = repo.activeNight.value?.identity,
        work: suspend (BedJetController) -> Unit): CommandOutcome = owned(label, target) {
        if (endsNight && expected != repo.activeNight.value?.identity) return@owned stale()
        connected { advanceGeneration(); manualChange(label, endsNight) { work(graph.controller()) } }
    }
    /**
     * IR-09: a Remote-screen temperature, fan or timer change. During a tracked night (programmed, or a manual run
     * already tracked) the night switches to tracking the new manual step, PENDING until the BedJet reports it, and
     * any pending re-time is dropped, so neither a takeover nor a re-time is ever made of the user's own change. With
     * no night, or one already uncertain, the change is sent and nothing else changes.
     */
    suspend fun adjust(label: String, change: ManualAdjust, target: String? = repo.settings.value.deviceId(),
        expected: NightIdentity? = repo.activeNight.value?.identity): CommandOutcome = owned(label, target) {
        // The tap's night, captured then: a change queued behind a Start, re-time or Stop never converts another night.
        if (expected != repo.activeNight.value?.identity) return@owned stale()
        connected {
            advanceGeneration()
            val send: suspend () -> Unit = {
                val c = graph.controller()
                change.temperature?.let { c.setTemperature(it) }
                change.fanStep?.let { c.setFan(it) }
                // The unit resets the timer on some changes, so the timer goes last.
                change.minutes?.let { c.setRuntime(it) }
            }
            val night = repo.activeNight.value
            val now = graph.now()
            val reported = graph.link.latestStatusFrom(target)?.takeIf { it.isRunning }
            val planned = night?.let { if (it.trackingManual) manualStep(it)?.let { s -> s to ZonedDateTime.parse(it.endsAt) }
                else it.activePlan(repo.profile(it.profileId))?.stepAt(now)?.second?.let { p -> p.step to p.endsAt } }
            val mode = reported?.mode ?: planned?.first?.mode
            if (night == null || mode == null || !(night.confidence == Confidence.CONFIRMED || awaitingReport(night)))
                return@connected manualChange(label, false, send)
            val minutes = change.minutes ?: reported?.let { (it.remainingSeconds + 59) / 60 }
                ?: planned?.let { java.time.Duration.between(now, it.second).toMinutes().toInt() } ?: 0
            val step = ProgramStep(mode, change.fanStep ?: reported?.fanStep ?: planned!!.first.fanStep,
                change.temperature ?: reported?.targetTemperature ?: planned!!.first.temperature,
                minutes.coerceAtLeast(1) / 60, minutes.coerceAtLeast(1) % 60)
            val n = reportsAfter({ sendManual(night, send) }) { reports -> trackManual(night, step, EventKind.ADJUSTED, label, reports) }
            CommandOutcome(OutcomeKind.ACCEPTED, manualMessage(n, label))
        }
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
        /** The least of [OPERATION_MS] a request must have left once it holds the lock (IR-11). */
        const val MIN_BUDGET_MS = 30_000L
        /** Commands that may write a slot, refused while the saved slot originals can't be read (IR-03). */
        private val SLOT_WRITES = setOf(Command.START_NIGHT, Command.REAPPLY, Command.BOOST, Command.RESUME)
        /** A step boundary is not a takeover: the unit's report may lag the planned boundary briefly. */
        private const val TAKEOVER_GRACE_S = 20L
        /** How long a manual command waits for the BedJet to report the run it asked for (IR-08). */
        private const val MANUAL_CONFIRM_MS = 5_000L
        /** A reported timer this close to the tracked end matches; a run this close to its end may stop by itself. */
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
