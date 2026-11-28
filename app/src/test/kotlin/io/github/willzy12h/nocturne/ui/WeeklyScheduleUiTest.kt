package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WeeklyScheduleUiTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun editingAProfileReturnsToThePendingFridayChoice() {
        val target = ui.graph.repository.profiles.value[1]
        val other = ui.graph.repository.profiles.value.first()
        ui.tab(ui.s(R.string.tab_profiles))
        ui.compose.onNodeWithTag("profiles-weekly-schedule").performClick()
        ui.compose.onNodeWithTag("weekly-day-5").performScrollTo().performClick()
        ui.compose.onNodeWithTag("weekly-pick-${target.id}").performScrollTo().performClick()
        ui.compose.onNodeWithTag("weekly-edit-${other.id}").performScrollTo().performClick()
        ui.compose.onNodeWithTag("editor-save").assertExists().performClick()
        ui.compose.onNodeWithTag("weekly-save-day").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil {
            ui.graph.repository.settings.value.resolveProfile(
                ui.graph.repository.profiles.value, ui.graph.now().plusDays(1)
            )?.id == target.id
        }
    }

    @Test fun tonightChoiceIsExplicitAndOffersTheWeeklySchedule() {
        val target = ui.graph.repository.profiles.value[1]
        ui.compose.onNodeWithTag("tonight-change").performScrollTo().performClick()
        ui.compose.onNodeWithTag("tonight-choose-${target.id}").performScrollTo().performClick()
        ui.compose.onNodeWithTag("tonight-picker-schedule").assertIsDisplayed()
        ui.compose.onNodeWithTag("tonight-picker-use").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.tonightChoice?.profileId == target.id }
        ui.compose.onNodeWithTag("tonight-profile-scope").performScrollTo().assertIsDisplayed()
        ui.compose.onNodeWithTag("tonight-change").performScrollTo().performClick()
        ui.compose.onNodeWithTag("tonight-picker-schedule").performClick()
        ui.compose.onNodeWithTag("weekly-tonight-override").performScrollTo().assertIsDisplayed()
    }
}
