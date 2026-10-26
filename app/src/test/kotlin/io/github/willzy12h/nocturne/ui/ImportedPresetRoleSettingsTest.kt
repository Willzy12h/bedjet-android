package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.BackupValidator
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Saved timed actions and the Quick buttons which share Extend presets have distinct eligibility. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportedPresetRoleSettingsTest(private val role: PresetRole) {
    enum class PresetRole(val command: Command, val title: Int, val quickName: Int?) {
        SNOOZE_WARM(Command.SNOOZE_WARM, R.string.settings_preset_snooze_warm, null),
        SNOOZE_COOL(Command.SNOOZE_COOL, R.string.settings_preset_snooze_cool, null),
        EXTEND_WARM(Command.EXTEND_WARM, R.string.settings_preset_extend_warm, R.string.feature_warm),
        EXTEND_COOL(Command.EXTEND_COOL, R.string.settings_preset_extend_cool, R.string.feature_cool);

        fun assign(settings: Settings, preset: ActionPreset): Settings = when (this) {
            SNOOZE_WARM -> settings.copy(snoozeWarm = preset)
            SNOOZE_COOL -> settings.copy(snoozeCool = preset)
            EXTEND_WARM -> settings.copy(extendWarm = preset)
            EXTEND_COOL -> settings.copy(extendCool = preset)
        }
        fun saved(settings: Settings): ActionPreset = when (this) {
            SNOOZE_WARM -> settings.snoozeWarm
            SNOOZE_COOL -> settings.snoozeCool
            EXTEND_WARM -> settings.extendWarm
            EXTEND_COOL -> settings.extendCool
        }
    }

    private val imported = ActionPreset(Mode.TURBO, 86, 100, 10)
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ -> runBlocking {
        val graph = fixture.graph
        val saved = graph.repository.bundle().let { it.copy(settings = role.assign(it.settings, imported)) }
        val decoded = BackupCodec.decode(BackupCodec.encode(saved))
        assertEquals(emptyList<String>(), BackupValidator.validate(decoded))
        graph.repository.restore(decoded)
        assertTrue(graph.repository.settings.value.demoMode)
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 100
        val result = graph.actions.execute(graph.actions.capture(role.command))
        assertEquals("The actual timed $role command supports the imported ten-minute Turbo preset",
            OutcomeKind.CONFIRMED, result.kind)
        assertEquals(Mode.TURBO, graph.link.status.value!!.mode)
        assertEquals(600, graph.link.status.value!!.remainingSeconds)
        assertEquals(0, graph.link.status.value!!.extra!!.programStep)
        assertEquals(imported, role.saved(graph.repository.settings.value))
        assertNull(graph.repository.activeNight.value)
    } }
    @get:Rule val rules = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun roles(): List<Array<Any>> = PresetRole.entries.map { arrayOf(it) }
    }

    @Test fun settingsDescribesTheSupportedTimedActionAndOnlyItsActualQuickRestriction() {
        val generation = ui.graph.repository.runtime.value.generation
        val history = ui.graph.repository.history.value
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        val summary = ui.s(R.string.settings_preset_summary, ui.s(role.title), ui.s(R.string.mode_turbo), 100, 10)
        val failures = mutableListOf<String>()
        if (ui.compose.onAllNodesWithText(summary).fetchSemanticsNodes().isEmpty()) {
            failures += "The supported timed action's ten-minute summary is missing"
        } else ui.compose.onNodeWithText(summary).performScrollTo().assertIsDisplayed()
        if (ui.compose.onAllNodesWithText(ui.s(R.string.settings_preset_unsupported)).fetchSemanticsNodes().isNotEmpty()) {
            failures += "The whole supported timed preset is incorrectly labelled unsupported"
        }
        if (role.quickName != null) {
            val note = "This preset is unsupported by the ${ui.s(role.quickName)} Quick button. Choose a supported mode below to use that button."
            if (ui.compose.onAllNodesWithText(note).fetchSemanticsNodes().isEmpty()) {
                failures += "The distinct Quick-button restriction is not explained"
            } else ui.compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        } else {
            if (ui.compose.onAllNodes(hasText("unsupported", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()) {
                failures += "A supported Snooze preset must not inherit Quick's unsupported warning"
            }
        }
        assertEquals("Viewing Settings cannot repair or normalize the imported action", imported,
            role.saved(ui.graph.repository.settings.value))
        assertEquals(generation, ui.graph.repository.runtime.value.generation)
        assertEquals(history, ui.graph.repository.history.value)
        assertNull(ui.graph.repository.activeNight.value)
        assertTrue("$role presentation must match its actual command policy:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}
