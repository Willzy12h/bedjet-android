package io.github.willzy12h.nocturne.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.willzy12h.nocturne.BuildConfig
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.FoundBedJet
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Preview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.core.store.Release
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.data.MiddleSuggestion
import io.github.willzy12h.nocturne.data.MorningCheckIn
import io.github.willzy12h.nocturne.core.store.UpdateChecker
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/** The screens' view of the app: stored data, the live BedJet, and the actions behind every button. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    internal val graph = application.graph
    private val repo = graph.repository

    val profiles = repo.profiles
    val settings = repo.settings
    val history = repo.history
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
    /** The chosen BedJet's own status: a report from a session still on another unit is not shown. */
    val status: StateFlow<DeviceStatus?> = repo.settings.flatMapLatest { s -> graph.link.sourcedStatus.map { displayedStatus(it, s.deviceId()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _busy = MutableStateFlow(false)
    /** True while a request from this screen waits for a save or for the command owner. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    /** What the command owner is running now and how many requests wait behind it, from any origin (U07). */
    val commandBusy: StateFlow<io.github.willzy12h.nocturne.night.BusyState> = graph.actions.busy
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    private val _haptics = MutableSharedFlow<OutcomeKind>(extraBufferCapacity = 8)
    /** Every command outcome's kind, for the accepted tick and the confirmed pattern. */
    val haptics: SharedFlow<OutcomeKind> = _haptics
    /** Outcomes already shown, by identity: the screen's own request and the owner's feed report the same object. */
    private val shownOutcomes = ArrayDeque<CommandOutcome>()
    private var screenVisible = false

    init {
        // The one in-app outcome collector (U08): widget, shortcut, alarm and charger outcomes are shown while the
        // app is open, and an outcome this screen asked for is shown once, whichever side sees it first.
        viewModelScope.launch {
            graph.actions.outcomes.collect { outcome ->
                _haptics.tryEmit(outcome.kind)
                if (screenVisible) report(outcome)
            }
        }
    }

    /** Shows [outcome] once. A plain "Settings saved." is not announced: the screen already shows the change. */
    fun report(outcome: CommandOutcome) {
        if (shownOutcomes.any { it === outcome }) return
        shownOutcomes.addLast(outcome)
        while (shownOutcomes.size > 32) shownOutcomes.removeFirst()
        if (outcome.kind == OutcomeKind.LOCAL && outcome.message == getApplication<Application>().getString(R.string.runtime_settings_saved)) return
        _messages.tryEmit(outcome.message)
    }
    private val _nextAlarm = MutableStateFlow<AlarmSource.NextAlarm?>(null)
    val nextAlarm: StateFlow<AlarmSource.NextAlarm?> = _nextAlarm.asStateFlow()
    private val _release = MutableStateFlow<Release?>(null)
    val release: StateFlow<Release?> = _release.asStateFlow()

    val version: String = BuildConfig.VERSION_NAME

    /** The app clock's current time. */
    fun now(): ZonedDateTime = graph.now()
    var updateToken: String
        get() = repo.updateToken
        set(value) { repo.updateToken = value }

    // Screen lifecycle: hold the BedJet while the app is on screen so the controls are live.

    fun onScreenVisible() {
        screenVisible = true
        refreshAlarm()
        graph.sessions.holdForScreen(true)
        // An alarm change that couldn't reach the BedJet earlier.
        graph.scope.launch {
            graph.actions.reconcile()
            if (graph.actions.retimePending) io.github.willzy12h.nocturne.service.BedJetService.start(getApplication(), NightActions.Command.RETRY_RETIME)
        }
        maybeAutoCheckUpdates()
    }

    fun onScreenHidden() {
        screenVisible = false
        graph.sessions.holdForScreen(false)
    }

    /** The connection chip: try the BedJet again. */
    fun reconnect() {
        graph.sessions.reconnect()
    }

    fun refreshAlarm() {
        _nextAlarm.value = AlarmSource.describe(getApplication(), repo.settings.value)
    }

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
    }

    /** One preview supplies the wake headline, curve and actual Start policy. */
    fun startPreview(profile: Profile, now: ZonedDateTime = graph.now()): Preview = NightPreview.forStartNow(
        profile, now, nextAlarm.value?.time, repo.settings.value.resolveWakeOverride(now),
        repo.settings.value, nextAlarm.value?.creator,
    )

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
            .let { it.copy(expected = confirmedNight ?: it.expected, replacementConfirmed = replacementConfirmed || confirmedNight != null) }
        act(hardware = command != NightActions.Command.STOP_TRACKING) { graph.actions.execute(request) }
    }

    /** A Remote control: sent straight to the BedJet. */
    fun remote(label: String, endsNight: Boolean = false, affectsProgramme: Boolean = false,
        confirms: ((io.github.willzy12h.nocturne.core.protocol.DeviceStatus) -> Boolean)? = null,
        work: suspend (BedJetController) -> Unit) {
        val captured = graph.actions.capture(NightActions.Command.OFF)
        val expected = confirmedNight ?: captured.expected
        act { graph.actions.remote(label, endsNight = endsNight, affectsProgramme = affectsProgramme,
            target = captured.deviceId, expected = expected, confirms = confirms, work = work) }
    }

    fun restoreSlot(backup: SlotBackup) = act {
        if (backup.deviceId != repo.settings.value.deviceId()) refusal(text(R.string.runtime_restore_target_changed))
        else if (repo.slotBackups.value.firstOrNull { it.deviceId == backup.deviceId && it.slot == backup.slot } != backup)
            refusal(text(R.string.runtime_restore_original_changed))
        else graph.actions.restoreOriginal(backup.slot)
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
    private fun refusal(message: String) = CommandOutcome(OutcomeKind.REFUSED, message)

    /** A message from resources, for outcomes and snackbars. */
    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun act(hardware: Boolean = true, work: suspend () -> CommandOutcome) {
        val pending = lastSave
        val target = graph.actions.capture(NightActions.Command.OFF).deviceId
        viewModelScope.launch {
            pending?.join()
            _busy.value = true
            val outcome = try {
                if (hardware) kotlinx.coroutines.withContext(io.github.willzy12h.nocturne.night.SubmissionTarget(target)) {
                    io.github.willzy12h.nocturne.service.BedJetService.runOwned(getApplication(), notifyFailures = false) { work() }
                } else work()
            } catch (e: TimeoutCancellationException) { CommandOutcome(OutcomeKind.UNKNOWN, e.message ?: text(R.string.runtime_no_answer))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { CommandOutcome(OutcomeKind.FAILED, e.message ?: text(R.string.runtime_something_wrong)) }
            finally { _busy.value = false }
            report(outcome)
        }
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
        withContext(Dispatchers.Main) { report(outcome) }
    }

    fun setTonightWake(wake: ZonedDateTime?) {
        val expected = activeNight.value?.identity
        act(hardware = expected != null) {
            if (activeNight.value?.identity != expected) refusal(text(R.string.runtime_wake_night_changed))
            else graph.actions.setWakeOverride(wake, expected)
        }
    }

    /** "Update tonight": the edited profile is saved before the rest of the night is rewritten from it. */
    fun saveProfileThenReapply(profile: Profile) {
        val request = graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)
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
    fun restoreSnapshot(id: String) = save { _messages.tryEmit(snapshotRestoreMessage { repo.restoreSnapshot(id) }) }

    fun export(uri: android.net.Uri) {
        graph.scope.launch {
            runCatching { repo.exportTo(uri) }
                .onSuccess { _messages.tryEmit(text(R.string.runtime_backup_saved)) }
                .onFailure { _messages.tryEmit(text(R.string.runtime_backup_save_failed, it.message.toString())) }
        }
    }

    /** Writes the diagnostics file the person chose (NightActions.diagnostics: no update token). */
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

    fun import(uri: android.net.Uri) {
        graph.scope.launch {
            runCatching { repo.restore(repo.importFrom(uri)) }
                .onSuccess { _messages.tryEmit(text(R.string.runtime_backup_restored)) }
                .onFailure { _messages.tryEmit(it.message ?: text(R.string.runtime_read_failed)) }
        }
    }

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

    fun discoverBedJets() {
        viewModelScope.launch {
            _discovering.value = true
            _foundBedJets.value = emptyList()
            try { _foundBedJets.value = graph.bleLink.discover(10_000) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _messages.tryEmit(e.message ?: text(R.string.runtime_discover_failed)) }
            finally { _discovering.value = false }
        }
    }

    fun selectBedJet(address: String) = act(hardware = false) {
        graph.actions.selectTarget("ble:$address", useRealDevice = true)
    }

    /** Setup's separate "Use demo instead": Demo on, the real target left as it is (null stays null). */
    fun useDemo() = act(hardware = false) { graph.actions.setDemoMode(true) }

    fun runSetupFanCheck() = act { graph.actions.setupFanCheck() }

    fun bedJetName(): String? = if (settings.value.demoMode) "BEDJET-DEMO" else graph.bleLink.deviceName

    // Updates

    fun checkForUpdates(quiet: Boolean = false) {
        graph.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(UpdateChecker.latestUrl(settings.value.updateRepo)).openConnection() as HttpURLConnection
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    repo.updateToken.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    when (val code = connection.responseCode) {
                        200 -> UpdateChecker.parseLatest(connection.inputStream.bufferedReader().readText())
                        404 -> error(text(R.string.runtime_update_no_release))
                        else -> error(text(R.string.runtime_update_http, code))
                    }
                }
            }
            getApplication<Application>().getSharedPreferences("updates", 0).edit().putLong("lastCheck", graph.clock.millis()).apply()
            result.onSuccess { latest ->
                if (latest != null && UpdateChecker.isNewer(latest.tag, version)) {
                    _release.value = latest
                    if (quiet) Notifications.showUpdate(getApplication(), latest.tag, latest.pageUrl)
                } else if (!quiet) _messages.tryEmit(text(R.string.runtime_update_latest, version))
            }.onFailure { if (!quiet) _messages.tryEmit(text(R.string.runtime_update_failed, it.message.toString())) }
        }
    }

    fun dismissRelease() { _release.value = null }

    private fun maybeAutoCheckUpdates() {
        if (!settings.value.autoUpdateCheck) return
        val last = getApplication<Application>().getSharedPreferences("updates", 0).getLong("lastCheck", 0)
        if (graph.clock.millis() - last > 7 * 24 * 3600 * 1000L) checkForUpdates(quiet = true)
    }
}

/**
 * What restoring a snapshot says. A restore that is kept but not yet finished says so in its own words; any other
 * failure is thrown, for the caller's "Couldn't save" message.
 */
internal suspend fun snapshotRestoreMessage(restore: suspend () -> Boolean): String = try {
    if (restore()) "Restored. What you had before is saved as a snapshot." else "That snapshot couldn't be read."
} catch (e: io.github.willzy12h.nocturne.data.RestoreUnfinished) {
    e.message.orEmpty()
}

/** What the screens show as the BedJet's status: only a report from [target] itself (or an unattributed one). */
internal fun displayedStatus(report: io.github.willzy12h.nocturne.core.link.SourcedStatus?, target: String?): DeviceStatus? =
    report?.takeIf { it.isFrom(target) }?.status
