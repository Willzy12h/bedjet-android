package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.store.PlatformSettings
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportAssociationProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = ZonedDateTime.parse("2026-10-24T22:30Z")
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
    }
    @After fun finish() { scope.cancel() }
    private fun repo() = Repository(context, scope, writer = Dispatchers.Unconfined) { now }

    @Test fun importedRemembranceCannotAssociateNewDevicesOrUndoTheLocalDemoMigration() = runBlocking {
        val repo = repo()
        val local = "ble:AA:BB:CC:DD:EE:FF"
        val foreign = "ble:11:22:33:44:55:66"
        repo.updateSettings { it.copy(targetDevice = local, rememberedBedJets = listOf(local), demoTargetMigrated = true) }
        val preview = repo.previewImport(repo.bundle().copy(settings = repo.settings.value.copy(
            targetDevice = foreign, rememberedBedJets = listOf(foreign), demoTargetMigrated = false)))
        val entries = preview.sections.first { it.area == ImportArea.SETTINGS }.entries.associateBy { it.id }
        assertEquals(ImportDisposition.PRESERVED, entries.getValue("rememberedBedJets").disposition)
        assertEquals(ImportDisposition.PRESERVED, entries.getValue("demoTargetMigrated").disposition)
        assertTrue("The review must disclose the incoming remembered association", foreign in preview.deviceAssociations)
        repo.applyImport(preview)
        assertEquals(local, repo.settings.value.targetDevice)
        assertEquals(listOf(local), repo.settings.value.rememberedBedJets)
        assertTrue(repo.settings.value.demoTargetMigrated)
    }

    @Test fun importedHealthExportOptInIsDisabledEvenWithAnUnchangedConfirmedLocalNight() = runBlocking {
        val repo = repo()
        val local = io.github.willzy12h.nocturne.core.store.NightRecord("local", "profile", "Local night",
            now.minusHours(8).toString(), now.toString(), false, deviceId = "ble:AA:BB:CC:DD:EE:FF",
            bedJetRunConfirmed = true, confirmedCompletionAt = now.toString(), healthExportVersion = 1)
        repo.addRecord(local)
        val preview = repo.previewImport(repo.bundle().copy(settings = repo.settings.value.copy(
            platform = PlatformSettings(healthSleepExport = true))))
        assertTrue(ImportWarning.AUTOMATIC_ACTIONS_DISABLED in preview.warnings)
        repo.applyImport(preview)
        assertFalse(repo.settings.value.platform.healthSleepExport)
        assertEquals(listOf(local), repo.history.value)
    }
}
