package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
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
class CooldownOfferTest {
    private val empty = Presets.all.first().copy(id = "empty", name = "Empty after wake", afterWake = emptyList())
    private val existing = Presets.all.first().copy(id = "existing", name = "Existing after wake", afterWake = listOf(Phase(Mode.HEAT, 64, 40, 20)))
    private val cooldown = listOf(Phase(Mode.COOL, 44, 30, 10))
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ ->
        runBlocking {
            fixture.graph.repository.profiles.value.forEach { fixture.graph.repository.deleteProfile(it.id) }
            fixture.graph.repository.saveProfile(empty)
            fixture.graph.repository.saveProfile(existing)
        }
    }
    @get:Rule val rules = ui.rules
    private fun profiles() {
        ui.compose.navTab(ui.s(R.string.tab_profiles))
        if (!ui.graph.repository.settings.value.cooldownOfferAnswered)
            ui.compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Add a 10-minute Cooldown after waking?"))
    }
    private fun choice(id: String) = ui.compose.onNodeWithTag("cooldown-choice-$id").performScrollTo()
    private fun offer() = ui.compose.onNodeWithText("Add a 10-minute Cooldown after waking?")
    private fun reopen() {
        ui.compose.navTab(ui.s(R.string.tab_tonight))
        profiles()
    }

    @Test fun offerDefaultsAreExplicitAndAddChangesOnlySelectedProfiles() {
        profiles()
        offer().assertIsDisplayed()
        choice(empty.id).assertIsOn()
        choice(existing.id).assertIsOff()
        choice(existing.id).assertTextContains("20 min", substring = true)
        assertEquals(empty.afterWake, ui.graph.repository.profiles.value.single { it.id == empty.id }.afterWake)
        assertEquals(existing.afterWake, ui.graph.repository.profiles.value.single { it.id == existing.id }.afterWake)
        ui.compose.onNode(hasText("Add") and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.profiles.value.single { it.id == empty.id }.afterWake == cooldown }
        assertEquals(existing.afterWake, ui.graph.repository.profiles.value.single { it.id == existing.id }.afterWake)
        offer().assertDoesNotExist()
        reopen()
        offer().assertDoesNotExist()
    }

    @Test fun changingSelectionsReplacesOnlyTheExplicitlySelectedExistingPhase() {
        profiles()
        choice(empty.id).performClick()
        choice(existing.id).performClick()
        ui.compose.onNode(hasText("Add") and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.profiles.value.single { it.id == existing.id }.afterWake == cooldown }
        assertEquals(empty.afterWake, ui.graph.repository.profiles.value.single { it.id == empty.id }.afterWake)
        reopen()
        offer().assertDoesNotExist()
    }

    @Test fun noThanksLeavesAllProfilesUnchangedAndDismissesPermanently() {
        profiles()
        ui.compose.onNode(hasText("No thanks") and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil { ui.compose.onAllNodesWithText("Add a 10-minute Cooldown after waking?").fetchSemanticsNodes().isEmpty() }
        assertEquals(empty.afterWake, ui.graph.repository.profiles.value.single { it.id == empty.id }.afterWake)
        assertEquals(existing.afterWake, ui.graph.repository.profiles.value.single { it.id == existing.id }.afterWake)
        reopen()
        offer().assertDoesNotExist()
    }

    @Test fun newProfileFromPresetKeepsItsOwnAfterWake() {
        profiles()
        ui.compose.onNodeWithTag("profiles-new").performClick()
        val preset = Presets.summerCool
        ui.compose.onNode(hasText(preset.name) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-phase-AFTER_WAKE-0").performScrollTo().assertTextContains("20 min", substring = true)
            .assertTextContains("40%", substring = true)
    }

    @Test fun newProfileFromPresetWithoutAfterWakeStartsWithCooldown() {
        profiles()
        ui.compose.onNodeWithTag("profiles-new").performClick()
        val preset = Presets.springAutumn
        ui.compose.onNode(hasText(preset.name) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-phase-AFTER_WAKE-0").performScrollTo().assertTextContains("10 min", substring = true)
            .assertContentDescriptionContains("22°C", substring = true).assertTextContains("30%", substring = true)
        assertEquals(empty.afterWake, ui.graph.repository.profiles.value.single { it.id == empty.id }.afterWake)
        assertEquals(existing.afterWake, ui.graph.repository.profiles.value.single { it.id == existing.id }.afterWake)
    }
}
