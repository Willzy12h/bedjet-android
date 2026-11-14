package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.RuntimeState
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.util.AtomicFile
import androidx.core.content.edit
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.BackupFailure
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.History
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.NotificationLog
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.store.SnapshotInfo
import io.github.willzy12h.nocturne.core.store.SnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the app keeps, as JSON files in its private storage. Changes to profiles and settings are snapshotted
 * automatically (debounced), and on the first launch of each day.
 *
 * Every change is written durably, off the main thread and one at a time on [writer], before its new value is
 * published: a flow never shows something that a power cut could take back. When the write fails the old value stays
 * and the error is thrown to the caller. [writeFile] is how a file is written; tests replace it to make writes fail.
 *
 * This file holds the state and the public API. The work behind it is in extension functions in this package, one
 * file per responsibility: RepositoryStorage.kt (durable files and the restore journal), RepositoryImport.kt,
 * RepositoryOriginals.kt, RepositoryMorningCheckIn.kt and RepositoryDemoSession.kt. Members marked internal for
 * those files are not for use elsewhere.
 */
@OptIn(FlowPreview::class)
class Repository(
    internal val context: Context,
    scope: CoroutineScope,
    internal val writer: CoroutineDispatcher = defaultWriter(),
    internal val writeFile: (File, ByteArray) -> Unit = ::writeDurably,
    private val openInput: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
    private val openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it, "wt") },
    /** How the restore journal is removed; tests replace it to make the removal fail. */
    internal val deleteFile: (File) -> Unit = { AtomicFile(it).delete() },
    internal val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    internal val dir = context.filesDir
    val snapshots = SnapshotStore(File(dir, "snapshots").toPath()) { now() }
    internal val lock = Mutex()
    val operationAdmission = OperationAdmission()

    // Declared before the loads below, which add to them.
    /** Files found unreadable while loading, moved aside by the writer once the Repository is made. */
    internal val toSetAside = mutableListOf<File>()
    /**
     * The contents, by file name, of a multi-file change ([writeAllOrNone]) that is committed (its journal is on disk)
     * but not yet completely written; null when there is none. What is shown already follows it, so it is finished on
     * disk before any of its files is written again ([finishJournalBefore]); otherwise the next start would roll it
     * forward over that write.
     */
    internal var pendingJournal: Map<String, ByteArray>? = null
    // Complete any interrupted multi-file restore before individual files are loaded as one repository state.
    private val journalState = recoverPendingRestore()
    /** A journal still pending after start-up recovery: reported in [unreadable] until it is finished. */
    internal var journalStuck = journalState == JournalState.STUCK
        set(value) { field = value; _restoreUnfinished.value = value }
    internal val _restoreUnfinished = MutableStateFlow(journalState == JournalState.STUCK)
    /** True while a committed restore still isn't finished on disk ([RESTORE_JOURNAL] listed in [unreadable] for that
     * reason, not because a damaged journal was set aside). Dismissing the notice doesn't clear it. */
    val restoreUnfinished: StateFlow<Boolean> = _restoreUnfinished.asStateFlow()
    internal val _unreadable = MutableStateFlow((readUnreadableMarker() + listOfNotNull(RESTORE_JOURNAL.takeIf { journalState != JournalState.NONE })).distinct())
    // Dismissing the unreadable-file notice must not make an unknown slot original safe to overwrite.
    private val _originalsBlocked = MutableStateFlow(File(dir, SLOT_BACKUPS_BLOCK).exists())
    /**
     * True while the saved slot originals are known to have been unreadable (IR-03): a slot with no saved original
     * must not be written, whatever the notice in [unreadable] says. It stays true across restarts and a plain
     * [dismissUnreadable], and is lifted only when the person accepts the loss with
     * `dismissUnreadable(acceptLostOriginals = true)`.
     */
    val originalsBlocked: StateFlow<Boolean> = _originalsBlocked.asStateFlow()
    internal var originalsIntegrityBlocked: Boolean
        get() = _originalsBlocked.value
        set(value) { _originalsBlocked.value = value }
    /** When the loss of unreadable originals was accepted, or null; originals saved since are marked uncertain. */
    // A damaged record is set aside and reported, and read as accepted now: originals saved from now on stay
    // uncertain, while one saved earlier keeps the current-wins rule instead of giving way to any backup's.
    internal var originalsLossAcceptedAt: String? =
        load(SLOT_BACKUPS_LOSS) { text -> decode(String.serializer(), text).also { require(runCatching { ZonedDateTime.parse(it) }.isSuccess) } }
            ?: now().toString().takeIf { SLOT_BACKUPS_LOSS in _unreadable.value }
    /**
     * Files that couldn't be read, so their defaults are showing instead. Each was moved aside as
     * `<name>.corrupt-<millis>`. Kept across restarts until a restore or [dismissUnreadable]; automatic snapshots are
     * paused meanwhile, so they don't push the good ones out.
     */
    val unreadable: StateFlow<List<String>> = _unreadable.asStateFlow()

    internal val _profiles = MutableStateFlow(load(PROFILES) { decode(ListSerializer(Profile.serializer()), it) } ?: Presets.all)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val settingsLoadedAt = now()
    internal val _settings = MutableStateFlow(load(SETTINGS) { decode(Settings.serializer(), it) } ?.withoutUnidentifiedAlarms() ?: Settings().withoutUnidentifiedAlarms())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    // Publication signal, not persisted state: equal-value restores must still reconcile side effects.
    internal val _settingsPublication = MutableStateFlow(0L)
    internal val settingsPublication: StateFlow<Long> = _settingsPublication.asStateFlow()
    // Unlike demoMode, this cannot conflate an enter/exit round trip back to the same value.
    internal val _sessionRevision = MutableStateFlow(0L)
    internal val sessionRevision: StateFlow<Long> = _sessionRevision.asStateFlow()
    // Activity recreation keeps this instance; a new process/repository must invalidate every pending Share.
    private val shareInstanceNonce = UUID.randomUUID().toString()
    internal val _history = MutableStateFlow(History.prune(load(HISTORY, Migrations::decodeHistory).orEmpty(), now(), _settings.value.historyKeepDays))
    val history: StateFlow<List<NightRecord>> = _history.asStateFlow()
    internal val _slotBackups = MutableStateFlow(load(SLOT_BACKUPS, Migrations::decodeSlotBackups).orEmpty())
    /** What each device's slots held before the app first wrote them. See [recordOriginalSlot]. */
    val slotBackups: StateFlow<List<SlotBackup>> = _slotBackups.asStateFlow()
    internal val _activeNight = MutableStateFlow(load(ACTIVE_NIGHT) { decode(ActiveNight.serializer().nullable, it) })
    val activeNight: StateFlow<ActiveNight?> = _activeNight.asStateFlow()
    internal val _notices = MutableStateFlow(load(NOTICES) { decode(ListSerializer(LoggedNotice.serializer()), it) }.orEmpty())
    /** Every notification the app has shown, newest first. Not part of backups. */
    val notices: StateFlow<List<LoggedNotice>> = _notices.asStateFlow()
    internal val _snapshotList = MutableStateFlow(snapshots.list())
    val snapshotList: StateFlow<List<SnapshotInfo>> = _snapshotList.asStateFlow()

    internal val _runtime = MutableStateFlow(load(RUNTIME) { decode(RuntimeState.serializer(), it) } ?: RuntimeState())
    val runtime: StateFlow<RuntimeState> = _runtime.asStateFlow()
    suspend fun updateRuntime(change: (RuntimeState) -> RuntimeState) = update(_runtime, RUNTIME, RuntimeState.serializer(), change)

    internal val _automation = MutableStateFlow(load(AUTOMATION) {
        decode(io.github.willzy12h.nocturne.platform.AutomationState.serializer(), it)
    } ?: io.github.willzy12h.nocturne.platform.AutomationState())
    val automation: StateFlow<io.github.willzy12h.nocturne.platform.AutomationState> = _automation.asStateFlow()
    suspend fun updateAutomation(change: (io.github.willzy12h.nocturne.platform.AutomationState) -> io.github.willzy12h.nocturne.platform.AutomationState) =
        update(_automation, AUTOMATION, io.github.willzy12h.nocturne.platform.AutomationState.serializer(), change)
    /** A queued report may outlive its target or demo session; reject it before any durable write. */
    suspend fun recordDeviceObservation(observation: io.github.willzy12h.nocturne.platform.DeviceObservation) = serially {
        if (_settings.value.deviceId() != observation.device) return@serially
        val state = _automation.value.copy(observed = observation)
        writeValue(AUTOMATION, io.github.willzy12h.nocturne.platform.AutomationState.serializer(), state)
        _automation.value = state
    }
    suspend fun claimAutomation(key: String): Boolean = serially {
        val current = _automation.value
        if (key in current.claims) false else {
            val next = current.copy(claims = (current.claims + key).takeLast(256))
            writeValue(AUTOMATION, io.github.willzy12h.nocturne.platform.AutomationState.serializer(), next)
            _automation.value = next
            true
        }
    }

    /** The same flows serve a disposable demo; their durable real values are restored verbatim on exit. */
    internal data class SessionState(
        val settings: Settings,
        val profiles: List<Profile>,
        val history: List<NightRecord>,
        val originals: List<SlotBackup>,
        val activeNight: ActiveNight?,
        val runtime: RuntimeState,
        val automation: io.github.willzy12h.nocturne.platform.AutomationState,
        val notices: List<LoggedNotice>,
        val snapshots: List<SnapshotInfo>,
        val unreadable: List<String>,
        val originalsBlocked: Boolean,
        val originalsLossAcceptedAt: String?,
        val restoreUnfinished: Boolean,
    )
    @Volatile private var realSession: SessionState? = null
    /** Explicit demo is transient; a legacy persisted demo flag is migrated at actual application startup. */
    val isDemoSession: Boolean get() = realSession != null
    private val demoOriginals = ConcurrentHashMap<Int, SlotBackup>()
    @Volatile var startupMigrationBlocked: Boolean = false
        private set

    internal suspend fun enterDemoSession() = serially {
        if (isDemoSession) return@serially
        check(!startupMigrationBlocked) { context.getString(R.string.gpt12_demo_migration_failed) }
        check(_activeNight.value == null) { context.getString(R.string.runtime_active_target) }
        val real = captureSession()
        val appearance = real.settings
        realSession = real
        demoOriginals.clear()
        publishSession(real.copy(
            settings = Settings(demoMode = true, fahrenheit = appearance.fahrenheit, simpleView = appearance.simpleView,
                themeMode = appearance.themeMode, nightPalette = appearance.nightPalette, dayPalette = appearance.dayPalette,
                scheduledDarkMinute = appearance.scheduledDarkMinute,
                scheduledLightMinute = appearance.scheduledLightMinute, alarmPackages = appearance.alarmPackages,
                acceptUnknownAlarmSource = false, alarmApps = appearance.alarmApps, alarmAppsChosen = appearance.alarmAppsChosen,
                quickControlsCollapsed = appearance.quickControlsCollapsed, collapsedTonightSections = appearance.collapsedTonightSections,
                tonightSectionOrder = appearance.tonightSectionOrder),
            profiles = Presets.all, history = emptyList(), originals = emptyList(), activeNight = null,
            runtime = RuntimeState(generation = real.runtime.generation + 1),
            automation = io.github.willzy12h.nocturne.platform.AutomationState(), notices = emptyList(),
            snapshots = emptyList(), unreadable = emptyList(), originalsBlocked = false,
            originalsLossAcceptedAt = null, restoreUnfinished = false,
        ))
    }

    internal suspend fun exitDemoSession() = serially {
        val real = realSession ?: return@serially
        publishSession(real)
        realSession = null
        demoOriginals.clear()
    }

    /** Must finish before Graph starts observers, schedulers, widgets or a first-screen connection. */
    internal suspend fun migrateLegacyDemoSelection(): Boolean = serially {
        if (isDemoSession) return@serially true
        if (startupMigrationBlocked) return@serially false
        val previous = _settings.value
        val demoNight = _activeNight.value?.takeIf { it.deviceId == DeviceId.DEMO }
        if (!previous.demoMode && previous.targetDevice != DeviceId.DEMO && demoNight == null) return@serially true
        val settings = previous.copy(demoMode = false, targetDevice = null, setupFanCheckedDevice = null, demoTargetMigrated = true)
        val active = _activeNight.value.takeUnless { it?.deviceId == DeviceId.DEMO }
        val oldRuntime = _runtime.value
        val demoNights = setOfNotNull(demoNight?.recordId,
            oldRuntime.retime?.takeIf { it.deviceId == DeviceId.DEMO }?.expected?.nightId,
            oldRuntime.alarmInbox?.takeIf { it.deviceId == DeviceId.DEMO }?.nightId,
            oldRuntime.resumeOffer?.night?.takeIf { it.deviceId == DeviceId.DEMO }?.recordId,
            oldRuntime.slotWrite?.takeIf { it.deviceId == DeviceId.DEMO }?.nightId)
        val clearEnd = oldRuntime.endRequested?.nightId in demoNights
        val runtime = oldRuntime.copy(
            localDeliveries = oldRuntime.localDeliveries.filterNot { it.expected.nightId in demoNights },
            alarmInbox = oldRuntime.alarmInbox?.takeUnless { it.deviceId == DeviceId.DEMO },
            ending = oldRuntime.ending?.takeUnless { it.identity.nightId in demoNights },
            endRequested = oldRuntime.endRequested.takeUnless { clearEnd },
            endRequestedLabel = oldRuntime.endRequestedLabel.takeUnless { clearEnd },
            endRequestedAt = oldRuntime.endRequestedAt.takeUnless { clearEnd },
            endRequestedEpoch = oldRuntime.endRequestedEpoch.takeUnless { clearEnd },
            endRequestedSequence = oldRuntime.endRequestedSequence.takeUnless { clearEnd },
            wakeSourceChange = oldRuntime.wakeSourceChange?.takeUnless { it.expected.nightId in demoNights },
            resumeOffer = oldRuntime.resumeOffer?.takeUnless { it.night.deviceId == DeviceId.DEMO },
            retime = oldRuntime.retime?.takeUnless { it.deviceId == DeviceId.DEMO },
            slotWrite = oldRuntime.slotWrite?.takeUnless { it.deviceId == DeviceId.DEMO },
            reason = oldRuntime.reason.takeUnless { demoNight != null },
            wakeShown = oldRuntime.wakeShown?.takeUnless { it.nightId in demoNights },
            manualWakeShown = oldRuntime.manualWakeShown && oldRuntime.wakeShown?.nightId !in demoNights,
        )
        try {
            writeAllOrNone(listOf(
                Write(SETTINGS, encode(Settings.serializer(), previous), encode(Settings.serializer(), settings)) {
                    _settings.value = settings; _settingsPublication.value += 1
                },
                Write(ACTIVE_NIGHT, encode(ActiveNight.serializer().nullable, _activeNight.value), encode(ActiveNight.serializer().nullable, active)) {
                    _activeNight.value = active
                },
                Write(RUNTIME, encode(RuntimeState.serializer(), oldRuntime), encode(RuntimeState.serializer(), runtime)) {
                    _runtime.value = runtime
                },
            ))
            true
        } catch (e: Exception) {
            // A readable file whose migration could not be saved is not described as corrupt.
            // The old journal and every existing preservation marker remain available for the next startup.
            _activeNight.value = active
            _runtime.value = runtime
            _settings.value = settings
            _settingsPublication.value += 1
            startupMigrationBlocked = true
            if (pendingJournal != null) {
                journalStuck = true
                _unreadable.value = (_unreadable.value + RESTORE_JOURNAL).distinct()
            }
            val notice = LoggedNotice(now().toString(), NoticeKind.PROBLEM,
                context.getString(R.string.gpt12_demo_migration_title), context.getString(R.string.gpt12_demo_migration_failed))
            _notices.value = NotificationLog.add(_notices.value, notice)
            runCatching { writeValue(NOTICES, ListSerializer(LoggedNotice.serializer()), _notices.value) }
            false
        }
    }

    init {
        // The removed updater no longer needs its local credential or last-check timestamp.
        context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit { remove("updateToken") }
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit { remove("lastCheck") }
        // Moving the unreadable files aside and saving the report are writes, so they wait for the writer. Every
        // later write queues behind this one, so nothing can be written over an unreadable file before it is moved.
        if (toSetAside.isNotEmpty()) scope.launch(writer) { serially { setAsidePending() } }
        if (journalStuck) scope.launch {
            runCatching {
                addNotice(LoggedNotice(now().toString(), NoticeKind.PROBLEM, context.getString(R.string.restore_unfinished_title),
                    context.getString(R.string.runtime_restore_unfinished_notice)))
            }.onFailure { Log.e(TAG, "Couldn't log the unfinished restore", it) }
        }
        if (_settings.value.selectedProfileId != null) scope.launch {
            updateSettings { it.migrateTonightChoice(settingsLoadedAt) }
        }
        scope.launch {
            combine(_profiles, _settings) { _, _ -> }.drop(1).debounce(30_000).collect { autoSnapshot() }
        }
        scope.launch {
            val latest = snapshots.list().firstOrNull { !it.manual }?.createdAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
            if (latest == null || latest.isBefore(now().minusDays(1))) autoSnapshot()
        }
    }

    // Profiles and settings

    fun profile(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    /** The profile for tonight: the one picked, else the one set for today's weekday, else the first. */
    fun tonightsProfile(now: ZonedDateTime = now()): Profile? =
        _settings.value.migrateTonightChoice(settingsLoadedAt).resolveProfile(_profiles.value, now)

    suspend fun saveProfile(profile: Profile) = update(_profiles, PROFILES, ListSerializer(Profile.serializer())) { list ->
        if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
    }

    suspend fun answerCooldownOffer(expected: Map<String, List<Phase>>, expectedSessionRevision: Long = sessionRevision.value): Boolean {
        val admission = operationAdmission.tryCommand() ?: throw importBusy()
        try {
            return serially {
                if (expectedSessionRevision != _sessionRevision.value || _settings.value.cooldownOfferAnswered) return@serially false
                if (SETTINGS in _unreadable.value || (expected.isNotEmpty() && PROFILES in _unreadable.value)) return@serially false
                if (expected.any { (id, afterWake) -> _profiles.value.firstOrNull { it.id == id }?.afterWake != afterWake }) return@serially false
                val settings = _settings.value.copy(cooldownOfferAnswered = true)
                if (expected.isEmpty()) {
                    writeValue(SETTINGS, Settings.serializer(), settings)
                    _settings.value = settings
                    _settingsPublication.value += 1
                } else {
                    val profiles = _profiles.value.map { profile ->
                        if (profile.id in expected) profile.copy(afterWake = listOf(Phase(Mode.COOL, 44, 30, 10))) else profile
                    }
                    writeAllOrNone(listOf(
                        Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value), encode(ListSerializer(Profile.serializer()), profiles)) { _profiles.value = profiles },
                        Write(SETTINGS, encode(Settings.serializer(), _settings.value), encode(Settings.serializer(), settings)) {
                            _settings.value = settings
                            _settingsPublication.value += 1
                        },
                    ))
                }
                true
            }
        } finally { admission.close() }
    }

    /** UX-02: saves the Profiles list in [ids] order; a profile [ids] does not name keeps its place after them. */
    suspend fun reorderProfiles(ids: List<String>) = update(_profiles, PROFILES, ListSerializer(Profile.serializer())) { list ->
        val byId = list.associateBy { it.id }
        ids.distinct().mapNotNull(byId::get) + list.filterNot { it.id in ids }
    }

    suspend fun deleteProfile(id: String) = update(_profiles, PROFILES, ListSerializer(Profile.serializer())) { list -> list.filterNot { it.id == id } }

    suspend fun updateSettings(change: (Settings) -> Settings) = update(_settings, SETTINGS, Settings.serializer()) {
        change(it).migrateTonightChoice(now()).withoutUnidentifiedAlarms().let { changed ->
            if (isDemoSession) changed.copy(demoMode = true, targetDevice = null, rememberedBedJets = emptyList(),
                setupFanCheckedDevice = null) else changed
        }
    }

    // Night state

    suspend fun setActiveNight(night: ActiveNight?) = update(_activeNight, ACTIVE_NIGHT, ActiveNight.serializer().nullable) { night }

    suspend fun addRecord(record: NightRecord) = update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { list ->
        History.prune(list.filterNot { it.id == record.id } + record, now(), _settings.value.historyKeepDays)
    }

    /** Keeps [days] of nights from now on; a shorter period deletes the older nights straight away. */
    suspend fun setHistoryKeepDays(days: Int) {
        val keep = History.keepDays(days)
        updateSettings { it.copy(historyKeepDays = keep) }
        update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { History.prune(it, now(), keep) }
    }

    /** Deletes every saved night except one still running or being ended. Health Connect keeps its own copy. */
    suspend fun deleteHistory() {
        val tonight = setOfNotNull(_activeNight.value?.recordId, _runtime.value.ending?.identity?.nightId)
        update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { list -> list.filter { it.id in tonight } }
    }

    suspend fun addEvent(recordId: String, event: HistoryEvent) =
        update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { History.addEvent(it, recordId, event) }

    /** The ending journal can replay after a crash without duplicating an event or advancing its export version. */
    suspend fun completeHistoryRecord(recordId: String, event: HistoryEvent, confirmedCompletionAt: String?) = serially {
            if (_settings.value.demoMode) return@serially
            val before = _history.value
            val records = before.map { record ->
                if (record.id != recordId) record else {
                    val events = if (event in record.events) record.events else record.events + event
                    val completed = confirmedCompletionAt?.takeIf {
                        record.bedJetRunConfirmed && !record.isTestRun && !DeviceId.isDemo(record.deviceId) &&
                            event.kind == EventKind.ENDED && events.none { e -> e.kind in
                                setOf(EventKind.ERROR, EventKind.STOPPED, EventKind.REMOTE_CHANGE, EventKind.RECOVERED) }
                    }
                    if (completed == null || completed == record.confirmedCompletionAt) record.copy(events = events)
                    else record.copy(events = events, confirmedCompletionAt = completed,
                        healthExportVersion = maxOf(record.healthExportVersion.saturatedIncrement(),
                            java.time.ZonedDateTime.parse(completed).toInstant().toEpochMilli(), 1))
                }
            }
            if (records != before) {
                writeValue(HISTORY, ListSerializer(NightRecord.serializer()), records)
                _history.value = records
            }
        }

    private fun Long.saturatedIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1

    /** Feedback is linked to a completed record. Rating alone never edits a reusable profile. */
    suspend fun recordMorningCheckIn(nightId: String, comfort: MorningComfort): MorningCheckIn? = serially {
        val night = _history.value.firstOrNull { it.id == nightId } ?: return@serially null
        if (night.isTestRun) return@serially null
        if (_activeNight.value?.recordId == nightId || night.events.any { it.kind == EventKind.MORNING_FEEDBACK }) return@serially null
        val end = night.events.lastOrNull { it.kind == EventKind.ENDED || it.kind == EventKind.STOPPED } ?: return@serially null
        val detail = context.getString(when (comfort) {
            MorningComfort.TOO_HOT -> R.string.checkin_too_hot
            MorningComfort.JUST_RIGHT -> R.string.checkin_just_right
            MorningComfort.TOO_COLD -> R.string.checkin_too_cold
        })
        val updated = History.addEvent(_history.value, nightId, HistoryEvent(now().toString(), EventKind.MORNING_FEEDBACK, detail, comfort))
        writeValue(HISTORY, ListSerializer(NightRecord.serializer()), updated)
        _history.value = updated
        makeMorningCheckIn(updated.first { it.id == nightId }, end.at, comfort)
    }

    /** Rebuilds the preview after screen recreation; an applied suggestion is no longer offered. */
    fun morningCheckIn(nightId: String): MorningCheckIn? {
        val night = _history.value.firstOrNull { it.id == nightId } ?: return null
        if (night.isTestRun) return null
        if (_activeNight.value?.recordId == nightId) return null
        val end = night.events.lastOrNull { it.kind == EventKind.ENDED || it.kind == EventKind.STOPPED } ?: return null
        val comfort = night.events.lastOrNull { it.kind == EventKind.MORNING_FEEDBACK }?.comfort ?: return null
        return makeMorningCheckIn(night, end.at, comfort)
    }

    /** Accept only the exact Middle edit the person previewed, with a matching live profile and feedback. */
    suspend fun acceptMorningSuggestion(suggestion: MiddleSuggestion): Boolean = serially {
        val night = _history.value.firstOrNull { it.id == suggestion.nightId } ?: return@serially false
        if (night.isTestRun) return@serially false
        if (_activeNight.value?.recordId == night.id || night.profileId != suggestion.profileId ||
            night.events.none { it.kind == EventKind.ENDED || it.kind == EventKind.STOPPED } ||
            night.events.none { it.kind == EventKind.MORNING_FEEDBACK && it.comfort == suggestion.comfort } ||
            night.events.any { it.kind == EventKind.MORNING_SUGGESTION_APPLIED }) return@serially false
        val profile = _profiles.value.firstOrNull { it.id == suggestion.profileId } ?: return@serially false
        // Legacy nights without a saved profile can still compile their effective plan from this reusable profile.
        // Keep a preview available until that night has ended rather than changing its fallback under it.
        if (_activeNight.value?.profileId == profile.id) return@serially false
        if (profile.middle != suggestion.expectedMiddle || profile.limits != suggestion.expectedLimits ||
            proposedMiddle(profile, suggestion.comfort) != suggestion.proposedMiddle) return@serially false
        val profiles = _profiles.value.map { if (it.id == profile.id) it.copy(middle = suggestion.proposedMiddle) else it }
        val hot = suggestion.comfort == MorningComfort.TOO_HOT
        val detail = context.getString(if (suggestion.changesFan) { if (hot) R.string.history_accepted_more_fan else R.string.history_accepted_less_fan }
            else if (hot) R.string.history_accepted_lower_temp else R.string.history_accepted_higher_temp)
        val history = History.addEvent(_history.value, night.id,
            HistoryEvent(now().toString(), EventKind.MORNING_SUGGESTION_APPLIED, detail))
        writeAllOrNone(listOf(
            Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value), encode(ListSerializer(Profile.serializer()), profiles)) {
                _profiles.value = profiles
            },
            Write(HISTORY, encode(ListSerializer(NightRecord.serializer()), _history.value), encode(ListSerializer(NightRecord.serializer()), history)) {
                _history.value = history
            },
        ))
        true
    }

    /**
     * Saves what [deviceId]'s [slot] held before the app writes to it: [program], or an empty-slot marker when null.
     * The first original is kept: once a slot is recorded, later calls return that record unchanged, since by then
     * the slot may hold the app's own program. Throws when it can't be saved, and then the slot must not be written.
     */
    suspend fun recordOriginalSlot(deviceId: String, slot: Int, program: StoredProgram?): SlotBackup = serially {
        if (isDemoSession) {
            check(deviceId == DeviceId.DEMO) { context.getString(R.string.gpt12_demo_backup_unavailable) }
            return@serially demoOriginals.getOrPut(slot) { SlotBackup(DeviceId.DEMO, slot, program, now().toString()) }
        }
        if (originalsIntegrityBlocked && _slotBackups.value.none { it.deviceId == deviceId && it.slot == slot }) {
            throw SlotBackupsUnreadable()
        }
        adoptMigrated(deviceId)
        // After the loss was accepted the slot may hold the app's own programme, so the record says it may.
        originalSlot(deviceId, slot) ?: SlotBackup(deviceId, slot, program, now().toString(), uncertain = originalsLossAcceptedAt != null).also { backup ->
            val list = _slotBackups.value + backup
            writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), list)
            _slotBackups.value = list
        }
    }

    /** Whether [deviceId]'s [slot] has no saved original while the saved originals are unreadable, so it must not be
     * written: the check [recordOriginalSlot] makes, asked before any work starts (IR-03). */
    fun originalsUnreadableFor(deviceId: String, slot: Int): Boolean = originalsIntegrityBlocked && originalSlot(deviceId, slot) == null

    /** What [deviceId]'s [slot] held before the app first wrote to it, or null when that wasn't recorded. */
    fun originalSlot(deviceId: String, slot: Int): SlotBackup? =
        if (isDemoSession) demoOriginals[slot].takeIf { deviceId == DeviceId.DEMO }
        else _slotBackups.value.firstOrNull { it.deviceId == deviceId && it.slot == slot }
            ?: _slotBackups.value.firstOrNull { it.slot == slot && !it.imported && adopts(deviceId, it.deviceId) }

    /**
     * The slot backups that count for [deviceId], to tell NightRunner which slots are already backed up. A real
     * BedJet first adopts, durably, any original saved before devices were recorded for a slot it has none for: that
     * original came from the BedJet the app was used with, while the slot may by now hold the app's own program. With
     * no BedJet chosen (null) those migrated originals are returned as they are, so nothing is saved over them. The
     * demo unit never adopts them.
     */
    suspend fun originalsFor(deviceId: String?): List<SlotBackup> {
        if (isDemoSession) return if (deviceId == DeviceId.DEMO) demoOriginals.values.sortedBy { it.slot } else emptyList()
        if (deviceId == null) return _slotBackups.value.filter { it.deviceId == Migrations.UNKNOWN_DEVICE }
        serially { adoptMigrated(deviceId) }
        return _slotBackups.value.filter { it.deviceId == deviceId }
    }

    suspend fun addNotice(notice: LoggedNotice) =
        update(_notices, NOTICES, ListSerializer(LoggedNotice.serializer())) { NotificationLog.add(it, notice) }

    suspend fun clearNotices() = update(_notices, NOTICES, ListSerializer(LoggedNotice.serializer())) { emptyList() }

    /**
     * Stops reporting the files listed in [unreadable]. Their set-aside copies stay where they are.
     *
     * A plain dismissal leaves [originalsBlocked] as it is. With [acceptLostOriginals], asked only after the person
     * has explicitly confirmed that the saved originals of the slots with none are lost, it also lifts that barrier
     * (IR-03): the acceptance is recorded durably first (`slot-backups-loss-accepted.json`, its time), then the
     * barrier file is removed. From then on a slot with no saved original can be written; what it holds then is
     * saved as its original, marked [SlotBackup.uncertain], as it may be one of the app's own programmes. Throws,
     * changing nothing it hasn't recorded, when that can't be saved. Does nothing extra while the originals are
     * readable.
     */
    suspend fun dismissUnreadable(acceptLostOriginals: Boolean = false) = serially {
        requireRealSession()
        if (acceptLostOriginals && originalsIntegrityBlocked) acceptOriginalsLoss()
        clearUnreadable()
    }

    // Backups

    /** Everything a real-session backup holds; private preferences are excluded. */
    fun bundle(): BackupBundle {
        requireRealSession()
        return BackupBundle(
        createdAt = now().toString(),
        profiles = _profiles.value,
        settings = _settings.value,
        slotBackups = _slotBackups.value,
        history = _history.value,
        )
    }

    suspend fun snapshotNow(label: String) = serially {
        requireRealSession()
        snapshots.save(bundle(), label.ifBlank { context.getString(R.string.snapshot_label_manual) }, manual = true)
        _snapshotList.value = snapshots.list()
    }

    suspend fun deleteSnapshot(id: String) = serially {
        requireRealSession()
        snapshots.delete(id)
        _snapshotList.value = snapshots.list()
    }

    /**
     * Replaces profiles, settings and history with [bundle]'s, after saving what was there as a manual snapshot named
     * [label]. An invalid bundle is refused with [BackupException], listing every problem, before anything changes.
     *
     * The chosen BedJet, local device associations and the night under way are kept: a restore changes what the app
     * knows, never which BedJet it talks to or what that BedJet is doing. Slot backups are merged, current ones
     * winning: an older bundle's backup of a slot may be one of the app's own programs, not the user's original.
     * Everything is written before anything is published; if a write fails the files already written are put back.
     * If that fails too the restore stays committed and is finished later: see [writeAllOrNone] and [RestoreUnfinished].
     */
    suspend fun restore(bundle: BackupBundle, label: String = context.getString(R.string.snapshot_label_before_import)) {
        requireRealSession()
        val errors = BackupValidator.validate(bundle)
        if (errors.isNotEmpty()) throw BackupException(errors)
        val admission = operationAdmission.tryImport() ?: throw importBusy()
        try { serially {
            requireImportIdle()
            // Validation before queueing is only an early error. Re-read the actual bundle under the writer,
            // then freeze it so a mutable caller cannot change what is written after this final validation.
            val finalErrors = BackupValidator.validate(bundle)
            if (finalErrors.isNotEmpty()) throw BackupException(finalErrors)
            val checked = BackupCodec.decode(BackupCodec.encode(bundle))
            val checkedErrors = BackupValidator.validate(checked)
            if (checkedErrors.isNotEmpty()) throw BackupException(checkedErrors)
            restoreLocked(checked, label)
        } } finally { admission.close() }
    }

    /** Build a read-only review. No snapshot or repository file is written until the person applies it. */
    suspend fun previewImport(bundle: BackupBundle,
        label: String = context.getString(R.string.snapshot_label_before_import), profilesOnly: Boolean = false): ImportPreview {
        val admission = operationAdmission.tryImport() ?: throw importBusy()
        try { return serially {
            requireImportIdle()
            val current = bundle()
            val errors = BackupValidator.validate(bundle)
            if (errors.isNotEmpty()) return@serially ImportPreview("", "", label, bundle.createdAt,
                ImportArea.entries.map { ImportSection(it, emptyList()) }, emptySet(), emptyList(), errors.size, profilesOnly)
            val encoded = BackupCodec.encode(bundle)
            val checked = BackupCodec.decode(encoded)
            val finalErrors = BackupValidator.validate(checked)
            if (finalErrors.isNotEmpty()) throw BackupException(finalErrors)
            val projected = if (profilesOnly) profileImportProjection(checked) else restoreProjection(checked, now())
            val projectedErrors = if (profilesOnly) BackupValidator.validate(projected) else emptyList()
            if (projectedErrors.isNotEmpty()) return@serially ImportPreview("", "", label, checked.createdAt,
                ImportArea.entries.map { ImportSection(it, emptyList()) }, emptySet(), emptyList(), projectedErrors.size, profilesOnly)
            val sections = importSections(current, if (profilesOnly) projected else checked, projected)
            if (profilesOnly) return@serially ImportPreview(encoded, importFingerprint(projected), label, checked.createdAt,
                sections, emptySet(), emptyList(), 0, profilesOnly = true)
            val originals = checked.slotBackups + projected.slotBackups
            val devices = (listOfNotNull(checked.settings.targetDevice, checked.settings.setupFanCheckedDevice,
                checked.settings.platform.chargerDevice, checked.settings.platform.autoStart?.deviceId) +
                checked.settings.rememberedBedJets + checked.slotBackups.map { it.deviceId } + checked.history.map { it.deviceId }).distinct().sorted()
            val warnings = buildSet {
                if (originals.any { it.uncertain || savedAfterLoss(it) }) add(ImportWarning.UNCERTAIN_ORIGINALS)
                if (originals.any { it.deviceId == Migrations.UNKNOWN_DEVICE }) add(ImportWarning.UNASSIGNED_ORIGINALS)
                if (devices.isNotEmpty()) add(ImportWarning.DEVICE_ASSOCIATIONS)
                if (checked.slotBackups.any { original -> original.program?.steps.orEmpty().any {
                        it.mode != io.github.willzy12h.nocturne.core.protocol.Mode.STANDBY &&
                            it.mode != io.github.willzy12h.nocturne.core.protocol.Mode.WAIT && it.temperature !in it.mode.temperatureRange
                    } }) add(ImportWarning.UNSUPPORTED_ORIGINALS)
                if (sections.first { it.area == ImportArea.ORIGINALS }.count(ImportDisposition.PRESERVED) > 0)
                    add(ImportWarning.ORIGINALS_PROTECTED)
                if (sections.first { it.area == ImportArea.HISTORY }.count(ImportDisposition.OMITTED) > 0)
                    add(ImportWarning.HISTORY_OMITTED)
                if (checked.settings.platform.chargerAutoStart || current.settings.platform.chargerAutoStart ||
                    checked.settings.platform.autoStart?.enabled == true || current.settings.platform.autoStart?.enabled == true ||
                    checked.settings.platform.healthSleepExport || current.settings.platform.healthSleepExport)
                    add(ImportWarning.AUTOMATIC_ACTIONS_DISABLED)
            }
            ImportPreview(encoded, importFingerprint(projected), label, checked.createdAt, sections, warnings, devices, 0)
        } } finally { admission.close() }
    }

    suspend fun previewSnapshot(id: String): ImportPreview? {
        val bundle = serially {
            bundle() // Enforce the real-session boundary before reading a saved snapshot, under the writer.
            snapshots.load(id)
        } ?: return null
        return previewImport(bundle, context.getString(R.string.snapshot_label_before_restore))
    }

    /** Revalidate the reviewed bytes and local state after the writer wait, then apply that exact projection. */
    suspend fun applyImport(preview: ImportPreview) {
        if (!preview.canApply) throw BackupException("BACKUP_FIELD_INVALID")
        val admission = operationAdmission.tryImport() ?: throw importBusy()
        try { serially {
            requireImportIdle()
            val checked = BackupCodec.decode(preview.encodedInput)
            val errors = BackupValidator.validate(checked)
            if (errors.isNotEmpty()) throw BackupException(errors)
            val projected = if (preview.profilesOnly) profileImportProjection(checked) else restoreProjection(checked, now())
            val projectedErrors = if (preview.profilesOnly) BackupValidator.validate(projected) else emptyList()
            if (projectedErrors.isNotEmpty()) throw BackupException(projectedErrors)
            if (importFingerprint(projected) != preview.expectedState)
                throw BackupException(context.getString(R.string.import_preview_changed), failure = BackupFailure.CHANGED)
            if (preview.profilesOnly) {
                snapshots.save(bundle(), preview.snapshotLabel, manual = true)
                _snapshotList.value = snapshots.list()
                writeAllOrNone(listOf(Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value),
                    encode(ListSerializer(Profile.serializer()), projected.profiles)) { _profiles.value = projected.profiles }))
            } else restoreLocked(checked, preview.snapshotLabel, projected)
        } } finally { admission.close() }
    }

    /** Restores a snapshot; false when it couldn't be read. */
    suspend fun restoreSnapshot(id: String): Boolean {
        val bundle = serially { requireRealSession(); snapshots.load(id) } ?: return false
        restore(bundle, context.getString(R.string.snapshot_label_before_restore))
        return true
    }

    /** Capture the exact selected profile, with default settings only, in the existing backup format. */
    internal fun profileShareRequest(profile: Profile): ProfileShareRequest = ProfileShareRequest(
        BackupCodec.encode(BackupBundle(createdAt = now().toString(), profiles = listOf(profile), settings = Settings())),
        sessionRevision.value, shareInstanceNonce)

    /** A provider result cannot read a new profile or cross a real/demo session boundary. */
    internal suspend fun exportProfileTo(uri: Uri, request: ProfileShareRequest) {
        ExportDestination.check(context, uri)
        val bytes = serially {
            requireRealSession()
            if (request.repositoryNonce != shareInstanceNonce || request.sessionRevision != sessionRevision.value)
                throw IOException(context.getString(R.string.parity_profile_share_changed))
            request.encodedProfile.toByteArray(Charsets.UTF_8)
        }
        withContext(Dispatchers.IO) {
            ExportDestination.open(context, uri, openOutput).use { it.write(bytes) }
        }
    }

    suspend fun exportTo(uri: Uri) {
        ExportDestination.check(context, uri)
        val bytes = serially {
            requireRealSession()
            BackupCodec.encode(bundle()).toByteArray(Charsets.UTF_8)
        }
        // A document provider may wait indefinitely; only capturing the real data needs the night writer.
        withContext(Dispatchers.IO) {
            ExportDestination.open(context, uri, openOutput).use { it.write(bytes) }
        }
    }

    /**
     * Reads a backup file; throws [BackupException] when it isn't one or is over [BackupValidator.MAX_BYTES]. Check it
     * with [restore], which validates it before changing anything.
     */
    suspend fun importFrom(uri: Uri): BackupBundle {
        serially { requireRealSession() }
        return withContext(Dispatchers.IO) {
            openInput(uri)?.use { importFrom(it) } ?: throw IOException(context.getString(R.string.runtime_import_open_failed))
        }
    }

    /** As [importFrom], from a stream. The size is checked before any decoding. */
    fun importFrom(input: InputStream): BackupBundle {
        requireRealSession()
        return BackupCodec.decode(BackupValidator.readWithinLimit(input))
    }

    /** One file of a [writeAllOrNone] change: its bytes before and after, and how to show the new value. */
    internal class Write(val name: String, val old: ByteArray, val new: ByteArray, val publish: () -> Unit)
    @Serializable internal data class JournalWrite(val name: String, val bytes: String, val sha256: String)
    @Serializable internal data class RestoreJournal(val writes: List<JournalWrite>)

    internal enum class JournalState { NONE, DAMAGED, STUCK }

    companion object {
        internal const val TAG = "Repository"
        internal const val PROFILES = "profiles.json"
        internal const val SETTINGS = "settings.json"
        internal const val HISTORY = "history.json"
        internal const val SLOT_BACKUPS = "slot-backups.json"
        internal const val SLOT_BACKUPS_BLOCK = "slot-backups-integrity.json"
        /**
         * When the loss of unreadable originals was accepted. It travels with [SLOT_BACKUPS] and [SLOT_BACKUPS_BLOCK]
         * in cloud backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml).
         */
        internal const val SLOT_BACKUPS_LOSS = "slot-backups-loss-accepted.json"
        const val RESTORE_JOURNAL = "restore-pending.json"
        internal const val RUNTIME = "runtime.json"
        private const val AUTOMATION = "automation.json"
        internal const val ACTIVE_NIGHT = "active-night.json"
        private const val NOTICES = "notices.json"
        /** Lists the files in [unreadable], so the report survives a restart. */
        internal const val UNREADABLE = "unreadable.json"

        /** One write at a time, on an IO thread. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultWriter(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /**
         * AtomicFile syncs before replacing the old file, so a power cut leaves either the old or the new one. The
         * directory is synced after the rename ([syncDirectory]), so the replacement itself is durable too (IR-29).
         */
        fun writeDurably(file: File, bytes: ByteArray, syncDirectory: (File) -> Unit = ::syncDirectory) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
                // AndroidX AtomicFile logs a failed rename without throwing. On Windows test hosts,
                // File.renameTo cannot replace an existing destination, leaving the old bytes visible.
                // A failed replacement must never be reported as a durable write.
                val pending = File(file.path + ".new")
                if (pending.exists()) Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: IOException) {
                atomic.failWrite(out)
                throw e
            }
            file.absoluteFile.parentFile?.let(syncDirectory)
        }

        /**
         * Syncs [dir] so a rename in it survives a power cut, as far as the platform allows: fsync on the directory,
         * else forcing it as a channel, else nothing. Best effort; it never fails a write that is already on disk.
         */
        fun syncDirectory(dir: File) {
            try {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            } catch (e: Exception) {
                forceDirectory(dir)
            } catch (e: LinkageError) {
                forceDirectory(dir)
            }
        }

        private fun forceDirectory(dir: File) {
            try { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            catch (e: Exception) { Log.w(TAG, "Couldn't sync ${dir.name}", e) }
        }
    }
}

/**
 * A change of several files (a restore, or an accepted morning suggestion) is committed, but not every file could be
 * written yet. It is shown as made, and finished before any of those files is next written, or at the next start;
 * a later change that can't wait for that is refused with this.
 */
class RestoreUnfinished(
    cause: Throwable,
    // The repository passes runtime_change_unfinished from resources; this English default is for callers without a Context.
    message: String = "The change is kept, but not every file could be written yet. Nocturne finishes it before your next change, or when it next starts.",
) : IOException(message, cause)

/** The saved slot originals are unreadable and this slot has none, so it must not be written (IR-03). */
class SlotBackupsUnreadable : IOException("Saved slot originals are unreadable. Restore a backup of this BedJet's slot before writing it.")
