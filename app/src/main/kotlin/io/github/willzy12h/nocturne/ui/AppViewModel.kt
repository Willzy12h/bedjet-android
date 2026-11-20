package io.github.willzy12h.nocturne.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
import io.github.willzy12h.nocturne.night.ClockApps
import io.github.willzy12h.nocturne.night.ConnectingCommand
import io.github.willzy12h.nocturne.core.link.latestStatusFrom
import io.github.willzy12h.nocturne.core.store.AlarmApps
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.ZonedDateTime

private const val READING_RECENT_MS = 10_000L

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
    /** Keep the selected sleep session while moving between tabs and Settings pages. */
    internal val selectedSleepSession = MutableStateFlow<String?>(null)
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
    /** A real sensor head stays recent for ten seconds. Replaying or supplementing it cannot renew its age. */
    val readingFresh = combine(visible, repo.settings.map { it.deviceId() }.distinctUntilChanged()) { shown, target ->
        target.takeIf { shown }
    }.flatMapLatest { target ->
        if (target == null) flowOf(false)
        else graph.link.sourcedStatus.map { it?.takeIf { report -> report.isFrom(target) }?.measuredAtElapsedMs }
            .distinctUntilChanged().transformLatest { measuredAt ->
                val age = measuredAt?.let { SystemClock.elapsedRealtime() - it }
                if (age != null && age in 0 until READING_RECENT_MS) {
                    emit(true)
                    delay(READING_RECENT_MS - age)
                }
                emit(false)
            }.onStart { emit(false) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), false)

    /** Retain a selected unit's last display when hidden or disconnected. Controls use every report;
     * coalesce sensor bursts against a fixed deadline so continuous readings cannot postpone the next update. */
    val status: StateFlow<DeviceStatus?> = repo.settings.map { it.deviceId() }.distinctUntilChanged().flatMapLatest { target ->
        visible.flatMapLatest { shown ->
            if (!shown) {
                _hasLiveStatus.value = false
                emptyFlow<DeviceStatus?>()
            } else {
                var previous: DeviceStatus? = null
                var publishedAt = 0L
                graph.link.sourcedStatus.transformLatest { report ->
                    val current = displayedStatus(report, target)
                    if (current == null) {
                        previous = null
                        _hasLiveStatus.value = false
                        return@transformLatest
                    }
                    val last = previous
                    if (last == null || current.actualTemperature != last.actualTemperature ||
                        current.ambientTemperature != last.ambientTemperature) {
                        val wait = publishedAt + 1_000 - SystemClock.elapsedRealtime()
                        if (last != null && wait > 0) {
                            emit(current.copy(actualTemperature = last.actualTemperature, ambientTemperature = last.ambientTemperature))
                            _hasLiveStatus.value = true
                            delay(wait)
                        }
                        publishedAt = SystemClock.elapsedRealtime()
                        previous = current
                    }
                    emit(current)
                    _hasLiveStatus.value = true
                }
            }
        }.onStart {
            _hasLiveStatus.value = false
            emit(null) // A newly selected unit never inherits readings from the old one.
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var pendingCommands = 0
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
            // Tonight and Settings read the same alarm; read it again the moment Android says it changed.
            if (intent.action == android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED && screenVisible) refreshAlarm()
        }
    }

    // Declared before init: the alarm-settings collector below writes it straight away.
    private val _nextAlarm = MutableStateFlow<AlarmSource.NextAlarm?>(null)
    val nextAlarm: StateFlow<AlarmSource.NextAlarm?> = _nextAlarm.asStateFlow()

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
        // Whether the next alarm counts depends on the alarm settings, so a change there is shown at once.
        viewModelScope.launch {
            repo.settings.map { listOf(it.acceptUnknownAlarmSource, it.alarmPackages, it.alarmApps, it.unnamedAlarms) }.distinctUntilChanged().collect { refreshAlarm() }
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
    /** Set when a widget's setup asks to edit the quick presets; Tonight opens its editor and clears it. */
    val presetEditingRequested = MutableStateFlow(false)
    private val _alarmCheck = MutableStateFlow<String?>(null)
    val alarmCheck: StateFlow<String?> = _alarmCheck.asStateFlow()

    val version: String = BuildConfig.VERSION_NAME
    /**
     * A test build, signed with the test key. A release can't be installed over it without uninstalling it, which
     * deletes its profiles and settings, so the app reminds the person to save a backup file first.
     */
    val testBuild: Boolean = version.contains("-test")

    private val firstRun = application.getSharedPreferences("first-run", android.content.Context.MODE_PRIVATE)
    private val _restoreOffer = MutableStateFlow(run {
        // Remembered, because the first launch writes settings, so the next launch no longer starts empty.
        if (repo.startedEmpty && !firstRun.contains(RESTORE_OFFER_PENDING))
            firstRun.edit(commit = true) { putBoolean(RESTORE_OFFER_PENDING, true) }
        firstRun.getBoolean(RESTORE_OFFER_PENDING, false) && !firstRun.getBoolean(RESTORE_OFFER_DISMISSED, false)
    })
    /** Shown on Tonight after a fresh install, until it is dismissed or a backup is restored: restore a backup file. */
    val restoreOffer: StateFlow<Boolean> = _restoreOffer.asStateFlow()
    fun dismissRestoreOffer() {
        _restoreOffer.value = false
        firstRun.edit { putBoolean(RESTORE_OFFER_DISMISSED, true) }
    }

    /** Built-in profiles back as they ship, keeping their nights; own profiles stay. A snapshot is saved first. */
    fun restoreBuiltInProfiles() = save {
        repo.restoreBuiltInProfiles()
        _messages.tryEmit(text(R.string.reset_profiles_done))
    }

    /** Boost, the Remote's quick settings and snooze or extend back to their defaults. A snapshot is saved first. */
    fun resetControls() = save {
        repo.snapshotBeforeReset()
        val outcome = graph.actions.updateSettings(Settings::withDefaultControls)
        if (outcome.succeeded) _messages.tryEmit(text(R.string.reset_controls_done))
        else withContext(Dispatchers.Main) { report(outcome) }
    }

    /** The app clock's current time. */
    fun now(): ZonedDateTime = graph.now()

    // Screen lifecycle: hold the BedJet while the app is on screen so the controls are live.

    fun onScreenVisible() {
        screenVisible = true
        visible.value = true
        tapHaptics.setVisible(true)
        if (!bluetoothReceiverRegistered) {
            ContextCompat.registerReceiver(getApplication(), bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                    addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) }, ContextCompat.RECEIVER_EXPORTED)
            bluetoothReceiverRegistered = true
        }
        refreshBluetooth()
        refreshAlarm()
        refreshClockApps()
        graph.sessions.holdForScreen(true)
        // The app connects by itself while open: the widget wakes and its background reads go back to every 15 minutes.
        io.github.willzy12h.nocturne.widget.WidgetLink.opened(graph.context)
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
            return@runCatching preview.plan ?: error(preview.refusalReason ?: getApplication<Application>().getString(R.string.preview_cannot_start))
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
    private val _connectingCommand = MutableStateFlow<ConnectingCommand?>(null)
    internal val connectingCommand = _connectingCommand.asStateFlow()

    private fun connectingCommand(request: CommandRequest, label: String, off: Boolean = false): ConnectingCommand {
        val starting = request.command == NightActions.Command.START_NIGHT
        fun selectedProfile() = if (request.profileId == null) repo.tonightsProfile() else repo.profile(request.profileId)
        val profile = if (starting) selectedProfile() else null
        val wake = profile?.let { startPreview(it).wake }
        val slot = settings.value.workingSlot
        val generation = repo.runtime.value.generation
        val seen = if (hasLiveStatus.value && graph.link.state.value == LinkState.READY)
            graph.link.latestStatusFrom(request.deviceId) else null
        return ConnectingCommand(label, off, request.deviceId, seen) {
            val sameNight = generation == repo.runtime.value.generation && activeNight.value?.identity == request.expected
            val samePlan = when {
                !starting -> true
                profile == null -> false
                else -> {
                    val saved = repo.settings.value
                    val now = graph.now()
                    val alarm = AlarmSource.describe(getApplication(), saved)
                    val preview = NightPreview.forStartNow(profile, now, alarm?.time, saved.resolveWakeOverride(now), saved, alarm?.creator)
                    selectedProfile() == profile && saved.workingSlot == slot && preview.plan != null && preview.wake == wake
                }
            }
            (off && !request.replacementConfirmed) || (sameNight && samePlan)
        }
    }

    /** Only the positive dialog callback enters this synchronous capture scope. */
    fun withConfirmedNight(expected: io.github.willzy12h.nocturne.core.night.NightIdentity, action: () -> Unit) {
        if (activeNight.value?.identity != expected) {
            _messages.tryEmit(getApplication<Application>().getString(io.github.willzy12h.nocturne.R.string.runtime_stale))
            return
        }
        confirmedNight = expected
        try { action() } finally { confirmedNight = null }
    }

    fun run(command: NightActions.Command, profileId: String? = null, replacementConfirmed: Boolean = false, onFinished: () -> Unit = {}) {
        val request = graph.actions.capture(command, profileId, requireIdentity = command == NightActions.Command.START_NIGHT ||
            replacementConfirmed || confirmedNight != null)
            .let { it.copy(expected = confirmedNight ?: it.expected, replacementConfirmed = replacementConfirmed || confirmedNight != null,
                origin = CommandOrigin.APP) }
        val tap = when (command) {
            NightActions.Command.START_NIGHT, NightActions.Command.OFF, NightActions.Command.END_NIGHT ->
                connectingCommand(request, NightActions.commandLabel(getApplication(), command), command != NightActions.Command.START_NIGHT)
            else -> null
        }
        act(hardware = command != NightActions.Command.STOP_TRACKING, tap = tap,
            deferBluetoothCheck = command == NightActions.Command.RETIME || command == NightActions.Command.RETRY_RETIME, onFinished = onFinished) {
            graph.actions.execute(request)
        }
    }

    /** The reviewed target, night and one-action preset remain captured across the dialogue. */
    internal fun runCaptured(request: io.github.willzy12h.nocturne.night.CommandRequest, onFinished: () -> Unit = {}) {
        val tap = if (request.command == NightActions.Command.STOP_TRACKING) null else connectingCommand(request,
            if (request.command != NightActions.Command.BOOST && request.actionPreset != null)
                text(Format.modeNameRes(request.actionPreset.mode)) else NightActions.commandLabel(getApplication(), request.command),
            request.command in setOf(NightActions.Command.OFF, NightActions.Command.END_NIGHT))
        act(hardware = request.command != NightActions.Command.STOP_TRACKING, onFinished = onFinished, tap = tap) {
            graph.actions.execute(request.copy(origin = CommandOrigin.APP))
        }
    }

    internal fun quickAction(command: NightActions.Command): CapturedQuickAction =
        requireNotNull(captureQuickAction(graph, graph.actions.capture(command, requireIdentity = true)))

    internal fun remoteCaptured(label: String, request: io.github.willzy12h.nocturne.night.CommandRequest,
        mode: io.github.willzy12h.nocturne.core.protocol.Mode) {
        act(tap = connectingCommand(request, label)) { graph.actions.selectRemoteMode(label, request, mode) }
    }

    internal fun adjustRemoteControl(label: String, mode: io.github.willzy12h.nocturne.core.protocol.Mode,
        temperature: Int? = null, fanStep: Int? = null, onFinished: () -> Unit) {
        val captured = graph.actions.capture(NightActions.Command.OFF)
        act(onFinished = onFinished) { graph.actions.adjustRemoteControl(label, captured, mode, temperature, fanStep) }
    }

    internal fun remoteDuration(label: String, request: CommandRequest, finishAt: java.time.Instant? = null) = act {
        graph.actions.remoteDuration(label, request.copy(origin = CommandOrigin.APP), finishAt)
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

    private fun act(hardware: Boolean = true, deferBluetoothCheck: Boolean = false, onFinished: () -> Unit = {},
        tap: ConnectingCommand? = null, work: suspend () -> CommandOutcome) {
        if (tap != null) {
            val prior = _connectingCommand.value
            if (prior != null && (!tap.off || prior.off)) { onFinished(); return }
            if (tap.off) prior?.cancelWaiting()
        }
        // A tap is pending before its coroutine can wait for earlier saves or reach the service/owner.
        val admission = repo.operationAdmission.tryCommand() ?: run {
            report(refusal(text(R.string.import_command_blocked)))
            onFinished()
            return
        }
        val pending = lastSave
        pendingCommands++
        _busy.value = true
        val target = graph.actions.capture(NightActions.Command.OFF).deviceId
        val feedback = if (hardware) tapHaptics.capture() else null
        if (tap != null) _connectingCommand.value = tap
        val job = viewModelScope.launch {
            var radio: BluetoothAdmission? = null
            try {
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
                                deferBluetoothCheck = deferBluetoothCheck) {
                                if (tap == null) work() else withContext(tap) { work() }
                            }
                        } else work()
                    }
                } catch (_: TimeoutCancellationException) {
                    if (tap != null && tap.phase.value != ConnectingCommand.Phase.SENDING)
                        CommandOutcome(OutcomeKind.FAILED, text(R.string.runtime_connection_no_report), origin = CommandOrigin.APP)
                    else CommandOutcome(OutcomeKind.UNKNOWN, text(R.string.runtime_no_answer), origin = CommandOrigin.APP)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { CommandOutcome(OutcomeKind.FAILED, e.planMessage(getApplication()) ?: text(R.string.runtime_something_wrong), origin = CommandOrigin.APP) }
                feedback?.complete(outcome)
                report(outcome)
            } finally { radio?.close(); feedback?.close(); admission.close() }
        }
        tap?.attach(job)
        job.invokeOnCompletion {
            admission.close()
            pendingCommands--
            _busy.value = pendingCommands > 0
            if (tap != null) _connectingCommand.compareAndSet(tap, null)
            onFinished()
        }
    }

    // Profiles, settings, backups

    // Saving happens off the main thread; the screens update once it is on disk.

    val cooldownOfferSessionRevision = repo.sessionRevision
    private val _cooldownOfferSaving = MutableStateFlow(false)
    val cooldownOfferSaving = _cooldownOfferSaving.asStateFlow()

    fun answerCooldownOffer(expected: Map<String, List<io.github.willzy12h.nocturne.core.plan.Phase>>, expectedSessionRevision: Long) {
        if (_cooldownOfferSaving.value) return
        _cooldownOfferSaving.value = true
        save {
            try {
                if (!repo.answerCooldownOffer(expected, expectedSessionRevision))
                    _messages.tryEmit(text(R.string.cooldown_offer_changed))
            } finally {
                _cooldownOfferSaving.value = false
            }
        }
    }

    fun saveProfile(profile: Profile) = save { repo.saveProfile(profile) }
    /** "Next night": saved for later nights only; the running night keeps its snapshot, and the person is told so (U10). */
    fun saveProfileForNextNight(profile: Profile) = save {
        repo.saveProfile(profile)
        _messages.tryEmit(getApplication<Application>().getString(R.string.next_night_saved))
    }
    fun deleteProfile(id: String) = save { repo.deleteProfile(id) }
    fun reorderProfiles(ids: List<String>) = save { repo.reorderProfiles(ids) }
    /** Moves one profile up ([by] -1) or down (+1): the accessible alternative to dragging. */
    fun moveProfile(id: String, by: Int) {
        val ids = profiles.value.map { it.id }.toMutableList()
        val from = ids.indexOf(id).takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (to == from) return
        ids.add(to, ids.removeAt(from))
        reorderProfiles(ids)
    }
    fun updateSettings(change: (Settings) -> Settings) = save {
        // Shown once with the owner's feed; a plain save is not announced.
        val outcome = graph.actions.updateSettings(change)
        withContext(Dispatchers.Main) { refreshAlarm(); report(outcome) }
    }

    /** A native picker retains the displayed night, even if another night starts before its approval. */
    fun setTonightWake(wake: ZonedDateTime?,
        expected: io.github.willzy12h.nocturne.core.night.NightIdentity? = activeNight.value?.identity,
        onFinished: () -> Unit = {}) {
        act(hardware = expected != null, onFinished = onFinished) {
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

    private val _clockApps = MutableStateFlow<List<ClockApps.App>>(emptyList())
    /** The clock apps on this phone, for choosing where alarms come from. */
    val clockApps: StateFlow<List<ClockApps.App>> = _clockApps.asStateFlow()

    private val _otherAlarmApps = MutableStateFlow<List<ClockApps.App>>(emptyList())
    /** Apps that aren't clock apps but may set alarms (a white-noise app, say), for ticking in the alarm-app list. */
    val otherAlarmApps: StateFlow<List<ClockApps.App>> = _otherAlarmApps.asStateFlow()
    private val _allAlarmAppsShown = MutableStateFlow(false)
    /** True once "Show more apps" asked for every app that can set alarms, not only those seen setting one. */
    val allAlarmAppsShown: StateFlow<Boolean> = _allAlarmAppsShown.asStateFlow()

    /** Looks for clock apps again; with "any clock app" chosen, a newly installed one counts from now on. */
    fun refreshClockApps() {
        val previous = _clockApps.value
        val found = ClockApps.installed(getApplication())
        _clockApps.value = found
        val settings = repo.settings.value
        if (settings.alarmApps == AlarmApps.CLOCK_APPS && !settings.demoMode) {
            // Other apps the person ticked stay counted alongside the clock apps.
            val packages = ClockApps.packagesFor(AlarmApps.CLOCK_APPS, found,
                ClockApps.extraApps(settings, found) - previous.map { it.packageName }.toSet())
            if (packages != settings.alarmPackages) save { repo.updateSettings { it.copy(alarmPackages = packages) } }
        }
    }

    /** Looks for other apps that set alarms, off the main thread; [all] also lists every app that asks to set them. */
    fun refreshOtherAlarmApps(all: Boolean = _allAlarmAppsShown.value) {
        _allAlarmAppsShown.value = all
        val context: android.app.Application = getApplication()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val creator = nextAlarm.value?.identifiedCreator
            _otherAlarmApps.value = ClockApps.otherAlarmApps(context, _clockApps.value, creator, all,
                ticked = settings.value.alarmPackages)
        }
    }

    /**
     * Where alarms come from: any clock app, only [chosen], or none. A running night is checked against the new
     * choice straight away, so a wake taken from an app no longer chosen goes back.
     */
    fun chooseAlarmApps(choice: AlarmApps, chosen: String? = null) = chooseAlarmApps(choice, setOfNotNull(chosen))
    fun chooseAlarmApps(choice: AlarmApps, chosen: Set<String>) = save {
        repo.updateSettings { ClockApps.choose(it, choice, _clockApps.value, chosen) }
        if (activeNight.value != null) recheckAlarm() else refreshAlarm()
    }
    fun chooseUnnamedAlarms(choice: io.github.willzy12h.nocturne.core.store.UnnamedAlarms) = save {
        repo.updateSettings { it.copy(unnamedAlarms = choice) }
        if (activeNight.value != null) recheckAlarm() else refreshAlarm()
    }
    fun snapshotNow(label: String) = save { repo.snapshotNow(label); _messages.tryEmit(text(R.string.runtime_snapshot_saved)) }
    fun deleteSnapshot(id: String) = save { repo.deleteSnapshot(id) }
    fun clearNotices() = save { repo.clearNotices() }
    fun setHistoryKeepDays(days: Int) = save { repo.setHistoryKeepDays(days) }
    internal fun setScheduledStartSkipped(ticket: io.github.willzy12h.nocturne.platform.AutoStartTicket, skip: Boolean) = save {
        if (!io.github.willzy12h.nocturne.platform.AutoStartSchedule.setSkipped(graph, ticket, skip))
            throw IllegalStateException(text(R.string.auto_start_review_settings))
    }
    fun deleteHistory() = save { repo.deleteHistory() }
    fun retryHistoryErasure() = save { repo.retryHistoryErasure() }
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
                _messages.tryEmit(text(if (preview.profilesOnly) R.string.parity_profiles_imported else R.string.runtime_backup_restored))
                if (!preview.profilesOnly && _restoreOffer.value) dismissRestoreOffer()
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
                .onFailure { if (it is CancellationException) throw it
                    _messages.tryEmit(if (it is BackupException) text(R.string.backup_unrestorable_library)
                        else text(R.string.runtime_backup_save_failed, it.message.toString())) }
        }
    }

    internal fun profileShareRequest(profile: Profile) = repo.profileShareRequest(profile)

    internal fun shareProfile(uri: android.net.Uri, request: io.github.willzy12h.nocturne.data.ProfileShareRequest) {
        graph.scope.launch {
            runCatching { repo.exportProfileTo(uri, request) }
                .onSuccess { _messages.tryEmit(text(R.string.parity_profile_shared)) }
                .onFailure { if (it is CancellationException) throw it
                    _messages.tryEmit(text(R.string.runtime_backup_save_failed, it.message.toString())) }
        }
    }

    internal suspend fun diagnosticsPreview(includeAlarmTimes: Boolean): String? = try {
        withContext(Dispatchers.IO) { graph.actions.diagnostics(includeAlarmTimes) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _messages.tryEmit(text(R.string.diagnostics_preview_failed))
        null
    }

    fun exportDiagnostics(uri: android.net.Uri, includeAlarmTimes: Boolean = false,
        open: ((android.net.Uri) -> java.io.OutputStream?)? = null) = saveDiagnostics {
        if (open == null) graph.actions.exportDiagnostics(uri, includeAlarmTimes)
        else graph.actions.exportDiagnostics(uri, includeAlarmTimes, open)
    }

    internal fun exportReviewedDiagnostics(uri: android.net.Uri, report: String) = saveDiagnostics {
        graph.actions.exportReviewedDiagnostics(uri, report)
    }

    private fun saveDiagnostics(write: suspend () -> Unit) {
        val app = getApplication<Application>()
        graph.scope.launch {
            runCatching { write() }
                .onSuccess { _messages.tryEmit(app.getString(R.string.settings_diagnostics_saved)) }
                .onFailure { if (it is CancellationException) throw it
                    _messages.tryEmit(app.getString(R.string.settings_diagnostics_failed, it.message.orEmpty())) }
        }
    }

    fun import(uri: android.net.Uri) = stageImport { repo.previewImport(repo.importFrom(uri)) }
    fun importProfiles(uri: android.net.Uri) = stageImport { repo.previewImport(repo.importFrom(uri), profilesOnly = true) }

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

private const val RESTORE_OFFER_DISMISSED = "restoreOfferDismissed"
private const val RESTORE_OFFER_PENDING = "restoreOfferPending"
