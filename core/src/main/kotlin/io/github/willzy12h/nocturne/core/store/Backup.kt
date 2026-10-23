package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a BedJet's slot held before the app first overwrote it, on the device named by [deviceId] (a
 * [io.github.willzy12h.nocturne.core.link.DeviceId], or [Migrations.UNKNOWN_DEVICE] for one saved before devices were
 * recorded). [empty] marks a slot that held nothing, so putting it back means leaving it empty.
 *
 * [uncertain] marks one migrated from version 1. That version never recorded empty slots, so its records are
 * the best there is, but a slot it used may by then have held one of its own programmes: restore copy says so.
 * [fromBackup] marks one read from an imported backup with no known BedJet (an "unknown" or version 1 device). It
 * may come from another unit, so no BedJet adopts it and it never counts as a slot's original; it is put back only
 * when the person chooses it.
 */
@Serializable
data class SlotBackup(
    val deviceId: String,
    val slot: Int,
    val program: StoredProgram?,
    val savedAt: String,
    val empty: Boolean = program == null,
    val uncertain: Boolean = false,
    val fromBackup: Boolean = false,
)

/**
 * [this] as read from a backup file rather than from the app's own snapshots: every original with no known BedJet
 * becomes a [SlotBackup.fromBackup] record, whatever the file says.
 */
fun BackupBundle.asImported(): BackupBundle =
    copy(slotBackups = slotBackups.map { if (it.deviceId == Migrations.UNKNOWN_DEVICE) it.copy(fromBackup = true) else it })

@Serializable
data class BackupBundle(
    val schema: Int = BackupCodec.SCHEMA,
    val createdAt: String,
    val profiles: List<Profile>,
    val settings: Settings,
    val slotBackups: List<SlotBackup> = emptyList(),
    val history: List<NightRecord> = emptyList(),
)

/** A backup that can't be used. [errors] lists every problem found, each a sentence a person can act on. */
class BackupException(message: String, val errors: List<String> = listOf(message)) : Exception(message) {
    constructor(errors: List<String>) : this(errors.joinToString("\n"), errors)
}

object BackupCodec {
    /** 2 added the device to slot backups and nights, and the empty-slot marker. See [Migrations]. */
    const val SCHEMA = 2

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private const val DAMAGED = "This isn't a Nocturne backup, or it is damaged."
    private const val NEWER = "This backup is from a newer version of Nocturne. Update the app first."

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    /** Decodes a bundle of any schema up to [SCHEMA], migrating an older one first. */
    fun decode(text: String): BackupBundle = try {
        decode(json.parseToJsonElement(text).jsonObject)
    } catch (e: SerializationException) {
        throw BackupException(DAMAGED)
    } catch (e: IllegalArgumentException) {
        throw BackupException(DAMAGED)
    }

    /** As [decode], from the parsed JSON object. */
    fun decode(obj: JsonObject): BackupBundle {
        val schema = obj["schema"]?.jsonPrimitive?.int ?: 1
        if (schema > SCHEMA) throw BackupException(NEWER)
        return json.decodeFromJsonElement(BackupBundle.serializer(), Migrations.bundle(obj))
    }
}
