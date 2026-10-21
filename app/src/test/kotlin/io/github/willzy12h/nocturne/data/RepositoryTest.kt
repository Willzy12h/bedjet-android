package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.Season
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The plain Application keeps the real app's own Repository out of the way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    // Unconfined runs the start-up snapshot check inline, so its effect is visible straight away.
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val dir: File get() = temp.root

    private val context: Context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = dir
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun repository() = Repository(context, scope)

    private fun backup(slot: Int, name: String) =
        SlotBackup(slot, StoredProgram(name, listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0))), "2026-10-21T22:30Z")

    @Test
    fun valuesSurviveARestart() {
        val first = repository()
        val mine = Presets.summerCool.copy(id = "mine", name = "Mine")
        first.saveProfile(mine)
        first.updateSettings { it.copy(fahrenheit = true, workingSlot = 2) }
        first.addSlotBackup(backup(1, "original"))

        val second = repository()
        assertEquals(mine, second.profile("mine"))
        assertTrue(second.settings.value.fahrenheit)
        assertEquals(2, second.settings.value.workingSlot)
        assertEquals(listOf(backup(1, "original")), second.slotBackups.value)
        assertEquals(emptyList<String>(), second.unreadable.value)
    }

    @Test
    fun corruptProfilesAreKeptAsideAndReportedNotOverwritten() {
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
    fun restoreKeepsCurrentSlotBackupsAndAddsMissingOnes() {
        val repo = repository()
        repo.addSlotBackup(backup(1, "user's own"))
        val older = repo.bundle().copy(slotBackups = listOf(backup(1, "nocturne's"), backup(2, "slot two")))

        repo.restore(older)
        assertEquals(listOf(backup(1, "user's own"), backup(2, "slot two")), repo.slotBackups.value)
    }
}
