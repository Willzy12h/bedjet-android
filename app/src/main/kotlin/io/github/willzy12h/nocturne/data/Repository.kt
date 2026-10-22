package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.night.RuntimeState
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.History
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.LoggedNotice
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime

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
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val dir = context.filesDir
    val snapshots = SnapshotStore(File(dir, "snapshots").toPath()) { now() }
    private val lock = Mutex()

    // Declared before the loads below, which add to them.
    /** Files found unreadable while loading, moved aside by the writer once the Repository is made. */
    private val toSetAside = mutableListOf<File>()
    private val _unreadable = MutableStateFlow(readUnreadableMarker())
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

    /**
     * Saves what [deviceId]'s [slot] held before the app writes to it: [program], or an empty-slot marker when null.
     * The first original is kept: once a slot is recorded, later calls return that record unchanged, since by then
     * the slot may hold the app's own program. Throws when it can't be saved, and then the slot must not be written.
     */
    suspend fun recordOriginalSlot(deviceId: String, slot: Int, program: StoredProgram?): SlotBackup = serially {
        adoptMigrated(deviceId)
        originalSlot(deviceId, slot) ?: SlotBackup(deviceId, slot, program, now().toString()).also { backup ->
            val list = _slotBackups.value + backup
            writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), list)
            _slotBackups.value = list
        }
    }

    /** What [deviceId]'s [slot] held before the app first wrote to it, or null when that wasn't recorded. */
    fun originalSlot(deviceId: String, slot: Int): SlotBackup? =
        _slotBackups.value.firstOrNull { it.deviceId == deviceId && it.slot == slot }
            ?: _slotBackups.value.firstOrNull { it.slot == slot && adopts(deviceId, it.deviceId) }

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

    /** Stops reporting the files listed in [unreadable]. Their set-aside copies stay where they are. */
    suspend fun dismissUnreadable() = serially { clearUnreadable() }

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
        snapshots.save(bundle(), label.ifBlank { "Manual snapshot" }, manual = true)
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
     */
    suspend fun restore(bundle: BackupBundle, label: String = "Before import") {
        val errors = BackupValidator.validate(bundle)
        if (errors.isNotEmpty()) throw BackupException(errors)
        serially {
            snapshots.save(bundle(), label, manual = true)
            _snapshotList.value = snapshots.list()
            val current = _settings.value
            val profiles = bundle.profiles
            val settings = bundle.settings.copy(demoMode = current.demoMode, targetDevice = current.targetDevice)
            val history = History.prune(bundle.history, now())
            val slotBackups = _slotBackups.value.let { list ->
                list + bundle.slotBackups.filter { restored -> list.none { it.covers(restored) } }
            }
            val writes = listOf(
                Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value), encode(ListSerializer(Profile.serializer()), profiles)),
                Write(SETTINGS, encode(Settings.serializer(), current), encode(Settings.serializer(), settings)),
                Write(HISTORY, encode(ListSerializer(NightRecord.serializer()), _history.value), encode(ListSerializer(NightRecord.serializer()), history)),
                Write(SLOT_BACKUPS, encode(ListSerializer(SlotBackup.serializer()), _slotBackups.value), encode(ListSerializer(SlotBackup.serializer()), slotBackups)),
            )
            writeAllOrNone(writes)
            _profiles.value = profiles
            _settings.value = settings
            _history.value = history
            _slotBackups.value = slotBackups
            clearUnreadable()
        }
    }

    /** Restores a snapshot; false when it couldn't be read. */
    suspend fun restoreSnapshot(id: String): Boolean {
        val bundle = withContext(writer) { snapshots.load(id) } ?: return false
        restore(bundle, "Before restore")
        return true
    }

    suspend fun exportTo(uri: Uri) = withContext(writer) {
        val bytes = BackupCodec.encode(bundle()).toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Couldn't open the file to save to.")
    }

    /**
     * Reads a backup file; throws [BackupException] when it isn't one or is over [BackupValidator.MAX_BYTES]. Check it
     * with [restore], which validates it before changing anything.
     */
    suspend fun importFrom(uri: Uri): BackupBundle = withContext(writer) {
        context.contentResolver.openInputStream(uri)?.use { importFrom(it) } ?: throw IOException("Couldn't open that file.")
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
    }

    private fun <T> writeValue(name: String, serializer: KSerializer<T>, value: T) = writeFile(File(dir, name), encode(serializer, value))

    private class Write(val name: String, val old: ByteArray, val new: ByteArray)

    private fun writeAllOrNone(writes: List<Write>) {
        writes.forEachIndexed { i, write ->
            try {
                writeFile(File(dir, write.name), write.new)
            } catch (e: Exception) {
                writes.take(i).forEach { done ->
                    runCatching { writeFile(File(dir, done.name), done.old) }.onFailure { e.addSuppressed(it) }
                }
                throw e
            }
        }
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T) = BackupCodec.json.encodeToString(serializer, value).toByteArray()

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = BackupCodec.json.decodeFromString(serializer, text)

    /** Null when the file doesn't exist yet, or couldn't be read; then it's moved aside and reported in [unreadable]. */
    private fun <T> load(name: String, decode: (String) -> T): T? {
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
        return null
    }

    /** Runs on the writer. A report that can't be saved is shown as unreadable too, rather than lost on a restart. */
    private fun setAsidePending() {
        toSetAside.forEach { file ->
            val aside = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
            // If it can't be moved, a copy still keeps the original from being lost on the next write.
            if (!file.renameTo(aside)) runCatching { file.copyTo(aside) }.onFailure { Log.e(TAG, "Couldn't keep ${file.name}", it) }
        }
        toSetAside.clear()
        val names = _unreadable.value
        runCatching { writeValue(UNREADABLE, ListSerializer(String.serializer()), names) }.onFailure {
            Log.e(TAG, "Couldn't save $UNREADABLE", it)
            _unreadable.value = (names + UNREADABLE).distinct()
        }
    }

    /** True when [deviceId] takes over an original saved with [recordedFor] before devices were recorded. */
    private fun adopts(deviceId: String, recordedFor: String) = recordedFor == Migrations.UNKNOWN_DEVICE && DeviceId.address(deviceId) != null

    /** Two backups of the same slot of the same BedJet, counting a migrated one as the real BedJet's. */
    private fun SlotBackup.covers(other: SlotBackup) =
        slot == other.slot && (deviceId == other.deviceId || adopts(deviceId, other.deviceId) || adopts(other.deviceId, deviceId))

    /** Re-tags, durably, the migrated originals of slots [deviceId] has none for. Call on the writer. */
    private fun adoptMigrated(deviceId: String) {
        val list = _slotBackups.value
        val adopted = list.map { backup ->
            if (adopts(deviceId, backup.deviceId) && list.none { it.deviceId == deviceId && it.slot == backup.slot }) backup.copy(deviceId = deviceId) else backup
        }
        if (adopted == list) return
        writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), adopted)
        _slotBackups.value = adopted
    }

    private fun readUnreadableMarker(): List<String> =
        runCatching { decode(ListSerializer(String.serializer()), AtomicFile(File(dir, UNREADABLE)).readFully().decodeToString()) }.getOrDefault(emptyList())

    private fun clearUnreadable() {
        AtomicFile(File(dir, UNREADABLE)).delete()
        _unreadable.value = emptyList()
    }

    companion object {
        private const val TAG = "Repository"
        private const val PROFILES = "profiles.json"
        private const val SETTINGS = "settings.json"
        private const val HISTORY = "history.json"
        private const val SLOT_BACKUPS = "slot-backups.json"
        private const val RUNTIME = "runtime.json"
        private const val ACTIVE_NIGHT = "active-night.json"
        private const val NOTICES = "notices.json"
        /** Lists the files in [unreadable], so the report survives a restart. */
        private const val UNREADABLE = "unreadable.json"

        /** One write at a time, on an IO thread. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultWriter(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** AtomicFile syncs before replacing the old file, so a power cut leaves either the old or the new one. */
        fun writeDurably(file: File, bytes: ByteArray) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (e: IOException) {
                atomic.failWrite(out)
                throw e
            }
        }
    }
}
