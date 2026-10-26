package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A valid imported preset must not bypass the one-action Turbo cap through a named Quick button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportedTemporaryDurationTest {
    private val imported = ActionPreset(Mode.TURBO, 86, 100, 15)
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ -> runBlocking {
        val repo = fixture.graph.repository
        val saved = repo.bundle().let { it.copy(settings = it.settings.copy(extendWarm = imported)) }
        val decoded = BackupCodec.decode(BackupCodec.encode(saved))
        assertEquals(emptyList<String>(), BackupValidator.validate(decoded))
        repo.restore(decoded)
        assertEquals(imported, repo.settings.value.extendWarm)
        fixture.graph.actions.confirmMs = 20
        fixture.graph.actions.freshStatusMs = 20
        assertEquals(OutcomeKind.CONFIRMED, fixture.graph.actions.execute(
            fixture.graph.actions.capture(Command.START_NIGHT)).kind)
    } }
    @get:Rule val rules = ui.rules

    @Test fun importedTurboWarmStartsWithAnExplicitTenMinuteTemporaryPreviewAndDoesNotBecomeBoost() {
        val original = ui.graph.repository.activeNight.value!!
        ui.control(ui.s(R.string.feature_warm)).performClick()
        val inDialog = hasAnyAncestor(isDialog())
        ui.compose.onNode(hasText(ui.s(R.string.temporary_duration, "10 min")) and inDialog).assertExists()
        ui.compose.onNode(hasText(ui.s(R.string.temporary_confirm)) and hasClickAction() and inDialog)
            .assertIsEnabled().performClick()
        ui.waitUntil { ui.graph.repository.activeNight.value?.let {
            it.revision == original.revision + 1 && it.confidence == Confidence.CONFIRMED
        } == true && ui.graph.actions.busy.value.runningLabel == null }
        val updated = ui.graph.repository.activeNight.value!!
        assertEquals(original.recordId, updated.recordId)
        assertEquals(original.wakeAt, updated.wakeAt)
        assertEquals(10, updated.temporary!!.steps.sumOf { it.minutes })
        assertTrue("A Turbo-only choice must not add Boost's Heat continuation",
            updated.temporary!!.steps.all { it.mode == Mode.TURBO })
        assertEquals("The preview edits only this action, not the imported saved preset", imported,
            ui.graph.repository.settings.value.extendWarm)
    }

    @Test fun anUnsupportedImportedTurboEndingIsExplainedAndDisabledWithoutAdvertisingFifteenMinutes() {
        val original = ui.graph.repository.activeNight.value
        val physical = ui.graph.link.status.value
        val generation = ui.graph.repository.runtime.value.generation
        ui.control(ui.s(R.string.feature_warm)).performClick()
        val inDialog = hasAnyAncestor(isDialog())
        val selectedChoice = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        fun button(id: Int) = ui.compose.onNode(hasText(ui.s(id)) and hasClickAction() and inDialog and !selectedChoice)
        fun choice(id: Int) = ui.compose.onNode(hasText(ui.s(id)) and hasClickAction() and inDialog and selectedChoice)
        button(R.string.temporary_confirm).assertIsEnabled()
        ui.compose.onNode(hasText(ui.s(R.string.temporary_duration, "10 min")) and inDialog).assertExists()

        choice(R.string.temporary_end_choice).performScrollTo().performClick()
        button(R.string.guard_confirm).assertIsNotEnabled().performTouchInput { click() }
        ui.compose.onNode(hasText(ui.s(R.string.runtime_temporary_invalid)) and inDialog).assertExists()
        ui.compose.onAllNodes(hasText("15 min", substring = true) and inDialog).assertCountEquals(0)
        assertEquals("The unsupported ending must not enter the command owner", generation,
            ui.graph.repository.runtime.value.generation)
        assertEquals(original, ui.graph.repository.activeNight.value)
        assertEquals(physical, ui.graph.link.status.value)

        choice(R.string.temporary_choice).performScrollTo().performClick()
        button(R.string.temporary_confirm).assertIsEnabled()
        ui.compose.onNode(hasText(ui.s(R.string.temporary_duration, "10 min")) and inDialog).assertExists()
        button(R.string.guard_keep).performClick()
        assertEquals(original, ui.graph.repository.activeNight.value)
        assertEquals(physical, ui.graph.link.status.value)
        assertEquals(imported, ui.graph.repository.settings.value.extendWarm)
    }
}
