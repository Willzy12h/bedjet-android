package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.*
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportPreviewRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = ZonedDateTime.parse("2026-10-24T22:30Z")
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
    }
    private fun repo(writer: CoroutineDispatcher = Dispatchers.Unconfined) = Repository(context, scope, writer = writer) { now }
    private fun night(id: String) = NightRecord(id, "profile", "Night $id", now.minusHours(8).toString(), now.toString(), false, deviceId = device)
    private fun failure(error: Throwable?, reason: BackupFailure) {
        assertTrue("Expected $reason refusal, got $error", error is BackupException)
        assertEquals(reason, (error as BackupException).failure)
    }
    @After fun finish() { scope.cancel() }

    @Test fun theReviewUsesTheExactProtectedResultAndCreatesNoRecoverySnapshot() = runBlocking {
        val repo = repo()
        repo.updateSettings { it.copy(targetDevice = device) }
        val protected = repo.recordOriginalSlot(device, 0, null)
        val incoming = repo.bundle().copy(profiles = listOf(Presets.summerCool.copy(id = "new", name = "New profile")),
            settings = repo.settings.value.copy(fahrenheit = true, targetDevice = "ble:11:22:33:44:55:66"),
            slotBackups = listOf(protected.copy(program = StoredProgram("Foreign", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0))), empty = false),
                SlotBackup(Migrations.UNKNOWN_DEVICE, 1, null, now.toString(), uncertain = true)),
            history = listOf(night("old").copy(startedAt = now.minusDays(61).toString()), night("new")))
        val before = repo.bundle()
        val snapshots = repo.snapshotList.value
        val preview = repo.previewImport(incoming)
        assertEquals(before, repo.bundle())
        assertEquals(snapshots, repo.snapshotList.value)
        assertTrue(preview.canApply)
        assertEquals(ImportArea.entries.toSet(), preview.sections.map { it.area }.toSet())
        val profiles = preview.sections.first { it.area == ImportArea.PROFILES }
        assertEquals(1, profiles.count(ImportDisposition.ADDED))
        assertEquals(before.profiles.size, profiles.count(ImportDisposition.REMOVED))
        val settings = preview.sections.first { it.area == ImportArea.SETTINGS }.entries.associateBy { it.id }
        assertEquals(ImportDisposition.REPLACED, settings.getValue("fahrenheit").disposition)
        assertEquals(ImportDisposition.PRESERVED, settings.getValue("targetDevice").disposition)
        assertEquals(1, preview.sections.first { it.area == ImportArea.ORIGINALS }.count(ImportDisposition.PRESERVED))
        assertEquals(1, preview.sections.first { it.area == ImportArea.HISTORY }.count(ImportDisposition.OMITTED))
        assertTrue(preview.warnings.containsAll(setOf(ImportWarning.UNCERTAIN_ORIGINALS, ImportWarning.UNASSIGNED_ORIGINALS,
            ImportWarning.DEVICE_ASSOCIATIONS, ImportWarning.ORIGINALS_PROTECTED, ImportWarning.HISTORY_OMITTED)))
        assertTrue("Review must name the foreign associated unit", "ble:11:22:33:44:55:66" in preview.deviceAssociations)
    }

    @Test fun anIncompatibleReviewCannotApplyOrCreateARecoverySnapshot() = runBlocking {
        val repo = repo()
        val before = repo.bundle()
        val count = repo.snapshotList.value.count { it.manual }
        val preview = repo.previewImport(before.copy(profiles = listOf(Presets.summerCool.copy(name = "x".repeat(121)))))
        assertFalse(preview.canApply)
        assertTrue(preview.incompatibleFields > 0)
        failure(runCatching { repo.applyImport(preview) }.exceptionOrNull(), BackupFailure.INVALID)
        assertEquals(before, repo.bundle())
        assertEquals(count, repo.snapshotList.value.count { it.manual })
    }

    @Test fun applyingAnOldReviewRefusesChangedLocalProfilesAndCreatesNoRecoverySnapshot() = runBlocking {
        val repo = repo()
        val preview = repo.previewImport(repo.bundle().copy(profiles = listOf(Presets.summerCool.copy(id = "incoming"))))
        repo.saveProfile(Presets.springAutumn.copy(id = "saved-after-review"))
        val before = repo.bundle()
        val count = repo.snapshotList.value.count { it.manual }
        failure(runCatching { repo.applyImport(preview) }.exceptionOrNull(), BackupFailure.CHANGED)
        assertEquals(before, repo.bundle())
        assertEquals(count, repo.snapshotList.value.count { it.manual })
    }

    @Test fun localChangesAreRecheckedAfterTheApplyWaitsForTheWriter() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val pending = AtomicReference<Runnable?>()
        val entered = CountDownLatch(1)
        val writer = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (context[CoroutineName]?.name == "paused-apply" && pending.compareAndSet(null, block)) entered.countDown()
                else executor.execute(block)
            }
        }
        try {
            val repo = repo(writer)
            val preview = repo.previewImport(repo.bundle())
            val apply = async(Dispatchers.IO + CoroutineName("paused-apply")) { runCatching { repo.applyImport(preview) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull("Apply waiting for the writer must already exclude new commands", repo.operationAdmission.tryCommand())
            repo.updateSettings { it.copy(fahrenheit = !it.fahrenheit) }
            val before = repo.settings.value
            executor.execute(requireNotNull(pending.getAndSet(null)))
            failure(apply.await().exceptionOrNull(), BackupFailure.CHANGED)
            assertEquals(before, repo.settings.value)
            assertEquals(0, repo.snapshotList.value.count { it.manual })
        } finally {
            pending.getAndSet(null)?.let(executor::execute)
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun aRetentionBoundaryChangeRequiresReviewAgainBeforeApplying() = runBlocking {
        val repo = repo()
        val incoming = repo.bundle().copy(history = listOf(night("near-cutoff").copy(startedAt = now.minusDays(60).plusMinutes(1).toString())))
        val preview = repo.previewImport(incoming)
        assertEquals(1, preview.sections.first { it.area == ImportArea.HISTORY }.count(ImportDisposition.ADDED))
        now = now.plusMinutes(2)
        failure(runCatching { repo.applyImport(preview) }.exceptionOrNull(), BackupFailure.CHANGED)
        assertTrue(repo.history.value.isEmpty())
        assertEquals(0, repo.snapshotList.value.count { it.manual })
    }

    @Test fun changingOriginalLossConsentInvalidatesTheReviewedProtectionRules() = runBlocking {
        File(temporary.root, "slot-backups.json").writeText("damaged originals")
        val repo = repo()
        assertTrue(repo.originalsBlocked.value)
        val preview = repo.previewImport(repo.bundle())
        repo.dismissUnreadable(acceptLostOriginals = true)
        assertFalse(repo.originalsBlocked.value)
        failure(runCatching { repo.applyImport(preview) }.exceptionOrNull(), BackupFailure.CHANGED)
        assertEquals(0, repo.snapshotList.value.count { it.manual })
    }

    @Test fun queuedCommandsAfterReviewRefuseApplyWithoutChangingData() = runBlocking {
        val repo = repo()
        val before = repo.bundle()
        val preview = repo.previewImport(before)
        val command = requireNotNull(repo.operationAdmission.tryCommand())
        try {
            failure(runCatching { repo.applyImport(preview) }.exceptionOrNull(), BackupFailure.BUSY)
            assertEquals(before, repo.bundle())
            assertEquals(0, repo.snapshotList.value.count { it.manual })
        } finally { command.close() }
    }

    @Test fun reviewedBytesStayImmutableWhenTheCallerChangesItsLists() = runBlocking {
        val repo = repo()
        val reviewed = Presets.summerCool.copy(id = "reviewed")
        val profiles = mutableListOf(reviewed)
        val preview = repo.previewImport(repo.bundle().copy(profiles = profiles))
        profiles[0] = reviewed.copy(name = "Changed after review", middle = reviewed.middle.copy(rampSteps = 0))
        repo.applyImport(preview)
        assertEquals(listOf(reviewed), repo.profiles.value)
        assertEquals(1, repo.snapshotList.value.count { it.manual })
    }

    @Test fun incomingHealthProofIsClearedWhileAnUnchangedLocalRecordRetainsItsOwnProof() = runBlocking {
        val repo = repo()
        val local = night("local").copy(bedJetRunConfirmed = true, confirmedCompletionAt = now.toString(), healthExportVersion = 4, healthExportedVersion = 3)
        repo.addRecord(local)
        val invented = night("invented").copy(bedJetRunConfirmed = true, confirmedCompletionAt = now.toString(), healthExportVersion = 99, healthExportedVersion = 99)
        val preview = repo.previewImport(repo.bundle().copy(history = listOf(local.copy(healthExportVersion = 999, healthExportedVersion = 999), invented)))
        repo.applyImport(preview)
        val restored = repo.history.value.associateBy { it.id }
        assertEquals("Only the existing locally trusted proof survives unchanged", local, restored.getValue("local"))
        assertFalse(restored.getValue("invented").bedJetRunConfirmed)
        assertNull(restored.getValue("invented").confirmedCompletionAt)
        assertEquals(0L, restored.getValue("invented").healthExportVersion)
        assertEquals(0L, restored.getValue("invented").healthExportedVersion)
    }

    @Test fun importedAutomaticStartIsDisabledAndItsForeignDeviceAppearsInTheReview() = runBlocking {
        val repo = repo()
        val foreign = "ble:11:22:33:44:55:66"
        val autoStart = AutoStartSettings(enabled = true, profileId = repo.profiles.value.first().id, deviceId = foreign)
        val incoming = repo.bundle().copy(settings = repo.settings.value.copy(platform = PlatformSettings(autoStart = autoStart)))
        val preview = repo.previewImport(incoming)
        assertTrue(ImportWarning.AUTOMATIC_ACTIONS_DISABLED in preview.warnings)
        assertTrue(foreign in preview.deviceAssociations)
        repo.applyImport(preview)
        assertFalse(repo.settings.value.platform.autoStart!!.enabled)
        assertNull(repo.settings.value.targetDevice)
    }

    @Test fun importedCaseVariantIsStoredUnderTheCanonicalDeviceIdentity() = runBlocking {
        val repo = repo()
        repo.applyImport(repo.previewImport(repo.bundle().copy(slotBackups = listOf(SlotBackup(device.lowercase(), 1, null, now.toString())))))
        assertNotNull("A valid address from a backup must remain discoverable under the selected canonical unit", repo.originalSlot(device, 1))
    }
}
