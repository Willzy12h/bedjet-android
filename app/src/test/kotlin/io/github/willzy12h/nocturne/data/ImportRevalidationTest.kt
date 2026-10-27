package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupException
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.*
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
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportRevalidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val executor = Executors.newSingleThreadExecutor()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
    }
    private val writer = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val pausedImport = context[CoroutineName]?.name == "paused-import"
            if (pausedImport) entered.countDown()
            executor.execute {
                if (pausedImport) check(release.await(5, TimeUnit.SECONDS))
                block.run()
            }
        }
    }

    @After fun finish() {
        release.countDown()
        scope.cancel()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun validationRunsAgainAfterTheWriterQueueBeforeAnyRestoreFileChanges() = runBlocking {
        val repo = Repository(context, scope, writer = writer)
        repo.snapshotNow("Before queue")
        val before = repo.profiles.value
        val profiles = mutableListOf(Presets.summerCool.copy(id = "incoming"))
        val incoming = BackupBundle(createdAt = ZonedDateTime.now().toString(), profiles = profiles, settings = Settings())
        val restore = async(Dispatchers.IO + CoroutineName("paused-import")) { runCatching { repo.restore(incoming) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        profiles[0] = profiles[0].copy(middle = profiles[0].middle.copy(rampSteps = 0))
        release.countDown()
        val failure = restore.await().exceptionOrNull()
        assertTrue("A bundle changed while queued must be revalidated, got $failure", failure is BackupException)
        assertEquals(before, repo.profiles.value)
        assertEquals("No recovery snapshot is written for a refused import", 1, repo.snapshotList.value.count { it.manual })
    }

    @Test fun anImportedCaseVariantCannotDuplicateOrReplaceTheSameBedJetsSavedOriginal() = runBlocking {
        val repo = Repository(context, scope, writer = writer)
        val original = repo.recordOriginalSlot("ble:AA:BB:CC:DD:EE:FF", 0, null)
        repo.restore(repo.bundle().copy(slotBackups = listOf(SlotBackup("ble:aa:bb:cc:dd:ee:ff", 0, null, ZonedDateTime.now().minusDays(1).toString()))))
        assertEquals("Bluetooth device identities are case-insensitive; the first original must win", listOf(original), repo.slotBackups.value)
    }

    @Test fun aSecondRestoreIsRefusedWhileTheFirstRestoreIsWaitingForTheWriter() = runBlocking {
        val repo = Repository(context, scope, writer = writer)
        repo.snapshotNow("Before queue")
        val first = async(Dispatchers.IO + CoroutineName("paused-import")) { repo.restore(repo.bundle()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { runCatching { repo.restore(repo.bundle()) } }
        try {
            val result = withTimeoutOrNull(2_000) { second.await() }
            assertNotNull("Restore admission must refuse immediately instead of queueing a second restore", result)
            assertTrue(result!!.exceptionOrNull() is BackupException)
        } finally { release.countDown(); first.await() }
    }
}
