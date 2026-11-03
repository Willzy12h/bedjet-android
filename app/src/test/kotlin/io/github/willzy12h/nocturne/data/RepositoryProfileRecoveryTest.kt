package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime

/** Profile import and Cooldown use the same durable recovery boundary as the older transactions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryProfileRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val at = ZonedDateTime.parse("2026-10-29T22:30Z")
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        }
    }

    private fun repository(
        writeFile: (File, ByteArray) -> Unit = Repository::writeDurably,
        deleteFile: (File) -> Unit = { AtomicFile(it).delete() },
    ): Repository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return Repository(context, scope, writer = Dispatchers.Unconfined, writeFile = writeFile,
            deleteFile = deleteFile, now = { at })
    }

    @After fun finish() = scopes.forEach { it.cancel() }

    @Test fun committedProfileOnlyImportRollsForwardAfterInterruptedDataWrite() = runBlocking {
        var interrupt = false
        val first = repository(writeFile = { file, bytes ->
            if (interrupt && file.name == "profiles.json") throw IOException("Interrupted before profile write")
            Repository.writeDurably(file, bytes)
        }, deleteFile = { file ->
            if (interrupt) throw IOException("Cannot remove committed journal")
            AtomicFile(file).delete()
        })
        val original = first.profiles.value.first().copy(name = "Persisted original")
        first.saveProfile(original)
        val incoming = original.copy(name = "Reviewed imported replacement")
        val preview = first.previewImport(first.bundle().copy(profiles = listOf(incoming)), profilesOnly = true)
        interrupt = true
        val failure = runCatching { first.applyImport(preview) }.exceptionOrNull()
        assertTrue("The committed import must be reported unfinished", failure is RestoreUnfinished)
        assertEquals(incoming, first.profile(original.id))
        assertTrue(File(temp.root, Repository.RESTORE_JOURNAL).exists())
        scopes.forEach { it.cancel() }

        val restarted = repository()

        assertEquals("Startup must replay the committed profile-only import", incoming, restarted.profile(original.id))
        assertFalse("A valid profile-only journal is not corruption", Repository.RESTORE_JOURNAL in restarted.unreadable.value)
        assertFalse(File(temp.root, Repository.RESTORE_JOURNAL).exists())
    }

    @Test fun successfulProfileOnlyImportWithUnremovedJournalDoesNotReportCorruption() = runBlocking {
        var retainJournal = false
        val first = repository(deleteFile = { file ->
            if (retainJournal) throw IOException("Journal deletion unavailable")
            AtomicFile(file).delete()
        })
        val original = first.profiles.value.first()
        first.saveProfile(original)
        val incoming = original.copy(name = "Imported and written")
        val preview = first.previewImport(first.bundle().copy(profiles = listOf(incoming)), profilesOnly = true)
        retainJournal = true
        first.applyImport(preview)
        assertEquals(incoming, first.profile(original.id))
        assertTrue(File(temp.root, Repository.RESTORE_JOURNAL).exists())
        scopes.forEach { it.cancel() }

        val restarted = repository()

        assertEquals(incoming, restarted.profile(original.id))
        assertFalse("A valid journal must be finished, not set aside as damaged", Repository.RESTORE_JOURNAL in restarted.unreadable.value)
        assertFalse(File(temp.root, Repository.RESTORE_JOURNAL).exists())
    }

    @Test fun committedCooldownOfferRecoversBothProfileAndAnsweredFlag() = runBlocking {
        var interrupt = false
        var profileWrites = 0
        val first = repository(writeFile = { file, bytes ->
            if (interrupt && file.name == "settings.json") throw IOException("Interrupted before answered flag")
            if (interrupt && file.name == "profiles.json" && ++profileWrites > 1)
                throw IOException("Rollback unavailable after interruption")
            Repository.writeDurably(file, bytes)
        })
        val original = first.profiles.value.first().copy(afterWake = emptyList())
        first.saveProfile(original)
        first.updateSettings { it.copy(cooldownOfferAnswered = false) }
        interrupt = true
        val failure = runCatching { first.answerCooldownOffer(mapOf(original.id to original.afterWake)) }.exceptionOrNull()
        assertTrue("The partially committed pair must remain recoverable", failure is RestoreUnfinished)
        val committedProfile = requireNotNull(first.profile(original.id))
        assertTrue(first.settings.value.cooldownOfferAnswered)
        assertTrue(committedProfile.afterWake.isNotEmpty())
        assertTrue(File(temp.root, Repository.RESTORE_JOURNAL).exists())
        scopes.forEach { it.cancel() }

        val restarted = repository()

        assertEquals(committedProfile, restarted.profile(original.id))
        assertTrue("The committed profile and answered flag must recover together", restarted.settings.value.cooldownOfferAnswered)
        assertFalse("A valid Cooldown journal is not corruption", Repository.RESTORE_JOURNAL in restarted.unreadable.value)
        assertFalse(File(temp.root, Repository.RESTORE_JOURNAL).exists())
    }

    @Test fun quarantinedImportJournalDoesNotOverwriteLaterProfileEdits() = runBlocking {
        var retainJournal = false
        val first = repository(deleteFile = { file ->
            if (retainJournal) throw IOException("Journal deletion unavailable")
            AtomicFile(file).delete()
        })
        val original = first.profiles.value.first()
        first.saveProfile(original)
        val incoming = original.copy(name = "Earlier import")
        val preview = first.previewImport(first.bundle().copy(profiles = listOf(incoming)), profilesOnly = true)
        retainJournal = true
        first.applyImport(preview)
        val journal = File(temp.root, Repository.RESTORE_JOURNAL)
        val retainedBytes = journal.readBytes()
        scopes.forEach { it.cancel() }
        // An older app may already have quarantined a valid journal. Its contents are now historical.
        val quarantined = File(temp.root, Repository.RESTORE_JOURNAL + ".corrupt-123")
        assertTrue(journal.renameTo(quarantined))
        val editor = repository()
        val later = incoming.copy(name = "Later saved edit")
        editor.saveProfile(later)
        scopes.forEach { it.cancel() }

        val restarted = repository()

        assertEquals("Only the pending journal is replayable; quarantined copies cannot replace later work", later,
            restarted.profile(original.id))
        assertArrayEquals(retainedBytes, quarantined.readBytes())
    }
}
