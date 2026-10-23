package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.NightRunner
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Season
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The plain Application keeps the real app's own Repository out of the way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    // Unconfined runs the start-up snapshot check inline, so its effect is visible straight away.
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

    /** Writes run inline by default here, so tests see them at once; [writer] can be the real one. */
    private fun repository(
        writer: CoroutineDispatcher = Dispatchers.Unconfined,
        writeFile: (File, ByteArray) -> Unit = Repository::writeDurably,
    ) = Repository(context, scope, writer, writeFile) { now }

    /** A writer that fails for [name] and works for everything else. */
    private fun failingFor(name: String): (File, ByteArray) -> Unit = { file, bytes ->
        if (file.name == name) throw IOException("Disk full") else Repository.writeDurably(file, bytes)
    }

    private val program = StoredProgram("original", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))

    private fun backup(slot: Int, name: String, device: String = "ble:AA:BB") =
        SlotBackup(device, slot, StoredProgram(name, listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0))), now.toString())

    @Test
    fun valuesSurviveARestart() = runBlocking {
        val first = repository()
        val mine = Presets.summerCool.copy(id = "mine", name = "Mine")
        first.saveProfile(mine)
        first.updateSettings { it.copy(fahrenheit = true, workingSlot = 2) }
        backup(1, "original").let { first.recordOriginalSlot(it.deviceId, it.slot, it.program) }

        val second = repository()
        assertEquals(mine, second.profile("mine"))
        assertTrue(second.settings.value.fahrenheit)
        assertEquals(2, second.settings.value.workingSlot)
        assertEquals(listOf(backup(1, "original")), second.slotBackups.value)
        assertEquals(emptyList<String>(), second.unreadable.value)
    }

    @Test
    fun corruptProfilesAreKeptAsideAndReportedNotOverwritten() = runBlocking {
        val garbage = """[{"id":"mine","name":"Mine","steps":[{"mode":"PLASMA"""
        File(dir, "profiles.json").writeText(garbage)

        val repo = repository()
        assertEquals(listOf("profiles.json"), repo.unreadable.value)
        val aside = File(dir, Repository.SET_ASIDE_DIR).listFiles().orEmpty().filter { it.name.startsWith("profiles.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals(garbage, aside.single().readText())

        // An edit after that writes a fresh file; the user's original stays where it was put.
        repo.saveProfile(Presets.summerCool.copy(id = "new", name = "New"))
        assertEquals(garbage, aside.single().readText())
    }

    @Test
    fun profilesWithAnUnknownSeasonAfterADowngradeAreKeptAside() {
        val mine = Presets.summerCool.copy(id = "mine", name = "Mine", season = Season.WINTER)
        val text = BackupCodec.json.encodeToString(ListSerializer(Profile.serializer()), listOf(mine)).replace("\"WINTER\"", "\"MONSOON\"")
        File(dir, "profiles.json").writeText(text)

        val repo = repository()
        assertEquals(listOf("profiles.json"), repo.unreadable.value)
        assertEquals(text, File(dir, Repository.SET_ASIDE_DIR).listFiles().orEmpty().single { it.name.startsWith("profiles.json.corrupt-") }.readText())
        assertNull(repo.profile("mine"))
    }

    @Test
    fun noAutomaticSnapshotWhileAFileIsUnreadable() {
        File(dir, "settings.json").writeText("{ not json")
        val repo = repository()
        assertEquals(listOf("settings.json"), repo.unreadable.value)
        assertTrue(repo.snapshots.list().isEmpty())
    }

    @Test
    fun firstLaunchTakesAnAutomaticSnapshot() {
        val repo = repository()
        assertEquals(1, repo.snapshots.list().count { !it.manual })
    }

    @Test
    fun restoreKeepsCurrentSlotBackupsAndAddsMissingOnes() = runBlocking {
        val repo = repository()
        backup(1, "user's own").let { repo.recordOriginalSlot(it.deviceId, it.slot, it.program) }
        val older = repo.bundle().copy(slotBackups = listOf(backup(1, "nocturne's"), backup(2, "slot two"), backup(1, "demo's", "demo")))

        repo.restore(older)
        assertEquals(listOf(backup(1, "user's own"), backup(2, "slot two"), backup(1, "demo's", "demo")), repo.slotBackups.value)
    }

    // Durable before visible

    @Test
    fun aFailedWriteKeepsTheOldValueAndThrows() {
        val repo = repository(writeFile = failingFor("settings.json"))
        val e = assertThrows(IOException::class.java) { runBlocking { repo.updateSettings { it.copy(fahrenheit = true) } } }
        assertEquals("Disk full", e.message)
        assertFalse(repo.settings.value.fahrenheit)
        assertFalse(repository().settings.value.fahrenheit)
    }

    @Test
    fun theNewValueIsOnDiskBeforeItIsPublished() = runBlocking {
        lateinit var repo: Repository
        var publishedWhileWriting: Boolean? = null
        repo = repository(writeFile = { file, bytes ->
            Repository.writeDurably(file, bytes)
            if (file.name == "settings.json") publishedWhileWriting = repo.settings.value.fahrenheit
        })
        repo.updateSettings { it.copy(fahrenheit = true) }
        assertEquals(false, publishedWhileWriting)
        assertTrue(repo.settings.value.fahrenheit)
    }

    @Test
    fun writesHappenOffTheMainThread() = runBlocking {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val repo = repository(writer = Repository.defaultWriter(), writeFile = { file, bytes ->
            threads += Thread.currentThread()
            Repository.writeDurably(file, bytes)
        })
        assertSame(Looper.getMainLooper().thread, Thread.currentThread())
        repo.updateSettings { it.copy(fahrenheit = true) }
        repo.saveProfile(Presets.summerCool.copy(id = "mine"))
        repo.recordOriginalSlot("demo", 2, null)
        assertTrue(threads.size >= 3)
        assertTrue(threads.none { it === Looper.getMainLooper().thread })
        assertTrue(repo.settings.value.fahrenheit)
    }

    // Unreadable files

    @Test
    fun anUnreadableFileIsStillReportedAfterARestartUntilDismissed() = runBlocking {
        File(dir, "history.json").writeText("[{ broken")
        assertEquals(listOf("history.json"), repository().unreadable.value)

        val second = repository()
        assertEquals(listOf("history.json"), second.unreadable.value)
        assertEquals(1, File(dir, Repository.SET_ASIDE_DIR).listFiles().orEmpty().count { it.name.startsWith("history.json.corrupt-") })

        second.dismissUnreadable()
        assertEquals(emptyList<String>(), second.unreadable.value)
        assertEquals(emptyList<String>(), repository().unreadable.value)
    }

    @Test
    fun theUnreadableReportIsSavedOffTheMainThread() {
        File(dir, "settings.json").writeText("{ not json")
        val written = CountDownLatch(1)
        var thread: Thread? = null
        val repo = repository(writer = Repository.defaultWriter(), writeFile = { file, bytes ->
            Repository.writeDurably(file, bytes)
            if (file.name == "unreadable.json") { thread = Thread.currentThread(); written.countDown() }
        })
        assertEquals(listOf("settings.json"), repo.unreadable.value)
        assertTrue(written.await(5, TimeUnit.SECONDS))
        assertNotSame(Looper.getMainLooper().thread, thread)
        assertEquals(listOf("settings.json"), repository().unreadable.value)
    }

    @Test
    fun anUnreadableReportThatCantBeSavedIsShown() {
        File(dir, "settings.json").writeText("{ not json")
        val repo = repository(writeFile = failingFor("unreadable.json"))
        assertEquals(listOf("settings.json", "unreadable.json"), repo.unreadable.value)
    }

    @Test
    fun aRestoreClearsTheUnreadableReport() = runBlocking {
        File(dir, "settings.json").writeText("{ not json")
        val repo = repository()
        repo.restore(repo.bundle())
        assertEquals(emptyList<String>(), repo.unreadable.value)
        assertEquals(emptyList<String>(), repository().unreadable.value)
    }

    // Original slots

    @Test
    fun theFirstOriginalOfASlotIsKeptOnRetry() = runBlocking {
        val repo = repository()
        val first = repo.recordOriginalSlot("ble:AA:BB", 2, program)
        assertEquals(SlotBackup("ble:AA:BB", 2, program, now.toString(), empty = false), first)

        // A retry finds the app's own program in the slot; the user's original stays.
        val retry = repo.recordOriginalSlot("ble:AA:BB", 2, StoredProgram("Spring/Autumn", emptyList()))
        assertEquals(first, retry)
        assertEquals(first, repo.originalSlot("ble:AA:BB", 2))
        assertEquals(first, repository().originalSlot("ble:AA:BB", 2))
    }

    @Test
    fun anEmptySlotIsRecordedAsEmpty() = runBlocking {
        val repo = repository()
        val stored = repo.recordOriginalSlot("ble:AA:BB", 0, null)
        assertTrue(stored.empty)
        assertNull(stored.program)
        assertEquals(stored, repository().originalSlot("ble:AA:BB", 0))
    }

    @Test
    fun recordingAnOriginalThrowsWhenItCantBeSaved() {
        val repo = repository(writeFile = failingFor("slot-backups.json"))
        assertThrows(IOException::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB", 2, program) } }
        assertNull(repo.originalSlot("ble:AA:BB", 2))
        assertNull(repository().originalSlot("ble:AA:BB", 2))
    }

    @Test
    fun demoAndRealOriginalsAreSeparate() = runBlocking {
        val repo = repository()
        val demo = repo.recordOriginalSlot("demo", 2, StoredProgram("demo", emptyList()))
        val real = repo.recordOriginalSlot("ble:AA:BB", 2, program)
        assertNotEquals(demo, real)
        assertEquals(demo, repo.originalSlot("demo", 2))
        assertEquals(real, repo.originalSlot("ble:AA:BB", 2))
        assertNull(repo.originalSlot("ble:CC:DD", 2))

        backup(2, "another demo", "demo").let { repo.recordOriginalSlot(it.deviceId, it.slot, it.program) }
        assertEquals(real, repo.originalSlot("ble:AA:BB", 2))
    }

    // An unreadable slot-backups file (IR-03)

    @Test
    fun anUnreadableSlotBackupFileBlocksRecordingOriginals() = runBlocking {
        File(dir, "slot-backups.json").writeText("{garbage")
        val repo = repository()
        assertEquals(listOf("slot-backups.json"), repo.unreadable.value)

        assertThrows(SlotBackupsUnreadable::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB", 2, program) } }
        assertThrows(SlotBackupsUnreadable::class.java) { runBlocking { repo.originalsFor("ble:AA:BB") } }
        assertThrows(SlotBackupsUnreadable::class.java) { runBlocking { repo.originalsFor(null) } }
        assertFalse("nothing written over the unreadable originals", File(dir, "slot-backups.json").exists())
        assertEquals(emptyList<SlotBackup>(), repo.slotBackups.value)
        // Still blocked after a restart: the report survives it.
        assertThrows(SlotBackupsUnreadable::class.java) { runBlocking { repository().recordOriginalSlot("ble:AA:BB", 2, program) } }

        repo.dismissUnreadable()
        val recorded = repo.recordOriginalSlot("ble:AA:BB", 2, program)
        assertEquals(SlotBackup("ble:AA:BB", 2, program, now.toString()), recorded)
        assertEquals(listOf(recorded), repository().slotBackups.value)
    }

    @Test
    fun restoreLetsASnapshotsOriginalsWinOverOnesRecordedAfterTheLoss() = runBlocking {
        val before = repository()
        before.recordOriginalSlot("ble:AA:BB", 2, program)
        before.snapshotNow("Good")
        val good = before.snapshots.list().first { it.manual }

        File(dir, "slot-backups.json").writeText("{garbage")
        val repo = repository()
        repo.dismissUnreadable()
        // After the loss the slot holds Nocturne's own program, and that is what gets recorded.
        val fake = repo.recordOriginalSlot("ble:AA:BB", 2, StoredProgram("Spring/Autumn", emptyList()))
        val fresh = repo.recordOriginalSlot("ble:AA:BB", 1, null)
        assertEquals("Spring/Autumn", fake.program?.name)

        assertTrue(repo.restoreSnapshot(good.id))
        assertEquals(listOf(SlotBackup("ble:AA:BB", 2, program, now.toString()), fresh).toSet(), repo.slotBackups.value.toSet())
        assertEquals(program, repo.originalSlot("ble:AA:BB", 2)?.program)
        assertEquals(program, repository().originalSlot("ble:AA:BB", 2)?.program)
    }

    @Test
    fun restoringASnapshotWithoutOriginalsDuringTheLossStillLetsAnOlderGoodOneWin() = runBlocking {
        val before = repository()
        before.recordOriginalSlot("ble:AA:BB", 2, program)
        before.snapshotNow("Good")
        val good = before.snapshots.list().first { it.manual }

        File(dir, "slot-backups.json").writeText("{garbage")
        val repo = repository()
        // Taken while the file is unreadable, so it holds no originals: the newest and likeliest to be picked.
        repo.snapshotNow("During the loss")
        val empty = repo.snapshots.list().first { it.manual && it.id != good.id }
        assertTrue(repo.restoreSnapshot(empty.id))
        assertEquals(emptyList<String>(), repo.unreadable.value)

        val fake = repo.recordOriginalSlot("ble:AA:BB", 2, StoredProgram("Spring/Autumn", emptyList()))
        assertEquals("Spring/Autumn", fake.program?.name)
        // After a restart too: the time the loss was accepted is saved.
        val restarted = repository()
        assertTrue(restarted.restoreSnapshot(good.id))
        assertEquals(program, restarted.originalSlot("ble:AA:BB", 2)?.program)
        assertEquals(program, repository().originalSlot("ble:AA:BB", 2)?.program)
    }

    // Originals saved before devices were recorded

    private val migrated = SlotBackup("unknown", 2, program, "2026-10-20T22:30Z", uncertain = true)

    /** A version 1 backup of slot 3 (index 2), as an upgrade finds it. */
    private fun version1Original() = File(dir, "slot-backups.json").writeText(
        """[{"slot":2,"program":{"name":"original","steps":[{"mode":"DRY","fanStep":1,"temperature":60,"hours":1,"minutes":0}]},"savedAt":"2026-10-20T22:30Z"}]""",
    )

    @Test
    fun aMigratedOriginalIsAdoptedByTheFirstRealBedJet() = runBlocking {
        version1Original()
        val repo = repository()
        // The slot now holds Nocturne's own program from earlier nights.
        val link = DemoBedJet(listOf(null, null, StoredProgram("Spring/Autumn", listOf(ProgramStep(Mode.HEAT, 7, 64, 0, 20))))) { now }
        link.connect()
        val runner = NightRunner(BedJetController(link) { now }) { now }

        repo.originalsFor("ble:AA:BB")
        val change = (runner.prepareStart(Presets.springAutumn, null, 2, "ble:AA:BB", null, null) as io.github.willzy12h.nocturne.core.night.Preparation.Ready).change
        runner.apply(change, { repo.activeNight.value }, publish = { repo.setActiveNight(it) }, beforeWrite = {
            repo.recordOriginalSlot("ble:AA:BB", 2, BedJetController(link) { now }.storedPrograms()[2])
        })
        val adopted = migrated.copy(deviceId = "ble:AA:BB")
        assertEquals(adopted, repo.originalSlot("ble:AA:BB", 2))
        assertEquals(listOf(adopted), repo.slotBackups.value)
        assertEquals(listOf(adopted), repository().slotBackups.value)
        // Only one BedJet can have been the one it came from.
        assertNull(repo.originalSlot("ble:CC:DD", 2))
    }

    @Test
    fun recordingAnOriginalAdoptsAMigratedOne() = runBlocking {
        version1Original()
        val repo = repository()
        assertEquals(migrated, repo.originalSlot("ble:AA:BB", 2))
        val stored = repo.recordOriginalSlot("ble:AA:BB", 2, StoredProgram("Spring/Autumn", emptyList()))
        assertEquals(migrated.copy(deviceId = "ble:AA:BB"), stored)
        assertEquals(listOf(stored), repository().slotBackups.value)
    }

    @Test
    fun theDemoUnitAndNoTargetDoNotAdoptAMigratedOriginal() = runBlocking {
        version1Original()
        val repo = repository()
        assertEquals(emptyList<SlotBackup>(), repo.originalsFor("demo"))
        assertNull(repo.originalSlot("demo", 2))
        // With no BedJet chosen the migrated original still counts, so nothing new is saved over it.
        assertEquals(listOf(migrated), repo.originalsFor(null))
        assertEquals(listOf(migrated), repository().slotBackups.value)
    }

    @Test
    fun restoreDoesNotAddAnOlderBackupOfASlotAlreadyCovered() = runBlocking {
        version1Original()
        val repo = repository()
        repo.restore(repo.bundle().copy(slotBackups = listOf(backup(2, "nocturne's"))))
        assertEquals(listOf(migrated), repo.slotBackups.value)

        repo.originalsFor("ble:AA:BB")
        repo.restore(repo.bundle().copy(slotBackups = listOf(migrated.copy(program = StoredProgram("older", emptyList())))))
        assertEquals(listOf(migrated.copy(deviceId = "ble:AA:BB")), repo.slotBackups.value)
    }

    // Import

    private fun importable() = BackupBundle(
        createdAt = now.minusDays(2).toString(),
        profiles = listOf(Presets.winterWarm.copy(id = "imported", name = "Imported")),
        settings = Settings(fahrenheit = true, demoMode = false, targetDevice = "ble:11:22", workingSlot = 0),
        history = listOf(NightRecord("n1", "imported", "Imported", now.minusDays(3).toString(), now.minusDays(3).plusHours(8).toString(), true)),
    )

    @Test
    fun anInvalidImportIsRefusedBeforeAnythingChanges() = runBlocking {
        val repo = repository()
        val before = repo.bundle()
        val snapshots = repo.snapshots.list()
        val bad = importable().copy(profiles = listOf(Presets.winterWarm.copy(start = listOf(Phase(Mode.HEAT, 70, 50, 0)))))

        val e = runCatching { repo.restore(bad) }.exceptionOrNull()
        assertTrue(e is BackupException)
        assertEquals(BackupValidator.validate(bad), (e as BackupException).errors)
        assertEquals(before, repo.bundle())
        assertEquals(snapshots, repo.snapshots.list())
    }

    @Test
    fun aValidImportKeepsTheDeviceTokenAndNight() = runBlocking {
        val repo = repository()
        repo.updateSettings { it.copy(demoMode = true, targetDevice = "ble:AA:BB") }
        repo.updateToken = "ghp_secret"
        val night = ActiveNight("night-1", "preset-summer", now.toString(), now.plusHours(8).toString(), false, 2, now.plusHours(7).toString(), now.plusHours(9).toString())
        repo.setActiveNight(night)
        repo.recordOriginalSlot("ble:AA:BB", 2, program)

        repo.restore(importable())

        assertEquals(listOf("imported"), repo.profiles.value.map { it.id })
        assertTrue(repo.settings.value.fahrenheit)
        assertEquals(0, repo.settings.value.workingSlot)
        assertTrue(repo.settings.value.demoMode)
        assertEquals("ble:AA:BB", repo.settings.value.targetDevice)
        assertEquals("ghp_secret", repo.updateToken)
        assertEquals(night, repo.activeNight.value)
        assertEquals(listOf("n1"), repo.history.value.map { it.id })
        assertEquals(SlotBackup("ble:AA:BB", 2, program, now.toString()), repo.originalSlot("ble:AA:BB", 2))

        // What was there before is kept, and everything is on disk.
        val before = repo.snapshots.list().single { it.label == "Before import" }
        assertTrue(before.manual)
        assertEquals(listOf("preset-summer", "preset-winter", "preset-spring-autumn", "preset-hot-sleeper", "preset-warm-start"), repo.snapshots.load(before.id)!!.profiles.map { it.id })
        val restarted = repository()
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
        assertEquals(night, restarted.activeNight.value)
    }

    @Test
    fun aFailedImportLeavesEverythingAsItWas() {
        val repo = repository(writeFile = failingFor("history.json"))
        val before = repo.bundle()
        assertThrows(IOException::class.java) { runBlocking { repo.restore(importable()) } }
        assertEquals(before, repo.bundle())
        assertEquals(before.copy(createdAt = ""), repository().bundle().copy(createdAt = ""))
    }

    @Test
    fun importedHistoryOlderThanSixtyDaysIsDropped() = runBlocking {
        val repo = repository()
        val old = NightRecord("old", "p", "P", now.minusDays(61).toString(), now.minusDays(61).toString(), false)
        repo.restore(importable().let { it.copy(history = it.history + old) })
        assertEquals(listOf("n1"), repo.history.value.map { it.id })
    }

    @Test
    fun anOversizedFileIsRefusedBeforeDecoding() {
        val repo = repository()
        val huge = ByteArray(BackupValidator.MAX_BYTES + 1) { ' '.code.toByte() }
        val e = assertThrows(BackupException::class.java) { repo.importFrom(ByteArrayInputStream(huge)) }
        assertTrue(e.message!!.contains("5 MB"))
    }

    @Test
    fun aVersion1BackupImports() = runBlocking {
        val repo = repository()
        val v1 = """{"schema":1,"createdAt":"2026-10-20T22:30Z","profiles":[],"settings":{},
            "slotBackups":[{"slot":1,"program":{"name":"old","steps":[]},"savedAt":"2026-10-20T22:30Z"}]}"""
        repo.restore(repo.importFrom(ByteArrayInputStream(v1.toByteArray())))
        assertEquals("unknown", repo.slotBackups.value.single().deviceId)
    }

    // Migrations and retention

    @Test
    fun version1FilesOnDiskLoad() {
        File(dir, "slot-backups.json").writeText(
            """[{"slot":2,"program":{"name":"original","steps":[{"mode":"DRY","fanStep":1,"temperature":60,"hours":1,"minutes":0}]},"savedAt":"2026-10-20T22:30Z"}]""",
        )
        File(dir, "history.json").writeText(
            """[{"id":"n1","profileId":"p","profileName":"P","startedAt":"${now.minusDays(1)}","wakeAt":"${now.minusDays(1)}","wakeFromAlarm":true}]""",
        )
        val repo = repository()
        assertEquals(emptyList<String>(), repo.unreadable.value)
        assertEquals(SlotBackup("unknown", 2, program, "2026-10-20T22:30Z", uncertain = true), repo.slotBackups.value.single())
        assertEquals("unknown", repo.history.value.single().deviceId)
    }

    @Test
    fun historyKeepsSixtyDays() = runBlocking {
        val repo = repository()
        repo.addRecord(NightRecord("old", "p", "P", now.minusDays(61).toString(), now.toString(), false))
        repo.addRecord(NightRecord("recent", "p", "P", now.minusDays(59).toString(), now.toString(), false))
        assertEquals(listOf("recent"), repo.history.value.map { it.id })
    }

    // Secrets

    @Test
    fun theUpdateTokenIsNeverExportedOrSnapshotted() = runBlocking {
        val repo = repository()
        repo.updateToken = "ghp_secret"
        assertFalse(BackupCodec.encode(repo.bundle()).contains("ghp_secret"))
        repo.snapshotNow("Mine")
        val file = File(dir, "export.json")
        repo.exportTo(Uri.fromFile(file))
        assertTrue(file.readText().contains("\"schema\""))
        assertFalse(file.readText().contains("ghp_secret"))
        assertFalse(dir.walk().filter { it.isFile }.any { it.readText().contains("ghp_secret") })
    }
}
