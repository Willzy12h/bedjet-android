package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F4: a maximum-size uncertain original stays reviewable before the user accepts a restore. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoreOriginalDialogLayoutTest(look: U06Look) {
    private val ui = U06Fixture(look) { fixture, _ ->
        runBlocking {
            val repo = fixture.graph.repository
            val original = SlotBackup(
                DeviceId.DEMO,
                0,
                StoredProgram("Saved original", List(32) { ProgramStep(Mode.HEAT, 5, 70, 0, 1) }),
                fixture.graph.now().toString(),
                uncertain = true,
            )
            repo.restore(repo.importFrom(BackupCodec.encode(repo.bundle().copy(slotBackups = listOf(original))).byteInputStream()))
        }
    }
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf(it) }

        private const val UNCERTAINTY_WARNING = "May not be the true original: the slot may already have held one of Nocturne's programmes."
    }

    @Test fun everyOriginalStepAndTheCompleteWarningAreReachableWithBothActionsVisible() {
        ui.tab("Settings")
        ui.compose.openSettingsPage("bedjet")
        ui.control(ui.s(R.string.settings_preview_put_back)).performClick()

        val finalStep = dialogText(ui.s(R.string.settings_slot_step, 32, "Heat", 30, "1 min"))
        val finalNode = finalStep.fetchSemanticsNode()
        val scroll = generateSequence(finalNode) { it.parent }.firstOrNull {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
        }
        assertNotNull(
            "The 32nd original step is inaccessible without a vertical scroll route: " +
                "visible=${finalNode.boundsInRoot}, size=${finalNode.size}, position=${finalNode.positionInRoot}",
            scroll,
        )

        // Exercise the real dialog body in both directions. U06 checks all text lines, unclipped bounds
        // and 48 dp action targets, so a tiny visible part of a paragraph or button is not sufficient.
        ui.checkLayout(finalStep, "Original step 32")
        assertDialogFrame()
        ui.checkLayout(dialogText(UNCERTAINTY_WARNING), "Complete original uncertainty warning")
        assertDialogFrame()
        (1..32).forEach { step ->
            ui.checkLayout(dialogText(ui.s(R.string.settings_slot_step, step, "Heat", 30, "1 min")), "Original step $step")
        }
        assertDialogFrame()
    }

    private fun dialogText(text: String) = ui.compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun assertDialogFrame() {
        ui.checkLayout(dialogText(ui.s(R.string.settings_restore_slot_title, 1)), "Restore original title", scroll = false)
        ui.checkControl(dialogText(ui.s(R.string.settings_put_original_back)), "Put original back", scroll = false)
        ui.checkControl(dialogText(ui.s(R.string.feature_cancel)), "Cancel restore", scroll = false)
    }
}
