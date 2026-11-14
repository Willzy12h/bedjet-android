package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.R
import android.util.Log
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import io.github.willzy12h.nocturne.data.Repository.Companion.TAG
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS_BLOCK
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS_LOSS

// Part of [Repository], split out of Repository.kt: saved slot originals. Accepting the loss of unreadable ones
// (IR-03), and adopting originals saved before devices were recorded.

/** Records the accepted loss, then lifts the barrier. Call on the writer. */
internal fun Repository.acceptOriginalsLoss() {
    requireRealSession()
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

/** Whether [backup] was saved by this app after the loss of unreadable originals was accepted. */
internal fun Repository.savedAfterLoss(backup: SlotBackup): Boolean {
    val lossAt = originalsLossAcceptedAt ?: return false
    return backup.uncertain && !backup.imported &&
        runCatching { !ZonedDateTime.parse(backup.savedAt).isBefore(ZonedDateTime.parse(lossAt)) }.getOrDefault(true)
}

/** True when [deviceId] takes over an original saved with [recordedFor] before devices were recorded. */
internal fun adopts(deviceId: String, recordedFor: String) = recordedFor == Migrations.UNKNOWN_DEVICE && DeviceId.address(deviceId) != null

/** Two backups of the same slot of the same BedJet, counting a migrated one as the real BedJet's. */
internal fun SlotBackup.covers(other: SlotBackup) =
    slot == other.slot && (DeviceId.same(deviceId, other.deviceId) ||
        adopts(deviceId, other.deviceId) || (!imported && adopts(other.deviceId, deviceId)))

/** Re-tags, durably, the migrated originals of slots [deviceId] has none for. Call on the writer. */
internal fun Repository.adoptMigrated(deviceId: String) {
    if (originalsIntegrityBlocked) return
    val list = _slotBackups.value
    val adopted = list.map { backup ->
        if (!backup.imported && adopts(deviceId, backup.deviceId) && list.none { it.deviceId == deviceId && it.slot == backup.slot }) backup.copy(deviceId = deviceId) else backup
    }
    if (adopted == list) return
    writeValue(SLOT_BACKUPS, ListSerializer(SlotBackup.serializer()), adopted)
    _slotBackups.value = adopted
}
