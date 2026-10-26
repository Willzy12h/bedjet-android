package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moving connection release into Settings leaves Tonight's other recovery actions reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MoreReleasePlacementTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    private fun checkMore(tracked: Boolean) {
        ui.compose.runOnUiThread { runBlocking {
            ui.graph.repository.updateSettings { it.copy(platform = it.platform.copy(lullHandover = true)) }
            if (tracked) assertEquals(OutcomeKind.CONFIRMED, ui.graph.actions.execute(
                ui.graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        } }
        ui.compose.onNode(hasText(ui.s(R.string.recovery_more)) and hasClickAction()).performScrollTo().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.feature_request)).assertIsDisplayed().assertHasClickAction()
        if (tracked) ui.compose.onNodeWithText(ui.s(R.string.recovery_recheck_alarm)).assertIsDisplayed().assertHasClickAction()
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.feature_take_back)).assertDoesNotExist()
        assertEquals(tracked, ui.graph.repository.activeNight.value != null)
        assertFalse("Opening More must not release the connection", ui.graph.sessions.released.value)
    }

    @Test fun idleMoreKeepsTheRequestActionButNoLongerContainsRelease() = checkMore(tracked = false)

    @Test fun trackedMoreKeepsRecheckAndRequestButNoLongerContainsRelease() = checkMore(tracked = true)

    @Test fun settingsStillReleasesAndTakesBackTheConnectionThroughTheOwner() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).performScrollTo().performClick()
        ui.waitUntil { ui.graph.sessions.released.value }
        ui.compose.onNodeWithText(ui.s(R.string.feature_take_back)).performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil { !ui.graph.sessions.released.value && ui.graph.actions.busy.value.runningLabel == null }
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).assertExists()
        assertNull(ui.graph.repository.activeNight.value)
    }
}
