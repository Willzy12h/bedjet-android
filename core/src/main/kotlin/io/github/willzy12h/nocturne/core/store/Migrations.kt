package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Brings saved data written by an older version up to the current shape, one schema step at a time. Bundles carry
 * their schema; the files the app keeps in its own storage don't, so their records are migrated one by one, which is
 * safe to repeat on records already current.
 */
object Migrations {
    /** The device of a slot backup or night saved before the device was recorded. */
    const val UNKNOWN_DEVICE = "unknown"

    /** A bundle of any schema up to [BackupCodec.SCHEMA], as the current schema. */
    fun bundle(obj: JsonObject): JsonObject {
        var current = obj
        var schema = current["schema"]?.jsonPrimitive?.int ?: 1
        while (schema < BackupCodec.SCHEMA) {
            current = when (schema) {
                1 -> oneToTwo(current)
                2 -> twoToThree(current)
                else -> error("No migration from schema $schema")
            }
            schema++
        }
        return current
    }

    /** The app's own `slot-backups.json`, from any version. */
    fun decodeSlotBackups(text: String): List<SlotBackup> =
        BackupCodec.json.decodeFromJsonElement(ListSerializer(SlotBackup.serializer()), mapRecords(BackupCodec.json.parseToJsonElement(text), ::slotBackupV2))

    /** The app's own `history.json`, from any version. */
    fun decodeHistory(text: String): List<NightRecord> =
        BackupCodec.json.decodeFromJsonElement(ListSerializer(NightRecord.serializer()), mapRecords(BackupCodec.json.parseToJsonElement(text), ::nightRecordV2))

    /** 2 → 3: only new fields, each with a default, so the records are unchanged. */
    private fun twoToThree(obj: JsonObject): JsonObject = JsonObject(obj + ("schema" to JsonPrimitive(3)))

    /** 1 → 2: slot backups gain their device and empty marker, and are marked uncertain; nights gain their device. */
    private fun oneToTwo(obj: JsonObject): JsonObject = JsonObject(
        obj + mapOf(
            "schema" to JsonPrimitive(2),
            "slotBackups" to mapRecords(obj["slotBackups"] ?: JsonArray(emptyList()), ::slotBackupV2),
            "history" to mapRecords(obj["history"] ?: JsonArray(emptyList()), ::nightRecordV2),
        ),
    )

    private fun slotBackupV2(record: JsonObject): JsonObject {
        val program = record["program"]
        return JsonObject(
            record + mapOf(
                "deviceId" to (record["deviceId"] ?: JsonPrimitive(UNKNOWN_DEVICE)),
                "empty" to (record["empty"] ?: JsonPrimitive(program == null || program is JsonNull)),
                // Only a record without a device is from version 1, which never recorded empty slots (IR-31). A
                // current record keeps the flag it has.
                "uncertain" to (record["uncertain"] ?: JsonPrimitive(record["deviceId"] == null)),
            ),
        )
    }

    private fun nightRecordV2(record: JsonObject): JsonObject =
        JsonObject(record + ("deviceId" to (record["deviceId"] ?: JsonPrimitive(UNKNOWN_DEVICE))))

    private fun mapRecords(list: JsonElement, migrate: (JsonObject) -> JsonObject): JsonArray =
        JsonArray(list.jsonArray.map { migrate(it.jsonObject) })
}
