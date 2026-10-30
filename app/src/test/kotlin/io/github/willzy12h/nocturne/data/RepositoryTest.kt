package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.core.util.AtomicFile
import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.night.PendingEnd
import io.github.willzy12h.nocturne.core.night.NightRunner
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Season
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.MorningComfort
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
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
import java.io.InputStream
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
        openInput: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
        openOutput: (Uri) -> java.io.OutputStream? = { context.contentResolver.openOutputStream(it, "wt") },
        deleteFile: (File) -> Unit = { AtomicFile(it).delete() },
    ) = Repository(context, scope, writer, writeFile, openInput = openInput, openOutput = openOutput, deleteFile = deleteFile) { now }

    /** A writer that fails for [name] and works for everything else. */
    private fun failingFor(name: String): (File, ByteArray) -> Unit = { file, bytes ->
        if (file.name == name) throw IOException("Disk full") else Repository.writeDurably(file, bytes)
    }

    private val program = StoredProgram("original", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))

    private fun backup(slot: Int, name: String, device: String = "ble:AA:BB:CC:DD:EE:FF") =
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

    /** IR-29: the rename that replaces a file is itself made durable by syncing the directory after it. */
    @Test
    fun aDurableWriteSyncsTheDirectoryAfterTheRename() {
        val file = File(dir, "settings.json")
        val synced = mutableListOf<Pair<File, String>>()
        Repository.writeDurably(file, "new".toByteArray()) { d -> synced += d to file.readText() }
        assertEquals(listOf(dir.absoluteFile to "new"), synced)
        // The real sync works here too.
        Repository.writeDurably(file, "newer".toByteArray())
        assertEquals("newer", file.readText())
    }

    @Test
    fun corruptProfilesAreKeptAsideAndReportedNotOverwritten() = runBlocking {
        val garbage = """[{"id":"mine","name":"Mine","steps":[{"mode":"PLASMA"""
        File(dir, "profiles.json").writeText(garbage)

        val repo = repository()
        assertEquals(listOf("profiles.json"), repo.unreadable.value)
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("profiles.json.corrupt-") }
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
        assertEquals(text, dir.listFiles().orEmpty().single { it.name.startsWith("profiles.json.corrupt-") }.readText())
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
        assertEquals(listOf(backup(1, "user's own"), backup(2, "slot two").copy(imported = true),
            backup(1, "demo's", "demo").copy(imported = true)), repo.slotBackups.value)
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
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.startsWith("history.json.corrupt-") })

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
        val first = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program)
        assertEquals(SlotBackup("ble:AA:BB:CC:DD:EE:FF", 2, program, now.toString(), empty = false), first)

        // A retry finds the app's own program in the slot; the user's original stays.
        val retry = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, StoredProgram("Spring/Autumn", emptyList()))
        assertEquals(first, retry)
        assertEquals(first, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertEquals(first, repository().originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
    }

    @Test
    fun anEmptySlotIsRecordedAsEmpty() = runBlocking {
        val repo = repository()
        val stored = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 0, null)
        assertTrue(stored.empty)
        assertNull(stored.program)
        assertEquals(stored, repository().originalSlot("ble:AA:BB:CC:DD:EE:FF", 0))
    }

    @Test
    fun recordingAnOriginalThrowsWhenItCantBeSaved() {
        val repo = repository(writeFile = failingFor("slot-backups.json"))
        assertThrows(IOException::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        assertNull(repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertNull(repository().originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
    }

    @Test
    fun corruptOriginalsStayBlockedAfterDismissalAndRestartUntilTheirExactBackupIsRestored() = runBlocking {
        File(dir, "slot-backups.json").writeText("[{ broken")
        val first = repository()
        assertEquals(listOf("slot-backups.json"), first.unreadable.value)
        assertTrue(File(dir, "slot-backups-integrity.json").exists())
        first.dismissUnreadable()

        val second = repository()
        assertTrue(second.unreadable.value.isEmpty())
        assertThrows(IOException::class.java) { runBlocking { second.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        assertNull(second.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertFalse(File(dir, "slot-backups.json").exists())

        // A restore with no original for this BedJet cannot silently authorise the current programme as original.
        second.restore(second.bundle())
        assertThrows(IOException::class.java) { runBlocking { second.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        val recovered = backup(2, "known original")
        second.restore(second.bundle().copy(slotBackups = listOf(recovered)))
        assertEquals(recovered.copy(imported = true), second.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program))
        assertThrows(IOException::class.java) { runBlocking { second.recordOriginalSlot("ble:11:22:33:44:55:66", 2, program) } }
        assertThrows(IOException::class.java) { runBlocking { second.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 1, program) } }
        assertEquals(recovered.copy(imported = true), repository().recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program))
    }

    @Test
    fun failedIntegrityMarkerWriteLeavesCorruptOriginalForTheNextRestart() {
        val corrupt = "[{ broken"
        File(dir, "slot-backups.json").writeText(corrupt)
        val first = repository(writeFile = failingFor("slot-backups-integrity.json"))
        assertThrows(IOException::class.java) { runBlocking { first.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        assertEquals(corrupt, File(dir, "slot-backups.json").readText())
        assertThrows(IOException::class.java) { runBlocking { repository().recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
    }

    @Test
    fun acceptingTheLossOfUnreadableOriginalsLiftsTheBarrierAndMarksLaterOnesUncertain() = runBlocking {
        File(dir, "slot-backups.json").writeText("[{ broken")
        val repo = repository()
        assertTrue(repo.originalsBlocked.value)
        // A plain dismissal only hides the notice.
        repo.dismissUnreadable()
        assertTrue(repo.originalsBlocked.value)
        assertThrows(IOException::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }

        repo.dismissUnreadable(acceptLostOriginals = true)
        assertFalse(repo.originalsBlocked.value)
        assertFalse(File(dir, "slot-backups-integrity.json").exists())
        assertEquals("\"$now\"", File(dir, "slot-backups-loss-accepted.json").readText())
        // What the slot holds now is saved, marked as possibly the app's own programme.
        val recorded = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program)
        assertEquals(SlotBackup("ble:AA:BB:CC:DD:EE:FF", 2, program, now.toString(), uncertain = true), recorded)

        val restarted = repository()
        assertFalse(restarted.originalsBlocked.value)
        assertEquals(recorded, restarted.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertTrue(restarted.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 1, null).uncertain)
    }

    /** An original saved after the accepted loss may be the app's own programme, so a backup's exact one replaces it. */
    @Test
    fun aBackupsExactOriginalReplacesOneSavedAfterTheAcceptedLoss() = runBlocking {
        File(dir, "slot-backups.json").writeText("[{ broken")
        val repo = repository()
        repo.dismissUnreadable(acceptLostOriginals = true)
        val unsure = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, StoredProgram("Spring/Autumn", emptyList()))
        val unsureOther = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 1, null)
        assertTrue(unsure.uncertain)

        val known = backup(2, "known original")
        repo.restore(repo.bundle().copy(slotBackups = listOf(known)))
        assertEquals(known.copy(imported = true), repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        // A slot the backup has nothing for keeps what was saved.
        assertEquals(unsureOther, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 1))
        assertEquals(known.copy(imported = true), repository().originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
    }

    @Test
    fun aBarrierThatCantBeRemovedStaysInForce() {
        File(dir, "slot-backups.json").writeText("[{ broken")
        val repo = repository(deleteFile = { file -> if (file.name != "slot-backups-integrity.json") AtomicFile(file).delete() })
        assertThrows(IOException::class.java) { runBlocking { repo.dismissUnreadable(acceptLostOriginals = true) } }
        assertTrue(repo.originalsBlocked.value)
        assertThrows(IOException::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        assertTrue(repository().originalsBlocked.value)
    }

    /** A damaged record of the accepted loss is read as accepted now: later originals stay uncertain, older ones win. */
    @Test
    fun aDamagedLossRecordCountsFromNowAndIsReported() = runBlocking {
        val earlier = SlotBackup("ble:AA:BB:CC:DD:EE:FF", 2, program, now.minusHours(1).toString(), uncertain = true)
        File(dir, "slot-backups.json").writeText(BackupCodec.json.encodeToString(ListSerializer(SlotBackup.serializer()), listOf(earlier)))
        File(dir, "slot-backups-loss-accepted.json").writeText("{bad")
        val repo = repository()
        assertEquals(listOf("slot-backups-loss-accepted.json"), repo.unreadable.value)
        assertEquals("\"$now\"", File(dir, "slot-backups-loss-accepted.json").readText())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("slot-backups-loss-accepted.json.corrupt-") })
        assertTrue(repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 1, null).uncertain)
        // Saved before the time it is now read as, so a backup's record of the slot doesn't replace it.
        repo.restore(repo.bundle().copy(slotBackups = listOf(backup(2, "from a backup"))))
        assertEquals(earlier, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
    }

    @Test
    fun acceptingTheLossChangesNothingWhileTheOriginalsAreReadable() = runBlocking {
        val repo = repository()
        repo.dismissUnreadable(acceptLostOriginals = true)
        assertFalse(File(dir, "slot-backups-loss-accepted.json").exists())
        assertFalse(repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program).uncertain)
    }

    @Test
    fun aLossThatCantBeRecordedKeepsTheBarrierAndTheNotice() {
        File(dir, "slot-backups.json").writeText("[{ broken")
        val repo = repository(writeFile = failingFor("slot-backups-loss-accepted.json"))
        assertThrows(IOException::class.java) { runBlocking { repo.dismissUnreadable(acceptLostOriginals = true) } }
        assertTrue(repo.originalsBlocked.value)
        assertEquals(listOf("slot-backups.json"), repo.unreadable.value)
        assertThrows(IOException::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        assertTrue(repository().originalsBlocked.value)
    }

    @Test
    fun demoAndRealOriginalsAreSeparate() = runBlocking {
        val repo = repository()
        val demo = repo.recordOriginalSlot("demo", 2, StoredProgram("demo", emptyList()))
        val real = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program)
        assertNotEquals(demo, real)
        assertEquals(demo, repo.originalSlot("demo", 2))
        assertEquals(real, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertNull(repo.originalSlot("ble:11:22:33:44:55:66", 2))

        backup(2, "another demo", "demo").let { repo.recordOriginalSlot(it.deviceId, it.slot, it.program) }
        assertEquals(real, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
    }

    // Originals saved before devices were recorded

    /** As version 1 saved it; migrated records are uncertain (IR-31). */
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

        repo.originalsFor("ble:AA:BB:CC:DD:EE:FF")
        val change = (runner.prepareStart(Presets.springAutumn, null, 2, "ble:AA:BB:CC:DD:EE:FF", null, null) as io.github.willzy12h.nocturne.core.night.Preparation.Ready).change
        runner.apply(change, { repo.activeNight.value }, publish = { repo.setActiveNight(it) }, beforeWrite = {
            repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, BedJetController(link) { now }.storedPrograms()[2])
        })
        val adopted = migrated.copy(deviceId = "ble:AA:BB:CC:DD:EE:FF")
        assertEquals(adopted, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertEquals(listOf(adopted), repo.slotBackups.value)
        assertEquals(listOf(adopted), repository().slotBackups.value)
        // Only one BedJet can have been the one it came from.
        assertNull(repo.originalSlot("ble:11:22:33:44:55:66", 2))
    }

    @Test
    fun recordingAnOriginalAdoptsAMigratedOne() = runBlocking {
        version1Original()
        val repo = repository()
        assertEquals(migrated, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        val stored = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, StoredProgram("Spring/Autumn", emptyList()))
        assertEquals(migrated.copy(deviceId = "ble:AA:BB:CC:DD:EE:FF"), stored)
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

        repo.originalsFor("ble:AA:BB:CC:DD:EE:FF")
        repo.restore(repo.bundle().copy(slotBackups = listOf(migrated.copy(program = StoredProgram("older", emptyList())))))
        assertEquals(listOf(migrated.copy(deviceId = "ble:AA:BB:CC:DD:EE:FF")), repo.slotBackups.value)
    }

    // Import

    private fun importable() = BackupBundle(
        createdAt = now.minusDays(2).toString(),
        profiles = listOf(Presets.winterWarm.copy(id = "imported", name = "Imported")),
        settings = Settings(fahrenheit = true, demoMode = false, targetDevice = "ble:22:33:44:55:66:77", workingSlot = 0),
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
        repo.updateSettings { it.copy(demoMode = true, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("updateToken", "ghp_secret").commit()
        val night = ActiveNight("night-1", "preset-summer", now.toString(), now.plusHours(8).toString(), false, 2, now.plusHours(7).toString(), now.plusHours(9).toString())
        repo.setActiveNight(night)
        repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program)

        repo.restore(importable())

        assertEquals(listOf("imported"), repo.profiles.value.map { it.id })
        assertTrue(repo.settings.value.fahrenheit)
        assertEquals(0, repo.settings.value.workingSlot)
        assertTrue(repo.settings.value.demoMode)
        assertEquals("ble:AA:BB:CC:DD:EE:FF", repo.settings.value.targetDevice)
        assertEquals("ghp_secret", context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("updateToken", null))
        assertEquals(night, repo.activeNight.value)
        assertEquals(listOf("n1"), repo.history.value.map { it.id })
        assertEquals(SlotBackup("ble:AA:BB:CC:DD:EE:FF", 2, program, now.toString()), repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))

        // What was there before is kept, and everything is on disk.
        val before = repo.snapshots.list().single { it.label == "Before import" }
        assertTrue(before.manual)
        assertEquals(listOf("preset-summer", "preset-winter", "preset-spring-autumn", "preset-hot-sleeper", "preset-warm-start"), repo.snapshots.load(before.id)!!.profiles.map { it.id })
        val restarted = repository()
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
        assertEquals(night, restarted.activeNight.value)
    }

    private fun completedNight(id: String = "completed") = NightRecord(
        id, "preset-winter", "Winter Warm", now.minusHours(8).toString(), now.toString(), false,
        events = listOf(HistoryEvent(now.toString(), EventKind.ENDED, "Night ended")),
    )

    @Test
    fun morningFeedbackIsDurableAndOnlyAcceptanceEditsTheCurrentMiddle() = runBlocking {
        val repo = repository()
        repo.addRecord(completedNight())
        val oldMiddle = repo.profile("preset-winter")!!.middle
        val otherActive = ActiveNight("other", "preset-summer", now.toString(), now.plusHours(8).toString(), false,
            2, now.plusHours(7).toString(), now.plusHours(9).toString())
        repo.setActiveNight(otherActive)

        val checkIn = repo.recordMorningCheckIn("completed", MorningComfort.TOO_HOT)!!
        val suggestion = checkIn.suggestion!!
        assertEquals(now.toString(), checkIn.endedAt)
        assertEquals(oldMiddle.tempStart - 2, suggestion.proposedMiddle.tempStart)
        assertEquals(oldMiddle.tempEnd - 2, suggestion.proposedMiddle.tempEnd)
        assertEquals(oldMiddle, repo.profile("preset-winter")!!.middle)
        assertEquals(MorningComfort.TOO_HOT, repo.history.value.first { it.id == "completed" }.events.last().comfort)

        // An unrelated edit must survive acceptance; the night already running is not republished.
        repo.saveProfile(repo.profile("preset-winter")!!.copy(name = "My winter"))
        assertTrue(repo.acceptMorningSuggestion(suggestion))
        assertEquals("My winter", repo.profile("preset-winter")!!.name)
        assertEquals(suggestion.proposedMiddle, repo.profile("preset-winter")!!.middle)
        assertEquals(otherActive, repo.activeNight.value)
        assertEquals(EventKind.MORNING_SUGGESTION_APPLIED, repo.history.value.first { it.id == "completed" }.events.last().kind)
        assertFalse(repo.acceptMorningSuggestion(suggestion))

        val restarted = repository()
        assertEquals(suggestion.proposedMiddle, restarted.profile("preset-winter")!!.middle)
        assertEquals(EventKind.MORNING_SUGGESTION_APPLIED, restarted.history.value.first { it.id == "completed" }.events.last().kind)
        assertEquals(otherActive, restarted.activeNight.value)
    }

    /** P5: a Cool or Dry Middle has no temperature, so the suggestion moves its fan by 10 per cent, within the limits. */
    @Test
    fun aCoolMiddleIsSuggestedAFanChangeTheUserMustAccept() = runBlocking {
        val repo = repository()
        val cool = Presets.winterWarm.copy(middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 25, tempStart = 44, tempEnd = 44, rampSteps = 3))
        repo.saveProfile(cool)
        repo.addRecord(completedNight("hot"))
        val hot = repo.recordMorningCheckIn("hot", MorningComfort.TOO_HOT)!!.suggestion!!
        assertEquals(60, hot.proposedMiddle.fanStart)
        assertEquals(35, hot.proposedMiddle.fanEnd)
        assertEquals(cool.middle, repo.profile("preset-winter")!!.middle)
        assertTrue(repo.acceptMorningSuggestion(hot))
        assertEquals(hot.proposedMiddle, repo.profile("preset-winter")!!.middle)
        assertEquals("Accepted 10% more fan in Middle", repo.history.value.first { it.id == "hot" }.events.last().detail)

        repo.saveProfile(cool.copy(middle = cool.middle.copy(mode = Mode.DRY)))
        repo.addRecord(completedNight("cold"))
        val cold = repo.recordMorningCheckIn("cold", MorningComfort.TOO_COLD)!!.suggestion!!
        assertEquals(40, cold.proposedMiddle.fanStart)
        assertEquals(15, cold.proposedMiddle.fanEnd)
        assertEquals(Mode.DRY, cold.proposedMiddle.mode)
    }

    /** R1: a Quiet middle's settle, asleep and rise speeds all move together; an asleep speed at the limit blocks it. */
    @Test
    fun aQuietMiddleFanSuggestionMovesEverySpeed() = runBlocking {
        val repo = repository()
        val quiet = io.github.willzy12h.nocturne.core.plan.QuietFan(settle = 50, settleMinutes = 30, asleep = 25, rise = 45, riseMinutes = 40)
        repo.saveProfile(Presets.winterWarm.copy(middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 45, tempStart = 44, tempEnd = 44, quietFan = quiet)))
        repo.addRecord(completedNight("hot"))
        val hot = repo.recordMorningCheckIn("hot", MorningComfort.TOO_HOT)!!.suggestion!!
        assertTrue(hot.changesFan)
        assertEquals(quiet.copy(settle = 60, asleep = 35, rise = 55), hot.proposedMiddle.quietFan)

        repo.saveProfile(Presets.winterWarm.copy(middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 45, tempStart = 44, tempEnd = 44,
            quietFan = quiet.copy(asleep = 10)), limits = ProfileLimits(minFan = 5)))
        repo.addRecord(completedNight("cold"))
        assertNull(repo.recordMorningCheckIn("cold", MorningComfort.TOO_COLD)!!.suggestion)
    }

    @Test
    fun aFanSuggestionOutsideTheLimitsIsNotOffered() = runBlocking {
        val repo = repository()
        repo.saveProfile(Presets.winterWarm.copy(middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 20, tempStart = 44, tempEnd = 44, rampSteps = 3),
            limits = ProfileLimits(minFan = 15)))
        repo.addRecord(completedNight("cold"))
        assertNull(repo.recordMorningCheckIn("cold", MorningComfort.TOO_COLD)!!.suggestion)
        repo.saveProfile(Presets.winterWarm.copy(middle = Middle(Mode.COOL, fanStart = 95, fanEnd = 60, tempStart = 44, tempEnd = 44, rampSteps = 3)))
        repo.addRecord(completedNight("hot"))
        assertNull(repo.recordMorningCheckIn("hot", MorningComfort.TOO_HOT)!!.suggestion)
    }

    @Test
    fun morningSuggestionRefusesActiveDeletedStaleAndOutOfRangeProfiles() = runBlocking {
        val repo = repository()
        repo.addRecord(completedNight("active"))
        repo.setActiveNight(ActiveNight("active", "preset-winter", now.toString(), now.plusHours(8).toString(), false,
            2, now.plusHours(7).toString(), now.plusHours(9).toString()))
        assertNull(repo.recordMorningCheckIn("active", MorningComfort.TOO_HOT))
        repo.setActiveNight(null)

        val suggestion = repo.recordMorningCheckIn("active", MorningComfort.TOO_COLD)!!.suggestion!!
        assertNull(repo.recordMorningCheckIn("active", MorningComfort.TOO_HOT))
        repo.saveProfile(repo.profile("preset-winter")!!.copy(middle = repo.profile("preset-winter")!!.middle.copy(fanEnd = 25)))
        assertFalse(repo.acceptMorningSuggestion(suggestion))
        repo.deleteProfile("preset-winter")
        assertFalse(repo.acceptMorningSuggestion(suggestion))

        repo.addRecord(completedNight("limit"))
        repo.saveProfile(Presets.winterWarm.copy(middle = Presets.winterWarm.middle.copy(tempEnd = Presets.winterWarm.limits.minTemp)))
        assertNull(repo.recordMorningCheckIn("limit", MorningComfort.TOO_HOT)!!.suggestion)
    }

    @Test
    fun morningAcceptanceWaitsWhileAnotherNightUsesTheSameLiveProfileFallback() = runBlocking {
        val repo = repository()
        repo.addRecord(completedNight())
        val suggestion = repo.recordMorningCheckIn("completed", MorningComfort.TOO_HOT)!!.suggestion!!
        val active = ActiveNight("current", "preset-winter", now.toString(), now.plusHours(8).toString(), false,
            2, now.plusHours(7).toString(), now.plusHours(9).toString())
        assertTrue(active.usesLiveProfileFallback)
        repo.setActiveNight(active)
        val before = active.activePlan(repo.profile(active.profileId))
        assertFalse(repo.acceptMorningSuggestion(suggestion))
        assertEquals(before, repo.activeNight.value!!.activePlan(repo.profile(active.profileId)))
        assertEquals(suggestion, repo.morningCheckIn("completed")!!.suggestion)
        assertEquals(suggestion.expectedMiddle, repo.profile("preset-winter")!!.middle)

        repo.setActiveNight(null)
        assertTrue(repo.acceptMorningSuggestion(suggestion))
    }

    @Test
    fun failedMorningAcceptanceKeepsProfileAndHistoryTogether() = runBlocking {
        var failHistory = false
        val repo = repository(writeFile = { file, bytes ->
            if (failHistory && file.name == "history.json") throw IOException("Disk full")
            Repository.writeDurably(file, bytes)
        })
        repo.addRecord(completedNight())
        val suggestion = repo.recordMorningCheckIn("completed", MorningComfort.TOO_HOT)!!.suggestion!!
        val beforeProfile = repo.profile("preset-winter")
        val beforeHistory = repo.history.value

        failHistory = true
        assertThrows(IOException::class.java) { runBlocking { repo.acceptMorningSuggestion(suggestion) } }
        assertEquals(beforeProfile, repo.profile("preset-winter"))
        assertEquals(beforeHistory, repo.history.value)
        val restarted = repository()
        assertEquals(beforeProfile, restarted.profile("preset-winter"))
        assertEquals(beforeHistory, restarted.history.value)
    }

    @Test
    fun restorePreservesTheCurrentTonightChoiceAndWakeOverride() = runBlocking {
        val repo = repository()
        val wake = now.plusHours(9)
        repo.updateSettings { it.chooseTonight("preset-summer", now).setWakeOverride(wake, now) }
        val current = repo.settings.value
        val imported = importable().copy(settings = Settings(
            selectedProfileId = "imported", tonightOverride = TonightOverride(now.plusDays(1).toString(), now.toString())))

        repo.restore(imported)

        assertEquals(current.tonightChoice, repo.settings.value.tonightChoice)
        assertEquals(current.tonightOverride, repo.settings.value.tonightOverride)
        assertNull(repo.settings.value.selectedProfileId)
        assertEquals(current.tonightChoice, repository().settings.value.tonightChoice)
    }

    /**
     * IR-30: an empty current override stays empty, but a version 1 selection in the backup is migrated, as the
     * app's own version 1 settings are, and becomes tonight's choice when none is set.
     */
    @Test
    fun restoreMigratesAVersion1SelectionIntoAnEmptyTonightChoiceButKeepsAnEmptyWakeOverride() = runBlocking {
        val repo = repository()
        val imported = Settings(selectedProfileId = "imported").setWakeOverride(now.plusHours(8), now)
        repo.restore(importable().copy(settings = imported))

        val migrated = Settings().chooseTonight("imported", now).tonightChoice
        assertEquals(migrated, repo.settings.value.tonightChoice)
        assertEquals("imported", repo.tonightsProfile(now)?.id)
        assertNull(repo.settings.value.tonightOverride)
        assertNull(repo.settings.value.selectedProfileId)
        assertEquals(migrated, repository().settings.value.tonightChoice)
        assertNull(repository().settings.value.tonightOverride)
    }

    @Test
    fun restoreCannotImportAnotherPhonesSupervisedFanCheck() = runBlocking {
        val repo = repository()
        repo.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:FF", setupFanCheckedDevice = "ble:AA:BB:CC:DD:EE:FF") }
        repo.restore(importable().copy(settings = importable().settings.copy(setupFanCheckedDevice = "ble:22:33:44:55:66:77")))

        assertEquals("ble:AA:BB:CC:DD:EE:FF", repo.settings.value.targetDevice)
        assertEquals("ble:AA:BB:CC:DD:EE:FF", repo.settings.value.setupFanCheckedDevice)
        assertEquals("ble:AA:BB:CC:DD:EE:FF", repository().settings.value.setupFanCheckedDevice)
    }

    @Test
    fun slowExternalImportDoesNotHoldTheNightStateWriter() = runBlocking {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = BackupCodec.encode(importable()).toByteArray()
        val repo = repository(writer = Repository.defaultWriter(), openInput = {
            opened.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            ByteArrayInputStream(bytes)
        })
        val import = async(Dispatchers.IO) { repo.importFrom(Uri.parse("content://slow-provider/backup")) }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            withTimeout(3_000) { repo.updateRuntime { it.copy(reason = "night state saved") } }
            assertEquals("night state saved", repo.runtime.value.reason)
        } finally {
            release.countDown()
        }
        assertEquals(importable().profiles, import.await().profiles)
    }

    /** IR-15: only taking the bundle waits for the writer; a slow document provider doesn't hold up the app's saves. */
    @Test
    fun slowExternalExportDoesNotHoldTheNightStateWriter() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = java.io.ByteArrayOutputStream()
        val gated = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                saved.write(b, off, len)
            }
        }
        val repo = repository(writer = Repository.defaultWriter(), openOutput = { gated })
        val export = async(Dispatchers.IO) { repo.exportTo(Uri.parse("content://slow-provider/export")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(3_000) { repo.updateRuntime { it.copy(reason = "night state saved") } }
            assertEquals("night state saved", repo.runtime.value.reason)
        } finally {
            release.countDown()
        }
        export.await()
        assertTrue(saved.toString().contains("\"schema\""))
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
    fun interruptedRestoreCompletesAllFilesBeforeTheNextRepositoryLoads() = runBlocking {
        val incoming = importable().copy(slotBackups = listOf(backup(1, "imported slot")))
        var replacements = 0
        var stopped = false
        // The process stops after the first file: nothing after that, a rollback included, reaches the disk.
        val repo = repository(writeFile = { file, bytes ->
            if (stopped) throw AssertionError("process stopped")
            if (file.name in setOf("profiles.json", "settings.json", "history.json", "slot-backups.json")) {
                replacements++
                if (replacements == 2) { stopped = true; throw AssertionError("process stopped between restore files") }
            }
            Repository.writeDurably(file, bytes)
        })
        assertThrows(AssertionError::class.java) { runBlocking { repo.restore(incoming) } }
        assertTrue(File(dir, "restore-pending.json").exists())

        val restarted = repository()
        assertFalse(File(dir, "restore-pending.json").exists())
        assertEquals(incoming.profiles, restarted.profiles.value)
        assertEquals(incoming.settings.workingSlot, restarted.settings.value.workingSlot)
        assertEquals(incoming.history, restarted.history.value)
        assertEquals(incoming.slotBackups.map { it.copy(imported = true) }, restarted.slotBackups.value)
    }

    @Test
    fun aSnapshotHoldingARecordedOriginalOutsideTheModeRangeStillRestores() = runBlocking {
        val repo = repository()
        // What the BedJet held, recorded as it was: hotter than the official app allows for Heat.
        val recorded = StoredProgram("bedjet", listOf(ProgramStep(Mode.HEAT, 5, 200, 1, 0)))
        repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, recorded)
        repo.snapshotNow("Mine")
        val id = repo.snapshots.list().single { it.label == "Mine" }.id

        assertTrue(repo.restoreSnapshot(id))
        assertEquals(recorded, repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2)!!.program)
    }

    // A restore that can't be finished in process (IR-13)

    /** Fails `history.json` and the rollback of `profiles.json` while [broken], so a restore leaves its journal. */
    private class RollbackFails {
        var broken = true
        private var profileWrites = 0
        val write: (File, ByteArray) -> Unit = { file, bytes ->
            if (broken && file.name == "history.json") throw IOException("Disk full")
            if (broken && file.name == "profiles.json" && ++profileWrites == 2) throw IOException("Disk full")
            Repository.writeDurably(file, bytes)
        }
    }

    @Test
    fun anOriginalRecordedAfterAFailedRollbackSurvivesTheNextStart() = runBlocking {
        val disk = RollbackFails()
        val repo = repository(writeFile = disk.write)
        assertThrows(IOException::class.java) { runBlocking { repo.restore(importable()) } }
        assertTrue(File(dir, "restore-pending.json").exists())

        disk.broken = false
        val recorded = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program)
        val restarted = repository()
        assertEquals(recorded, restarted.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        // What was shown is what the next start loads.
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
    }

    @Test
    fun anOriginalIsRefusedWhileAFailedRestoreStillCantBeFinished() = runBlocking {
        val disk = RollbackFails()
        val repo = repository(writeFile = disk.write)
        assertThrows(IOException::class.java) { runBlocking { repo.restore(importable()) } }

        assertThrows(IOException::class.java) { runBlocking { repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, program) } }
        assertNull(repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        val restarted = repository()
        assertNull(restarted.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
    }

    @Test
    fun aProfileSavedAfterAFailedRollbackSurvivesTheNextStart() = runBlocking {
        val disk = RollbackFails()
        val repo = repository(writeFile = disk.write)
        assertThrows(IOException::class.java) { runBlocking { repo.restore(importable()) } }

        disk.broken = false
        val mine = Presets.summerCool.copy(id = "mine", name = "Mine")
        repo.saveProfile(mine)
        val restarted = repository()
        assertEquals(mine, restarted.profile("mine"))
        assertEquals(repo.profiles.value, restarted.profiles.value)
        assertFalse(File(dir, "restore-pending.json").exists())
    }

    @Test
    fun anErrorPartWayThroughARestoreStillPutsTheFilesBack() {
        var thrown = false
        val repo = repository(writeFile = { file, bytes ->
            if (!thrown && file.name == "history.json") { thrown = true; throw OutOfMemoryError("no room") }
            Repository.writeDurably(file, bytes)
        })
        val before = repo.bundle()
        assertThrows(OutOfMemoryError::class.java) { runBlocking { repo.restore(importable()) } }
        assertFalse(File(dir, "restore-pending.json").exists())
        assertEquals(before, repo.bundle())
        assertEquals(before.copy(createdAt = ""), repository().bundle().copy(createdAt = ""))
    }

    @Test
    fun aJournalThatCantBeRemovedAfterARestoreIsNotReappliedOverLaterEdits() = runBlocking {
        var keepJournal = true
        val repo = repository(deleteFile = { file ->
            if (keepJournal && file.name == "restore-pending.json") keepJournal = false else AtomicFile(file).delete()
        })
        repo.restore(importable())
        assertEquals(importable().profiles, repo.profiles.value)

        val mine = Presets.summerCool.copy(id = "mine", name = "Mine")
        repo.saveProfile(mine)
        val restarted = repository()
        assertEquals(mine, restarted.profile("mine"))
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
    }

    @Test
    fun aFailedRestoreWhoseJournalCantBeRemovedIsNotReappliedOverLaterEdits() = runBlocking {
        var failHistory = true
        var keepJournal = true
        val repo = repository(
            writeFile = { file, bytes ->
                if (failHistory && file.name == "history.json") { failHistory = false; throw IOException("Disk full") }
                Repository.writeDurably(file, bytes)
            },
            deleteFile = { file -> if (keepJournal && file.name == "restore-pending.json") keepJournal = false else AtomicFile(file).delete() },
        )
        assertThrows(IOException::class.java) { runBlocking { repo.restore(importable()) } }

        val mine = Presets.summerCool.copy(id = "mine", name = "Mine")
        repo.saveProfile(mine)
        val restarted = repository()
        assertEquals(mine, restarted.profile("mine"))
        assertEquals(repo.bundle().copy(createdAt = ""), restarted.bundle().copy(createdAt = ""))
        assertFalse(File(dir, "restore-pending.json").exists())
    }

    @Test
    fun aDamagedRestoreJournalIsSetAsideAndReportedInsteadOfStoppingStartUp() {
        File(dir, "restore-pending.json").writeText("{bad")
        val repo = repository()
        assertEquals(listOf("restore-pending.json"), repo.unreadable.value)
        assertFalse(File(dir, "restore-pending.json").exists())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("restore-pending.json.corrupt-") })
        assertEquals(listOf("restore-pending.json"), repository().unreadable.value)
    }

    @Test
    fun aRestoreJournalFailingItsCheckChangesNoFile() {
        val profiles = BackupCodec.json.encodeToString(ListSerializer(Profile.serializer()), listOf(Presets.winterWarm))
        File(dir, "profiles.json").writeText(profiles)
        fun entry(name: String, text: String, sha: String? = null): String {
            val bytes = text.toByteArray()
            val digest = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            return """{"name":"$name","bytes":"${java.util.Base64.getEncoder().encodeToString(bytes)}","sha256":"${sha ?: digest}"}"""
        }
        File(dir, "restore-pending.json").writeText("""{"writes":[${entry("profiles.json", "[]")},${entry("history.json", "[]", "wrong")}]}""")

        val repo = repository()
        assertEquals(profiles, File(dir, "profiles.json").readText())
        assertEquals(listOf(Presets.winterWarm), repo.profiles.value)
        assertEquals(listOf("restore-pending.json"), repo.unreadable.value)
    }

    /** A journal still pending after a restart is reported, as it holds up changes to its files until finished. */
    @Test
    fun aRestoreStillUnfinishedAfterARestartIsReportedUntilItIsFinished() = runBlocking {
        val incoming = importable()
        var stopped = false
        val first = repository(writeFile = { file, bytes ->
            if (stopped) throw AssertionError("process stopped")
            if (file.name == "settings.json") { stopped = true; throw AssertionError("process stopped between restore files") }
            Repository.writeDurably(file, bytes)
        })
        assertThrows(AssertionError::class.java) { runBlocking { first.restore(incoming) } }

        var broken = true
        val second = repository(writeFile = { file, bytes ->
            if (broken && file.name == "profiles.json") throw IOException("Disk full")
            Repository.writeDurably(file, bytes)
        })
        assertEquals(listOf("restore-pending.json"), second.unreadable.value)
        assertEquals(NoticeKind.PROBLEM, second.notices.value.first().kind)
        assertEquals("A restore isn't finished", second.notices.value.first().title)

        broken = false
        second.saveProfile(Presets.summerCool.copy(id = "mine", name = "Mine"))
        assertEquals(emptyList<String>(), second.unreadable.value)
        assertEquals(emptyList<String>(), repository().unreadable.value)
    }

    /** P5: dismissing the notice must not hide a restore that still isn't finished, and the UI can tell it apart. */
    @Test
    fun dismissingTheNoticeKeepsAStuckRestoreReported() = runBlocking {
        val incoming = importable()
        var stopped = false
        val first = repository(writeFile = { file, bytes ->
            if (stopped) throw AssertionError("process stopped")
            if (file.name == "settings.json") { stopped = true; throw AssertionError("process stopped between restore files") }
            Repository.writeDurably(file, bytes)
        })
        assertThrows(AssertionError::class.java) { runBlocking { first.restore(incoming) } }

        val second = repository(writeFile = failingFor("profiles.json"))
        assertTrue(second.restoreUnfinished.value)
        second.dismissUnreadable()
        assertEquals(listOf("restore-pending.json"), second.unreadable.value)
        assertTrue(second.restoreUnfinished.value)
    }

    @Test
    fun aDamagedJournalSetAsideIsNotAnUnfinishedRestore() {
        File(dir, "restore-pending.json").writeText("{bad")
        val repo = repository()
        assertEquals(listOf("restore-pending.json"), repo.unreadable.value)
        assertFalse(repo.restoreUnfinished.value)
    }

    @Test
    fun aStartUpRecoveryThatCantWriteStillStartsAndShowsTheRestore() = runBlocking {
        val incoming = importable()
        var replacements = 0
        var stopped = false
        val first = repository(writeFile = { file, bytes ->
            if (stopped) throw AssertionError("process stopped")
            if (file.name == "settings.json") { stopped = true; throw AssertionError("process stopped between restore files") }
            replacements++
            Repository.writeDurably(file, bytes)
        })
        assertThrows(AssertionError::class.java) { runBlocking { first.restore(incoming) } }

        // The next start can't write profiles.json: it still starts, showing the restore it couldn't finish.
        val second = repository(writeFile = failingFor("profiles.json"))
        assertEquals(incoming.profiles, second.profiles.value)
        assertEquals(incoming.settings.workingSlot, second.settings.value.workingSlot)
        assertTrue(File(dir, "restore-pending.json").exists())
        assertThrows(IOException::class.java) { runBlocking { second.saveProfile(Presets.summerCool.copy(id = "mine")) } }

        val third = repository()
        assertEquals(incoming.profiles, third.profiles.value)
        assertFalse(File(dir, "restore-pending.json").exists())
    }

    @Test
    fun importedHistoryOlderThanTheKeepPeriodIsDropped() = runBlocking {
        val repo = repository()
        val old = NightRecord("old", "p", "P", now.minusDays(91).toString(), now.minusDays(91).toString(), false)
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
        assertTrue(repo.slotBackups.value.single().imported)
    }

    @Test
    fun importedUnknownDeviceOriginalIsNotAdoptedAsTheSelectedBedJetsOriginal() = runBlocking {
        val repo = repository()
        repo.restore(importable().copy(slotBackups = listOf(migrated)))
        assertTrue(repo.slotBackups.value.single().imported)
        assertNull(repo.originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
        assertTrue(repo.originalsFor("ble:AA:BB:CC:DD:EE:FF").isEmpty())
        assertNull(repository().originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))

        val current = StoredProgram("actual selected BedJet original", listOf(ProgramStep(Mode.HEAT, 7, 64, 0, 20)))
        val recorded = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 2, current)
        assertEquals(current, recorded.program)
        assertEquals("ble:AA:BB:CC:DD:EE:FF", recorded.deviceId)
        assertFalse(recorded.imported)
        assertEquals(recorded, repository().originalSlot("ble:AA:BB:CC:DD:EE:FF", 2))
    }

    @Test
    fun importPreservesTheLiveNightsHistoryRecord() = runBlocking {
        val repo = repository()
        val live = NightRecord("live", "p", "Live", now.toString(), now.plusHours(8).toString(), false)
        repo.addRecord(live)
        repo.setActiveNight(ActiveNight("live", "p", now.toString(), now.plusHours(8).toString(), false, 2,
            now.plusHours(7).toString(), now.plusHours(9).toString()))
        val importedCollision = live.copy(profileName = "Imported replacement")
        repo.restore(importable().copy(history = listOf(importedCollision) + importable().history))

        assertEquals(live, repo.history.value.single { it.id == "live" })
        assertEquals(setOf("live", "n1"), repo.history.value.map { it.id }.toSet())
        assertEquals(live, repository().history.value.single { it.id == "live" })
    }

    /** IR-28: a night being ended keeps its record too, as the end is still to be added to it. */
    @Test
    fun importKeepsTheRecordOfTheNightBeingEnded() = runBlocking {
        val repo = repository()
        val ending = NightRecord("night-0", "p", "Ending", now.minusHours(1).toString(), now.plusHours(7).toString(), false,
            events = listOf(HistoryEvent(now.minusHours(1).toString(), EventKind.STARTED, "Started")))
        repo.addRecord(ending)
        repo.updateRuntime { it.copy(ending = PendingEnd(NightIdentity("night-0", 0), HistoryEvent(now.toString(), EventKind.ENDED, "Ended"))) }

        // The backup holds an older copy of the record, without its events.
        repo.restore(importable().let { it.copy(history = it.history + ending.copy(events = emptyList())) })

        assertEquals(setOf("n1", "night-0"), repo.history.value.map { it.id }.toSet())
        assertEquals(ending, repo.history.value.single { it.id == "night-0" })
        assertEquals(ending, repository().history.value.single { it.id == "night-0" })
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
    fun historyKeepsNinetyDaysByDefault() = runBlocking {
        val repo = repository()
        repo.addRecord(NightRecord("old", "p", "P", now.minusDays(91).toString(), now.toString(), false))
        repo.addRecord(NightRecord("recent", "p", "P", now.minusDays(89).toString(), now.toString(), false))
        assertEquals(listOf("recent"), repo.history.value.map { it.id })
    }

    @Test
    fun shorteningTheKeepPeriodDeletesOlderNights() = runBlocking {
        val repo = repository()
        repo.addRecord(NightRecord("month", "p", "P", now.minusDays(20).toString(), now.minusDays(20).toString(), false))
        repo.addRecord(NightRecord("week", "p", "P", now.minusDays(3).toString(), now.minusDays(3).toString(), false))
        repo.setHistoryKeepDays(7)
        assertEquals(7, repo.settings.value.historyKeepDays)
        assertEquals(listOf("week"), repo.history.value.map { it.id })
    }

    @Test
    fun deletingHistoryRemovesEveryFinishedNight() = runBlocking {
        val repo = repository()
        repo.addRecord(NightRecord("a", "p", "P", now.minusDays(3).toString(), now.minusDays(3).toString(), false))
        repo.addRecord(NightRecord("b", "p", "P", now.minusDays(2).toString(), now.minusDays(2).toString(), false))
        repo.deleteHistory()
        assertTrue(repo.history.value.isEmpty())
    }

    // Secrets

    @Test
    fun removingTheUpdaterDeletesItsLegacyTokenAndSchedule() {
        val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
        val updates = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        secrets.edit().putString("updateToken", "legacy-private-repository-token").putString("unrelated", "keep").commit()
        updates.edit().putLong("lastCheck", 123L).commit()
        repository()
        assertFalse("Removed updater credentials must not remain on the phone", secrets.contains("updateToken"))
        assertFalse("Removed updater schedule must not remain on the phone", updates.contains("lastCheck"))
        assertEquals("keep", secrets.getString("unrelated", null))
    }

    @Test
    fun exportsRefuseAPickerThatPointsAtPrivateStorage() = runBlocking {
        val repo = repository()
        val private = File(dir, "victim.json").apply { writeText("keep") }
        val backup = runCatching { repo.exportTo(Uri.fromFile(private)) }.exceptionOrNull()
        assertTrue("SEC-01: a file:// backup destination must be refused, got $backup", backup is UnsafeExportDestination)
        val share = runCatching { repo.exportProfileTo(Uri.fromFile(private), repo.profileShareRequest(repo.profiles.value.first())) }.exceptionOrNull()
        assertTrue("SEC-01: a file:// profile destination must be refused, got $share", share is UnsafeExportDestination)
        assertEquals("Nothing is truncated or written", "keep", private.readText())
    }

    @Test
    fun aLegacyUpdateTokenIsNeverExportedOrSnapshotted() = runBlocking {
        val repo = repository()
        context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("updateToken", "ghp_secret").commit()
        assertFalse(BackupCodec.encode(repo.bundle()).contains("ghp_secret"))
        repo.snapshotNow("Mine")
        val file = File(dir, "export.json")
        val uri = Uri.parse("content://test.documents/export.json")
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerOutputStream(uri, java.io.FileOutputStream(file))
        repo.exportTo(uri)
        assertTrue(file.readText().contains("\"schema\""))
        assertFalse(file.readText().contains("ghp_secret"))
        assertFalse(dir.walk().filter { it.isFile }.any { it.readText().contains("ghp_secret") })
    }
}
