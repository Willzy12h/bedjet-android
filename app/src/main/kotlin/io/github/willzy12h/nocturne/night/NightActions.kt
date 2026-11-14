package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.CommandSafety

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.BluetoothAdmission
import io.github.willzy12h.nocturne.ble.BluetoothCommandException
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
 * Callers own the coroutine lifecycle: foreground UI, bounded service, or cancellable JobService.
 *
 * This file holds the state, command ownership and the public entry points. The work behind them is in extension
 * functions in this package, one file per responsibility: NightStart.kt (preparing commands) with
 * AutoStartAttribution.kt, NightProgrammeApply.kt, NightManual.kt, NightRetime.kt, NightEnd.kt,
 * NightProgrammeObservation.kt, NightTakeover.kt and NightSlots.kt. Members marked internal for those files are
 * not for use elsewhere. */
class NightActions(internal val graph: Graph) {
    internal class RetimeTakeover : Exception("The BedJet programme changed during retime upload.")
    internal val repo get() = graph.repository
    internal val context get() = graph.context
    internal val lock = Mutex()
    /** Link reports and request boundaries share one process order; queued reports keep their original receipt. */
    class StatusReceipt internal constructor(val epoch: String, val sequence: Long)
    internal val statusEpoch = StatusReceiptOrder.processId
    internal val observationStartedSequence = StatusReceiptOrder.next()
    internal fun statusReceipt() = StatusReceipt(statusEpoch, StatusReceiptOrder.next())
    internal fun receiptOf(report: SourcedStatus) = StatusReceipt(statusEpoch, report.receiptOrder)
    internal fun receiptOf(status: DeviceStatus, target: String?): StatusReceipt? =
        graph.link.latestSourcedStatus()?.takeIf { it.status === status && it.isFrom(target) }?.let(::receiptOf)
    internal data class StatusObservation(val status: DeviceStatus, val target: String?, val receivedAt: ZonedDateTime,
        val source: String?, val receipt: StatusReceipt?)
    internal data class ProgrammeBoundary(val identity: NightIdentity, val target: String, val receipt: Long? = null)
    internal data class ProgrammeReports(val boundary: ProgrammeBoundary, var latest: StatusObservation? = null)
    internal data class ProgrammeObservation(val boundary: ProgrammeBoundary, val report: StatusObservation)
    /** Queueing a report and the command's final unlock share this short, non-suspending handoff. */
    internal val statusOwnership = Any()
    internal var confirmedProgrammeBoundary: ProgrammeBoundary? = null
    internal var reconciledProgrammeReceipt = Long.MIN_VALUE
    internal var ownedProgrammeReports: ProgrammeReports? = null
    /** A failed local save cannot turn already-observed uncertainty back into visible confirmation. */
    internal val unreconciledProgramme = MutableStateFlow<ProgrammeObservation?>(null)
    /** Monotonic milliseconds for admission and manual-run elapsed time; tests move it by hand. */
    internal var elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }
    private val _busy = MutableStateFlow(BusyState())
    val busy: StateFlow<BusyState> = _busy.asStateFlow()
    private val _outcomes = MutableSharedFlow<CommandOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<CommandOutcome> = _outcomes.asSharedFlow()
    /** A slot write colours the status only while it is in question and concerns the tracked night, or, with no night,
     * only when a night could depend on it; an unknown pin of another slot is reported by its own outcome (IR-06). */
    val status: StateFlow<NightStatus> = combine(repo.activeNight, repo.runtime, repo.settings, unreconciledProgramme) { n, r, s, observed ->
        val slot = r.unresolvedSlotWrite?.takeIf { if (n == null) it.affectsNights(s.workingSlot) else it.displaced == n.identity }
        val unresolved = observed?.concerns(n, s.deviceId()) == true && n?.confidence != Confidence.PENDING
        NightStatus(if (unresolved) Confidence.UNKNOWN
            else if (wakeChangePending(n, r, s) && n?.confidence == Confidence.CONFIRMED) Confidence.PENDING
            else slot?.confidence ?: n?.confidence, if (unresolved) text(R.string.runtime_takeover) else r.reason) }
        .stateIn(graph.scope, SharingStarted.Eagerly, NightStatus(repo.activeNight.value?.confidence, repo.runtime.value.reason))

    enum class Command { START_NIGHT, END_NIGHT, OFF, TURBO, BOOST, COOL, WARM, RETIME, RETRY_RETIME, REAPPLY, SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, RESUME, STOP_TRACKING, KEEP_TRACKING, CHECK_BEDJET, TEMPORARY }
    fun capture(command: Command, profileId: String? = null, requireIdentity: Boolean = false) =
        CommandRequest(command, profileId, repo.settings.value.deviceId(), repo.activeNight.value?.identity, requireIdentity,
            origin = defaultCommandOrigin(command))

    fun shortTestPreview(): ShortTestPreview? = repo.settings.value.deviceId()?.let {
        ShortTestPreview(it, repo.settings.value.workingSlot, graph.now())
    }
    suspend fun startShortTest(preview: ShortTestPreview): CommandOutcome =
        owned(text(R.string.short_test_start), preview.deviceId, MIN_BUDGET_MS, origin = CommandOrigin.APP) {
            if (repo.activeNight.value != null) return@owned refused(R.string.short_test_active)
            if (repo.settings.value.workingSlot != preview.slot || preview.previewedAt.isAfter(graph.now()) ||
                !graph.now().isBefore(preview.previewedAt.plusMinutes(5))) return@owned stale(R.string.short_test_preview_changed)
            slotWriteRefusal(Command.START_NIGHT)?.let { return@owned it }
            connected {
                advanceGeneration()
                apply(graph.runner().prepareShortTest(text(R.string.short_test_name), preview.slot,
                    preview.deviceId, repo.activeNight.value), R.string.short_test_started)
            }
        }

    fun captureTemporary(preset: ActionPreset): CommandRequest =
        capture(Command.TEMPORARY, requireIdentity = true).copy(actionPreset = preset)

    /** Pure preview of the captured target, identity and preset. Execution prepares again under ownership. */
    fun previewTemporary(request: CommandRequest): TemporaryActionPreview = when (val preparation = prepareTemporary(request)) {
        is Preparation.Ready -> TemporaryActionPreview(preparation.change.plan, null)
        is Preparation.Refused -> TemporaryActionPreview(null, preparation.refusalText(context))
        is Preparation.Stale -> TemporaryActionPreview(null, text(R.string.runtime_stale))
    }

    /** Unconfirmed emergency Off resolves the captured target; a reviewed approval retains its captured night. */
    suspend fun perform(command: Command, profileId: String? = null, replacementConfirmed: Boolean = false): String =
        execute(capture(command, profileId, requireIdentity = replacementConfirmed).copy(replacementConfirmed = replacementConfirmed)).message
    /** Every command that may reach the BedJet is admitted only with [MIN_BUDGET_MS] left; Stop tracking is local (IR-11). */
    suspend fun execute(request: CommandRequest): CommandOutcome = owned(label(request.command), request.deviceId,
        minimumRemainingMs = if (request.command != Command.STOP_TRACKING) MIN_BUDGET_MS else 0,
        retimeWork = request.command == Command.RETIME || request.command == Command.RETRY_RETIME,
        origin = currentCoroutineContext()[CommandOriginContext]?.origin?.takeUnless { it == CommandOrigin.APP } ?: request.origin) {
        // A visible Off approval belongs to its reviewed night; unconfirmed emergency Off remains target-based.
        if (request.command != Command.CHECK_BEDJET && (request.command != Command.OFF || request.replacementConfirmed) &&
            (request.requireIdentity || request.command == Command.TEMPORARY) &&
            request.expected != repo.activeNight.value?.identity) return@owned stale()
        if (request.command.usesCapturedPreset() && request.actionPreset?.let { !validCapturedPreset(request.command, it) } == true)
            return@owned refused(R.string.runtime_temporary_invalid)
        val manualPreset = timedPreset(request.command)
        manualPreset?.let { timedPresetRefusal(context, it) }?.let { reason ->
            return@owned CommandOutcome(OutcomeKind.REFUSED, reason)
        }
        if (ReplacementPolicy.decide(request.command, repo.activeNight.value).needsConfirmation && !request.replacementConfirmed)
            return@owned CommandOutcome(OutcomeKind.CONFIRMATION_REQUIRED, text(R.string.runtime_replace_confirmation))
        when (request.command) {
            Command.CHECK_BEDJET -> connected {
                val report = freshReport(requireProgress = false)
                val detail = text(R.string.runtime_bedjet_checked, bedJetTargetName(context, request.deviceId),
                    text(io.github.willzy12h.nocturne.ui.Format.modeNameRes(report.status.mode)), report.status.fanPercent)
                val notify = request.origin == CommandOrigin.NOTIFICATION
                if (notify) Notifications.showInfo(context, text(R.string.runtime_command_check_bedjet), detail)
                // A fresh status read cannot confirm an earlier programme write, or replay it.
                CommandOutcome(OutcomeKind.LOCAL, detail, notificationHandled = notify)
            }
            Command.STOP_TRACKING -> stopTrackingLocked()
            Command.KEEP_TRACKING -> keepTrackingLocked()
            Command.TEMPORARY -> temporaryLocked(request)
            Command.RETRY_RETIME -> consumeAlarmObservation() ?: retryRetimeLocked()
            Command.RETIME -> {
                val result = consumeAlarmObservation() ?: kotlin.run {
                    val previous = repo.runtime.value.retime?.id
                    val queued = queueRetimeLocked()
                    // A quiet no-op must not execute an older pending request merely because its outcome is LOCAL.
                    if (queued.kind == OutcomeKind.LOCAL && repo.runtime.value.retime?.id?.let { it != previous } == true)
                        retryRetimeLocked() else queued
                }
                if (request.origin == CommandOrigin.APP) {
                    val detail = AlarmExplanation.resolve(repo.settings.value, repo.tonightsProfile(), graph.now(),
                        AlarmSource.describe(context, repo.settings.value), repo.activeNight.value, repo.runtime.value, status.value)
                        .recheckDescription(context)
                    result.copy(message = if (result.succeeded) detail else text(R.string.alarm_recheck_outcome, detail, result.message))
                } else result
            }
            else -> slotWriteRefusal(request.command) ?: connected { run(request, manualPreset) }
        }
    }
    /** No FGS, detached launch or retry. Job cancellation propagates after durable uncertainty is saved. */
    suspend fun performJobOwned(request: CommandRequest, automaticWake: io.github.willzy12h.nocturne.core.plan.WakeChoice? = null,
        stillEligible: () -> Boolean = { true }, onPrepared: (PreparedChange) -> Unit = {},
        eligible: suspend (DeviceStatus) -> Boolean): CommandOutcome =
        owned(label(request.command), request.deviceId, minimumRemainingMs = MIN_BUDGET_MS, origin = CommandOrigin.CHARGER) {
            if (request.requireIdentity && request.expected != repo.activeNight.value?.identity) return@owned stale()
            val manualPreset = timedPreset(request.command)
            manualPreset?.let { timedPresetRefusal(context, it) }?.let { reason ->
                return@owned CommandOutcome(OutcomeKind.REFUSED, reason)
            }
            slotWriteRefusal(request.command)?.let { return@owned it }
            connected {
                val fresh = freshStatus()
                if (repo.activeNight.value != null || !eligible(fresh) || !stillEligible()) return@connected refused(R.string.runtime_ineligible)
                run(request, manualPreset, automaticWake, stillEligible, onPrepared)
            }
        }

    /** A read-only compatibility route. Production writes use named owned methods below. */
    internal suspend fun <T> exclusive(work: suspend () -> T): T {
        val admission = repo.operationAdmission.tryCommand() ?: error(text(R.string.import_command_blocked))
        try { return lock.withLock {
            try { withTimeout(OPERATION_MS) { graph.sessions.withConnection { work() } } }
            finally { drainLocalSafely() }
        } } finally { admission.close() }
    }

    /**
     * A read-only status read for the widgets, so they can show the BedJet without the app open. It steps aside
     * rather than wait: in demo, with no BedJet chosen, while a screen or command already holds the connection,
     * while the BedJet is released for another phone, during an import and while the owner is busy. It writes
     * nothing and reports no outcome; the report reaches the widgets the way every report does.
     */
    suspend fun readForWidget(): WidgetRead {
        val settings = repo.settings.value
        if (settings.demoMode || settings.deviceId() == null || graph.sessions.hasStatusDemand || graph.sessions.released.value)
            return WidgetRead.STEPPED_ASIDE
        val admission = repo.operationAdmission.tryCommand() ?: return WidgetRead.STEPPED_ASIDE
        try {
            val owner = Any()
            if (!lock.tryLock(owner)) return WidgetRead.STEPPED_ASIDE
            try {
                // Battery: one short try, the report the connection already streams rather than a second
                // connection, and the BedJet let go as soon as the read is done.
                return withTimeoutOrNull(WIDGET_READ_MS) {
                    connected(releaseAfterMs = 0) { freshReport(requireProgress = false, reconnect = false); WidgetRead.READ }
                } ?: WidgetRead.NOT_REACHED
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { return WidgetRead.NOT_REACHED
            } finally { try { drainLocalSafely() } finally { lock.unlock(owner) } }
        } finally { admission.close() }
    }

    /** What [pauseForPairing] did. */
    enum class PairingPause { PAUSED, ALREADY_FREE, BUSY }

    /**
     * Lets go of the BedJet so Android's companion-device search can see it: a connected BedJet stops advertising,
     * so the search would never find it. Nothing is sent. The release ends by itself after [forMs] should
     * [endPairingPause] never come. A running command is never interrupted ([PairingPause.BUSY]), and a BedJet already
     * released for another phone is left as it is ([PairingPause.ALREADY_FREE]).
     */
    suspend fun pauseForPairing(forMs: Long): PairingPause {
        if (repo.settings.value.demoMode || graph.sessions.released.value) return PairingPause.ALREADY_FREE
        val admission = repo.operationAdmission.tryCommand() ?: return PairingPause.BUSY
        try {
            val owner = Any()
            if (!lock.tryLock(owner)) return PairingPause.BUSY
            try { graph.sessions.suspendAutoConnect(graph.clock.millis() + forMs).join() } finally { lock.unlock(owner) }
            return PairingPause.PAUSED
        } finally { admission.close() }
    }

    /** Ends a [pauseForPairing] release: Nocturne reconnects if a screen is holding the BedJet. */
    fun endPairingPause() = graph.sessions.resumeAutoConnect()

    private suspend fun owned(label: String, target: String?, minimumRemainingMs: Long = 0, retimeWork: Boolean = false,
        origin: CommandOrigin? = null, declineIfBusy: Boolean = false,
        work: suspend () -> CommandOutcome): CommandOutcome {
        val source = origin ?: currentCoroutineContext()[CommandOriginContext]?.origin ?: CommandOrigin.APP
        fun tagged(outcome: CommandOutcome): CommandOutcome =
            if (outcome.origin == CommandOrigin.UNSPECIFIED) outcome.copy(origin = source) else outcome
        val admission = repo.operationAdmission.tryCommand()
            ?: return tagged(refused(R.string.import_command_blocked)).also { _outcomes.emit(it) }
        _busy.update { it.copy(queuedCount = it.queuedCount + 1) }
        var acquired = false
        val owner = Any()
        val startedAt = elapsedMs()
        var radio: BluetoothAdmission? = null
        try {
            if (minimumRemainingMs > 0 && !repo.settings.value.demoMode) {
                radio = currentCoroutineContext()[BluetoothAdmission] ?: BluetoothAdmission(context)
                // Alarm observations can settle entirely from saved metadata. Keep the interruption guard
                // through their queue, then check it only if resolution actually needs the BedJet.
                if (!retimeWork) radio.check()
            }
            return withTimeout(OPERATION_MS) {
                if (declineIfBusy) {
                    if (!lock.tryLock(owner)) return@withTimeout tagged(refused(R.string.runtime_busy)).also { _outcomes.emit(it) }
                } else lock.lock(owner)
                acquired = true
                _busy.update { it.copy(runningLabel = label, queuedCount = it.queuedCount - 1) }
                val submitted = currentCoroutineContext()[SubmissionTarget]
                val remainingMs = OPERATION_MS - (elapsedMs() - startedAt)
                val result = tagged(if (minimumRemainingMs > 0 && remainingMs < minimumRemainingMs) waitedTooLong(retimeWork)
                else if (target != repo.settings.value.deviceId() ||
                    (submitted != null && submitted.deviceId != repo.settings.value.deviceId())) stale(R.string.runtime_target_changed) else {
                    if (!retimeWork) radio?.admit()
                    currentCoroutineContext()[CommandFeedback]?.admitted(source)
                    withContext(radio ?: kotlin.coroutines.EmptyCoroutineContext) { work() }
                })
                val settled = reconcileProgrammeOutcome(result)
                _outcomes.emit(settled)
                settled
            }
        } catch (e: CancellationException) { throw e
        } catch (e: SlotBackupsUnreadable) {
            // Thrown before any slot write; the night's confidence is left as it was (IR-03).
            return tagged(refused(R.string.runtime_slot_backups_unreadable)).also { _outcomes.emit(it) }
        } catch (e: Exception) {
            val reason = e.planMessage(context)
            recordError("$label: ${reason.orEmpty()}")
            val bluetoothOff = e is BluetoothCommandException
            if (e is BluetoothCommandException) Notifications.showBluetoothFailure(context, source, e)
            // A slot write reports its own outcome; an old one in question says nothing about this failure (IR-06).
            return tagged(CommandOutcome(if (!bluetoothOff && repo.activeNight.value?.confidence == Confidence.UNKNOWN) OutcomeKind.UNKNOWN else OutcomeKind.FAILED,
                reason ?: text(R.string.runtime_failed), notificationHandled = bluetoothOff)).also { _outcomes.emit(it) }
        } finally {
            try {
            radio?.close()
            // Ownership is tested even if timeout raced with resumption after lock.lock().
            _busy.update { if (acquired) it.copy(runningLabel = null) else it.copy(queuedCount = (it.queuedCount - 1).coerceAtLeast(0)) }
            if (lock.holdsLock(owner)) {
                // Confirmed-command repair and local delivery share one cleanup grace after the owner budget.
                fun cleanupMs() = minOf(8_000L, OPERATION_MS + 8_000L - (elapsedMs() - startedAt))
                try { drainLocalSafely(cleanupMs()) } finally { releaseProgrammeReports(owner, cleanupMs()) }
            }
            } finally { admission.close() }
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
    internal suspend fun <T> connected(releaseAfterMs: Long? = null, work: suspend () -> T): T {
        check(repo.settings.value.deviceId() != null) { text(R.string.runtime_choose_target) }
        val radio = currentCoroutineContext()[BluetoothAdmission]
        if (radio != null) radio.admit()
        else io.github.willzy12h.nocturne.ble.BluetoothAvailability.requireEnabled(context)
        return if (releaseAfterMs == null) graph.sessions.withConnection { work() }
            else graph.sessions.withConnection(releaseAfterMs) { work() }
    }
    /** A newly displaced programme gets a new choice. Manual-only adjustments, including after tracking ended,
     * preserve its valid choice and expiry; programme replacement/reapply/resume and target changes supersede it. */
    internal suspend fun advanceGeneration(preserveOffer: Boolean = false) {
        repo.updateRuntime { it.copy(generation = it.generation + 1, reason = null,
            resumeOffer = validResumeOffer(it)?.takeIf { preserveOffer }
                ?.copy(commandVersion = it.generation + 1)) }
    }
    internal fun validResumeOffer(runtime: RuntimeState): ResumeOffer? = runtime.resumeOffer?.takeIf {
        it.commandVersion == runtime.generation && it.night.deviceId == repo.settings.value.deviceId() &&
            graph.now().isBefore(ZonedDateTime.parse(it.expiresAt)) && graph.now().isBefore(ZonedDateTime.parse(it.night.wakeAt))
    }
    /** A pending alarm belongs to the old revision. Retain its observation for the newly confirmed programme. */
    internal fun RuntimeState.withTemporaryAlarmHandoff(change: PreparedChange?): RuntimeState {
        if (change?.request?.kind != ChangeKind.TEMPORARY) return this
        val pending = retime?.takeIf { it.expected == change.previous?.identity && it.deviceId == change.intended.deviceId } ?: return this
        val matchingInbox = alarmInbox?.takeIf { it.nightId == change.intended.recordId && it.deviceId == pending.deviceId }
        return copy(alarmInbox = matchingInbox ?: AlarmObservation(UUID.randomUUID().toString(),
            change.intended.recordId, pending.deviceId, graph.now().toString()))
    }
    internal suspend fun backup(device: String, slot: Int) {
        repo.originalsFor(device)
        val existing = repo.originalSlot(device, slot)
        val original = existing?.program ?: if (existing == null) graph.controller().storedPrograms()[slot] else null
        repo.recordOriginalSlot(device, slot, original)
    }
    /** How long [sentAndConfirmed] waits for a matching report before reading the BedJet afresh; tests shorten it. */
    internal var confirmMs = 5_000L
    suspend fun finish(night: ActiveNight, kind: EventKind, detail: String) = lock.withLock {
        if (repo.activeNight.value?.identity == night.identity) finishLocked(night, kind, detail)
    }
    suspend fun expire(expected: NightIdentity? = repo.activeNight.value?.identity): Boolean = lock.withLock { expireLocked(expected) }
    val retimePending: Boolean get() = repo.runtime.value.retime != null || repo.runtime.value.alarmInbox != null
    suspend fun retryPendingRetime(): String? = if (!retimePending) null else owned(label(Command.RETIME), repo.settings.value.deviceId(), MIN_BUDGET_MS,
        retimeWork = true, origin = CommandOrigin.ALARM) { consumeAlarmObservation() ?: retryRetimeLocked() }.message
    /** Transient disposition for an expiry completed while another delivery waits. Display text is never policy. */
    internal data class RetimeExpiry(val expected: NightIdentity, val deviceId: String, val generation: Long)
    @Volatile internal var lastRetimeExpiry: RetimeExpiry? = null
    /** One terminal request can revisit cleanup, including from a direct caller without service context. */
    internal data class TerminalRetime(val requestId: String, val outcome: CommandOutcome, val notice: String)
    internal var lastTerminalRetime: TerminalRetime? = null
    /** This describes the current programme only; it never credits a timed-out request with a successful change. */
    internal fun currentConfirmedProgramme(): ActiveNight? = repo.activeNight.value?.takeIf {
        it.confidence == Confidence.CONFIRMED && !it.trackingManual && !hasUnreconciledProgramme(it) &&
            it.deviceId == repo.settings.value.deviceId() && !hasUnfinishedReplacement(it)
    }
    /** Quietly return a no-write timeout to the matching eligibility or expiry owner, including a due deadline. */
    internal fun quietRetimeTimeout(expected: NightIdentity?, target: String?, admitted: Boolean): String? {
        val night = repo.activeNight.value ?: return null
        if (night.identity != expected || night.deviceId != target || repo.settings.value.deviceId() != target) return null
        // A queued delivery never reached BLE. After admission, an unchanged confirmed programme is required;
        // a possible write publishes UNKNOWN and must receive its own terminal notice instead.
        val unchanged = night.confidence == Confidence.CONFIRMED && !night.trackingManual &&
            !hasUnreconciledProgramme(night) && !hasUnfinishedReplacement(night)
        if (admitted && !unchanged) return null
        val runtime = repo.runtime.value
        val now = graph.now()
        val pending = runtime.retime?.takeIf { it.expected == expected && it.deviceId == target }
        val observed = runtime.alarmInbox?.let { it.nightId == night.recordId && it.deviceId == target } == true
        when {
            pending != null && now.isBefore(ZonedDateTime.parse(pending.deadline)) -> NightAlarms.scheduleRetry(context, pending, now)
            // scheduleRetry cancels a due deadline. A future observation lets normal expiry own its sole notice.
            pending != null || observed -> NightAlarms.scheduleObservation(context, now)
            unchanged && runtime.retime == null && runtime.alarmInbox == null -> return text(
                if (retimeWasExpired(night)) R.string.runtime_retime_expired else R.string.runtime_unchanged)
            else -> return null
        }
        return text(R.string.runtime_retime_pending)
    }
    /** A coalesced observation inbox, not a second command owner. This only uses the repository writer.
     * The receiver can submit while a whole hardware transaction holds the coordinator mutex. */
    suspend fun submitAlarmChange(): CommandOutcome {
        val admission = repo.operationAdmission.tryCommand()
            ?: return refused(R.string.import_command_blocked).copy(origin = CommandOrigin.ALARM)
        try { return submitAlarmChangeLocal().copy(origin = CommandOrigin.ALARM) }
        finally { admission.close() }
    }
    suspend fun recordPendingRetime(): CommandOutcome = submitAlarmChange()
    internal fun retimeWarningDetail(detail: String, requestedWake: String? = requestedRetimeWake(),
        target: String? = repo.runtime.value.retime?.deviceId ?: repo.activeNight.value?.deviceId ?: repo.settings.value.deviceId()): String {
        val name = bedJetTargetName(context, target)
        return if (requestedWake == null) text(R.string.runtime_retime_target_context, name, detail)
        else text(R.string.runtime_retime_request_context, clock(requestedWake), name, detail)
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
    /** Confirmation may reach disk before History. Retain that proof without inferring a new command event. */
    private suspend fun recoverConfirmedRevision() {
        val night = repo.activeNight.value ?: return
        if (night.confidence != Confidence.CONFIRMED || night.trackingManual || night.pending != null ||
            hasUnfinishedReplacement(night) || night.applied.revision != night.revision) return
        val record = repo.history.value.firstOrNull { it.id == night.recordId && DeviceId.same(it.deviceId, night.deviceId) } ?: return
        // A recorded or newer revision is already authoritative; an absent record stays absent.
        if (record.revisions.any { it.revision >= night.applied.revision }) return
        val plan = runCatching { night.applied.plan() }.getOrNull() ?: return
        try { repo.addRecord(NightMutation.Applied(plan, night).recordRevision(record)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Retry the missing revision on a later reconcile. History storage cannot prevent
            // restoration of the confirmed programme's wake/end alarms and alarm observation.
        }
    }
    suspend fun reconcile() {
        lock.withLock {
            if (!retryUnreconciledProgramme()) return
            recoverConfirmedRevision()
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
                    it.copy(endRequested = null, endRequestedLabel = null, endRequestedAt = null,
                        endRequestedEpoch = null, endRequestedSequence = null) }
                else try {
                    // Standby is in the head; no programme-progress tail is needed to confirm an ending command.
                    connected { freshReport(requireProgress = false).let { finishRequestedEnd(it.status, receiptOf(it)) } }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { /* Keep the durable intention and UNKNOWN state until a target report arrives. */ }
            }
            repo.runtime.value.resumeOffer?.takeIf { !graph.now().isBefore(ZonedDateTime.parse(it.expiresAt)) }?.let {
                repo.updateRuntime { it.copy(resumeOffer = null) }
            }
        }
        drainLocalDeliveries()
        expire()
        repo.activeNight.value?.let { NightAlarms.schedule(context, it); refreshQuietNotification() }
        repo.runtime.value.retime?.let { pending ->
            val current = repo.activeNight.value
            if (current?.confidence == Confidence.CONFIRMED &&
                (pending.expected != current.identity || pending.deviceId != current.deviceId || pending.deviceId != repo.settings.value.deviceId()))
                settleRetimeAlarm(pending.id)
            else NightAlarms.scheduleRetry(context, pending, graph.now())
        }
        if (repo.runtime.value.alarmInbox != null) NightAlarms.scheduleObservation(context, graph.now())
    }
    suspend fun wakeUp(expected: NightIdentity?) = lock.withLock { wakeUpLocked(expected) }
    /** Keep the original receipt even if a later packet became the link's current value before collection. */
    suspend fun observeStatus(report: SourcedStatus, target: String?, receivedAt: ZonedDateTime = graph.now()) =
        observeStatus(report.status, target, receivedAt, report.source ?: target, receiptOf(report))

    suspend fun observeStatus(status: DeviceStatus, target: String?, receivedAt: ZonedDateTime = graph.now(), source: String? = target,
        receipt: StatusReceipt? = receiptOf(status, target)) {
        val observed = StatusObservation(status, target, receivedAt, source, receipt)
        val acquired = synchronized(statusOwnership) {
            if (lock.tryLock()) true else { queueProgrammeReport(observed); false }
        }
        if (!acquired) return
        try {
            if (!retryUnreconciledProgramme()) return
            val night = repo.activeNight.value
            if (night?.confidence != Confidence.CONFIRMED || night.trackingManual ||
                night.deviceId != target || target != repo.settings.value.deviceId() ||
                !DeviceId.same(source, target) || graph.link.state.value != LinkState.READY) {
                observeStatusLocked(observed)
            } else {
                // A restored night has no start receipt in this process. Its live observation still belongs
                // to this exact identity; the current-target/readiness checks above remain mandatory.
                val boundary = synchronized(statusOwnership) { confirmedProgrammeBoundary }
                    ?.takeIf { it.identity == night.identity && it.target == target }
                    ?: ProgrammeBoundary(night.identity, night.deviceId)
                try { withTimeoutOrNull(8_000) { observeProgrammeLocked(ProgrammeObservation(boundary, observed)) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!hasUnreconciledProgramme(night)) throw e
                    // The live collector must keep receiving reports while this known contradiction awaits a save.
                }
            }
        } finally { lock.unlock() }
    }

    internal fun hasProgrammeUncertainty(night: ActiveNight?): Boolean = hasUnreconciledProgramme(night)

    internal var lastNotice: Pair<String, Int>? = null
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
            val plan = preview.plan ?: return@connected CommandOutcome(OutcomeKind.REFUSED, preview.refusalText(context).orEmpty())
            val steps = plan.steps.map { it.step.copy(hours = it.minutes / 60, minutes = it.minutes % 60, endsAtClockTime = false) }
            val program = StoredProgram(profile.name, steps)
            if (previewExpired() || (expectedProgram != null && program != expectedProgram))
                return@connected stale(R.string.pin_preview_expired)
            advanceGeneration()
            writeSlotLocked(slot, program, text(R.string.runtime_pinned, slot + 1))
        }
    }
    /** Capture before owner admission: a direct slot request cannot bind to a later imported record either. */
    suspend fun restoreOriginal(slot: Int): CommandOutcome {
        val target = repo.settings.value.deviceId()
        return restoreOriginal(slot, target, target?.let { repo.originalSlot(it, slot) })
    }
    /** The exact record that was previewed, including its payload and provenance. */
    suspend fun restoreOriginal(original: SlotBackup): CommandOutcome =
        restoreOriginal(original.slot, original.deviceId, original)
    private suspend fun restoreOriginal(slot: Int, target: String?, expectedOriginal: SlotBackup?): CommandOutcome =
        owned(text(R.string.runtime_restore_label), target, MIN_BUDGET_MS) {
        if (!ReplacementPolicy.permitsSlotRestore(repo.activeNight.value)) return@owned refused(R.string.runtime_restore_active)
        if (slot !in 0..2) return@owned refused(R.string.runtime_pin_slot)
        val device = repo.settings.value.deviceId() ?: return@owned refused(R.string.runtime_choose_target)
        repo.originalsFor(device)
        val original = expectedOriginal ?: return@owned refused(R.string.runtime_no_original)
        fun originalChanged() = original.deviceId != device || original.slot != slot || repo.originalSlot(device, slot) != original
        if (originalChanged()) return@owned refused(R.string.runtime_restore_preview_changed)
        // The validator lets a saved original hold any temperature the codec carries, as a BedJet recorded it. One
        // from a backup file or snapshot may have been edited, so heating/cooling steps must stay in range,
        // including Turbo's fixed temperature. Standby and Wait ignore their placeholder temperature byte.
        if (original.imported && original.program?.steps.orEmpty().any {
                it.mode != Mode.STANDBY && it.mode != Mode.WAIT && it.temperature !in it.mode.temperatureRange
            })
            return@owned refused(R.string.runtime_restore_imported_out_of_range)
        if (original.program?.let { CommandSafety.programme(it, graph.now().toLocalTime()) } != null)
            return@owned refused(R.string.runtime_restore_unsafe)
        connected {
            if (freshStatus().extra?.programStep != 0) return@connected refused(R.string.runtime_slot_running)
            if (originalChanged()) return@connected refused(R.string.runtime_restore_preview_changed)
            advanceGeneration()
            if (originalChanged()) return@connected refused(R.string.runtime_restore_preview_changed)
            writeSlotLocked(slot, original.program, text(R.string.runtime_restored, slot + 1), expectedOriginal = original)
        }
    }
    /** The user has checked the BedJet: a slot write in question no longer holds anything back (IR-06). Local only. */
    suspend fun acknowledgeSlotWrite(): CommandOutcome = lock.withLock {
        repo.updateRuntime { it.copy(slotWrite = null) }
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_slot_write_acknowledged), origin = CommandOrigin.APP)
    }
    suspend fun initialise() {
        lock.withLock {
            if (repo.settings.value.targetDevice == null && !repo.settings.value.demoMode &&
                !repo.settings.value.demoTargetMigrated && !repo.startupMigrationBlocked) graph.bleLink.rememberedAddress()?.let { address ->
                repo.updateSettings { it.copy(targetDevice = DeviceId.ble(address)) }
                repo.addNotice(LoggedNotice(graph.now().toString(), NoticeKind.INFO, text(R.string.runtime_target_saved), text(R.string.runtime_adopted)))
            }
            repo.runtime.value.retime?.takeIf { pending ->
                val current = repo.activeNight.value
                current == null || pending.expected != current.identity || pending.deviceId != current.deviceId ||
                    pending.deviceId != repo.settings.value.deviceId()
            }?.let { obsolete ->
                try {
                    repo.updateRuntime { if (it.retime?.id == obsolete.id) it.copy(retime = null) else it }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    // Keep the durable journal if storage is unavailable; it cannot make the confirmed night pending.
                } finally { settleRetimeAlarm(obsolete.id) }
            }
        }
        reconcile()
    }
    suspend fun setWakeOverride(wake: ZonedDateTime?, expected: NightIdentity? = repo.activeNight.value?.identity): CommandOutcome =
        owned(text(R.string.runtime_override_label), repo.settings.value.deviceId(), if (expected != null) MIN_BUDGET_MS else 0) {
        val n = repo.activeNight.value
        if (n?.identity != expected) return@owned stale()
        if (n?.isTestRun == true) return@owned refused(R.string.short_test_fixed_plan)
        if (n != null && (n.confidence != Confidence.CONFIRMED || n.trackingManual)) return@owned refused(R.string.runtime_reconcile_needed)
        if (n == null) {
            repo.updateSettings { if (wake == null) it.clearWakeOverride() else it.setWakeOverride(wake, graph.now()) }
            return@owned CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_settings_saved), origin = CommandOrigin.SETTINGS)
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
        val prepared = if (wake != null) graph.runner().prepareWakeOverride(n, profile, n.identity, wake)
            else graph.runner().prepareRetime(n.copy(wakeOrigin = WakeOrigin.PROFILE_DEFAULT), n.identity,
                alarm?.time, liveProfile = profile, settings = repo.settings.value, alarmOrigin = alarm?.creator)
        if (prepared is Preparation.Refused) return@owned CommandOutcome(OutcomeKind.REFUSED, prepared.refusalText(context))
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
    suspend fun updateSettings(change: (Settings) -> Settings): CommandOutcome = owned(text(R.string.runtime_target_label), repo.settings.value.deviceId(),
        origin = CommandOrigin.SETTINGS) {
        val old = repo.settings.value
        val updated = change(old)
        if (updated.demoMode != old.demoMode) return@owned changeDemoModeLocked(updated.demoMode)
        if (updated.deviceId() != old.deviceId() || updated.targetDevice != old.targetDevice) {
            if (repo.startupMigrationBlocked) return@owned refused(R.string.gpt12_demo_migration_failed)
            if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
            advanceGeneration(); graph.link.disconnect()
        }
        repo.updateSettings { updated }
        refreshQuietNotification()
        CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_settings_saved))
    }
    suspend fun selectTarget(deviceId: String?, useRealDevice: Boolean = false): CommandOutcome = owned(text(R.string.runtime_target_label), repo.settings.value.deviceId()) {
        if (repo.startupMigrationBlocked) return@owned refused(R.string.gpt12_demo_migration_failed)
        if (deviceId != null && DeviceId.address(deviceId)?.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) != true) return@owned refused(R.string.runtime_invalid_target)
        if (repo.isDemoSession) {
            if (!useRealDevice) return@owned refused(R.string.gpt12_demo_pair_help)
            val exit = changeDemoModeLocked(false)
            if (!exit.succeeded) return@owned exit
        }
        if (repo.activeNight.value != null) return@owned refused(R.string.runtime_active_target)
        val selected = deviceId?.let { DeviceId.ble(DeviceId.address(it)!!) }
        advanceGeneration()
        graph.link.disconnect()
        // Setup must not expose a real target while Demo is still selected, or vice versa.
        repo.updateSettings { current ->
            val remembered = current.normalisedRememberedBedJets()
            current.copy(targetDevice = selected, demoMode = if (useRealDevice) false else current.demoMode,
                rememberedBedJets = if (selected == null) remembered.filterNot { it == current.targetDevice }
                    else (listOf(selected) + remembered).distinct().take(16), demoTargetMigrated = false,
                setupFanCheckedDevice = current.setupFanCheckedDevice.takeIf { checked -> selected != null && checked == selected })
        }
        if (selected == null) graph.bleLink.forget()
        CommandOutcome(OutcomeKind.LOCAL, text(if (selected == null) R.string.runtime_target_forgotten else R.string.runtime_target_saved))
    }
    suspend fun setDemoMode(enabled: Boolean): CommandOutcome = owned(text(R.string.runtime_demo_label),
        repo.settings.value.deviceId(), declineIfBusy = enabled) { changeDemoModeLocked(enabled) }

    private suspend fun changeDemoModeLocked(enabled: Boolean): CommandOutcome {
        if (repo.startupMigrationBlocked) return refused(R.string.gpt12_demo_migration_failed)
        if (enabled == repo.settings.value.demoMode) return local(if (enabled) R.string.gpt12_demo_started else R.string.gpt12_demo_ended)
        if (enabled) {
            if (repo.activeNight.value != null) return refused(R.string.runtime_active_target)
            try {
                graph.sessions.beginDemoTransition()
                graph.link.disconnect()
                graph.resetDemoSession()
                repo.enterDemoSession()
            } finally {
                // Publication may win a cancellation while returning from the repository writer.
                graph.sessions.finishDemoTransition(repo.isDemoSession)
            }
        } else if (repo.isDemoSession) {
            // The owner has finished any simulated command. No simulated ending reaches real History or originals.
            try {
                graph.sessions.beginDemoTransition()
                graph.link.disconnect()
                NightAlarms.cancel(context)
                NightAlarms.cancelLocalRetry(context)
                NightAlarms.cancelReconcileRetry(context)
                Notifications.cancelWakeUp(context)
                Notifications.cancelResume(context)
                repo.exitDemoSession()
                graph.resetDemoSession()
            } finally {
                graph.sessions.finishDemoTransition(repo.isDemoSession)
            }
        } else {
            // Compatibility for a pre-upgrade repository used before application startup normalisation.
            if (repo.activeNight.value != null) return refused(R.string.runtime_active_target)
            advanceGeneration()
            graph.link.disconnect()
            repo.updateSettings { it.copy(demoMode = false) }
            graph.resetDemoSession()
        }
        refreshQuietNotification()
        return local(if (enabled) R.string.gpt12_demo_started else R.string.gpt12_demo_ended)
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
    suspend fun releaseForOtherPhone(): CommandOutcome {
        return owned(text(R.string.runtime_release_label), repo.settings.value.deviceId()) {
            // Handover invalidates queued commands, but an existing recovery reason and
            // short-lived resume offer remain meaningful when this phone releases the link.
            repo.updateRuntime { it.copy(generation = it.generation + 1,
                resumeOffer = validResumeOffer(it)?.copy(commandVersion = it.generation + 1)) }
            graph.sessions.suspendAutoConnect(null).join()
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_released))
        }
    }
    suspend fun takeBack(): CommandOutcome = owned(text(R.string.runtime_take_back), repo.settings.value.deviceId(), MIN_BUDGET_MS) {
        graph.sessions.resumeAutoConnect()
        connected {
            val pendingEnd = repo.runtime.value.endRequested?.let { it == repo.activeNight.value?.identity } == true
            val report = freshReport(requireProgress = !pendingEnd)
            val status = report.status
            if (finishRequestedEnd(status, receiptOf(report))) return@connected CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_taken_back))
            // Uncertain hardware is never replayed. The user can keep a fresh manual run or stop tracking.
            repo.activeNight.value?.let { n ->
                // The same comparison as takeover detection: a kept manual run against its own step (IR-08).
                val runs = if (n.trackingManual) !contradictsManual(n, status, graph.now()) else matchesCurrentProgramme(n, status, graph.now())
                if (!runs) {
                    recordTakeover(n)
                    // The outcome says what the check found, not only that it ran.
                    return@connected CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_taken_back_takeover), notificationHandled = true)
                }
            }
            CommandOutcome(OutcomeKind.LOCAL, text(R.string.runtime_taken_back))
        }
    }
    /** A reviewed runtime keeps its target/night through both dialogues and rechecks the live mode's limit. */
    suspend fun remoteDuration(label: String, request: CommandRequest): CommandOutcome =
        owned(label, request.deviceId, MIN_BUDGET_MS, origin = request.origin) {
            if (request.expected != repo.activeNight.value?.identity) return@owned stale()
            val preset = request.actionPreset ?: return@owned refused(R.string.remote_custom_invalid)
            connected {
                val current = graph.link.latestSourcedStatus()?.takeIf { it.isFrom(request.deviceId) }?.status
                    ?: return@connected refused(R.string.remote_custom_changed)
                if (current.mode != preset.mode) return@connected refused(R.string.remote_custom_changed)
                val leaveProgramme = request.expected != null || (current.extra?.programStep ?: 0) > 0
                if (leaveProgramme && (current.targetTemperature != preset.temperature || current.fanPercent != preset.fanPercent))
                    return@connected refused(R.string.remote_custom_changed)
                // runManual applies the mode's protocol range. Refuse rather than change the reviewed live setpoint.
                if (leaveProgramme && preset.mode.usesTemperature && preset.temperature !in preset.mode.temperatureRange)
                    return@connected refused(R.string.remote_custom_temperature)
                if (preset.minutes !in 1..manualRuntimeMaximum(current))
                    return@connected refused(R.string.remote_custom_changed)
                advanceGeneration(preserveOffer = repo.activeNight.value?.trackingManual != false)
                var sentAt = elapsedMs()
                manualChange(label, endsNight = true, affectsProgramme = true, confirms = { report ->
                    val elapsedSeconds = (elapsedMs() - sentAt).coerceAtLeast(0) / 1_000
                    val remaining = (preset.minutes * 60L - elapsedSeconds).coerceAtLeast(0)
                    report.mode == preset.mode && report.remainingSeconds.toLong() in (remaining - 5).coerceAtLeast(0)..remaining &&
                        (!leaveProgramme || (report.extra?.programStep == 0 &&
                            (!preset.mode.usesTemperature || report.targetTemperature == preset.temperature) &&
                            (!preset.mode.usesFan || report.fanPercent == preset.fanPercent)))
                }) {
                    sentAt = elapsedMs()
                    if (leaveProgramme) graph.controller().runManual(preset.mode, preset.temperature, (preset.fanPercent / 5) - 1, preset.minutes)
                    else graph.controller().setRuntime(preset.minutes)
                }.let { if (it.kind == OutcomeKind.ACCEPTED) it.copy(kind = OutcomeKind.UNKNOWN) else it }
            }
        }

    /** A Remote-screen change. [confirms] says which BedJet report shows it took effect; without one it is only sent. */
    suspend fun remote(label: String, endsNight: Boolean = false, affectsProgramme: Boolean = false,
        target: String? = repo.settings.value.deviceId(), expected: NightIdentity? = repo.activeNight.value?.identity,
        confirms: ((DeviceStatus) -> Boolean)? = null,
        announceConfirmation: Boolean = true,
        work: suspend (BedJetController) -> Unit): CommandOutcome = owned(label, target, MIN_BUDGET_MS) {
        if (endsNight && confirms == null) return@owned refused(R.string.runtime_remote_confirmation_required)
        // The tap's night, captured then: a change queued behind a Start or re-time never converts another night (IR-09).
        if ((endsNight || affectsProgramme) && expected != repo.activeNight.value?.identity) return@owned stale()
        connected {
            advanceGeneration(preserveOffer = !endsNight || repo.activeNight.value?.trackingManual != false)
            // Only lights, beeps and notice acknowledgement leave both the programme and tracking untouched.
            manualChange(label, endsNight, affectsProgramme, confirms, allowCurrentStatus = !endsNight && !affectsProgramme) {
                work(graph.controller())
            }.let { if (!announceConfirmation && it.kind == OutcomeKind.CONFIRMED) it.copy(announceInApp = false) else it }
        }
    }
    /** [open] is how the file is opened; tests make it fail. A file that can't be opened throws, so nothing claims it saved. */
    suspend fun exportDiagnostics(uri: android.net.Uri,
        open: (android.net.Uri) -> java.io.OutputStream? = { context.contentResolver.openOutputStream(it, "wt") }) {
        io.github.willzy12h.nocturne.data.ExportDestination.check(context, uri)
        val snapshot = diagnostics()
        withContext(Dispatchers.IO) {
            io.github.willzy12h.nocturne.data.ExportDestination.check(context, uri)
            open(uri)?.use { it.write(snapshot.toByteArray()) }
                ?: throw java.io.IOException(text(R.string.runtime_export_failed))
        }
    }
    fun diagnostics(): String = kotlinx.serialization.json.buildJsonObject {
        put("version", kotlinx.serialization.json.JsonPrimitive(io.github.willzy12h.nocturne.BuildConfig.VERSION_NAME))
        put("deviceModel", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL))
        put("linkState", kotlinx.serialization.json.JsonPrimitive(graph.link.state.value.name))
        put("unreadable", kotlinx.serialization.json.JsonArray(repo.unreadable.value.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("originalsBlocked", kotlinx.serialization.json.JsonPrimitive(repo.originalsBlocked.value))
        put("runtimeReason", kotlinx.serialization.json.JsonPrimitive(repo.runtime.value.reason))
        put("nightConfidence", kotlinx.serialization.json.JsonPrimitive(repo.activeNight.value?.confidence?.name))
        put("alarm", AlarmSource.diagnostics(context, repo.settings.value,
            repo.activeNight.value?.let { repo.profile(it.profileId) } ?: repo.tonightsProfile(),
            repo.activeNight.value, repo.runtime.value, status.value, graph.now()))
        put("settings", BackupCodec.json.encodeToJsonElement(Settings.serializer(), repo.settings.value))
        put("activeNight", repo.activeNight.value?.let { BackupCodec.json.encodeToJsonElement(ActiveNight.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
        put("events", BackupCodec.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(HistoryEvent.serializer()), repo.history.value.flatMap { it.events }.sortedByDescending { it.at }.take(50)))
        put("notices", BackupCodec.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LoggedNotice.serializer()), repo.notices.value.take(50)))
        put("lastCrash", io.github.willzy12h.nocturne.data.CrashLog.read(context.filesDir)?.let { kotlinx.serialization.json.JsonPrimitive(it) }
            ?: kotlinx.serialization.json.JsonNull)
    }.toString()

    internal suspend fun publishNight(night: ActiveNight?) {
        repo.setActiveNight(night)
        // A refused/pre-write-failed replacement keeps the same night and its ending intention. A different
        // published identity supersedes it; a leftover marker after a crash is also checked by reconcile.
        if (repo.runtime.value.endRequested?.let { it != night?.identity } == true)
            repo.updateRuntime { if (it.endRequested == night?.identity) it
                else it.copy(endRequested = null, endRequestedLabel = null, endRequestedAt = null,
                    endRequestedEpoch = null, endRequestedSequence = null) }
        refreshQuietNotification()
    }
    internal fun refreshQuietNotification() {
        val night = repo.activeNight.value
        if (night == null || hasUnreconciledProgramme(night)) Notifications.cancelQuiet(context)
        else Notifications.showQuiet(context, night, repo.settings.value, wakeChangePending())
    }
    /** Only work for this displayed night and target makes its wake pending. An obsolete journal can remain
     * after a save failure without undoing a published confirmation; raw [retimePending] still requests cleanup. */
    internal fun wakeChangePending(night: ActiveNight? = repo.activeNight.value, runtime: RuntimeState = repo.runtime.value,
        settings: Settings = repo.settings.value): Boolean {
        val target = settings.deviceId()
        if (night == null || night.deviceId != target) return false
        return runtime.retime?.let { it.expected == night.identity && it.deviceId == target } == true ||
            runtime.alarmInbox?.let { it.nightId == night.recordId && it.deviceId == target } == true
    }
    internal fun event(kind: EventKind, detail: String) = HistoryEvent(graph.now().toString(), kind, detail)
    internal suspend fun recordError(detail: String) { repo.activeNight.value?.let { repo.addEvent(it.recordId, event(EventKind.ERROR, detail)) } }
    internal fun limits(): ProfileLimits = (repo.activeNight.value?.let { it.profile ?: repo.profile(it.profileId) } ?: repo.tonightsProfile())?.limits ?: ProfileLimits()
    internal fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    internal fun local(id: Int) = CommandOutcome(OutcomeKind.LOCAL, text(id))
    internal fun refused(id: Int) = CommandOutcome(OutcomeKind.REFUSED, text(id))
    internal fun stale(id: Int = R.string.runtime_stale) = CommandOutcome(OutcomeKind.STALE, text(id))
    internal fun label(command: Command) = commandLabel(context, command)
    internal fun clock(iso: String) = io.github.willzy12h.nocturne.ui.Format.clock(ZonedDateTime.parse(iso))
    companion object {
        const val OPERATION_MS = 90_000L
        /** A background widget read's whole budget: one connection attempt and its first report, no retries. */
        const val WIDGET_READ_MS = 15_000L
        /** The least of [OPERATION_MS] a hardware request must have left once it holds the lock (IR-11). */
        const val MIN_BUDGET_MS = 30_000L
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
            Command.CHECK_BEDJET -> R.string.runtime_command_check_bedjet
            Command.TEMPORARY -> R.string.runtime_command_temporary
        })
    }
}

/** How a background widget read went. Only [NOT_REACHED] counts towards reading less often. */
enum class WidgetRead {
    /** A fresh report arrived. */
    READ,
    /** Another holder, demo, a release for another phone or a busy owner: nothing was tried. */
    STEPPED_ASIDE,
    /** The BedJet did not answer in time: out of range, off, or Bluetooth unavailable. */
    NOT_REACHED,
}
