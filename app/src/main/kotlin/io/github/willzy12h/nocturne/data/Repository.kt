package io.github.willzy12h.nocturne.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.History
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NotificationLog
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.store.SnapshotInfo
import io.github.willzy12h.nocturne.core.store.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZonedDateTime

/**
 * Everything the app keeps, as JSON files in its private storage. Changes to profiles and settings are snapshotted
 * automatically (debounced), and on the first launch of each day.
 */
@OptIn(FlowPreview::class)
class Repository(private val context: Context, scope: CoroutineScope, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {
    private val dir = context.filesDir
    val snapshots = SnapshotStore(File(dir, "snapshots").toPath()) { now() }

    // Declared before the loads below, which fill it.
    private val _unreadable = MutableStateFlow<List<String>>(emptyList())
    /**
     * Files that couldn't be read at start-up, so their defaults are showing instead. Each was moved aside as
     * `<name>.corrupt-<millis>`. Automatic snapshots are paused while this isn't empty; a restore clears it.
     */
    val unreadable: StateFlow<List<String>> = _unreadable.asStateFlow()

    private val _profiles = MutableStateFlow(load("profiles.json", ListSerializer(Profile.serializer())) ?: Presets.all)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _settings = MutableStateFlow(load("settings.json", Settings.serializer()) ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _history = MutableStateFlow(History.prune(load("history.json", ListSerializer(NightRecord.serializer())).orEmpty(), now()))
    val history: StateFlow<List<NightRecord>> = _history.asStateFlow()
    private val _slotBackups = MutableStateFlow(load("slot-backups.json", ListSerializer(SlotBackup.serializer())).orEmpty())
    val slotBackups: StateFlow<List<SlotBackup>> = _slotBackups.asStateFlow()
    private val _activeNight = MutableStateFlow(load("active-night.json", ActiveNight.serializer().nullable))
    val activeNight: StateFlow<ActiveNight?> = _activeNight.asStateFlow()
    private val _notices = MutableStateFlow(load("notices.json", ListSerializer(LoggedNotice.serializer())).orEmpty())
    /** Every notification the app has shown, newest first. Not part of backups. */
    val notices: StateFlow<List<LoggedNotice>> = _notices.asStateFlow()
    private val _snapshotList = MutableStateFlow(snapshots.list())
    val snapshotList: StateFlow<List<SnapshotInfo>> = _snapshotList.asStateFlow()

    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    /** GitHub token for checking a private repository. Kept out of backups. */
    var updateToken: String
        get() = secrets.getString("updateToken", "").orEmpty()
        set(value) = secrets.edit().putString("updateToken", value.trim()).apply()

    init {
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
        profile(_settings.value.selectedProfileId)
            ?: _profiles.value.firstOrNull { now.dayOfWeek.value in it.days }
            ?: _profiles.value.firstOrNull()

    fun saveProfile(profile: Profile) = update(_profiles, "profiles.json", ListSerializer(Profile.serializer())) { list ->
        if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
    }

    fun deleteProfile(id: String) = update(_profiles, "profiles.json", ListSerializer(Profile.serializer())) { list -> list.filterNot { it.id == id } }

    fun updateSettings(change: (Settings) -> Settings) = update(_settings, "settings.json", Settings.serializer(), change)

    // Night state

    fun setActiveNight(night: ActiveNight?) = update(_activeNight, "active-night.json", ActiveNight.serializer().nullable) { night }

    fun addRecord(record: NightRecord) = update(_history, "history.json", ListSerializer(NightRecord.serializer())) { list ->
        History.prune(list.filterNot { it.id == record.id } + record, now())
    }

    fun addEvent(recordId: String, event: HistoryEvent) =
        update(_history, "history.json", ListSerializer(NightRecord.serializer())) { History.addEvent(it, recordId, event) }

    fun addSlotBackup(backup: SlotBackup) =
        update(_slotBackups, "slot-backups.json", ListSerializer(SlotBackup.serializer())) { list -> list.filterNot { it.slot == backup.slot } + backup }

    fun addNotice(notice: LoggedNotice) =
        update(_notices, "notices.json", ListSerializer(LoggedNotice.serializer())) { NotificationLog.add(it, notice) }

    fun clearNotices() = update(_notices, "notices.json", ListSerializer(LoggedNotice.serializer())) { emptyList() }

    // Backups

    fun bundle(): BackupBundle = BackupBundle(
        createdAt = now().toString(),
        profiles = _profiles.value,
        settings = _settings.value,
        slotBackups = _slotBackups.value,
        history = _history.value,
    )

    fun snapshotNow(label: String) {
        snapshots.save(bundle(), label.ifBlank { "Manual snapshot" }, manual = true)
        _snapshotList.value = snapshots.list()
    }

    fun deleteSnapshot(id: String) {
        snapshots.delete(id)
        _snapshotList.value = snapshots.list()
    }

    /**
     * Replaces everything with [bundle], keeping a snapshot of what was there first. Slot backups are merged, current
     * ones winning: an older bundle's backup of a slot may be one of the app's own programs, not the user's original.
     */
    fun restore(bundle: BackupBundle) {
        snapshots.save(bundle(), "Before restore", manual = true)
        update(_profiles, "profiles.json", ListSerializer(Profile.serializer())) { bundle.profiles }
        update(_settings, "settings.json", Settings.serializer()) { bundle.settings }
        update(_slotBackups, "slot-backups.json", ListSerializer(SlotBackup.serializer())) { current ->
            current + bundle.slotBackups.filter { restored -> current.none { it.slot == restored.slot } }
        }
        update(_history, "history.json", ListSerializer(NightRecord.serializer())) { bundle.history }
        _unreadable.value = emptyList()
        _snapshotList.value = snapshots.list()
    }

    fun restoreSnapshot(id: String): Boolean = snapshots.load(id)?.let { restore(it); true } ?: false

    fun exportTo(uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(BackupCodec.encode(bundle()).toByteArray()) }
    }

    /** Reads a backup file; throws [io.github.willzy12h.nocturne.core.store.BackupException] when it isn't one. */
    fun importFrom(uri: Uri): BackupBundle {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
        return BackupCodec.decode(text)
    }

    private fun autoSnapshot() {
        // Snapshots of the defaults standing in for an unreadable file would push the good ones out.
        if (_unreadable.value.isNotEmpty()) return
        if (snapshots.saveIfChanged(bundle())) _snapshotList.value = snapshots.list()
    }

    // Files

    /** Null when the file doesn't exist yet, or couldn't be read; then it's moved aside and reported in [unreadable]. */
    private fun <T> load(name: String, serializer: KSerializer<T>): T? {
        val file = AtomicFile(File(dir, name))
        return try {
            BackupCodec.json.decodeFromString(serializer, file.readFully().decodeToString())
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            setAside(file.baseFile, e)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: bad JSON, or a value from a newer version such as an unknown enum.
            setAside(file.baseFile, e)
        }
    }

    private fun <T> setAside(file: File, cause: Exception): T? {
        val aside = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
        Log.w(TAG, "Couldn't read ${file.name}; moved it to ${aside.name}", cause)
        // If it can't be moved, a copy still keeps the original from being lost on the next write.
        if (!file.renameTo(aside)) runCatching { file.copyTo(aside) }.onFailure { Log.e(TAG, "Couldn't keep ${file.name}", it) }
        _unreadable.value += file.name
        return null
    }

    @Synchronized
    private fun <T> update(flow: MutableStateFlow<T>, name: String, serializer: KSerializer<T>, change: (T) -> T) {
        val value = change(flow.value)
        flow.value = value
        // AtomicFile syncs before replacing the old file, so a power cut leaves either the old or the new one.
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(BackupCodec.json.encodeToString(serializer, value).toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val TAG = "Repository"
    }
}
