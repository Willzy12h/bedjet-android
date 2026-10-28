package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R5: review and cancellation remain usable at 320 dp and 200% text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportPreviewUiTest {
    private lateinit var snapshotId: String
    private val ui = U06Fixture(U06Look.MIDNIGHT) { fixture, _ -> runBlocking {
        val repo = fixture.graph.repository
        repo.restore(repo.bundle().copy(slotBackups = listOf(SlotBackup(Migrations.UNKNOWN_DEVICE, 0, null, fixture.graph.now().toString(), uncertain = true))))
        repo.snapshotNow("Review this backup")
        snapshotId = repo.snapshotList.value.first { it.manual }.id
        repo.saveProfile(repo.profiles.value.first().copy(id = "local-only", name = "Local-only profile"))
    } }
    @get:Rule val rules = ui.rules

    @Test fun snapshotReviewShowsEveryAreaAndLeavesDataUntouchedWhenDismissed() {
        val before = ui.graph.repository.profiles.value
        ui.tab("Settings")
        ui.compose.runOnUiThread { ui.model.restoreSnapshot(snapshotId) }
        ui.compose.waitUntil(5_000) {
            ui.graph.repository.profiles.value != before || ui.compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.compose.onAllNodes(isDialog()).assertCountEquals(1)
        listOf("Profiles", "Settings", "History", "Saved BedJet originals").forEach { title ->
            ui.checkLayout(ui.compose.onNode(hasText(title) and hasAnyAncestor(isDialog())), title)
        }
        ui.checkLayout(ui.compose.onNode(hasText("Some saved originals may not be the true originals.") and hasAnyAncestor(isDialog())), "Uncertain-original warning")
        ui.checkControl(ui.compose.onNode(hasText("Apply import") and hasAnyAncestor(isDialog())), "Apply import", scroll = false)
        ui.checkControl(ui.compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())), "Cancel import", scroll = false)
        assertEquals(before, ui.graph.repository.profiles.value)
        ui.compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(before, ui.graph.repository.profiles.value)
        ui.compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun anOldApplyCallbackCannotApproveADifferentNewlyStagedBackup() {
        val repo = ui.graph.repository
        val laterId = runBlocking {
            repo.saveProfile(repo.profiles.value.first().copy(id = "later-file", name = "Unreviewed later profile"))
            repo.snapshotNow("Later backup")
            val id = repo.snapshotList.value.first { it.label == "Later backup" }.id
            repo.deleteProfile("later-file")
            id
        }
        val before = repo.profiles.value
        val snapshots = repo.snapshotList.value.count { it.manual }
        ui.tab("Settings")
        ui.compose.runOnUiThread { ui.model.restoreSnapshot(snapshotId) }
        ui.waitUntil { ui.model.importPreview.value != null }
        val reviewed = ui.model.importPreview.value
        val oldApply = requireNotNull(ui.compose.onNode(hasText("Apply import") and hasAnyAncestor(isDialog()))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        ui.compose.runOnUiThread { ui.model.restoreSnapshot(laterId) }
        ui.waitUntil { ui.model.importPreview.value != null && ui.model.importPreview.value !== reviewed }
        ui.compose.runOnUiThread { oldApply() }
        ui.waitUntil { !ui.model.importApplying.value }
        assertEquals("An old review must never apply a replacement preview", before, repo.profiles.value)
        assertEquals("Stale confirmation creates no recovery snapshot", snapshots, repo.snapshotList.value.count { it.manual })
    }

    @Test fun demoModeDisablesBackupFilePickersAndSnapshotMutations() {
        ui.tab("Settings")
        ui.compose.openSettingsPage("backups")
        listOf(R.string.settings_export_file, R.string.settings_restore_from_file, R.string.settings_snapshot_now).forEach {
            ui.control(ui.s(it)).assertIsNotEnabled()
        }
        val deletes = ui.compose.onAllNodes(hasText(ui.s(R.string.settings_delete)) and hasClickAction())
        assertTrue(deletes.fetchSemanticsNodes().isNotEmpty())
        deletes.fetchSemanticsNodes().indices.forEach { index ->
            ui.checkControl(deletes[index], "Delete snapshot ${index + 1}").assertIsNotEnabled()
        }
        val restores = ui.compose.onAllNodes(hasText(ui.s(R.string.settings_restore)) and hasClickAction())
        assertTrue(restores.fetchSemanticsNodes().isNotEmpty())
        restores.fetchSemanticsNodes().indices.forEach { restores[it].assertIsNotEnabled() }
    }

}
