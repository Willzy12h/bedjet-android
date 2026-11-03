package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class P8LegacyTurboDisplayTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun legacyTurboShowsEffectiveTemperatureWithoutChangingStoredChainedPhase() {
        val phase = Phase(Mode.TURBO, temperature = 60, fanPercent = 40, minutes = 30)
        val profile = ui.graph.repository.profiles.value.first().copy(id = "legacy-turbo", start = listOf(phase))
        val compiled = PlanCompiler.phaseSteps(phase, profile.limits, profile.turboChain)
        assertEquals(86, compiled.first().temperature)
        assertEquals(30, compiled.sumOf { it.durationMinutes })
        var draft: Profile = profile
        for ((fahrenheit, expected) in listOf(false to "43°C", true to "109°F")) {
            ui.compose.runOnUiThread { runBlocking { ui.graph.repository.updateSettings { it.copy(fahrenheit = fahrenheit) } } }
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(ui.graph.repository.settings.collectAsState().value, ui.graph.now()) {
                    key(fahrenheit) { ProfileEditor(ui.model, profile, onDone = {}, onDraftChange = { draft = it }) }
                }
            } }
            ui.compose.waitForIdle()
            val row = ui.compose.onNodeWithTag("editor-phase-START-0").performScrollTo()
            row.assertContentDescriptionContains(expected, substring = true)
            row.assertTextContains("30 min", substring = true)
            row.performClick()
            val controls = hasAnyAncestor(hasTestTag("editor-phase-controls-START-0"))
            ui.compose.onNodeWithTag("step-temp").assertTextEquals(expected)
            ui.compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and controls).assertCountEquals(0)
            assertEquals(phase, draft.start.single())
            assertEquals(compiled, PlanCompiler.phaseSteps(draft.start.single(), draft.limits, draft.turboChain))
        }
    }
}
