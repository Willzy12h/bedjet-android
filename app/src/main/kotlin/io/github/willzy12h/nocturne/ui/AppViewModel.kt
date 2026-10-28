package io.github.willzy12h.nocturne.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.willzy12h.nocturne.BuildConfig
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.FoundBedJet
import io.github.willzy12h.nocturne.ble.BluetoothAvailability
import io.github.willzy12h.nocturne.ble.BluetoothAdmission
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.PlanException
import io.github.willzy12h.nocturne.core.plan.NightDurationException
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Preview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.BackupFailure
import io.github.willzy12h.nocturne.data.ImportPreview
import io.github.willzy12h.nocturne.data.RestoreUnfinished
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.data.MiddleSuggestion
import io.github.willzy12h.nocturne.data.MorningCheckIn
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.ShortTestPreview
import io.github.willzy12h.nocturne.night.recheckDescription
import io.github.willzy12h.nocturne.night.refusalText
import io.github.willzy12h.nocturne.night.planMessage
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.ZonedDateTime

/** The screens' view of the app: stored data, the live BedJet, and the actions behind every button. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    internal val graph = application.graph
    private val repo = graph.repository

    val profiles = repo.profiles
    val settings = repo.settings
    private val _setupChecklistOpen = MutableStateFlow(false)
    val setupChecklistOpen = _setupChecklistOpen.asStateFlow()
    private val _setupChecklistRequest = MutableStateFlow(0)
    val setupChecklistRequest = _setupChecklistRequest.asStateFlow()
    fun openSetupChecklist() {
        updateSettings { it.copy(setupDismissedOptional = emptySet()) }
        _setupChecklistOpen.value = true
        _setupChecklistRequest.value += 1
    }
    fun closeSetupChecklist() { _setupChecklistOpen.value = false }
    /** Name replies are independent of status receipts; both sources remain bound to their own device. */
    val selectedBedJetName = combine(settings, graph.bleLink.verifiedNames, graph.bleLink.displayNames) { s, verified, displayed ->
        if (s.demoMode) null else s.targetDevice?.let { target ->
            verified?.takeIf { it.first == target }?.second ?: displayed?.takeIf { it.first == target }?.second
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, bedJetName())
    val history = repo.history.map { records -> records.filterNot { it.isTestRun } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.history.value.filterNot { it.isTestRun })
    fun morningCheckIn(nightId: String): MorningCheckIn? = repo.morningCheckIn(nightId)

    fun recordMorningCheckIn(nightId: String, comfort: MorningComfort, onResult: (MorningCheckIn?) -> Unit) {
        viewModelScope.launch {
            val result = try { repo.recordMorningCheckIn(nightId, comfort) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            _messages.tryEmit(getApplication<Application>().getString(if (result == null) R.string.checkin_no_longer_available
                else R.string.checkin_saved_message))
            onResult(result)
        }
    }

    fun acceptMorningSuggestion(suggestion: MiddleSuggestion, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val applied = try { repo.acceptMorningSuggestion(suggestion) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            _messages.tryEmit(getApplication<Application>().getString(if (applied) R.string.checkin_applied_message else R.string.checkin_stale_message))
            onResult(applied)
        }
    }
    val activeNight = repo.activeNight
    val slotBackups = repo.slotBackups
    val snapshots = repo.snapshotList
    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()
    private val _importApplying = MutableStateFlow(false)
    val importApplying: StateFlow<Boolean> = _importApplying.asStateFlow()
    private var importGeneration = 0L
    private var importSessionRevision = repo.sessionRevision.value
    val notices = repo.notices
    val unreadable = repo.unreadable
    /** A restore still not finished on disk; the banner says so instead of "set aside" (P5). */
    val restoreUnfinished = repo.restoreUnfinished
    val nightStatus = graph.actions.status
    val runtime = repo.runtime
    private val _foundBedJets = MutableStateFlow<List<FoundBedJet>>(emptyList())
    val foundBedJets: StateFlow<List<FoundBedJet>> = _foundBedJets.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /** Follows the live link, which changes when demo mode is switched. Collected only while this model lives (U07). */
    val linkState: StateFlow<LinkState> = repo.settings.map { graph.link }.flatMapLatest { it.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LinkState.SEARCHING)
    private val visible = MutableStateFlow(false)
    private val _hasLiveStatus = MutableStateFlow(false)
    /** Whether the displayed value came from the selected target's current visible connection. */
    val hasLiveStatus: StateFlow<Boolean> = _hasLiveStatus.asStateFlow()
    /** Retain the selected target's last display while hidden/reconnecting; only visible screens collect live data.
     * Control fields stay live; sensor changes of at least half a degree publish at most every 30 seconds. */
    val status: StateFlow<DeviceStatus?> = repo.settings.map { it.deviceId() }.distinctUntilChanged().flatMapLatest { target ->
        // These survive a visibility change, but never a selected-target change.
        var lastSensors: Pair<Int, Int>? = null
        var lastSensorAt = 0L
        visible.flatMapLatest { shown ->
            if (!shown) {
                _hasLiveStatus.value = false
                emptyFlow<DeviceStatus?>()
            } else {
                val live = graph.link.sourcedStatus.map { displayedStatus(it, target) }
                    .onEach { if (it == null) _hasLiveStatus.value = false }
                val temperatures = live.map { it?.let { s -> s.actualTemperature to s.ambientTemperature } }
                    .distinctUntilChanged().transformLatest<Pair<Int, Int>?, Pair<Int, Int>> { sensors ->
                        // A retired connection cancels its queued sample without erasing the display cache.
                        if (sensors == null) return@transformLatest
                        val previous = lastSensors
                        if (previous != null) emit(previous)
                        if (previous == sensors) return@transformLatest
                        if (previous != null) delay((lastSensorAt + 30_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        // Protocol sensor values are half-degrees: one unit is the 0.5 °C threshold.
                        val next = if (previous == null) sensors else
                            (if (kotlin.math.abs(sensors.first - previous.first) >= 1) sensors.first else previous.first) to
                            (if (kotlin.math.abs(sensors.second - previous.second) >= 1) sensors.second else previous.second)
                        lastSensors = next
                        lastSensorAt = SystemClock.elapsedRealtime()
                        emit(next)
                    }
                combine(live, temperatures) { current, (actual, ambient) ->
                    current?.copy(actualTemperature = actual, ambientTemperature = ambient)
                }.transform<DeviceStatus?, DeviceStatus?> {
                    if (it == null) _hasLiveStatus.value = false
                    else {
                        emit(it)
                        _hasLiveStatus.value = true
                    }
                }
            }
        }.onStart {
            _hasLiveStatus.value = false
            emit(null) // A different selected target must never inherit the previous unit's display.
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _busy = MutableStateFlow(false)
    /** True while a request from this screen waits for a save or for the command owner. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    /** What the command owner is running now and how many requests wait behind it, from any origin (U07). */
    val commandBusy: StateFlow<io.github.willzy12h.nocturne.night.BusyState> = graph.actions.busy
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    private val tapHaptics = Haptics()
    /** Only the collecting view's own admitted APP taps, followed by their matching confirmed outcomes. */
    val haptics = tapHaptics.feedback
    /** Outcomes already shown, by identity: the screen's own request and the owner's feed report the same object. */
    private val shownOutcomes = ArrayDeque<CommandOutcome>()
    private var screenVisible = false
    private val _bluetoothOff = MutableStateFlow(false)
    val bluetoothOff: StateFlow<Boolean> = _bluetoothOff.asStateFlow()
    private var bluetoothReceiverRegistered = false
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED && screenVisible) refreshBluetooth()
        }
    }

    init {
        // The one in-app outcome collector (U08): widget, shortcut, alarm and charger outcomes are shown while the
        // app is open, and an outcome this screen asked for is shown once, whichever side sees it first.
        viewModelScope.launch {
            graph.actions.outcomes.collect { outcome ->
                if (screenVisible) report(outcome)
            }
        }
        viewModelScope.launch {
            repo.sessionRevision.collect { revision ->
                if (revision != importSessionRevision) cancelImport()
            }
        }
        viewModelScope.launch {
            repo.settings.map { it.demoMode }.distinctUntilChanged().collect {
                if (screenVisible) refreshBluetooth()
            }
        }
    }

    /** Shows [outcome] once. Local settings saves stay quiet because the screen already shows the change. */
    fun report(outcome: CommandOutcome) {
        if (shownOutcomes.any { it === outcome }) return
        shownOutcomes.addLast(outcome)
        while (shownOutcomes.size > 32) shownOutcomes.removeFirst()
        if (outcome.kind == OutcomeKind.LOCAL && outcome.origin == CommandOrigin.SETTINGS) return
        if (!outcome.announceInApp && outcome.kind == OutcomeKind.CONFIRMED) return
        _messages.tryEmit(outcome.message)
    }
    private val _nextAlarm = MutableStateFlow<AlarmSource.NextAlarm?>(null)
    val nextAlarm: StateFlow<AlarmSource.NextAlarm?> = _nextAlarm.asStateFlow()
    private val _alarmCheck = MutableStateFlow<String?>(null)
    val alarmCheck: StateFlow<String?> = _alarmCheck.asStateFlow()

    val version: String = BuildConfig.VERSION_NAME

    /** The app clock's current time. */
    fun now(): ZonedDateTime = graph.now()

    // Screen lifecycle: hold the BedJet while the app is on screen so the controls are live.

    fun onScreenVisible() {
        screenVisible = true
        visible.value = true
        tapHaptics.setVisible(true)
        if (!bluetoothReceiverRegistered) {
            ContextCompat.registerReceiver(getApplication(), bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            bluetoothReceiverRegistered = true
        }
        refreshBluetooth()
        refreshAlarm()
        graph.sessions.holdForScreen(true)
        // An alarm change that couldn't reach the BedJet earlier.
        graph.scope.launch {
            graph.actions.reconcile()
            if (graph.actions.retimePending) io.github.willzy12h.nocturne.service.BedJetService.start(getApplication(), NightActions.Command.RETRY_RETIME)
        }
    }

    fun onScreenHidden() {
        screenVisible = false
        _hasLiveStatus.value = false
        visible.value = false
        tapHaptics.setVisible(false)
        if (bluetoothReceiverRegistered) {
            getApplication<Application>().unregisterReceiver(bluetoothReceiver)
            bluetoothReceiverRegistered = false
        }
        graph.sessions.holdForScreen(false)
    }

    /** Reconnect after the adapter returns, without re-submitting any refused command. */
    fun refreshBluetooth() {
        val state = BluetoothAvailability.read(getApplication())
        val wasOff = _bluetoothOff.value
        _bluetoothOff.value = !repo.settings.value.demoMode && state == BluetoothAvailability.State.OFF
        if (wasOff && state == BluetoothAvailability.State.ENABLED) {
            Notifications.clearBluetoothOff(getApplication())
            if (screenVisible && !repo.settings.value.demoMode) graph.sessions.reconnect()
        }
    }

    override fun onCleared() {
        onScreenHidden()
        super.onCleared()
    }

    /** The connection chip: try the BedJet again. */
    fun reconnect() {
        graph.sessions.reconnect()
    }

    fun refreshAlarm() {
        _nextAlarm.value = AlarmSource.describe(getApplication(), repo.settings.value)
    }

    /** Every explicit tap reads Android again, including a repeated or absent alarm. */
    fun recheckAlarm() {
        refreshAlarm()
        fun currentExplanation() = AlarmExplanation.resolve(repo.settings.value, repo.tonightsProfile(), graph.now(),
            nextAlarm.value, activeNight.value, runtime.value, nightStatus.value).recheckDescription(getApplication())
        val message = currentExplanation()
        // Reading Android is complete even when the subsequent BedJet operation waits or times out.
        _alarmCheck.value = text(R.string.alarm_recheck_last, Format.clock(now()), message)
        if (activeNight.value == null) {
            _messages.tryEmit(message)
        } else {
            val request = graph.actions.capture(NightActions.Command.RETIME).copy(origin = CommandOrigin.APP)
            act(deferBluetoothCheck = true) {
                val outcome = graph.actions.execute(request)
                withContext(Dispatchers.Main) {
                    refreshAlarm()
                    _alarmCheck.value = text(R.string.alarm_recheck_last, Format.clock(now()), currentExplanation())
                }
                outcome
            }
        }
    }

    fun shortTestPreview(): ShortTestPreview? = graph.actions.shortTestPreview()
    fun startShortTest(preview: ShortTestPreview) = act { graph.actions.startShortTest(preview) }

    // Previews

    fun tonightsProfile(): Profile? = repo.tonightsProfile()

    /** When a profile would wake you if it ran tonight, and whether that's from the alarm. */
    fun wakePreview(profile: Profile, now: ZonedDateTime = graph.now()): WakeChoice {
        val bedtime = WakeResolver.nextBedtime(profile, now)
        return WakeResolver.resolve(profile, bedtime, nextAlarm.value?.takeIf { it.counts }?.time)
    }

    /** The plan a profile would run tonight, or the reason it can't. */
    fun previewPlan(profile: Profile, startNow: Boolean = false): Result<Plan> = runCatching {
        val now = graph.now()
        if (startNow) {
            val preview = startPreview(profile, now)
            return@runCatching preview.plan ?: error(preview.refusalReason ?: "This night cannot start now.")
        }
        val start = if (startNow) now else WakeResolver.nextBedtime(profile, now)
        PlanCompiler.compile(profile, start, WakeResolver.resolve(profile, start, nextAlarm.value?.takeIf { it.counts }?.time).time)
    }.recoverCatching { cause ->
        val message = cause.planMessage(getApplication())
        if (cause is NightDurationException || (cause is PlanException && message != cause.message))
            throw PlanException(message.orEmpty()).also { it.initCause(cause) }
        throw cause
    }

    /** One preview supplies the wake headline, curve and actual Start policy. */
    fun startPreview(profile: Profile, now: ZonedDateTime = graph.now()): Preview = NightPreview.forStartNow(
        profile, now, nextAlarm.value?.time, repo.settings.value.resolveWakeOverride(now),
        repo.settings.value, nextAlarm.value?.creator,
    ).let { it.copy(refusalReason = it.refusalText(getApplication())) }

    /** The running night's plan, rebuilt from what was stored when it started. */
    fun activePlan(): Plan? {
        val night = activeNight.value ?: return null
        return runCatching { night.activePlan(repo.profile(night.profileId)) }.getOrNull()
    }

    // Commands

    // Everything goes through NightActions, so a button can't interleave with a widget or an alarm change.

    private var confirmedNight: io.github.willzy12h.nocturne.core.night.NightIdentity? = null

    /** Only the positive dialog callback enters this synchronous capture scope. */
    fun withConfirmedNight(expected: io.github.willzy12h.nocturne.core.night.NightIdentity, action: () -> Unit) {
        if (activeNight.value?.identity != expected) {
            _messages.tryEmit(getApplication<Application>().getString(io.github.willzy12h.nocturne.R.string.runtime_stale))
            return
        }
        confirmedNight = expected
        try { action() } finally { confirmedNight = null }
    }

    fun run(command: NightActions.Command, profileId: String? = null, replacementConfirmed: Boolean = false) {
        val request = graph.actions.capture(command, profileId, requireIdentity = replacementConfirmed || confirmedNight != null)
            .let { it.copy(expected = confirmedNight ?: it.expected, replacementConfirmed = replacementConfirmed || confirmedNight != null,
                origin = CommandOrigin.APP) }
        act(hardware = command != NightActions.Command.STOP_TRACKING,
            deferBluetoothCheck = command == NightActions.Command.RETIME || command == NightActions.Command.RETRY_RETIME) {
            graph.actions.execute(request)
        }
    }

    /** The reviewed target, night and one-action preset remain captured across the dialogue. */
    internal fun runCaptured(request: io.github.willzy12h.nocturne.night.CommandRequest) {
        act(hardware = request.command != NightActions.Command.STOP_TRACKING) {
            graph.actions.execute(request.copy(origin = CommandOrigin.APP))
        }
    }

    internal fun quickAction(command: NightActions.Command): CapturedQuickAction =
        requireNotNull(captureQuickAction(graph, graph.actions.capture(command, requireIdentity = true)))

    internal fun remoteCaptured(label: String, request: io.github.willzy12h.nocturne.night.CommandRequest,
        mode: io.github.willzy12h.nocturne.core.protocol.Mode) {
        act { graph.actions.remote(label, endsNight = true, target = request.deviceId, expected = request.expected,
            confirms = { it.mode == mode && (request.expected == null || it.extra?.programStep == 0) }) { it.setMode(mode) } }
    }

    internal fun remoteDuration(label: String, request: CommandRequest) = act {
        graph.actions.remoteDuration(label, request.copy(origin = CommandOrigin.APP))
    }

    /** A Remote control: sent straight to the BedJet. */
    fun remote(label: String, endsNight: Boolean = false, affectsProgramme: Boolean = false,
        confirms: ((io.github.willzy12h.nocturne.core.protocol.DeviceStatus) -> Boolean)? = null,
        announceConfirmation: Boolean = true,
        work: suspend (BedJetController) -> Unit) {
        val captured = graph.actions.capture(NightActions.Command.OFF)
        val expected = confirmedNight ?: captured.expected
        act { graph.actions.remote(label, endsNight = endsNight, affectsProgramme = affectsProgramme,
            target = captured.deviceId, expected = expected, confirms = confirms,
            announceConfirmation = announceConfirmation, work = work) }
    }

    fun restoreSlot(backup: SlotBackup) = act {
        if (backup.deviceId != repo.settings.value.deviceId()) refusal(text(R.string.runtime_restore_target_changed))
        else if (repo.slotBackups.value.firstOrNull { it.deviceId == backup.deviceId && it.slot == backup.slot } != backup)
            refusal(text(R.string.runtime_restore_original_changed))
        else graph.actions.restoreOriginal(backup)
    }

    fun pinProfile(profile: Profile, slot: Int, expectedTarget: String, expectedWorkingSlot: Int,
        previewedAt: ZonedDateTime, expectedProgram: StoredProgram) = act {
        graph.actions.pin(profile.id, slot, expectedTarget, profile, expectedWorkingSlot, previewedAt, expectedProgram)
    }

    fun dismissUnreadable() = save { repo.dismissUnreadable() }

    /** "Mark as checked" on an unconfirmed slot write: local only, through the command owner (IR-06). */
    fun acknowledgeSlotWrite() = act(hardware = false) { graph.actions.acknowledgeSlotWrite() }

    /** IR-03: true while slots with no saved original are refused because the saved originals were unreadable. */
    val originalsBlocked = repo.originalsBlocked

    /**
     * Lifts [originalsBlocked]. Call only after the person has confirmed, in a dialog that says so plainly, that the
     * saved originals of those slots are lost and that what the slots hold now will be kept as their originals.
     */
    fun acceptLostOriginals() = save { repo.dismissUnreadable(acceptLostOriginals = true) }

    /** Commands wait for saves asked for before them, so they act on what the person just saved. */
    private fun refusal(message: String) = CommandOutcome(OutcomeKind.REFUSED, message, origin = CommandOrigin.APP)

    /** A message from resources, for outcomes and snackbars. */
    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun act(hardware: Boolean = true, deferBluetoothCheck: Boolean = false, work: suspend () -> CommandOutcome) {
        // A tap is pending before its coroutine can wait for earlier saves or reach the service/owner.
        val admission = repo.operationAdmission.tryCommand() ?: run {
            report(refusal(text(R.string.import_command_blocked)))
            return
        }
        val pending = lastSave
        val target = graph.actions.capture(NightActions.Command.OFF).deviceId
        val feedback = if (hardware) tapHaptics.capture() else null
        viewModelScope.launch {
            var radio: BluetoothAdmission? = null
            try {
                _busy.value = true
                val outcome = try {
                    // The UI submission includes its save wait. Observe radio interruptions from here,
                    // retain the same marker through service/owner admission, and bound its whole lifetime.
                    withTimeout(io.github.willzy12h.nocturne.service.BedJetService.RUN_OWNED_MS) {
                        if (hardware && !repo.settings.value.demoMode) {
                            radio = BluetoothAdmission(getApplication())
                            if (!deferBluetoothCheck) radio?.check()
                        }
                        pending?.join()
                        if (hardware) withContext(io.github.willzy12h.nocturne.night.SubmissionTarget(target) +
                            (feedback?.context ?: kotlin.coroutines.EmptyCoroutineContext) +
                            (radio ?: kotlin.coroutines.EmptyCoroutineContext)) {
                            io.github.willzy12h.nocturne.service.BedJetService.runOwned(getApplication(), notifyFailures = false,
                                deferBluetoothCheck = deferBluetoothCheck) { work() }
                        } else work()
                    }
                } catch (_: TimeoutCancellationException) { CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_no_answer), origin = CommandOrigin.APP)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { CommandOutcome(OutcomeKind.FAILED, e.planMessage(getApplication()) ?: text(R.string.runtime_something_wrong), origin = CommandOrigin.APP) }
                finally { _busy.value = false }
                feedback?.complete(outcome)
                report(outcome)
            } finally { radio?.close(); feedback?.close(); admission.close() }
        }.invokeOnCompletion { admission.close() }
    }

    // Profiles, settings, backups

    // Saving happens off the main thread; the screens update once it is on disk.

    fun saveProfile(profile: Profile) = save { repo.saveProfile(profile) }
    /** "Next night": saved for later nights only; the running night keeps its snapshot, and the person is told so (U10). */
    fun saveProfileForNextNight(profile: Profile) = save {
        repo.saveProfile(profile)
        _messages.tryEmit(getApplication<Application>().getString(R.string.next_night_saved))
    }
    fun deleteProfile(id: String) = save { repo.deleteProfile(id) }
    fun updateSettings(change: (Settings) -> Settings) = save {
        // Shown once with the owner's feed; a plain save is not announced.
        val outcome = graph.actions.updateSettings(change)
        withContext(Dispatchers.Main) { refreshAlarm(); report(outcome) }
    }

    /** A native picker retains the displayed night, even if another night starts before its approval. */
    fun setTonightWake(wake: ZonedDateTime?,
        expected: io.github.willzy12h.nocturne.core.night.NightIdentity? = activeNight.value?.identity) {
        act(hardware = expected != null) {
            if (activeNight.value?.identity != expected) refusal(text(R.string.runtime_wake_night_changed))
            else graph.actions.setWakeOverride(wake, expected)
        }
    }

    /** "Update tonight": the edited profile is saved before the rest of the night is rewritten from it. */
    fun saveProfileThenReapply(profile: Profile) =
        saveProfileThenReapply(profile, graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true))

    /** The save dialog supplies its captured night and target; edits cannot adopt a replacement's consent. */
    fun saveProfileThenReapply(profile: Profile, request: CommandRequest) {
        act {
            repo.saveProfile(profile)
            graph.actions.execute(request)
        }
    }

    /** Lets [creator]'s alarms move the wake-up, and shows the next alarm under the saved setting. */
    fun useAlarmsFrom(creator: String) = save {
        repo.updateSettings { it.copy(alarmPackages = it.alarmPackages + creator) }
        refreshAlarm()
    }
    fun snapshotNow(label: String) = save { repo.snapshotNow(label); _messages.tryEmit(text(R.string.runtime_snapshot_saved)) }
    fun deleteSnapshot(id: String) = save { repo.deleteSnapshot(id) }
    fun clearNotices() = save { repo.clearNotices() }
    fun restoreSnapshot(id: String) = stageImport { repo.previewSnapshot(id) }

    private fun stageImport(read: suspend () -> ImportPreview?) {
        if (_importApplying.value) return
        val generation = ++importGeneration
        val sessionRevision = repo.sessionRevision.value
        importSessionRevision = sessionRevision
        _importPreview.value = null
        save {
            try {
                val preview = read()
                if (generation != importGeneration || sessionRevision != repo.sessionRevision.value) return@save
                _importPreview.value = preview
                _messages.tryEmit(text(if (preview == null) R.string.runtime_snapshot_unreadable else R.string.import_review_ready))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == importGeneration && sessionRevision == repo.sessionRevision.value)
                    _messages.tryEmit(importError(e))
            }
        }
    }

    fun cancelImport() {
        if (_importApplying.value) return
        importGeneration++
        _importPreview.value = null
    }

    fun confirmImport(preview: ImportPreview) {
        if (_importPreview.value !== preview || importSessionRevision != repo.sessionRevision.value) {
            cancelImport()
            _messages.tryEmit(text(R.string.import_preview_changed))
            return
        }
        if (_importApplying.value || !preview.canApply) return
        _importApplying.value = true
        save {
            try {
                repo.applyImport(preview)
                _importPreview.value = null
                _messages.tryEmit(text(R.string.runtime_backup_restored))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (e is BackupException && e.failure == BackupFailure.CHANGED) _importPreview.value = null
                _messages.tryEmit(importError(e))
            } finally { _importApplying.value = false }
        }
    }

    private fun importError(error: Exception): String = when (error) {
        is RestoreUnfinished -> error.message ?: text(R.string.runtime_read_failed)
        is BackupException -> text(when (error.failure) {
            BackupFailure.BUSY -> R.string.import_busy
            BackupFailure.CHANGED -> R.string.import_preview_changed
            BackupFailure.NEWER_SCHEMA -> R.string.import_newer
            BackupFailure.TOO_LARGE -> R.string.import_too_large
            BackupFailure.DAMAGED -> R.string.import_damaged
            BackupFailure.INVALID -> R.string.import_invalid
        })
        else -> text(R.string.runtime_read_failed)
    }

    fun export(uri: android.net.Uri) {
        graph.scope.launch {
            runCatching { repo.exportTo(uri) }
                .onSuccess { _messages.tryEmit(text(R.string.runtime_backup_saved)) }
                .onFailure { _messages.tryEmit(text(R.string.runtime_backup_save_failed, it.message.toString())) }
        }
    }

    /** Writes the diagnostics file the person chose (NightActions.diagnostics: no private preferences). */
    fun exportDiagnostics(uri: android.net.Uri,
        open: ((android.net.Uri) -> java.io.OutputStream?)? = null) {
        val app = getApplication<Application>()
        graph.scope.launch {
            runCatching { if (open == null) graph.actions.exportDiagnostics(uri) else graph.actions.exportDiagnostics(uri, open) }
                .onSuccess { _messages.tryEmit(app.getString(R.string.settings_diagnostics_saved)) }
                .onFailure { if (it is CancellationException) throw it
                    _messages.tryEmit(app.getString(R.string.settings_diagnostics_failed, it.message.orEmpty())) }
        }
    }

    fun import(uri: android.net.Uri) = stageImport { repo.previewImport(repo.importFrom(uri)) }

    /** The latest save, which later saves and commands wait for. Only touched on the main thread. */
    private var lastSave: Job? = null

    private fun save(work: suspend () -> Unit) {
        val previous = lastSave
        lastSave = graph.scope.launch {
            previous?.join()
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(text(R.string.runtime_save_failed, e.message ?: text(R.string.runtime_something_wrong_lower)))
            }
        }
    }

    fun forgetBedJet() = act(hardware = false) { graph.actions.forgetTarget() }

    private var discoveryJob: Job? = null

    fun discoverBedJets() {
        if (repo.settings.value.demoMode) {
            _messages.tryEmit(text(R.string.gpt12_demo_pair_help))
            return
        }
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            _discovering.value = true
            _foundBedJets.value = emptyList()
            try { _foundBedJets.value = graph.bleLink.discover(10_000) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _messages.tryEmit(e.message ?: text(R.string.runtime_discover_failed)) }
            finally { _discovering.value = false }
        }
    }

    fun selectBedJet(address: String) = act(hardware = false) {
        graph.actions.selectTarget("ble:$address", useRealDevice = true).also {
            if (it.succeeded && screenVisible) graph.sessions.reconnect()
        }
    }

    /** A separate in-memory session, entered only after any real discovery has closed. */
    fun useDemo() = act(hardware = false) {
        discoveryJob?.cancelAndJoin()
        graph.actions.setDemoMode(true).also {
            if (it.succeeded && screenVisible) graph.sessions.reconnect()
        }
    }

    fun exitDemo() = act(hardware = false) {
        graph.actions.setDemoMode(false).also {
            if (it.succeeded && screenVisible) graph.sessions.reconnect()
        }
    }

    fun runSetupFanCheck() = act { graph.actions.setupFanCheck() }

    fun bedJetName(): String? = if (settings.value.demoMode) "BEDJET-DEMO" else settings.value.targetDevice?.let {
        graph.bleLink.deviceNameFor(it) ?: graph.bleLink.deviceName
    }


}

/**
 * What restoring a snapshot says. A restore that is kept but not yet finished says so in its own words; any other
 * failure is thrown, for the caller's "Couldn't save" message.
 */
internal suspend fun snapshotRestoreMessage(
    // Default messages support callers without a Context.
    restored: String = "Restored. What you had before is saved as a snapshot.",
    unreadable: String = "That snapshot couldn't be read.",
    restore: suspend () -> Boolean,
): String = try {
    if (restore()) restored else unreadable
} catch (e: io.github.willzy12h.nocturne.data.RestoreUnfinished) {
    e.message.orEmpty()
}

/** What the screens show as the BedJet's status: only a report from [target] itself (or an unattributed one). */
internal fun displayedStatus(report: io.github.willzy12h.nocturne.core.link.SourcedStatus?, target: String?): DeviceStatus? =
    report?.takeIf { it.isFrom(target) }?.status
