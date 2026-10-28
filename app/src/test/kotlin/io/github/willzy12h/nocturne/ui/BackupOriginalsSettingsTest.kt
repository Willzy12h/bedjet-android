package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** G9: exercise real imported identities and the Settings confirmation, not a synthetic listing helper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BackupOriginalsSettingsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            graph.clock = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int) = compose.activity.getString(id)
    private fun program(name: String, mode: Mode = Mode.HEAT, temperature: Int = 70) =
        StoredProgram(name, listOf(ProgramStep(mode, 5, temperature, 0, 1)))

    private fun importOriginals(vararg backups: SlotBackup) {
        compose.runOnUiThread { runBlocking {
            val repo = graph.repository
            repo.restore(repo.importFrom(BackupCodec.encode(repo.bundle().copy(slotBackups = backups.toList())).byteInputStream()))
        } }
        compose.waitForIdle()
    }
    private fun backup(device: String, slot: Int, name: String) =
        SlotBackup(device, slot, program(name), graph.now().toString())
    private fun settings() { compose.onNodeWithText("Settings").performClick(); compose.openSettingsPage("bedjet") }

    @Test fun importedUnknownAndLegacyOriginalsAreListedSeparatelyAndCannotWrite() {
        val repo = graph.repository
        val records = listOf(
            backup(DeviceId.DEMO, 0, "Matching saved"),
            backup(DeviceId.ble("AA:BB:CC:DD:EE:FF"), 0, "Other saved"),
            backup(Migrations.UNKNOWN_DEVICE, 1, "Unknown saved"),
            backup(Migrations.UNKNOWN_DEVICE, 2, "Legacy saved"),
        )
        // A real v1 file lacked the legacy record's deviceId. Migration must classify it, not a test alias.
        compose.runOnUiThread { runBlocking {
            val encoded = BackupCodec.json.parseToJsonElement(BackupCodec.encode(repo.bundle().copy(slotBackups = records))).jsonObject
            val oldRecords = encoded.getValue("slotBackups").jsonArray.mapIndexed { index, element ->
                if (index == 3) JsonObject(element.jsonObject - "deviceId" - "uncertain") else element
            }
            val legacy = JsonObject(encoded + mapOf("schema" to JsonPrimitive(1), "slotBackups" to JsonArray(oldRecords)))
            repo.restore(repo.importFrom(legacy.toString().byteInputStream()))
        } }
        val originals = repo.slotBackups.value.toList()
        assertEquals(4, originals.size)
        assertTrue(originals.all { it.imported })
        assertEquals(Migrations.UNKNOWN_DEVICE, originals.single { it.slot == 2 }.deviceId)
        assertTrue(originals.single { it.slot == 2 }.uncertain)
        assertNull(repo.originalSlot(DeviceId.DEMO, 1))
        val demo = graph.link as io.github.willzy12h.nocturne.core.link.DemoBedJet
        val slotsBefore = demo.slots.toList()
        settings()
        compose.onNodeWithText("From backup").performScrollTo().assertIsDisplayed()
        listOf("Other saved", "Unknown saved", "Legacy saved").forEach { name ->
            compose.onNode(hasText(name, substring = true)).performScrollTo().assertIsDisplayed()
        }
        compose.onNode(hasText("Matching saved", substring = true)).assertExists()
        // Only the matching original offers the established preview/confirmation restore flow.
        compose.onAllNodesWithText(s(R.string.settings_preview_put_back)).assertCountEquals(1)
        assertEquals(originals, repo.slotBackups.value)
        assertEquals(slotsBefore, demo.slots.toList())
        assertNull(repo.runtime.value.slotWrite)
    }

    @Test fun aMatchingImportedOriginalRequiresPreviewAndExplicitConfirmation() {
        importOriginals(backup(DeviceId.DEMO, 0, "Chosen original"))
        settings()
        compose.onNodeWithText("From backup").assertDoesNotExist()
        compose.onNodeWithText(s(R.string.settings_preview_put_back)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.settings_put_original_back)).assertIsDisplayed()
        assertNull(graph.repository.runtime.value.slotWrite)
        compose.onNodeWithText(s(R.string.feature_cancel)).performClick()
        assertNull(graph.repository.runtime.value.slotWrite)
        compose.onNodeWithText(s(R.string.settings_put_original_back)).assertDoesNotExist()
    }

    @Test fun anOutOfRangeMatchingImportedOriginalIsRefusedAfterExplicitConfirmation() {
        importOriginals(backup(DeviceId.DEMO, 0, "Edited original").copy(program = program("Edited original", temperature = 200)))
        val demo = graph.link as io.github.willzy12h.nocturne.core.link.DemoBedJet
        val before = demo.slots.toList()
        settings()
        compose.onNodeWithText(s(R.string.settings_preview_put_back)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.settings_put_original_back)).performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(s(R.string.runtime_restore_imported_out_of_range)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(s(R.string.runtime_restore_imported_out_of_range)).assertExists()
        assertEquals(before, demo.slots.toList())
        assertNull(graph.repository.runtime.value.slotWrite)
    }

    @Test fun matchingStandbyAndWaitRestoreTheirUnusedTemperatureBytesAfterConfirmation() {
        val original = StoredProgram("Idle original", listOf(
            ProgramStep(Mode.STANDBY, 5, 255, 0, 1),
            ProgramStep(Mode.WAIT, 5, 1, 0, 1),
            ProgramStep(Mode.HEAT, 5, 70, 0, 1),
        ))
        importOriginals(backup(DeviceId.DEMO, 0, "Idle original").copy(program = original))
        val demo = graph.link as io.github.willzy12h.nocturne.core.link.DemoBedJet
        val before = demo.slots.toList()
        settings()
        compose.onNodeWithText(s(R.string.settings_preview_put_back)).performScrollTo().performClick()
        assertEquals("Preview cannot write a slot", before, demo.slots.toList())
        compose.onNodeWithText(s(R.string.settings_put_original_back)).performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            demo.slots[0] == original
        }
        assertEquals(original, demo.slots[0])
        assertEquals(before.drop(1), demo.slots.drop(1))
    }

    @Test fun anUnknownImportedOriginalCannotBeSilentlyAssignedToTheSelectedBedJet() {
        importOriginals(backup(Migrations.UNKNOWN_DEVICE, 0, "Unassigned"))
        val repo = graph.repository
        val original = repo.slotBackups.value.single()
        val demo = graph.link as io.github.willzy12h.nocturne.core.link.DemoBedJet
        val before = demo.slots.toList()
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread { model.restoreSlot(original) }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            compose.onAllNodesWithText(s(R.string.runtime_restore_target_changed)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(before, demo.slots.toList())
        assertEquals(listOf(original), repo.slotBackups.value)
        assertNull(repo.originalSlot(DeviceId.DEMO, 0))
        assertNull(repo.runtime.value.slotWrite)
    }

    @Test fun anUnassignedEmptySlotMarkerIsVisibleWhenNoBedJetIsSelected() {
        importOriginals(SlotBackup(Migrations.UNKNOWN_DEVICE, 0, null, graph.now().toString()))
        compose.runOnUiThread { runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
        } }
        settings()
        compose.onNodeWithText("From backup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved from slot 1: empty slot").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(s(R.string.settings_preview_put_back)).assertCountEquals(0)
        assertNull(graph.repository.runtime.value.slotWrite)
    }
}
