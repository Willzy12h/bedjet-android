package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.PendingEnd
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Task 6 (IR-13, IR-15, IR-16, IR-28, IR-29, IR-30, IR-32): what the app keeps survives crashes, slow files and imports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorageDurabilityTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val dir: File get() = temp.root
    private val now = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, ZoneId.of("Europe/London"))

    private val context: Context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = dir
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun repository(
        writer: CoroutineDispatcher = Dispatchers.Unconfined,
        writeFile: (File, ByteArray) -> Unit = Repository::writeDurably,
    ) = Repository(context, scope, writer, writeFile) { now }

    private fun importable() = BackupBundle(
        createdAt = now.minusDays(2).toString(),
        profiles = listOf(Presets.winterWarm.copy(id = "imported", name = "Imported")),
        settings = Settings(fahrenheit = true, demoMode = false, targetDevice = "ble:11:22", workingSlot = 0),
        history = listOf(NightRecord("n1", "imported", "Imported", now.minusDays(3).toString(), now.minusDays(3).plusHours(8).toString(), true)),
    )

    private val program = StoredProgram("original", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))

    /** Some state of the user's own, on disk, before anything is imported. */
    private suspend fun seeded(): BackupBundle {
        val repo = repository()
        repo.saveProfile(Presets.summerCool.copy(id = "mine", name = "Mine"))
        repo.updateSettings { it.copy(workingSlot = 1, boostMinutes = 20) }
        repo.addRecord(NightRecord("old-night", "mine", "Mine", now.minusDays(1).toString(), now.minusDays(1).plusHours(8).toString(), true))
        repo.recordOriginalSlot("ble:AA:BB", 1, program)
        return repo.bundle().copy(createdAt = "")
    }

    private class ProcessDeath : Error("The process was killed")

    /**
     * Writes normally until [armed], then the process "dies" while writing data file number [killAt] (counting from
     * 1): that write and every write after it fail, as nothing more runs in a killed process.
     */
    private class DyingWriter(private val killAt: Int) : (File, ByteArray) -> Unit {
        var armed = false
        private var dataFiles = 0
        private var dead = false
        override fun invoke(file: File, bytes: ByteArray) {
            if (dead) throw ProcessDeath()
            if (armed && file.name in DATA) {
                dataFiles++
                if (dataFiles == killAt) { dead = true; throw ProcessDeath() }
            }
            Repository.writeDurably(file, bytes)
        }
        companion object { val DATA = setOf("profiles.json", "settings.json", "history.json", "slot-backups.json") }
    }

    // IR-13: an import interrupted by process death is rolled back on the next start

    @Test
    fun anImportKilledAfterTheSecondFileIsRolledBackWhenTheAppStartsAgain() = runBlocking {
        val before = seeded()
        val dying = DyingWriter(killAt = 3)
        val repo = repository(writeFile = dying)
        dying.armed = true
        val thrown = runCatching { repo.restore(importable()) }.exceptionOrNull()
        assertTrue("the import died part-way: $thrown", thrown is ProcessDeath)
        // On disk: the imported profiles and settings, the old history and originals.
        assertTrue(File(dir, "profiles.json").readText().contains("\"imported\""))

        val restarted = repository()
        assertEquals("all of the old state, none of the import", before, restarted.bundle().copy(createdAt = ""))
        assertFalse(File(dir, "restore-pending.json").exists())
        val notice = restarted.notices.value.firstOrNull()
        assertNotNull("the user is told", notice)
        assertEquals(NoticeKind.PROBLEM, notice!!.kind)
        assertTrue(notice.text, notice.text.contains("put back as it was"))
        // The snapshot taken before the import is still there to restore from, and a third start changes nothing.
        assertTrue(restarted.snapshots.list().any { it.label == "Before import" })
        assertEquals(before, repository().bundle().copy(createdAt = ""))
        assertEquals(1, repository().notices.value.size)
    }

    @Test
    fun aCompletedImportLeavesNoMarkerAndSurvivesARestart() = runBlocking {
        seeded()
        val repo = repository()
        repo.restore(importable())
        assertFalse(File(dir, "restore-pending.json").exists())
        val restarted = repository()
        assertEquals(listOf("imported"), restarted.profiles.value.map { it.id })
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
        assertEquals(emptyList<Any>(), restarted.notices.value)
    }

    @Test
    fun aRestoreWhoseRollbackFailsKeepsMemoryAndDiskTheSame() = runBlocking {
        val before = seeded()
        var profileWrites = 0
        val repo = repository(writeFile = { file, bytes ->
            when (file.name) {
                "history.json" -> throw IOException("Disk full")
                // The import's write goes through; putting the old profiles back fails.
                "profiles.json" -> if (++profileWrites > 1) throw IOException("Disk full") else Repository.writeDurably(file, bytes)
                else -> Repository.writeDurably(file, bytes)
            }
        })
        val thrown = runCatching { repo.restore(importable()) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IOException)

        val onDiskProfiles = BackupCodec.json.decodeFromString(ListSerializer(Profile.serializer()), File(dir, "profiles.json").readText())
        val onDiskSettings = BackupCodec.json.decodeFromString(Settings.serializer(), File(dir, "settings.json").readText())
        assertEquals("memory shows what is on disk", onDiskProfiles, repo.profiles.value)
        assertEquals(onDiskSettings, repo.settings.value)
        assertEquals(before.settings, repo.settings.value)
        // Another restore now would replace the record of what to put back, so it waits for the next start.
        val shown = repo.bundle()
        assertTrue(runCatching { repo.restore(importable()) }.exceptionOrNull() is IOException)
        assertEquals(shown, repo.bundle())
        // The next start finishes putting things back.
        assertEquals(before, repository().bundle().copy(createdAt = ""))
    }

    // IR-29: renames are made durable, and half-written snapshots are cleared away

    @Test
    fun aDurableWriteSyncsTheDirectoryAfterTheRename() {
        val file = File(dir, "settings.json")
        val synced = mutableListOf<Pair<File, String>>()
        Repository.writeDurably(file, "new".toByteArray()) { d -> synced += d to file.readText() }
        assertEquals(listOf(dir to "new"), synced)
        // The real sync works here too.
        Repository.writeDurably(file, "newer".toByteArray())
        assertEquals("newer", file.readText())
    }

    @Test
    fun aLeftoverSnapshotTempFileIsDeletedAtStartUp() {
        val snapshots = File(dir, "snapshots").apply { mkdirs() }
        val leftover = File(snapshots, "snap-1000-a.json.tmp").apply { writeText("half a snapshot") }
        repository()
        assertFalse(leftover.exists())
    }

    // IR-15: reading and writing the backup file never holds up the app's own saves

    private fun gatedInput(bytes: ByteArray, entered: CountDownLatch, release: CountDownLatch) = object : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = read(ByteArray(1), 0, 1).let { n -> if (n < 0) -1 else 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            return inner.read(b, off, len)
        }
    }

    @Test
    fun aSlowBackupFileBeingReadDoesNotHoldUpOtherSaves() = runBlocking {
        val repo = repository(writer = Repository.defaultWriter())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://io.github.willzy12h.test/backup.json")
        shadowOf(context.contentResolver).registerInputStream(uri, gatedInput(BackupCodec.encode(importable()).toByteArray(), entered, release))

        val reading = async(Dispatchers.IO) { repo.importFrom(uri) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            withTimeout(2_000) { repo.updateRuntime { it.copy(generation = it.generation + 1) } }
        } finally {
            release.countDown()
        }
        assertEquals(listOf("imported"), reading.await().profiles.map { it.id })
    }

    @Test
    fun aSlowBackupFileBeingSavedDoesNotHoldUpOtherSaves() = runBlocking {
        val repo = repository(writer = Repository.defaultWriter())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = ByteArrayOutputStream()
        val gated = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                saved.write(b, off, len)
            }
        }
        val uri = Uri.parse("content://io.github.willzy12h.test/export.json")
        shadowOf(context.contentResolver).registerOutputStream(uri, gated)

        val saving = async(Dispatchers.IO) { repo.exportTo(uri) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            withTimeout(2_000) { repo.updateRuntime { it.copy(generation = it.generation + 1) } }
        } finally {
            release.countDown()
        }
        saving.await()
        assertTrue(saved.toString().contains("\"schema\""))
    }

    // IR-16: an imported original with no known BedJet is never adopted

    private val version1 = """{"schema":1,"createdAt":"2026-10-20T22:30Z","profiles":[],"settings":{},
        "slotBackups":[{"slot":1,"program":{"name":"theirs","steps":[{"mode":"COOL","fanStep":5,"temperature":44,"hours":2,"minutes":0}]},"savedAt":"2026-10-20T22:30Z"}]}"""

    @Test
    fun anImportedOriginalWithNoKnownBedJetIsNeverAdopted() = runBlocking {
        val repo = repository()
        repo.restore(repo.importFrom(ByteArrayInputStream(version1.toByteArray())))
        val imported = repo.slotBackups.value.single()
        assertEquals("unknown", imported.deviceId)
        assertTrue("kept as a record from the backup", imported.fromBackup)

        assertEquals(emptyList<Any>(), repo.originalsFor("ble:AA:BB"))
        assertNull(repo.originalSlot("ble:AA:BB", 1))
        assertEquals("not counted with no BedJet chosen either", emptyList<Any>(), repo.originalsFor(null))

        // The BedJet's own slot is backed up from what it really holds.
        val live = StoredProgram("live", listOf(ProgramStep(Mode.HEAT, 5, 64, 1, 0)))
        assertEquals(live, repo.recordOriginalSlot("ble:AA:BB", 1, live).program)
        val restarted = repository()
        assertEquals(live, restarted.originalSlot("ble:AA:BB", 1)?.program)
        assertEquals(imported, restarted.slotBackups.value.single { it.fromBackup })
    }

    @Test
    fun anImportCantMarkAnUnknownOriginalAsTheBedJetsOwn() = runBlocking {
        val repo = repository()
        val crafted = version1.replace("\"slot\":1,", "\"slot\":1,\"deviceId\":\"unknown\",\"fromBackup\":false,").replace("\"schema\":1", "\"schema\":2")
        repo.restore(repo.importFrom(ByteArrayInputStream(crafted.toByteArray())))
        assertTrue(repo.slotBackups.value.single().fromBackup)
        assertNull(repo.originalSlot("ble:AA:BB", 1))
    }

    @Test
    fun anImportedOriginalOfAKnownBedJetIsStillItsOwn() = runBlocking {
        val repo = repository()
        val known = version1.replace("\"slot\":1,", "\"slot\":1,\"deviceId\":\"ble:AA:BB\",\"empty\":false,").replace("\"schema\":1", "\"schema\":2")
        repo.restore(repo.importFrom(ByteArrayInputStream(known.toByteArray())))
        assertEquals("theirs", repo.originalSlot("ble:AA:BB", 1)?.program?.name)
        assertFalse(repo.slotBackups.value.single().fromBackup)
    }

    @Test
    fun anImportWithAnInvalidSavedProgrammeIsRefused() = runBlocking {
        val repo = repository()
        val bad = version1.replace("\"fanStep\":5", "\"fanStep\":40")
        val e = runCatching { repo.restore(repo.importFrom(ByteArrayInputStream(bad.toByteArray()))) }.exceptionOrNull()
        assertTrue("$e", e is io.github.willzy12h.nocturne.core.store.BackupException)
        assertEquals(emptyList<Any>(), repo.slotBackups.value)
    }

    // IR-28: an import during a night keeps tonight's history

    @Test
    fun anImportDuringANightKeepsTonightsRecord() = runBlocking {
        val repo = repository()
        val night = ActiveNight("night-1", "preset-summer", now.toString(), now.plusHours(8).toString(), false, 2, now.plusHours(7).toString(), now.plusHours(9).toString())
        repo.setActiveNight(night)
        repo.addRecord(NightRecord("night-1", "preset-summer", "Summer", now.toString(), now.plusHours(8).toString(), false))
        repo.addEvent("night-1", HistoryEvent(now.toString(), EventKind.STARTED, "Started"))
        val tonight = repo.history.value.single()
        // A night being ended keeps its record too, as the end is still to be added to it.
        val ending = NightRecord("night-0", "preset-summer", "Summer", now.minusHours(1).toString(), now.plusHours(7).toString(), false)
        repo.addRecord(ending)
        repo.updateRuntime { it.copy(ending = PendingEnd(NightIdentity("night-0", 0), HistoryEvent(now.toString(), EventKind.ENDED, "Ended"))) }

        // The backup holds an older copy of tonight's record, without tonight's events.
        repo.restore(importable().let { it.copy(history = it.history + tonight.copy(events = emptyList())) })

        assertEquals(setOf("n1", "night-1", "night-0"), repo.history.value.map { it.id }.toSet())
        assertEquals(tonight, repo.history.value.single { it.id == "night-1" })
        repo.addEvent("night-1", HistoryEvent(now.plusHours(8).toString(), EventKind.ENDED, "Ended"))
        assertEquals(2, repository().history.value.single { it.id == "night-1" }.events.size)
    }

    // IR-30: a restore leaves tonight's own settings alone

    @Test
    fun restoreKeepsTonightsOverrideAndChoice() = runBlocking {
        val repo = repository()
        repo.updateSettings { it.chooseTonight("preset-summer", now).setWakeOverride(now.plusHours(9), now) }
        val before = repo.settings.value
        val other = Settings().chooseTonight("imported", now.minusDays(2)).setWakeOverride(now.minusDays(2).plusHours(8), now.minusDays(2))
        repo.restore(importable().let { it.copy(settings = it.settings.copy(tonightChoice = other.tonightChoice, tonightOverride = other.tonightOverride)) })

        assertTrue(repo.settings.value.fahrenheit)
        assertEquals(before.tonightChoice, repo.settings.value.tonightChoice)
        assertEquals(before.tonightOverride, repo.settings.value.tonightOverride)
        assertEquals(before.tonightOverride, repository().settings.value.tonightOverride)
    }

    @Test
    fun restoreMigratesAVersion1Selection() = runBlocking {
        val repo = repository()
        repo.restore(importable().let { it.copy(settings = it.settings.copy(selectedProfileId = "imported")) })
        assertNull(repo.settings.value.selectedProfileId)
        assertEquals(Settings().chooseTonight("imported", now).tonightChoice, repo.settings.value.tonightChoice)
        assertEquals("imported", repo.tonightsProfile(now)?.id)
        assertNull(repository().settings.value.selectedProfileId)
    }

    // IR-32: set-aside copies go into the folder the backup rules keep on the phone

    @Test
    fun anUnreadableFileIsSetAsideInTheExcludedFolder() {
        val garbage = "{garbage"
        File(dir, "profiles.json").writeText(garbage)
        // A copy an earlier version set aside next to the files is moved in too.
        File(dir, "history.json.corrupt-1000").writeText("old garbage")
        val repo = repository()
        assertEquals(listOf("profiles.json"), repo.unreadable.value)
        val aside = File(dir, Repository.SET_ASIDE_DIR).listFiles().orEmpty().map { it.name to it.readText() }.toSet()
        assertEquals(2, aside.size)
        assertTrue(aside.any { it.first.startsWith("profiles.json.corrupt-") && it.second == garbage })
        assertTrue(("history.json.corrupt-1000" to "old garbage") in aside)
        assertEquals(emptyList<String>(), dir.listFiles().orEmpty().map { it.name }.filter { ".corrupt-" in it })
    }
}
