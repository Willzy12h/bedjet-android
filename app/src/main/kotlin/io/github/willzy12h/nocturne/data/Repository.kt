package io.github.willzy12h.nocturne.data

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
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.asImported
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
import java.time.ZonedDateTime

/**
 * Everything the app keeps, as JSON files in its private storage. Changes to profiles and settings are snapshotted
 * automatically (debounced), and on the first launch of each day.
 *
 * Every change is written durably, off the main thread and one at a time on [writer], before its new value is
 * published: a flow never shows something that a power cut could take back. When the write fails the old value stays
 * and the error is thrown to the caller. [writeFile] is how a file is written; tests replace it to make writes fail.
 *
 * A restore replaces several files, so before the first one it saves `restore-pending.json` naming the snapshot of
 * what was there; that marker is deleted only once every file is written. A Repository that finds the marker on
 * start-up (the process died part-way) puts the snapshot back before loading anything, and leaves a notice.
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
    val snapshots = SnapshotStore(File(dir, "snapshots").toPath(), syncDirectory = { syncDirectory(it.toFile()) }) { now() }
    private val lock = Mutex()

    /** Runs before the loads below, so they read the files as they were before an interrupted restore. */
    private val interruptedRestore: LoggedNotice? = recoverInterruptedRestore()

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
    // Publication signal, not persisted state: equal-value restores must still reconcile side effects.
    private val _settingsPublication = MutableStateFlow(0L)
    internal val settingsPublication: StateFlow<Long> = _settingsPublication.asStateFlow()
    private val _history = MutableStateFlow(History.prune(load(HISTORY, Migrations::decodeHistory).orEmpty(), now()))
    val history: StateFlow<List<NightRecord>> = _history.asStateFlow()
    private val _slotBackups = MutableStateFlow(load(SLOT_BACKUPS, Migrations::decodeSlotBackups).orEmpty())
    /** What each device's slots held before the app first wrote them. See [recordOriginalSlot]. */
    val slotBackups: StateFlow<List<SlotBackup>> = _slotBackups.asStateFlow()
    /**
     * When the loss of an unreadable `slot-backups.json` was accepted, with [dismissUnreadable] or by a [restore] while
     * it was unreadable, or null. Originals saved from then on may be the app's own programs, so a restore lets a
     * snapshot's originals win over them.
     */
    private var slotBackupsLostAt: ZonedDateTime? = readSlotBackupsLostAt()
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
        // Tidying the files is a write too, and runs before any snapshot is saved.
        scope.launch(writer) {
            serially {
                if (toSetAside.isNotEmpty()) setAsidePending()
                tidyFiles()
            }
        }
        interruptedRestore?.let { notice -> scope.launch { runCatching { addNotice(notice) }.onFailure { Log.e(TAG, "Couldn't log the notice", it) } } }
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
     * Throws [SlotBackupsUnreadable], saving nothing, while the saved originals can't be read.
     */
    suspend fun recordOriginalSlot(deviceId: String, slot: Int, program: StoredProgram?): SlotBackup = serially {
        requireSlotBackupsReadable()
        adoptMigrated(deviceId)
        originalSlot(deviceId, slot) ?: SlotBackup(deviceId, slot, program, now().toString()).also { backup ->
            val list = _slotBackups.value + backup
            writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), list)
            _slotBackups.value = list
        }
    }

    /** What [deviceId]'s [slot] held before the app first wrote to it, or null when that wasn't recorded. */
    fun originalSlot(deviceId: String, slot: Int): SlotBackup? =
        _slotBackups.value.firstOrNull { !it.fromBackup && it.deviceId == deviceId && it.slot == slot }
            ?: _slotBackups.value.firstOrNull { it.slot == slot && adopts(deviceId, it) }

    /**
     * The slot backups that count for [deviceId], to tell NightRunner which slots are already backed up. A real
     * BedJet first adopts, durably, any original saved before devices were recorded for a slot it has none for: that
     * original came from the BedJet the app was used with, while the slot may by now hold the app's own program. With
     * no BedJet chosen (null) those migrated originals are returned as they are, so nothing is saved over them. The
     * demo unit never adopts them. Records from an imported backup ([SlotBackup.fromBackup]) never count.
     * Throws [SlotBackupsUnreadable] while the saved originals can't be read.
     */
    suspend fun originalsFor(deviceId: String?): List<SlotBackup> {
        requireSlotBackupsReadable()
        if (deviceId == null) return _slotBackups.value.filter { !it.fromBackup && it.deviceId == Migrations.UNKNOWN_DEVICE }
        serially { adoptMigrated(deviceId) }
        return _slotBackups.value.filter { !it.fromBackup && it.deviceId == deviceId }
    }

    suspend fun addNotice(notice: LoggedNotice) =
        update(_notices, NOTICES, ListSerializer(LoggedNotice.serializer())) { NotificationLog.add(it, notice) }

    suspend fun clearNotices() = update(_notices, NOTICES, ListSerializer(LoggedNotice.serializer())) { emptyList() }

    /** True while `slot-backups.json` is reported unreadable: no slot may be written until it is restored or dismissed. */
    val slotBackupsUnreadable: Boolean get() = SLOT_BACKUPS in _unreadable.value

    /** Throws [SlotBackupsUnreadable] while [slotBackupsUnreadable]. */
    fun requireSlotBackupsReadable() {
        if (slotBackupsUnreadable) throw SlotBackupsUnreadable()
    }

    /**
     * Stops reporting the files listed in [unreadable]. Their set-aside copies stay where they are. Dismissing an
     * unreadable `slot-backups.json` accepts the loss of its originals: the time is saved first, so a later restore
     * lets a snapshot's originals win over ones recorded from now on.
     */
    suspend fun dismissUnreadable() = serially {
        if (slotBackupsUnreadable) markSlotBackupsLost()
        clearUnreadable()
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
     * knows, never which BedJet it talks to or what that BedJet is doing. So tonight's choice and wake-up override
     * stay as they are (a version 1 selected profile in [bundle] becomes tonight's choice only when there is none),
     * and the history record of the night under way, or being ended, is kept with its events. Slot backups are
     * merged, current ones winning: an older bundle's backup of a slot may be one of the app's own programs, not the
     * user's original. The exception is a current one saved after the loss of an unreadable slot-backups file was
     * accepted (dismissed, or a restore while it was unreadable): that may be the app's own program, so the bundle's
     * backup of the same slot wins. Records from an imported backup ([SlotBackup.fromBackup]) are kept beside the
     * BedJets' own originals, never in their place.
     *
     * Everything is written before anything is published. If a write fails the files already written are put back;
     * if putting one back fails too, what is shown follows what is on disk and the next start finishes the job. If
     * the process dies part-way, the next start puts the snapshot back (see the class comment).
     */
    suspend fun restore(bundle: BackupBundle, label: String = "Before import") {
        val errors = BackupValidator.validate(bundle)
        if (errors.isNotEmpty()) throw BackupException(errors)
        serially {
            // A restore whose undo is still owed keeps its marker until the next start finishes the undo.
            if (File(dir, RESTORE_PENDING).exists()) throw IOException("An earlier restore didn't finish. Close and reopen the app, then try again.")
            val before = snapshots.save(bundle(), label, manual = true)
            _snapshotList.value = snapshots.list()
            val current = _settings.value
            val profiles = bundle.profiles
            val settings = bundle.settings.copy(
                demoMode = current.demoMode, targetDevice = current.targetDevice,
                platform = bundle.settings.platform.copy(chargerAutoStart = false, chargerDevice = null),
                tonightChoice = current.tonightChoice, tonightOverride = current.tonightOverride,
            ).migrateTonightChoice(now())
            val tonight = setOfNotNull(_activeNight.value?.recordId, _runtime.value.ending?.identity?.nightId)
            val carried = _history.value.filter { it.id in tonight }
            val history = History.prune(bundle.history.filterNot { restored -> carried.any { it.id == restored.id } } + carried, now())
            val slotBackups = _slotBackups.value.let { list ->
                val kept = list.filterNot { current -> savedAfterLoss(current) && bundle.slotBackups.any { current.covers(it) } }
                kept + bundle.slotBackups.filter { restored -> kept.none { it.covers(restored) } }
            }
            val writes = listOf(
                Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value), encode(ListSerializer(Profile.serializer()), profiles)) {
                    _profiles.value = profiles
                },
                Write(SETTINGS, encode(Settings.serializer(), current), encode(Settings.serializer(), settings)) {
                    _settings.value = settings
                    _settingsPublication.value += 1
                },
                Write(HISTORY, encode(ListSerializer(NightRecord.serializer()), _history.value), encode(ListSerializer(NightRecord.serializer()), history)) {
                    _history.value = history
                },
                Write(SLOT_BACKUPS, encode(ListSerializer(SlotBackup.serializer()), _slotBackups.value), encode(ListSerializer(SlotBackup.serializer()), slotBackups)) {
                    _slotBackups.value = slotBackups
                },
            )
            // Restoring while the originals are unreadable accepts their loss, as a dismiss does: a snapshot taken
            // meanwhile holds none, and originals recorded after this one must not outrank an older good snapshot's.
            if (slotBackupsUnreadable) markSlotBackupsLost()
            writeValue(RESTORE_PENDING, PendingRestore.serializer(), PendingRestore(before.id, writes.map { it.name }))
            writeAllOrNone(writes)
            writes.forEach { it.publish() }
            clearUnreadable()
        }
    }

    /** Restores a snapshot; false when it couldn't be read. */
    suspend fun restoreSnapshot(id: String): Boolean {
        val bundle = withContext(writer) { snapshots.load(id) } ?: return false
        restore(bundle, "Before restore")
        return true
    }

    /**
     * Saves a backup to [uri]. Only taking the [bundle] waits for the writer: the file itself, which a cloud document
     * provider can take a long time over, is written on another IO thread, so the app's own saves carry on meanwhile.
     */
    suspend fun exportTo(uri: Uri) {
        val bytes = serially { BackupCodec.encode(bundle()).toByteArray() }
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Couldn't open the file to save to.")
        }
    }

    /**
     * Reads a backup file; throws [BackupException] when it isn't one or is over [BackupValidator.MAX_BYTES]. Check it
     * with [restore], which validates it before changing anything. Read off the writer, as [exportTo] writes.
     */
    suspend fun importFrom(uri: Uri): BackupBundle = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { importFrom(it) } ?: throw IOException("Couldn't open that file.")
    }

    /**
     * As [importFrom], from a stream. The size is checked before any decoding. Originals in it with no known BedJet
     * become read-only records from the backup ([asImported]): no BedJet adopts them.
     */
    fun importFrom(input: InputStream): BackupBundle = BackupCodec.decode(BackupValidator.readWithinLimit(input)).asImported()

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

    private fun <T> writeValue(name: String, serializer: KSerializer<T>, value: T) = writeFile(File(dir, name), encode(serializer, value))

    /** One file of a [restore]: its bytes before and after, and how to show the new value once it is on disk. */
    private class Write(val name: String, val old: ByteArray, val new: ByteArray, val publish: () -> Unit)

    /** What a [restore] under way saves before its first file, so the next start can undo it if the process dies. */
    @Serializable
    private data class PendingRestore(val snapshotId: String, val files: List<String>)

    /**
     * Writes every file of a restore, then deletes the [RESTORE_PENDING] marker. On a failure the files already
     * written are put back and the marker deleted. Any that can't be put back are shown as they are on disk and the
     * marker stays, for the next start to finish putting things back. Call on the writer.
     */
    private fun writeAllOrNone(writes: List<Write>) {
        writes.forEachIndexed { i, write ->
            try {
                writeFile(File(dir, write.name), write.new)
            } catch (e: Throwable) {
                // Also for an Error, which would otherwise leave the files mixed until the next start.
                throw undo(writes.take(i), e)
            }
        }
        if (!deletePendingRestore()) throw undo(writes, IOException("Couldn't finish the restore."))
    }

    /** Puts [done] back; returns [e], or a [RestoreInterrupted] for it when a file couldn't be put back. */
    private fun undo(done: List<Write>, e: Throwable): Throwable {
        val stuck = done.filter { write ->
            runCatching { writeFile(File(dir, write.name), write.old) }.onFailure { e.addSuppressed(it) }.isFailure
        }
        if (stuck.isEmpty()) {
            // Left behind, it only makes the next start put back the same old files.
            deletePendingRestore()
            return e
        }
        stuck.forEach { it.publish() }
        return if (e is Exception) RestoreInterrupted(e) else e
    }

    private fun deletePendingRestore(): Boolean {
        AtomicFile(File(dir, RESTORE_PENDING)).delete()
        return !File(dir, RESTORE_PENDING).exists()
    }

    /**
     * Before anything is loaded: when [RESTORE_PENDING] is there a restore was cut short, so the snapshot it names
     * is written back over the files it lists. Returns the notice to leave, or null when there was nothing to do.
     * Written on the calling thread, as the loads that follow read these files.
     */
    private fun recoverInterruptedRestore(): LoggedNotice? {
        val marker = AtomicFile(File(dir, RESTORE_PENDING))
        val text = try {
            marker.readFully().decodeToString()
        } catch (_: FileNotFoundException) {
            return null
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't read $RESTORE_PENDING", e)
            null
        }
        val pending = text?.let { runCatching { decode(PendingRestore.serializer(), it) }.getOrNull() }
        val before = pending?.let { snapshots.load(it.snapshotId) }
        if (pending == null || before == null) {
            // Nothing to put back: keep what is on disk, say so once, and don't try again.
            marker.delete()
            return restoreNotice(
                "The app closed part-way through a restore, and what you had before couldn't be read back. Some of " +
                    "the backup may have been restored. Check your profiles and settings, or restore a snapshot.",
            )
        }
        return try {
            pending.files.forEach { name -> encoded(name, before)?.let { writeFile(File(dir, name), it) } }
            marker.delete()
            restoreNotice("The app closed part-way through a restore, so everything was put back as it was before. Import the backup again to restore it.")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't undo the interrupted restore", e)
            restoreNotice("The app closed part-way through a restore, and putting things back as they were failed. It will be tried again when the app next starts.")
        }
    }

    private fun restoreNotice(text: String) = LoggedNotice(now().toString(), NoticeKind.PROBLEM, "The restore didn't finish", text)

    /** [name]'s contents as [bundle] holds them. */
    private fun encoded(name: String, bundle: BackupBundle): ByteArray? = when (name) {
        PROFILES -> encode(ListSerializer(Profile.serializer()), bundle.profiles)
        SETTINGS -> encode(Settings.serializer(), bundle.settings)
        HISTORY -> encode(ListSerializer(NightRecord.serializer()), bundle.history)
        SLOT_BACKUPS -> encode(ListSerializer(SlotBackup.serializer()), bundle.slotBackups)
        else -> null
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
        val asideDir = File(dir, SET_ASIDE_DIR).apply { mkdirs() }
        toSetAside.forEach { file ->
            val aside = File(asideDir, "${file.name}.corrupt-${System.currentTimeMillis()}")
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

    /**
     * Runs on the writer at start-up: deletes half-written snapshots, and moves copies an earlier version set aside
     * next to the files into [SET_ASIDE_DIR], which backups and device transfer leave out.
     */
    private fun tidyFiles() {
        runCatching { snapshots.removeLeftovers() }.onFailure { Log.w(TAG, "Couldn't remove leftover snapshot files", it) }
        val earlier = dir.listFiles { file -> file.isFile && ".corrupt-" in file.name }.orEmpty()
        if (earlier.isEmpty()) return
        val asideDir = File(dir, SET_ASIDE_DIR).apply { mkdirs() }
        earlier.forEach { file -> if (!file.renameTo(File(asideDir, file.name))) Log.w(TAG, "Couldn't move ${file.name}") }
    }

    /** True when [deviceId] takes over an original saved with [recordedFor] before devices were recorded. */
    private fun adopts(deviceId: String, recordedFor: String) = recordedFor == Migrations.UNKNOWN_DEVICE && DeviceId.address(deviceId) != null

    /** As above for [backup]; never one from an imported backup, which may come from another BedJet. */
    private fun adopts(deviceId: String, backup: SlotBackup) = !backup.fromBackup && adopts(deviceId, backup.deviceId)

    /**
     * Two backups of the same slot of the same BedJet, counting a migrated one as the real BedJet's. A record from an
     * imported backup only covers another such record.
     */
    private fun SlotBackup.covers(other: SlotBackup) = slot == other.slot && fromBackup == other.fromBackup &&
        (deviceId == other.deviceId || adopts(deviceId, other.deviceId) || adopts(other.deviceId, deviceId))

    /** Re-tags, durably, the migrated originals of slots [deviceId] has none for. Call on the writer. */
    private fun adoptMigrated(deviceId: String) {
        val list = _slotBackups.value
        val adopted = list.map { backup ->
            if (adopts(deviceId, backup) && list.none { !it.fromBackup && it.deviceId == deviceId && it.slot == backup.slot }) backup.copy(deviceId = deviceId) else backup
        }
        if (adopted == list) return
        writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), adopted)
        _slotBackups.value = adopted
    }

    /** Saves, durably, that the loss of unreadable slot backups was accepted now. Call on the writer. */
    private fun markSlotBackupsLost() {
        val at = now()
        writeFile(File(dir, SLOT_BACKUPS_LOST), at.toString().toByteArray())
        slotBackupsLostAt = at
    }

    /** True when [backup] was saved once the loss of unreadable slot backups had been accepted. */
    private fun savedAfterLoss(backup: SlotBackup): Boolean {
        val lost = slotBackupsLostAt ?: return false
        val saved = runCatching { ZonedDateTime.parse(backup.savedAt) }.getOrNull() ?: return false
        return !saved.toInstant().isBefore(lost.toInstant())
    }

    private fun readSlotBackupsLostAt(): ZonedDateTime? =
        runCatching { ZonedDateTime.parse(AtomicFile(File(dir, SLOT_BACKUPS_LOST)).readFully().decodeToString().trim()) }.getOrNull()

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
        /**
         * When the loss of an unreadable [SLOT_BACKUPS] was accepted. Device-local, like [UNREADABLE]: the backup rules
         * keep it and the other device-local files below off other phones (res/xml/data_extraction_rules.xml).
         */
        private const val SLOT_BACKUPS_LOST = "slot-backups-lost.txt"
        private const val RUNTIME = "runtime.json"
        private const val AUTOMATION = "automation.json"
        private const val ACTIVE_NIGHT = "active-night.json"
        private const val NOTICES = "notices.json"
        /** Lists the files in [unreadable], so the report survives a restart. */
        private const val UNREADABLE = "unreadable.json"
        /** Marks a restore under way; see the class comment. Device-local. */
        private const val RESTORE_PENDING = "restore-pending.json"
        /** Where unreadable files are moved aside, as `<name>.corrupt-<millis>`. Device-local. */
        const val SET_ASIDE_DIR = "set-aside"

        /** One write at a time, on an IO thread. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultWriter(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        /**
         * AtomicFile syncs before replacing the old file, so a power cut leaves either the old or the new one. The
         * directory is synced after the rename ([syncDirectory]), so the replacement itself is durable too.
         */
        fun writeDurably(file: File, bytes: ByteArray, syncDirectory: (File) -> Unit = ::syncDirectory) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (e: IOException) {
                atomic.failWrite(out)
                throw e
            }
            file.absoluteFile.parentFile?.let(syncDirectory)
        }

        /** Syncs [dir] so renames in it survive a power cut, where the platform allows; otherwise does nothing. */
        fun syncDirectory(dir: File) {
            try {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            } catch (e: Exception) {
                SnapshotStore.forceDirectory(dir.toPath())
            } catch (e: LinkageError) {
                SnapshotStore.forceDirectory(dir.toPath())
            }
        }
    }
}
