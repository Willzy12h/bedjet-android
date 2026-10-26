package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.RuntimeState
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
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

/** A proposed edit to the reusable profile; the expected values make an old preview safe to refuse. */
data class MiddleSuggestion(
    val nightId: String,
    val profileId: String,
    val comfort: MorningComfort,
    val expectedMiddle: Middle,
    val expectedLimits: ProfileLimits,
    val proposedMiddle: Middle,
) {
    /** A fan change (Cool or Dry Middle) rather than a temperature change. */
    val changesFan: Boolean get() = proposedMiddle.fanStart != expectedMiddle.fanStart || proposedMiddle.fanEnd != expectedMiddle.fanEnd
}

/** Middles whose comfort is adjusted by fan speed, not temperature (P5). */
private val FAN_COMFORT_MODES = setOf(io.github.willzy12h.nocturne.core.protocol.Mode.COOL, io.github.willzy12h.nocturne.core.protocol.Mode.DRY)

data class MorningCheckIn(
    val night: NightRecord,
    val endedAt: String,
    val comfort: MorningComfort,
    val suggestion: MiddleSuggestion?,
)

/**
 * Everything the app keeps, as JSON files in its private storage. Changes to profiles and settings are snapshotted
 * automatically (debounced), and on the first launch of each day.
 *
 * Every change is written durably, off the main thread and one at a time on [writer], before its new value is
 * published: a flow never shows something that a power cut could take back. When the write fails the old value stays
 * and the error is thrown to the caller. [writeFile] is how a file is written; tests replace it to make writes fail.
 */
@OptIn(FlowPreview::class)
class Repository(
    private val context: Context,
    scope: CoroutineScope,
    private val writer: CoroutineDispatcher = defaultWriter(),
    private val writeFile: (File, ByteArray) -> Unit = ::writeDurably,
    private val openInput: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
    private val openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it, "wt") },
    /** How the restore journal is removed; tests replace it to make the removal fail. */
    private val deleteFile: (File) -> Unit = { AtomicFile(it).delete() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val dir = context.filesDir
    val snapshots = SnapshotStore(File(dir, "snapshots").toPath()) { now() }
    private val lock = Mutex()
    val operationAdmission = OperationAdmission()

    // Declared before the loads below, which add to them.
    /** Files found unreadable while loading, moved aside by the writer once the Repository is made. */
    private val toSetAside = mutableListOf<File>()
    /**
     * The contents, by file name, of a multi-file change ([writeAllOrNone]) that is committed (its journal is on disk)
     * but not yet completely written; null when there is none. What is shown already follows it, so it is finished on
     * disk before any of its files is written again ([finishJournalBefore]); otherwise the next start would roll it
     * forward over that write.
     */
    private var pendingJournal: Map<String, ByteArray>? = null
    // Complete any interrupted multi-file restore before individual files are loaded as one repository state.
    private val journalState = recoverPendingRestore()
    /** A journal still pending after start-up recovery: reported in [unreadable] until it is finished. */
    private var journalStuck = journalState == JournalState.STUCK
        set(value) { field = value; _restoreUnfinished.value = value }
    private val _restoreUnfinished = MutableStateFlow(journalState == JournalState.STUCK)
    /** True while a committed restore still isn't finished on disk ([RESTORE_JOURNAL] listed in [unreadable] for that
     * reason, not because a damaged journal was set aside). Dismissing the notice doesn't clear it. */
    val restoreUnfinished: StateFlow<Boolean> = _restoreUnfinished.asStateFlow()
    private val _unreadable = MutableStateFlow((readUnreadableMarker() + listOfNotNull(RESTORE_JOURNAL.takeIf { journalState != JournalState.NONE })).distinct())
    // Dismissing the unreadable-file notice must not make an unknown slot original safe to overwrite.
    private val _originalsBlocked = MutableStateFlow(File(dir, SLOT_BACKUPS_BLOCK).exists())
    /**
     * True while the saved slot originals are known to have been unreadable (IR-03): a slot with no saved original
     * must not be written, whatever the notice in [unreadable] says. It stays true across restarts and a plain
     * [dismissUnreadable], and is lifted only when the person accepts the loss with
     * `dismissUnreadable(acceptLostOriginals = true)`.
     */
    val originalsBlocked: StateFlow<Boolean> = _originalsBlocked.asStateFlow()
    private var originalsIntegrityBlocked: Boolean
        get() = _originalsBlocked.value
        set(value) { _originalsBlocked.value = value }
    /** When the loss of unreadable originals was accepted, or null; originals saved since are marked uncertain. */
    // A damaged record is set aside and reported, and read as accepted now: originals saved from now on stay
    // uncertain, while one saved earlier keeps the current-wins rule instead of giving way to any backup's.
    private var originalsLossAcceptedAt: String? =
        load(SLOT_BACKUPS_LOSS) { text -> decode(String.serializer(), text).also { require(runCatching { ZonedDateTime.parse(it) }.isSuccess) } }
            ?: now().toString().takeIf { SLOT_BACKUPS_LOSS in _unreadable.value }
    /**
     * Files that couldn't be read, so their defaults are showing instead. Each was moved aside as
     * `<name>.corrupt-<millis>`. Kept across restarts until a restore or [dismissUnreadable]; automatic snapshots are
     * paused meanwhile, so they don't push the good ones out.
     */
    val unreadable: StateFlow<List<String>> = _unreadable.asStateFlow()

    private val _profiles = MutableStateFlow(load(PROFILES) { decode(ListSerializer(Profile.serializer()), it) } ?: Presets.all)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val settingsLoadedAt = now()
    private val _settings = MutableStateFlow(load(SETTINGS) { decode(Settings.serializer(), it) } ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    // Publication signal, not persisted state: equal-value restores must still reconcile side effects.
    private val _settingsPublication = MutableStateFlow(0L)
    internal val settingsPublication: StateFlow<Long> = _settingsPublication.asStateFlow()
    private val _history = MutableStateFlow(History.prune(load(HISTORY, Migrations::decodeHistory).orEmpty(), now()))
    val history: StateFlow<List<NightRecord>> = _history.asStateFlow()
    private val _slotBackups = MutableStateFlow(load(SLOT_BACKUPS, Migrations::decodeSlotBackups).orEmpty())
    /** What each device's slots held before the app first wrote them. See [recordOriginalSlot]. */
    val slotBackups: StateFlow<List<SlotBackup>> = _slotBackups.asStateFlow()
    private val _activeNight = MutableStateFlow(load(ACTIVE_NIGHT) { decode(ActiveNight.serializer().nullable, it) })
    val activeNight: StateFlow<ActiveNight?> = _activeNight.asStateFlow()
    private val _notices = MutableStateFlow(load(NOTICES) { decode(ListSerializer(LoggedNotice.serializer()), it) }.orEmpty())
    /** Every notification the app has shown, newest first. Not part of backups. */
    val notices: StateFlow<List<LoggedNotice>> = _notices.asStateFlow()
    private val _snapshotList = MutableStateFlow(snapshots.list())
    val snapshotList: StateFlow<List<SnapshotInfo>> = _snapshotList.asStateFlow()

    private val _runtime = MutableStateFlow(load(RUNTIME) { decode(RuntimeState.serializer(), it) } ?: RuntimeState())
    val runtime: StateFlow<RuntimeState> = _runtime.asStateFlow()
    suspend fun updateRuntime(change: (RuntimeState) -> RuntimeState) = update(_runtime, RUNTIME, RuntimeState.serializer(), change)

    private val _automation = MutableStateFlow(load(AUTOMATION) {
        decode(io.github.willzy12h.nocturne.platform.AutomationState.serializer(), it)
    } ?: io.github.willzy12h.nocturne.platform.AutomationState())
    val automation: StateFlow<io.github.willzy12h.nocturne.platform.AutomationState> = _automation.asStateFlow()
    suspend fun updateAutomation(change: (io.github.willzy12h.nocturne.platform.AutomationState) -> io.github.willzy12h.nocturne.platform.AutomationState) =
        update(_automation, AUTOMATION, io.github.willzy12h.nocturne.platform.AutomationState.serializer(), change)
    suspend fun claimAutomation(key: String): Boolean = serially {
        val current = _automation.value
        if (key in current.claims) false else {
            val next = current.copy(claims = (current.claims + key).takeLast(256))
            writeValue(AUTOMATION, io.github.willzy12h.nocturne.platform.AutomationState.serializer(), next)
            _automation.value = next
            true
        }
    }

    // Excluded from cloud backup and device transfer by res/xml/data_extraction_rules.xml and backup_rules.xml.
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    /** GitHub token for checking a private repository. Kept out of backups, exports and snapshots. */
    var updateToken: String
        get() = secrets.getString("updateToken", "").orEmpty()
        set(value) = secrets.edit().putString("updateToken", value.trim()).apply()

    init {
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

    suspend fun deleteProfile(id: String) = update(_profiles, PROFILES, ListSerializer(Profile.serializer())) { list -> list.filterNot { it.id == id } }

    suspend fun updateSettings(change: (Settings) -> Settings) = update(_settings, SETTINGS, Settings.serializer()) {
        change(it).migrateTonightChoice(now())
    }

    // Night state

    suspend fun setActiveNight(night: ActiveNight?) = update(_activeNight, ACTIVE_NIGHT, ActiveNight.serializer().nullable) { night }

    suspend fun addRecord(record: NightRecord) = update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { list ->
        History.prune(list.filterNot { it.id == record.id } + record, now())
    }

    suspend fun addEvent(recordId: String, event: HistoryEvent) =
        update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { History.addEvent(it, recordId, event) }

    /** The ending journal can replay after a crash without duplicating an event or advancing its export version. */
    suspend fun completeHistoryRecord(recordId: String, event: HistoryEvent, confirmedCompletionAt: String?) =
        update(_history, HISTORY, ListSerializer(NightRecord.serializer())) { records ->
            if (_settings.value.demoMode) records else records.map { record ->
                if (record.id != recordId) record else {
                    val events = if (event in record.events) record.events else record.events + event
                    val completed = confirmedCompletionAt?.takeIf {
                        record.bedJetRunConfirmed && !record.isTestRun && !DeviceId.isDemo(record.deviceId) &&
                            event.kind == EventKind.ENDED && events.none { e -> e.kind == EventKind.ERROR || e.kind == EventKind.STOPPED }
                    }
                    if (completed == null || completed == record.confirmedCompletionAt) record.copy(events = events)
                    else record.copy(events = events, confirmedCompletionAt = completed,
                        healthExportVersion = maxOf(record.healthExportVersion.saturatedIncrement(),
                            java.time.ZonedDateTime.parse(completed).toInstant().toEpochMilli(), 1))
                }
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

    private fun makeMorningCheckIn(night: NightRecord, endedAt: String, comfort: MorningComfort): MorningCheckIn {
        val suggestion = if (night.events.any { it.kind == EventKind.MORNING_SUGGESTION_APPLIED }) null else
            _profiles.value.firstOrNull { it.id == night.profileId }?.let { profile ->
                proposedMiddle(profile, comfort)?.let { MiddleSuggestion(night.id, profile.id, comfort, profile.middle, profile.limits, it) }
            }
        return MorningCheckIn(night, endedAt, comfort, suggestion)
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

    private fun proposedMiddle(profile: Profile, comfort: MorningComfort): Middle? {
        if (comfort == MorningComfort.JUST_RIGHT) return null
        // Cooling is felt as fan speed: a Cool or Dry Middle moves its fan by 10 per cent (more when too hot).
        if (profile.middle.mode in FAN_COMFORT_MODES) return proposedFan(profile, comfort)
        if (!profile.middle.mode.usesTemperature) return null
        val delta = if (comfort == MorningComfort.TOO_HOT) -2 else 2
        val middle = profile.middle.copy(tempStart = profile.middle.tempStart + delta, tempEnd = profile.middle.tempEnd + delta)
        val limits = profile.limits
        val modeRange = profile.middle.mode.temperatureRange
        fun validTemperature(value: Int) = value in limits.minTemp..limits.maxTemp && value in modeRange
        if (limits.minFan !in 5..100 || limits.maxFan !in 5..100 || limits.minFan > limits.maxFan ||
            profile.middle.fanStart !in limits.minFan..limits.maxFan || profile.middle.fanEnd !in limits.minFan..limits.maxFan ||
            middle.rampSteps !in 1..8 || limits.minTemp !in 38..86 || limits.maxTemp !in 38..86 ||
            limits.minTemp > limits.maxTemp || !validTemperature(profile.middle.tempStart) ||
            !validTemperature(profile.middle.tempEnd) || !validTemperature(middle.tempStart) ||
            !validTemperature(middle.tempEnd)) return null
        return middle
    }

    private fun proposedFan(profile: Profile, comfort: MorningComfort): Middle? {
        val delta = if (comfort == MorningComfort.TOO_HOT) 10 else -10
        val middle = profile.middle.copy(fanStart = profile.middle.fanStart + delta, fanEnd = profile.middle.fanEnd + delta)
        val limits = profile.limits
        fun valid(fan: Int) = fan in 5..100 && fan % 5 == 0 && fan in limits.minFan..limits.maxFan
        if (limits.minFan > limits.maxFan || middle.rampSteps !in 1..8 || !valid(profile.middle.fanStart) || !valid(profile.middle.fanEnd) ||
            !valid(middle.fanStart) || !valid(middle.fanEnd)) return null
        return middle
    }

    /**
     * Saves what [deviceId]'s [slot] held before the app writes to it: [program], or an empty-slot marker when null.
     * The first original is kept: once a slot is recorded, later calls return that record unchanged, since by then
     * the slot may hold the app's own program. Throws when it can't be saved, and then the slot must not be written.
     */
    suspend fun recordOriginalSlot(deviceId: String, slot: Int, program: StoredProgram?): SlotBackup = serially {
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
        _slotBackups.value.firstOrNull { it.deviceId == deviceId && it.slot == slot }
            ?: _slotBackups.value.firstOrNull { it.slot == slot && !it.imported && adopts(deviceId, it.deviceId) }

    /**
     * The slot backups that count for [deviceId], to tell NightRunner which slots are already backed up. A real
     * BedJet first adopts, durably, any original saved before devices were recorded for a slot it has none for: that
     * original came from the BedJet the app was used with, while the slot may by now hold the app's own program. With
     * no BedJet chosen (null) those migrated originals are returned as they are, so nothing is saved over them. The
     * demo unit never adopts them.
     */
    suspend fun originalsFor(deviceId: String?): List<SlotBackup> {
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
        if (acceptLostOriginals && originalsIntegrityBlocked) acceptOriginalsLoss()
        clearUnreadable()
    }

    /** Records the accepted loss, then lifts the barrier. Call on the writer. */
    private fun acceptOriginalsLoss() {
        // With no barrier file the unreadable file couldn't be set aside yet; the next start tries again first.
        if (!File(dir, SLOT_BACKUPS_BLOCK).exists()) {
            throw IOException(context.getString(R.string.runtime_originals_not_set_aside))
        }
        val at = now().toString()
        writeValue(SLOT_BACKUPS_LOSS, String.serializer(), at)
        originalsLossAcceptedAt = at
        runCatching { deleteFile(File(dir, SLOT_BACKUPS_BLOCK)) }.onFailure { Log.w(TAG, "Couldn't remove $SLOT_BACKUPS_BLOCK", it) }
        if (File(dir, SLOT_BACKUPS_BLOCK).exists()) throw IOException(context.getString(R.string.runtime_originals_block_stuck))
        originalsIntegrityBlocked = false
    }

    // Backups

    /** Everything a backup holds. The update token is never part of it. */
    fun bundle(): BackupBundle = BackupBundle(
        createdAt = now().toString(),
        profiles = _profiles.value,
        settings = _settings.value,
        slotBackups = _slotBackups.value,
        history = _history.value,
    )

    suspend fun snapshotNow(label: String) = serially {
        snapshots.save(bundle(), label.ifBlank { context.getString(R.string.snapshot_label_manual) }, manual = true)
        _snapshotList.value = snapshots.list()
    }

    suspend fun deleteSnapshot(id: String) = serially {
        snapshots.delete(id)
        _snapshotList.value = snapshots.list()
    }

    /**
     * Replaces profiles, settings and history with [bundle]'s, after saving what was there as a manual snapshot named
     * [label]. An invalid bundle is refused with [BackupException], listing every problem, before anything changes.
     *
     * The chosen BedJet, demo mode, the update token and the night under way are kept: a restore changes what the app
     * knows, never which BedJet it talks to or what that BedJet is doing. Slot backups are merged, current ones
     * winning: an older bundle's backup of a slot may be one of the app's own programs, not the user's original.
     * Everything is written before anything is published; if a write fails the files already written are put back.
     * If that fails too the restore stays committed and is finished later: see [writeAllOrNone] and [RestoreUnfinished].
     */
    suspend fun restore(bundle: BackupBundle, label: String = context.getString(R.string.snapshot_label_before_import)) {
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

    private fun importBusy() = BackupException(context.getString(R.string.import_busy), failure = BackupFailure.BUSY)

    /** A durable pending intention survives process queues; an already scheduled future alarm is not pending work. */
    private fun requireImportIdle() {
        val runtime = _runtime.value
        val night = _activeNight.value
        val pendingJob = context.getSystemService(android.app.job.JobScheduler::class.java)
            ?.getPendingJob(io.github.willzy12h.nocturne.platform.ChargerJobs.ID)
        if (pendingJournal != null || _restoreUnfinished.value ||
            runtime.slotWrite?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.PENDING ||
            night?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.PENDING || night?.pending != null ||
            runtime.retime != null || runtime.alarmInbox != null || pendingJob?.isPeriodic == false) throw importBusy()
    }

    /** Called only with exclusive import admission and the repository writer held. */
    private fun restoreLocked(bundle: BackupBundle, label: String) {
            snapshots.save(bundle(), label, manual = true)
            _snapshotList.value = snapshots.list()
            val restoreAt = now()
            val current = _settings.value.migrateTonightChoice(restoreAt)
            val profiles = bundle.profiles
            // Tonight's choice and override stay as they are. A version 1 selection in the bundle is migrated, as the
            // app's own is, so it becomes tonight's choice only when there is none (IR-30).
            val importedSettings = bundle.settings.copy(tonightChoice = current.tonightChoice, tonightOverride = current.tonightOverride)
                .migrateTonightChoice(restoreAt)
            val settings = importedSettings.copy(demoMode = current.demoMode, targetDevice = current.targetDevice,
                setupFanCheckedDevice = current.setupFanCheckedDevice,
                platform = bundle.settings.platform.copy(chargerAutoStart = false, chargerDevice = null))
            // Tonight's record, and that of a night being ended (its end is still to be added), are kept as they are.
            val tonight = setOfNotNull(_activeNight.value?.recordId, _runtime.value.ending?.identity?.nightId)
            val carried = _history.value.filter { it.id in tonight }
            val history = History.prune(bundle.history, now()).filterNot { restored -> carried.any { it.id == restored.id } } + carried
            val slotBackups = _slotBackups.value.let { current ->
                val incoming = bundle.slotBackups.map { it.copy(imported = true) }
                // One saved after the loss of unreadable originals was accepted may be the app's own programme, so
                // the bundle's exact record of that BedJet's slot replaces it.
                val list = current.filterNot { saved -> savedAfterLoss(saved) && incoming.any { DeviceId.same(it.deviceId, saved.deviceId) && it.slot == saved.slot } }
                list + incoming.filter { restored -> list.none { it.covers(restored) } }
            }
            writeAllOrNone(listOf(
                Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value), encode(ListSerializer(Profile.serializer()), profiles)) {
                    _profiles.value = profiles
                },
                Write(SETTINGS, encode(Settings.serializer(), _settings.value), encode(Settings.serializer(), settings)) {
                    _settings.value = settings
                    _settingsPublication.value += 1
                },
                Write(HISTORY, encode(ListSerializer(NightRecord.serializer()), _history.value), encode(ListSerializer(NightRecord.serializer()), history)) {
                    _history.value = history
                },
                Write(SLOT_BACKUPS, encode(ListSerializer(SlotBackup.serializer()), _slotBackups.value), encode(ListSerializer(SlotBackup.serializer()), slotBackups)) {
                    _slotBackups.value = slotBackups
                    clearUnreadable()
                },
            ))
    }

    /** Restores a snapshot; false when it couldn't be read. */
    suspend fun restoreSnapshot(id: String): Boolean {
        val bundle = withContext(writer) { snapshots.load(id) } ?: return false
        restore(bundle, context.getString(R.string.snapshot_label_before_restore))
        return true
    }

    suspend fun exportTo(uri: Uri) {
        val bytes = serially { BackupCodec.encode(bundle()).toByteArray() }
        withContext(Dispatchers.IO) {
            openOutput(uri)?.use { it.write(bytes) } ?: throw IOException(context.getString(R.string.runtime_export_open_failed))
        }
    }

    /**
     * Reads a backup file; throws [BackupException] when it isn't one or is over [BackupValidator.MAX_BYTES]. Check it
     * with [restore], which validates it before changing anything.
     */
    suspend fun importFrom(uri: Uri): BackupBundle = withContext(Dispatchers.IO) {
        openInput(uri)?.use { importFrom(it) } ?: throw IOException(context.getString(R.string.runtime_import_open_failed))
    }

    /** As [importFrom], from a stream. The size is checked before any decoding. */
    fun importFrom(input: InputStream): BackupBundle = BackupCodec.decode(BackupValidator.readWithinLimit(input))

    private suspend fun autoSnapshot() = serially {
        // Snapshots of the defaults standing in for an unreadable file would push the good ones out.
        if (_unreadable.value.isNotEmpty()) return@serially
        if (snapshots.saveIfChanged(bundle())) _snapshotList.value = snapshots.list()
    }

    // Files

    /** Runs [work] on the writer, one at a time. */
    private suspend fun <T> serially(work: suspend () -> T): T = withContext(writer) { lock.withLock { work() } }

    private suspend fun <T> update(flow: MutableStateFlow<T>, name: String, serializer: KSerializer<T>, change: (T) -> T): Unit = serially {
        val value = change(flow.value)
        writeValue(name, serializer, value)
        flow.value = value
        if (name == SETTINGS) _settingsPublication.value += 1
    }

    private fun <T> writeValue(name: String, serializer: KSerializer<T>, value: T) {
        finishJournalBefore(name)
        writeFile(File(dir, name), encode(serializer, value))
    }

    /** One file of a [writeAllOrNone] change: its bytes before and after, and how to show the new value. */
    private class Write(val name: String, val old: ByteArray, val new: ByteArray, val publish: () -> Unit)
    @Serializable private data class JournalWrite(val name: String, val bytes: String, val sha256: String)
    @Serializable private data class RestoreJournal(val writes: List<JournalWrite>)

    /**
     * Writes several files as one change. A journal of the new contents is saved first; from then on the change is
     * committed, as the next start rolls it forward. Call on the writer.
     *
     * - Every file written: the values are published and the journal removed. If it can't be removed it stays
     *   pending ([pendingJournal]), to be removed before these files are next written.
     * - A file fails (an Error too): the files already written are put back and the journal removed, and the failure
     *   is thrown with nothing published. If a file can't be put back, or the journal can't be removed, the change
     *   stays committed instead: the new values are published, as the next start will load them, the journal stays
     *   pending, and [RestoreUnfinished] is thrown (an Error as it is). Nothing written after that is lost to the
     *   roll-forward, as every later write of these files finishes the journal first or is refused.
     */
    private fun writeAllOrNone(writes: List<Write>) {
        // A journal still pending is finished first: the new one replaces it.
        finishJournalBefore(*writes.map { it.name }.toTypedArray())
        val entries = writes.associate { it.name to it.new }
        val journal = RestoreJournal(writes.map { JournalWrite(it.name, Base64.getEncoder().encodeToString(it.new), digest(it.new)) })
        writeValue(RESTORE_JOURNAL, RestoreJournal.serializer(), journal)
        writes.forEachIndexed { i, write ->
            try {
                writeFile(File(dir, write.name), write.new)
            } catch (e: Throwable) {
                var putBack = true
                writes.take(i).forEach { done ->
                    runCatching { writeFile(File(dir, done.name), done.old) }.onFailure { e.addSuppressed(it); putBack = false }
                }
                if (putBack && removeJournal()) throw e
                pendingJournal = entries
                writes.forEach { it.publish() }
                throw if (e is Exception) RestoreUnfinished(e, context.getString(R.string.runtime_change_unfinished)) else e
            }
        }
        writes.forEach { it.publish() }
        if (!removeJournal()) {
            Log.w(TAG, "Couldn't remove $RESTORE_JOURNAL; it is finished before these files are next written")
            pendingJournal = entries
        }
    }

    /**
     * Before any of [names] is written: finishes a pending journal that holds one of them, so the next start doesn't
     * roll it forward over the write. Throws [RestoreUnfinished], and then nothing is written, when it can't be done.
     */
    private fun finishJournalBefore(vararg names: String) {
        val entries = pendingJournal ?: return
        if (names.none { it in entries }) return
        try {
            finishJournal()
        } catch (e: Exception) {
            throw RestoreUnfinished(e, context.getString(R.string.runtime_earlier_change_unfinished))
        }
    }

    /** Writes every file of [pendingJournal], then removes the journal. Throws, leaving it pending, when either fails. */
    private fun finishJournal() {
        val entries = pendingJournal ?: return
        entries.forEach { (name, bytes) -> writeFile(File(dir, name), bytes) }
        if (!removeJournal()) throw IOException("Couldn't remove $RESTORE_JOURNAL.")
        pendingJournal = null
        if (journalStuck) {
            journalStuck = false
            _unreadable.value = _unreadable.value - RESTORE_JOURNAL
        }
    }

    /** Removes the journal; true only when none of its files is left behind. */
    private fun removeJournal(): Boolean {
        runCatching { deleteFile(File(dir, RESTORE_JOURNAL)) }.onFailure { Log.w(TAG, "Couldn't remove $RESTORE_JOURNAL", it) }
        return listOf("", ".new", ".bak").none { File(dir, RESTORE_JOURNAL + it).exists() }
    }

    /**
     * Runs before anything is loaded: rolls an interrupted multi-file change forward from its journal, through
     * [writeFile]. A journal that can't be read, names other files or fails its checks changes no file: it is noted
     * to be set aside and reported in [unreadable] instead of stopping start-up ([JournalState.DAMAGED]). When a
     * file can't be written now the journal stays pending ([JournalState.STUCK]): the loads read its contents, it is
     * reported in [unreadable] with a notice, and it is finished before any of its files is next written.
     */
    private fun recoverPendingRestore(): JournalState {
        val file = AtomicFile(File(dir, RESTORE_JOURNAL))
        val entries = try {
            readJournal(file.readFully())
        } catch (_: FileNotFoundException) {
            return JournalState.NONE
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't use $RESTORE_JOURNAL; it will be moved aside", e)
            toSetAside += file.baseFile
            return JournalState.DAMAGED
        }
        // A restore clears the unreadable report; a morning check-in, the other multi-file change, does not.
        if (SETTINGS in entries) AtomicFile(File(dir, UNREADABLE)).delete()
        pendingJournal = entries
        return try {
            finishJournal()
            JournalState.NONE
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't finish the interrupted change yet", e)
            JournalState.STUCK
        }
    }

    private enum class JournalState { NONE, DAMAGED, STUCK }

    /** Every entry of a journal, checked before any is used. Throws [IOException] when it can't be trusted. */
    private fun readJournal(bytes: ByteArray): Map<String, ByteArray> {
        val journal = try { decode(RestoreJournal.serializer(), bytes.decodeToString()) }
            catch (e: IllegalArgumentException) { throw IOException("The restore journal is unreadable.", e) }
        val names = journal.writes.map { it.name }
        if (names.size != names.toSet().size || names.toSet() !in setOf(setOf(PROFILES, SETTINGS, HISTORY, SLOT_BACKUPS), setOf(PROFILES, HISTORY))) {
            throw IOException("The restore journal does not name the expected files.")
        }
        return journal.writes.associate { entry ->
            val content = try { Base64.getDecoder().decode(entry.bytes) }
                catch (e: IllegalArgumentException) { throw IOException("The restore journal is damaged.", e) }
            if (digest(content) != entry.sha256) throw IOException("The restore journal failed its integrity check.")
            entry.name to content
        }
    }

    private fun digest(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun <T> encode(serializer: KSerializer<T>, value: T) = BackupCodec.json.encodeToString(serializer, value).toByteArray()

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = BackupCodec.json.decodeFromString(serializer, text)

    /**
     * Null when the file doesn't exist yet, or couldn't be read; then it's moved aside and reported in [unreadable]. A
     * file in a pending journal is read from the journal, which is what it will hold.
     */
    private fun <T> load(name: String, decode: (String) -> T): T? {
        pendingJournal?.get(name)?.let { bytes ->
            runCatching { decode(bytes.decodeToString()) }.onSuccess { return it }.onFailure { Log.e(TAG, "Couldn't read $name from the journal", it) }
        }
        val file = AtomicFile(File(dir, name))
        return try {
            decode(file.readFully().decodeToString())
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            setAside(file.baseFile, e)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: bad JSON, or a value from a newer version such as an unknown enum.
            setAside(file.baseFile, e)
        }
    }

    /** Notes [file] as unreadable; [setAsidePending] moves it aside on the writer. */
    private fun <T> setAside(file: File, cause: Exception): T? {
        Log.w(TAG, "Couldn't read ${file.name}; it will be moved aside", cause)
        toSetAside += file
        _unreadable.value = (_unreadable.value + file.name).distinct()
        if (file.name == SLOT_BACKUPS) originalsIntegrityBlocked = true
        return null
    }

    /** Runs on the writer. A report that can't be saved is shown as unreadable too, rather than lost on a restart. */
    private fun setAsidePending() {
        // Write the independent barrier before moving the corrupt file. If this write fails, a restart will still
        // encounter the corrupt original and refuse slot mutations rather than treating a missing file as new.
        val barrierSaved = if (toSetAside.any { it.name == SLOT_BACKUPS }) {
            runCatching { writeValue(SLOT_BACKUPS_BLOCK, String.serializer(), "blocked") }.onFailure {
                Log.e(TAG, "Couldn't save $SLOT_BACKUPS_BLOCK", it)
            }.isSuccess
        } else true
        val lossRecordAside = toSetAside.any { it.name == SLOT_BACKUPS_LOSS }
        toSetAside.forEach { file ->
            if (file.name == SLOT_BACKUPS && !barrierSaved) return@forEach
            val aside = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
            // If it can't be moved, a copy still keeps the original from being lost on the next write.
            if (!file.renameTo(aside)) runCatching { file.copyTo(aside) }.onFailure { Log.e(TAG, "Couldn't keep ${file.name}", it) }
        }
        toSetAside.clear()
        // A damaged record of the accepted loss is replaced by the time it is now read as.
        if (lossRecordAside) originalsLossAcceptedAt?.let { at ->
            runCatching { writeValue(SLOT_BACKUPS_LOSS, String.serializer(), at) }.onFailure { Log.e(TAG, "Couldn't save $SLOT_BACKUPS_LOSS", it) }
        }
        // A journal still pending is reported only while it is: each start finds it again.
        val names = _unreadable.value
        runCatching { writeValue(UNREADABLE, ListSerializer(String.serializer()), if (journalStuck) names - RESTORE_JOURNAL else names) }.onFailure {
            Log.e(TAG, "Couldn't save $UNREADABLE", it)
            _unreadable.value = (names + UNREADABLE).distinct()
        }
    }

    /** Whether [backup] was saved by this app after the loss of unreadable originals was accepted. */
    private fun savedAfterLoss(backup: SlotBackup): Boolean {
        val lossAt = originalsLossAcceptedAt ?: return false
        return backup.uncertain && !backup.imported &&
            runCatching { !ZonedDateTime.parse(backup.savedAt).isBefore(ZonedDateTime.parse(lossAt)) }.getOrDefault(true)
    }

    /** True when [deviceId] takes over an original saved with [recordedFor] before devices were recorded. */
    private fun adopts(deviceId: String, recordedFor: String) = recordedFor == Migrations.UNKNOWN_DEVICE && DeviceId.address(deviceId) != null

    /** Two backups of the same slot of the same BedJet, counting a migrated one as the real BedJet's. */
    private fun SlotBackup.covers(other: SlotBackup) =
        slot == other.slot && (DeviceId.same(deviceId, other.deviceId) ||
            adopts(deviceId, other.deviceId) || (!imported && adopts(other.deviceId, deviceId)))

    /** Re-tags, durably, the migrated originals of slots [deviceId] has none for. Call on the writer. */
    private fun adoptMigrated(deviceId: String) {
        if (originalsIntegrityBlocked) return
        val list = _slotBackups.value
        val adopted = list.map { backup ->
            if (!backup.imported && adopts(deviceId, backup.deviceId) && list.none { it.deviceId == deviceId && it.slot == backup.slot }) backup.copy(deviceId = deviceId) else backup
        }
        if (adopted == list) return
        writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), adopted)
        _slotBackups.value = adopted
    }

    private fun readUnreadableMarker(): List<String> =
        runCatching { decode(ListSerializer(String.serializer()), AtomicFile(File(dir, UNREADABLE)).readFully().decodeToString()) }.getOrDefault(emptyList())

    private fun clearUnreadable() {
        AtomicFile(File(dir, UNREADABLE)).delete()
        // A restore that still isn't finished stays reported: dismissing the notice doesn't finish it.
        _unreadable.value = if (journalStuck) listOf(RESTORE_JOURNAL) else emptyList()
    }

    companion object {
        private const val TAG = "Repository"
        private const val PROFILES = "profiles.json"
        private const val SETTINGS = "settings.json"
        private const val HISTORY = "history.json"
        private const val SLOT_BACKUPS = "slot-backups.json"
        private const val SLOT_BACKUPS_BLOCK = "slot-backups-integrity.json"
        /**
         * When the loss of unreadable originals was accepted. It travels with [SLOT_BACKUPS] and [SLOT_BACKUPS_BLOCK]
         * in cloud backup and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml).
         */
        private const val SLOT_BACKUPS_LOSS = "slot-backups-loss-accepted.json"
        const val RESTORE_JOURNAL = "restore-pending.json"
        private const val RUNTIME = "runtime.json"
        private const val AUTOMATION = "automation.json"
        private const val ACTIVE_NIGHT = "active-night.json"
        private const val NOTICES = "notices.json"
        /** Lists the files in [unreadable], so the report survives a restart. */
        private const val UNREADABLE = "unreadable.json"

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
