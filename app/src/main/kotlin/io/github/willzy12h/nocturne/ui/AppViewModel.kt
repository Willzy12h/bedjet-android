package io.github.willzy12h.nocturne.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.willzy12h.nocturne.BuildConfig
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.Release
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.UpdateChecker
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CancellationException
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
    private val graph = application.graph
    private val repo = graph.repository

    val profiles = repo.profiles
    val settings = repo.settings
    val history = repo.history
    val activeNight = repo.activeNight
    val slotBackups = repo.slotBackups
    val snapshots = repo.snapshotList
    val notices = repo.notices

    /** Follows the live link, which changes when demo mode is switched. */
    val linkState: StateFlow<LinkState> = repo.settings.map { graph.link }.flatMapLatest { it.state }
        .stateIn(graph.scope, SharingStarted.Eagerly, LinkState.SEARCHING)
    val status: StateFlow<DeviceStatus?> = repo.settings.map { graph.link }.flatMapLatest { it.status }
        .stateIn(graph.scope, SharingStarted.Eagerly, null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
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
        refreshAlarm()
        graph.sessions.holdForScreen(true)
        // An alarm change that couldn't reach the BedJet earlier.
        graph.scope.launch {
            graph.actions.reconcile()
            if (graph.actions.retimePending) io.github.willzy12h.nocturne.service.BedJetService.start(getApplication(), NightActions.Command.RETRY_RETIME)
        }
        maybeAutoCheckUpdates()
    }

    fun onScreenHidden() = graph.sessions.holdForScreen(false)

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
        val start = if (startNow) now else WakeResolver.nextBedtime(profile, now)
        PlanCompiler.compile(profile, start, WakeResolver.resolve(profile, start, nextAlarm.value?.takeIf { it.counts }?.time).time)
    }

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
        act(hardware = command != NightActions.Command.STOP_TRACKING) { graph.actions.execute(request).message }
    }

    /** A Remote control: sent straight to the BedJet. */
    fun remote(label: String, endsNight: Boolean = false, work: suspend (BedJetController) -> Unit) {
        val captured = graph.actions.capture(NightActions.Command.OFF)
        val expected = confirmedNight ?: captured.expected
        act { graph.actions.remote(label, endsNight, captured.deviceId, expected, work).message }
    }

    fun restoreSlot(slot: Int, program: StoredProgram) = act {
        graph.actions.restoreOriginal(slot).message
    }

    /** Commands wait for saves asked for before them, so they act on what the person just saved. */
    private fun act(hardware: Boolean = true, work: suspend () -> String) {
        val pending = lastSave
        val target = graph.actions.capture(NightActions.Command.OFF).deviceId
        graph.scope.launch {
            pending?.join()
            _busy.value = true
            val message = try {
                if (hardware) kotlinx.coroutines.withContext(io.github.willzy12h.nocturne.night.SubmissionTarget(target)) {
                    io.github.willzy12h.nocturne.service.BedJetService.runOwned(getApplication()) { work() }
                } else work()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { e.message ?: "Something went wrong." }
            finally { _busy.value = false }
            _messages.tryEmit(message)
        }
    }

    // Profiles, settings, backups

    // Saving happens off the main thread; the screens update once it is on disk.

    fun saveProfile(profile: Profile) = save { repo.saveProfile(profile) }
    fun deleteProfile(id: String) = save { repo.deleteProfile(id) }
    fun updateSettings(change: (Settings) -> Settings) = save {
        val outcome = graph.actions.updateSettings(change)
        if (!outcome.succeeded) _messages.tryEmit(outcome.message)
    }

    /** "Update tonight": the edited profile is saved before the rest of the night is rewritten from it. */
    fun saveProfileThenReapply(profile: Profile) {
        val request = graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)
        act {
            repo.saveProfile(profile)
            graph.actions.execute(request).message
        }
    }

    /** Lets [creator]'s alarms move the wake-up, and shows the next alarm under the saved setting. */
    fun useAlarmsFrom(creator: String) = save {
        repo.updateSettings { it.copy(alarmPackages = it.alarmPackages + creator) }
        refreshAlarm()
    }
    fun snapshotNow(label: String) = save { repo.snapshotNow(label); _messages.tryEmit("Snapshot saved.") }
    fun deleteSnapshot(id: String) = save { repo.deleteSnapshot(id) }
    fun clearNotices() = save { repo.clearNotices() }
    fun restoreSnapshot(id: String) = save {
        _messages.tryEmit(if (repo.restoreSnapshot(id)) "Restored. What you had before is saved as a snapshot." else "That snapshot couldn't be read.")
    }

    fun export(uri: android.net.Uri) {
        graph.scope.launch {
            runCatching { repo.exportTo(uri) }
                .onSuccess { _messages.tryEmit("Backup saved.") }
                .onFailure { _messages.tryEmit("Couldn't save the backup: ${it.message}") }
        }
    }

    fun import(uri: android.net.Uri) {
        graph.scope.launch {
            runCatching { repo.restore(repo.importFrom(uri)) }
                .onSuccess { _messages.tryEmit("Backup restored. What you had before is saved as a snapshot.") }
                .onFailure { _messages.tryEmit(it.message ?: "Couldn't read that file.") }
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
                _messages.tryEmit("Couldn't save: ${e.message ?: "something went wrong"}.")
            }
        }
    }

    fun forgetBedJet() = act(hardware = false) { graph.actions.forgetTarget().message }

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
                        404 -> error("No release found. A private repository needs a token in Settings.")
                        else -> error("GitHub answered $code.")
                    }
                }
            }
            getApplication<Application>().getSharedPreferences("updates", 0).edit().putLong("lastCheck", graph.clock.millis()).apply()
            result.onSuccess { latest ->
                if (latest != null && UpdateChecker.isNewer(latest.tag, version)) {
                    _release.value = latest
                    if (quiet) Notifications.showUpdate(getApplication(), latest.tag, latest.pageUrl)
                } else if (!quiet) _messages.tryEmit("You're on the latest version ($version).")
            }.onFailure { if (!quiet) _messages.tryEmit("Update check failed: ${it.message}") }
        }
    }

    fun dismissRelease() { _release.value = null }

    private fun maybeAutoCheckUpdates() {
        if (!settings.value.autoUpdateCheck) return
        val last = getApplication<Application>().getSharedPreferences("updates", 0).getLong("lastCheck", 0)
        if (graph.clock.millis() - last > 7 * 24 * 3600 * 1000L) checkForUpdates(quiet = true)
    }
}
