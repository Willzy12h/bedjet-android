package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CooldownOfferLayoutTest {
    private val profiles = listOf(
        Presets.all.first().copy(id = "layout-empty", name = "Empty after wake", afterWake = emptyList()),
        Presets.all.first().copy(id = "layout-existing", name = "Existing after wake", afterWake = listOf(Phase(Mode.HEAT, 64, 40, 20))),
    )
    private val ui = U06Fixture(U06Look.MIDNIGHT) { fixture, _ ->
        runBlocking {
            fixture.graph.repository.profiles.value.forEach { fixture.graph.repository.deleteProfile(it.id) }
            profiles.forEach { fixture.graph.repository.saveProfile(it) }
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun offerChoicesAndActionsRemainReachableAndReadableAtLargeText() {
        ui.tab(ui.s(R.string.tab_profiles))
        profiles.forEach { profile ->
            val row = ui.checkControl(ui.compose.onNodeWithTag("cooldown-choice-${profile.id}"), profile.name)
            row.assertTextContains(profile.name, substring = true).assertHasClickAction()
        }
        ui.checkControl(ui.compose.onNode(hasText("Add") and hasClickAction()), "Add")
        ui.checkControl(ui.compose.onNode(hasText("No thanks") and hasClickAction()), "No thanks")
    }
}
