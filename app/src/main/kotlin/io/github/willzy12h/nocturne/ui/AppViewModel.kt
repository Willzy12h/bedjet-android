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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    var updateToken: String
        get() = repo.updateToken
        set(value) { repo.updateToken = value }

    // Screen lifecycle: hold the BedJet while the app is on screen so the controls are live.

    fun onScreenVisible() {
        refreshAlarm()
        graph.scope.launch { runCatching { graph.sessions.acquire() }.onFailure { graph.sessions.releaseLater() } }
        maybeAutoCheckUpdates()
    }

    fun onScreenHidden() = graph.sessions.releaseLater()

    fun refreshAlarm() {
        _nextAlarm.value = AlarmSource.describe(getApplication(), repo.settings.value)
    }

    // Previews

    fun tonightsProfile(): Profile? = repo.tonightsProfile()

    /** When a profile would wake you if it ran tonight, and whether that's from the alarm. */
    fun wakePreview(profile: Profile, now: ZonedDateTime = ZonedDateTime.now()): WakeChoice {
        val bedtime = WakeResolver.nextBedtime(profile, now)
        return WakeResolver.resolve(profile, bedtime, nextAlarm.value?.takeIf { it.counts }?.time)
    }

    /** The plan a profile would run tonight, or the reason it can't. */
    fun previewPlan(profile: Profile, startNow: Boolean = false): Result<Plan> = runCatching {
        val now = ZonedDateTime.now()
        val start = if (startNow) now else WakeResolver.nextBedtime(profile, now)
        PlanCompiler.compile(profile, start, WakeResolver.resolve(profile, start, nextAlarm.value?.takeIf { it.counts }?.time).time)
    }

    /** The running night's plan, rebuilt from what was stored when it started. */
    fun activePlan(): Plan? {
        val night = activeNight.value ?: return null
        val profile = repo.profile(night.profileId) ?: return null
        return runCatching { PlanCompiler.compile(profile, ZonedDateTime.parse(night.startedAt), ZonedDateTime.parse(night.wakeAt)) }.getOrNull()
    }

    // Commands

    fun run(command: NightActions.Command, profileId: String? = null) = act {
        graph.sessions.withConnection { graph.actions.run(command, profileId) }
    }

    /** A Remote control: sent straight to the BedJet. */
    fun remote(label: String, work: suspend (BedJetController) -> Unit) = act {
        graph.sessions.withConnection { work(graph.controller()) }
        label
    }

    fun restoreSlot(slot: Int, program: StoredProgram) = act {
        graph.sessions.withConnection { graph.controller().store(program, slot) }
        "Put \"${program.name}\" back in slot ${slot + 1}."
    }

    private fun act(work: suspend () -> String) {
        graph.scope.launch {
            _busy.value = true
            val message = try { work() } catch (e: Exception) { e.message ?: "Something went wrong." }
            _busy.value = false
            _messages.tryEmit(message)
        }
    }

    // Profiles, settings, backups

    fun saveProfile(profile: Profile) = repo.saveProfile(profile)
    fun deleteProfile(id: String) = repo.deleteProfile(id)
    fun updateSettings(change: (Settings) -> Settings) = repo.updateSettings(change)
    fun snapshotNow(label: String) { repo.snapshotNow(label); _messages.tryEmit("Snapshot saved.") }
    fun deleteSnapshot(id: String) = repo.deleteSnapshot(id)
    fun restoreSnapshot(id: String) {
        _messages.tryEmit(if (repo.restoreSnapshot(id)) "Restored. What you had before is saved as a snapshot." else "That snapshot couldn't be read.")
    }

    fun export(uri: android.net.Uri) {
        runCatching { repo.exportTo(uri) }
            .onSuccess { _messages.tryEmit("Backup saved.") }
            .onFailure { _messages.tryEmit("Couldn't save the backup: ${it.message}") }
    }

    fun import(uri: android.net.Uri) {
        runCatching { repo.restore(repo.importFrom(uri)) }
            .onSuccess { _messages.tryEmit("Backup restored. What you had before is saved as a snapshot.") }
            .onFailure { _messages.tryEmit(it.message ?: "Couldn't read that file.") }
    }

    fun forgetBedJet() {
        graph.bleLink.forget()
        _messages.tryEmit("Forgot this BedJet. The next one found nearby will be used.")
    }

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
            getApplication<Application>().getSharedPreferences("updates", 0).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            result.onSuccess { latest ->
                if (latest != null && UpdateChecker.isNewer(latest.tag, version)) {
                    _release.value = latest
                    if (quiet) Notifications.showUpdate(getApplication(), latest.tag, latest.apkUrl ?: latest.pageUrl)
                } else if (!quiet) _messages.tryEmit("You're on the latest version ($version).")
            }.onFailure { if (!quiet) _messages.tryEmit("Update check failed: ${it.message}") }
        }
    }

    fun dismissRelease() { _release.value = null }

    private fun maybeAutoCheckUpdates() {
        if (!settings.value.autoUpdateCheck) return
        val last = getApplication<Application>().getSharedPreferences("updates", 0).getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last > 7 * 24 * 3600 * 1000L) checkForUpdates(quiet = true)
    }
}
