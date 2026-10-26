package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.ShortTestPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ShortTestTonightIntegrationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, _ ->
        runBlocking {
            val settings = f.graph.repository.settings.value
            assertEquals(OutcomeKind.CONFIRMED, f.graph.actions.startShortTest(
                ShortTestPreview(settings.deviceId()!!, settings.workingSlot, f.graph.now())).kind)
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun aTestRunShowsItsNameAndFixedTimingWithAGuardedStop() {
        ui.compose.onNodeWithText("Five-minute BedJet test").assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.tonight_set_wake)).assertDoesNotExist()
        ui.compose.onNodeWithText("Stop night").performScrollTo().performClick()
        ui.compose.onNodeWithText("Stop night?").assertIsDisplayed()
        ui.compose.onNodeWithText("Keep tonight").performClick()
    }
}
