package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupTest {
    private var now = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, ZoneId.of("Europe/London"))

    private fun bundle(name: String = "Mine") = BackupBundle(
        createdAt = now.toString(),
        profiles = listOf(Presets.summerCool.copy(id = "mine", name = name)),
        settings = Settings(workingSlot = 1, fahrenheit = true),
        slotBackups = listOf(SlotBackup("ble:AA:BB", 0, StoredProgram("old", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0))), now.toString())),
    )

    @Test
    fun roundTrip() {
        val original = bundle()
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(original)))
    }

    @Test
    fun loadsVersion1Bundle() {
        // The first schema, with fields added later missing: defaults fill them in.
        val json = """{"schema":1,"createdAt":"2026-10-21T22:30+01:00[Europe/London]","profiles":[],"settings":{"workingSlot":0}}"""
        val decoded = BackupCodec.decode(json)
        assertEquals(0, decoded.settings.workingSlot)
        assertEquals(Settings().notificationActions, decoded.settings.notificationActions)
        assertTrue(decoded.history.isEmpty())
    }

    @Test
    fun newerSchemaIsRefused() {
        val result = runCatching { BackupCodec.decode("""{"schema":99,"createdAt":"x","profiles":[],"settings":{}}""") }
        assertTrue(result.exceptionOrNull() is BackupException)
    }

    @Test
    fun automaticSnapshotsKeepTheLatestTwentyAndManualOnesStay(@TempDir dir: Path) {
        val store = SnapshotStore(dir) { now }
        store.save(bundle("manual"), label = "Before winter", manual = true)
        repeat(25) { i ->
            now = now.plusMinutes(1)
            store.save(bundle("auto $i"), label = null, manual = false)
        }
        val list = store.list()
        assertEquals(20, list.count { !it.manual })
        assertEquals(1, list.count { it.manual })
        assertEquals("Before winter", list.single { it.manual }.label)
        // Newest first, and the oldest automatic ones were the ones dropped.
        assertEquals("auto 24", store.load(list.first().id)!!.profiles.single().name)
        assertFalse(list.any { store.load(it.id)!!.profiles.single().name == "auto 4" })
    }

    @Test
    fun unchangedAutomaticSnapshotIsSkipped(@TempDir dir: Path) {
        val store = SnapshotStore(dir) { now }
        val first = bundle()
        assertTrue(store.saveIfChanged(first))
        now = now.plusMinutes(5)
        assertFalse(store.saveIfChanged(first.copy(createdAt = now.toString())))
        assertTrue(store.saveIfChanged(first.copy(settings = first.settings.copy(fahrenheit = false))))
        assertEquals(2, store.list().size)
    }

    @Test
    fun snapshotFromANewerSchemaDoesNotLoad(@TempDir dir: Path) {
        val store = SnapshotStore(dir) { now }
        val info = store.save(bundle().copy(schema = BackupCodec.SCHEMA + 1), "From the future", manual = true)
        assertNull(store.load(info.id))
        // It is still listed, so it can be seen and deleted.
        assertEquals("From the future", store.list().single().label)
    }

    @Test
    fun listingReadsOnlyTheHeader(@TempDir dir: Path) {
        // A snapshot whose bundle this version can't decode still lists, from its header alone.
        dir.resolve("snap-1000-m.json").toFile().writeText(
            """{"createdAt":"2026-10-21T22:30Z","label":"Odd","manual":true,"bundle":{"schema":1,"profiles":"not a list"}}""",
        )
        val info = SnapshotStore(dir) { now }.list().single()
        assertEquals(SnapshotInfo("snap-1000-m", "2026-10-21T22:30Z", "Odd", true), info)
    }

    @Test
    fun deleteRemovesASnapshot(@TempDir dir: Path) {
        val store = SnapshotStore(dir) { now }
        val info = store.save(bundle(), "x", manual = true)
        store.delete(info.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.load(info.id))
    }
}
