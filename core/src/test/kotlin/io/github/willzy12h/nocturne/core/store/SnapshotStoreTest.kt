package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Presets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.exists

class SnapshotStoreTest {
    private var now = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, ZoneId.of("Europe/London"))

    private fun bundle() = BackupBundle(createdAt = now.toString(), profiles = listOf(Presets.summerCool), settings = Settings())

    @Test
    fun listingStopsAtTheHeader(@TempDir dir: Path) {
        // Cut off part-way through the bundle: only a reader that stops after the header can list these.
        dir.resolve("snap-1000-a.json").toFile().writeText(
            """{"createdAt":"2026-10-21T22:30Z","label":null,"manual":false,"bundle":{"schema":1,"profiles":[{"id":"x""",
        )
        dir.resolve("snap-2000-m.json").toFile().writeText(
            """{ "createdAt" : "2026-10-22T22:30Z", "label" : "Quote \" and \\ and é", "manual" : true, "bundle" : """,
        )
        val list = SnapshotStore(dir) { now }.list()
        assertEquals(
            listOf(
                SnapshotInfo("snap-2000-m", "2026-10-22T22:30Z", "Quote \" and \\ and é", true),
                SnapshotInfo("snap-1000-a", "2026-10-21T22:30Z", null, false),
            ),
            list,
        )
    }

    @Test
    fun aFileWithoutAHeaderIsNotListed(@TempDir dir: Path) {
        dir.resolve("snap-1000-a.json").toFile().writeText("garbage")
        dir.resolve("snap-2000-a.json").toFile().writeText("""{"bundle":{}}""")
        assertTrue(SnapshotStore(dir) { now }.list().isEmpty())
    }

    @Test
    fun aVersion1SnapshotLoads(@TempDir dir: Path) {
        dir.resolve("snap-1000-m.json").toFile().writeText(
            """{"createdAt":"2026-10-21T22:30Z","label":"Old","manual":true,"bundle":{"schema":1,"createdAt":"2026-10-21T22:30Z","profiles":[],"settings":{},
               "slotBackups":[{"slot":2,"program":{"name":"old","steps":[]},"savedAt":"2026-10-21T22:30Z"}]}}""",
        )
        val bundle = SnapshotStore(dir) { now }.load("snap-1000-m")!!
        assertEquals(BackupCodec.SCHEMA, bundle.schema)
        assertEquals("unknown", bundle.slotBackups.single().deviceId)
    }

    @Test
    fun savingLeavesOnlyTheFinishedFile(@TempDir dir: Path) {
        val store = SnapshotStore(dir) { now }
        val info = store.save(bundle(), "Mine", manual = true)
        assertEquals(listOf("${info.id}.json"), dir.listDirectoryEntries().map { it.name })
        assertEquals(bundle(), store.load(info.id))
    }

    @Test
    fun restartDiscardsAnInterruptedSnapshotButKeepsCompletedCopies(@TempDir dir: Path) {
        val first = SnapshotStore(dir) { now }
        val completed = first.save(bundle(), "Before interruption", manual = true)
        val abandoned = dir.resolve("snap-9999-a.json.tmp")
        abandoned.toFile().writeText("partial private snapshot")
        val unrelated = dir.resolve("notes.json.tmp")
        unrelated.toFile().writeText("leave me alone")

        val restarted = SnapshotStore(dir) { now }
        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
        assertEquals(listOf(completed), restarted.list())
        assertEquals(bundle(), restarted.load(completed.id))

        now = now.plusMinutes(1)
        val next = restarted.save(bundle(), "After interruption", manual = true)
        assertEquals(bundle(), SnapshotStore(dir) { now }.load(next.id))
    }

    @Test
    fun manualSnapshotsAreKeptHoweverManyAutomaticOnesFollow(@TempDir dir: Path) {
        val store = SnapshotStore(dir) { now }
        repeat(3) { now = now.plusMinutes(1); store.save(bundle(), "Manual $it", manual = true) }
        repeat(SnapshotStore.KEEP_AUTOMATIC + 5) { now = now.plusMinutes(1); store.save(bundle(), null, manual = false) }
        assertEquals(20, SnapshotStore.KEEP_AUTOMATIC)
        assertEquals(3, store.list().count { it.manual })
        assertEquals(20, store.list().count { !it.manual })
    }
}
