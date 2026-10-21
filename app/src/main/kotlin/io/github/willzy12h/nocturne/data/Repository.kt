package io.github.willzy12h.nocturne.data

import android.content.Context
import android.net.Uri
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.History
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
import java.time.ZonedDateTime

/**
 * Everything the app keeps, as JSON files in its private storage. Changes to profiles and settings are snapshotted
 * automatically (debounced), and on the first launch of each day.
 */
@OptIn(FlowPreview::class)
class Repository(private val context: Context, scope: CoroutineScope) {
    private val dir = context.filesDir
    val snapshots = SnapshotStore(File(dir, "snapshots").toPath()) { ZonedDateTime.now() }

    private val _profiles = MutableStateFlow(load("profiles.json", ListSerializer(Profile.serializer())) ?: Presets.all)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _settings = MutableStateFlow(load("settings.json", Settings.serializer()) ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _history = MutableStateFlow(History.prune(load("history.json", ListSerializer(NightRecord.serializer())).orEmpty(), ZonedDateTime.now()))
    val history: StateFlow<List<NightRecord>> = _history.asStateFlow()
    private val _slotBackups = MutableStateFlow(load("slot-backups.json", ListSerializer(SlotBackup.serializer())).orEmpty())
    val slotBackups: StateFlow<List<SlotBackup>> = _slotBackups.asStateFlow()
    private val _activeNight = MutableStateFlow(load("active-night.json", ActiveNight.serializer().nullable))
    val activeNight: StateFlow<ActiveNight?> = _activeNight.asStateFlow()
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
            if (latest == null || latest.isBefore(ZonedDateTime.now().minusDays(1))) autoSnapshot()
        }
    }

    // Profiles and settings

    fun profile(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    /** The profile for tonight: the one picked, else the one set for today's weekday, else the first. */
    fun tonightsProfile(now: ZonedDateTime = ZonedDateTime.now()): Profile? =
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
        History.prune(list.filterNot { it.id == record.id } + record, ZonedDateTime.now())
    }

    fun addEvent(recordId: String, event: HistoryEvent) =
        update(_history, "history.json", ListSerializer(NightRecord.serializer())) { History.addEvent(it, recordId, event) }

    fun addSlotBackup(backup: SlotBackup) =
        update(_slotBackups, "slot-backups.json", ListSerializer(SlotBackup.serializer())) { list -> list.filterNot { it.slot == backup.slot } + backup }

    // Backups

    fun bundle(): BackupBundle = BackupBundle(
        createdAt = ZonedDateTime.now().toString(),
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

    /** Replaces everything with [bundle], keeping a snapshot of what was there first. */
    fun restore(bundle: BackupBundle) {
        snapshots.save(bundle(), "Before restore", manual = true)
        update(_profiles, "profiles.json", ListSerializer(Profile.serializer())) { bundle.profiles }
        update(_settings, "settings.json", Settings.serializer()) { bundle.settings }
        update(_slotBackups, "slot-backups.json", ListSerializer(SlotBackup.serializer())) { bundle.slotBackups }
        update(_history, "history.json", ListSerializer(NightRecord.serializer())) { bundle.history }
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
        if (snapshots.saveIfChanged(bundle())) _snapshotList.value = snapshots.list()
    }

    // Files

    private fun <T> load(name: String, serializer: KSerializer<T>): T? = runCatching {
        val file = File(dir, name)
        if (file.exists()) BackupCodec.json.decodeFromString(serializer, file.readText()) else null
    }.getOrNull()

    @Synchronized
    private fun <T> update(flow: MutableStateFlow<T>, name: String, serializer: KSerializer<T>, change: (T) -> T) {
        val value = change(flow.value)
        flow.value = value
        val file = File(dir, name)
        val temp = File(dir, "$name.tmp")
        temp.writeText(BackupCodec.json.encodeToString(serializer, value))
        temp.renameTo(file)
    }
}
