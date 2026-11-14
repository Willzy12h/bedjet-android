package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.RuntimeState
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.BackupFailure
import io.github.willzy12h.nocturne.core.store.History
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import io.github.willzy12h.nocturne.data.Repository.Companion.PROFILES
import io.github.willzy12h.nocturne.data.Repository.Companion.SETTINGS
import io.github.willzy12h.nocturne.data.Repository.Companion.HISTORY
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS
import io.github.willzy12h.nocturne.data.Repository.Write

// Part of [Repository], split out of Repository.kt: restoring and importing backups. What a restore keeps local,
// the fingerprint that makes a reviewed import safe to apply, and automatic snapshots.

internal fun Repository.importBusy() = BackupException(context.getString(R.string.import_busy), failure = BackupFailure.BUSY)

/** A durable pending intention survives process queues; an already scheduled future alarm is not pending work. */
internal fun Repository.requireImportIdle() {
    val runtime = _runtime.value
    val night = _activeNight.value
    val pendingJob = context.getSystemService(android.app.job.JobScheduler::class.java)
        ?.getPendingJob(io.github.willzy12h.nocturne.platform.ChargerJobs.ID)
    if (pendingJournal != null || _restoreUnfinished.value ||
        runtime.slotWrite?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.PENDING ||
        night?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.PENDING || night?.pending != null ||
        runtime.retime != null || runtime.alarmInbox != null || runtime.localDeliveries.isNotEmpty() ||
        pendingJob?.isPeriodic == false) throw importBusy()
}

/** Selection, tonight's live record and protected originals remain local. */
internal fun Repository.restoreProjection(bundle: BackupBundle, restoreAt: ZonedDateTime): BackupBundle {
    val current = _settings.value.migrateTonightChoice(restoreAt)
    // Tonight's choice and override stay as they are. A version 1 selection in the bundle is migrated, as the
    // app's own is, so it becomes tonight's choice only when there is none (IR-30).
    val importedSettings = bundle.settings.copy(tonightChoice = current.tonightChoice, tonightOverride = current.tonightOverride)
        .migrateTonightChoice(restoreAt)
    val settings = importedSettings.copy(demoMode = current.demoMode, targetDevice = current.targetDevice,
        rememberedBedJets = current.rememberedBedJets, demoTargetMigrated = current.demoTargetMigrated,
        setupFanCheckedDevice = current.setupFanCheckedDevice,
        platform = bundle.settings.platform.copy(chargerAutoStart = false, chargerDevice = null, healthSleepExport = false,
            autoStart = bundle.settings.platform.autoStart?.copy(enabled = false))).withoutUnidentifiedAlarms()
    // Tonight's record, and that of a night being ended (its end is still to be added), are kept as they are.
    val tonight = setOfNotNull(_activeNight.value?.recordId, _runtime.value.ending?.identity?.nightId)
    val carried = _history.value.filter { it.id in tonight }
    val localHistory = _history.value.associateBy { it.id }
    val importedHistory = bundle.history.map { incoming ->
        val unverified = withoutImportedProof(incoming)
        // An identical local record keeps only the proof already held locally; a file cannot mint proof,
        // reset an exported marker or change a record while carrying its old hardware confirmation.
        localHistory[incoming.id]?.takeIf { withoutImportedProof(it) == unverified } ?: unverified
    }
    val history = History.prune(importedHistory, restoreAt, bundle.settings.historyKeepDays).filterNot { restored -> carried.any { it.id == restored.id } } + carried
    val slotBackups = _slotBackups.value.let { current ->
        val incoming = bundle.slotBackups.map { it.copy(imported = true, deviceId = canonicalImportDevice(it.deviceId)) }
        // One saved after the loss of unreadable originals was accepted may be the app's own programme, so
        // the bundle's exact record of that BedJet's slot replaces it.
        val list = current.filterNot { saved -> savedAfterLoss(saved) && incoming.any { DeviceId.same(it.deviceId, saved.deviceId) && it.slot == saved.slot } }
        list + incoming.filter { restored -> list.none { it.covers(restored) } }
    }
    return bundle.copy(settings = settings, history = history, slotBackups = slotBackups)
}

private fun Repository.canonicalImportDevice(id: String): String = DeviceId.address(id)?.let(DeviceId::ble) ?: id

private fun Repository.withoutImportedProof(night: NightRecord): NightRecord = night.copy(
    deviceId = canonicalImportDevice(night.deviceId), bedJetRunConfirmed = false, confirmedCompletionAt = null,
    healthExportVersion = 0, healthExportedVersion = 0)

/** Called only with exclusive import admission and the repository writer held. */
internal fun Repository.restoreLocked(bundle: BackupBundle, label: String, projected: BackupBundle = restoreProjection(bundle, now())) {
    requireRealSession()
    val profiles = projected.profiles
    val settings = projected.settings
    val history = projected.history
    val slotBackups = projected.slotBackups
    snapshots.save(bundle(), label, manual = true)
    _snapshotList.value = snapshots.list()
    writeAllOrNone(listOf(
        Write(PROFILES, encode(ListSerializer(Profile.serializer()), _profiles.value), encode(ListSerializer(Profile.serializer()), profiles)) {
            _profiles.value = profiles
        },
        Write(SETTINGS, encode(Settings.serializer(), _settings.value), encode(Settings.serializer(), settings)) {
            _settings.value = settings
            _settingsPublication.value += 1
            _sessionRevision.value += 1
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

/** Profiles import merges by ID; every other part of the local bundle remains byte-for-byte owned locally. */
internal fun Repository.profileImportProjection(incoming: BackupBundle): BackupBundle {
    val replacements = incoming.profiles.associateBy { it.id }
    val current = _profiles.value
    val currentIds = current.map { it.id }.toSet()
    return bundle().copy(profiles = current.map { replacements[it.id] ?: it } + incoming.profiles.filter { it.id !in currentIds })
}

/** Compare the data and protection state as well as the projected result, including time-based retention. */
internal fun Repository.importFingerprint(projected: BackupBundle): String {
    val state = JsonArray(listOf(
        BackupCodec.json.encodeToJsonElement(BackupBundle.serializer(), bundle().copy(createdAt = "")),
        BackupCodec.json.encodeToJsonElement(BackupBundle.serializer(), projected.copy(createdAt = "")),
        BackupCodec.json.encodeToJsonElement(ActiveNight.serializer().nullable, _activeNight.value),
        BackupCodec.json.encodeToJsonElement(RuntimeState.serializer(), _runtime.value),
        JsonPrimitive(_sessionRevision.value),
        JsonPrimitive(originalsIntegrityBlocked), JsonPrimitive(originalsLossAcceptedAt),
        JsonArray(_unreadable.value.map(::JsonPrimitive)),
    ))
    return digest(state.toString().toByteArray(Charsets.UTF_8))
}

internal suspend fun Repository.autoSnapshot() = serially {
    if (isDemoSession || startupMigrationBlocked) return@serially
    // Snapshots of the defaults standing in for an unreadable file would push the good ones out.
    if (_unreadable.value.isNotEmpty()) return@serially
    if (snapshots.saveIfChanged(bundle())) _snapshotList.value = snapshots.list()
}
