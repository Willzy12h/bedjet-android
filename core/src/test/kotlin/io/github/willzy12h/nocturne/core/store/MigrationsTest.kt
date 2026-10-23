package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationsTest {
    private val program = StoredProgram("old", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))

    /** A bundle as version 1 wrote it: slot backups without a device or empty marker, nights without a device. */
    private val v1 = """
        {
          "schema": 1,
          "createdAt": "2026-10-21T22:30Z",
          "profiles": [],
          "settings": {"workingSlot": 2},
          "slotBackups": [
            {"slot": 0, "program": {"name": "old", "steps": [{"mode": "DRY", "fanStep": 1, "temperature": 60, "hours": 1, "minutes": 0}]}, "savedAt": "2026-10-20T22:30Z"}
          ],
          "history": [
            {"id": "n1", "profileId": "p", "profileName": "P", "startedAt": "2026-10-20T22:30Z", "wakeAt": "2026-10-21T06:30Z", "wakeFromAlarm": true}
          ]
        }
    """.trimIndent()

    @Test
    fun version1BundleMigratesToTheCurrentSchema() {
        val bundle = BackupCodec.decode(v1)
        assertEquals(2, BackupCodec.SCHEMA)
        assertEquals(BackupCodec.SCHEMA, bundle.schema)
        assertEquals(listOf(SlotBackup("unknown", 0, program, "2026-10-20T22:30Z", empty = false, uncertain = true)), bundle.slotBackups)
        assertEquals("unknown", bundle.history.single().deviceId)
    }

    /** IR-31: version 1 never recorded empty slots, so a slot it saved may already have held one of its programmes. */
    @Test
    fun version1OriginalsAreMarkedUncertain() {
        assertTrue(BackupCodec.decode(v1).slotBackups.single().uncertain)
        val onDisk = """[{"slot": 1, "program": null, "savedAt": "x"}]"""
        assertTrue(Migrations.decodeSlotBackups(onDisk).single().uncertain)
        // A current record keeps the flag it has.
        val current = listOf(SlotBackup("ble:AA", 1, program, "y"), SlotBackup("unknown", 2, program, "z", uncertain = true))
        assertEquals(current, Migrations.decodeSlotBackups(BackupCodec.json.encodeToString(ListSerializer(SlotBackup.serializer()), current)))
    }

    @Test
    fun migratedBundleRoundTrips() {
        val migrated = BackupCodec.decode(v1)
        val text = BackupCodec.encode(migrated)
        assertTrue(text.contains("\"schema\": 2"))
        assertEquals(migrated, BackupCodec.decode(text))
    }

    @Test
    fun version1SlotBackupFileLoads() {
        val onDisk = """[{"slot": 1, "program": {"name": "old", "steps": [{"mode": "DRY", "fanStep": 1, "temperature": 60, "hours": 1, "minutes": 0}]}, "savedAt": "x"}]"""
        val list = Migrations.decodeSlotBackups(onDisk)
        assertEquals(listOf(SlotBackup("unknown", 1, program, "x", uncertain = true)), list)
        assertFalse(list.single().empty)
    }

    @Test
    fun version1HistoryFileLoads() {
        val onDisk = """[{"id": "n1", "profileId": "p", "profileName": "P", "startedAt": "s", "wakeAt": "w", "wakeFromAlarm": false}]"""
        assertEquals("unknown", Migrations.decodeHistory(onDisk).single().deviceId)
    }

    @Test
    fun currentFilesLoadUnchanged() {
        val backups = listOf(SlotBackup("demo", 2, null, "x"), SlotBackup("ble:AA", 1, program, "y"))
        val text = BackupCodec.json.encodeToString(ListSerializer(SlotBackup.serializer()), backups)
        assertEquals(backups, Migrations.decodeSlotBackups(text))
        assertTrue(backups.first().empty)
    }
}
