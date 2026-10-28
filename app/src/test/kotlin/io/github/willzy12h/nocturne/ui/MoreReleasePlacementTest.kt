package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.DeviceNames
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Moving connection release into Settings leaves Tonight's other recovery actions reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MoreReleasePlacementTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    private fun checkMore(tracked: Boolean) {
        ui.compose.runOnUiThread { runBlocking {
            if (tracked) assertEquals(OutcomeKind.CONFIRMED, ui.graph.actions.execute(
                ui.graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        } }
        if (tracked) ui.compose.onNode(hasText(ui.s(R.string.recovery_more)) and hasClickAction()).performScrollTo().performClick()
        else ui.compose.onNode(hasText(ui.s(R.string.recovery_more)) and hasClickAction()).assertDoesNotExist()
        ui.compose.onNodeWithText("Request BedJet from another phone").assertDoesNotExist()
        if (tracked) ui.compose.onNodeWithText(ui.s(R.string.recovery_recheck_alarm)).assertIsDisplayed().assertHasClickAction()
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).assertDoesNotExist()
        ui.compose.onNodeWithText(ui.s(R.string.feature_take_back)).assertDoesNotExist()
        assertEquals(tracked, ui.graph.repository.activeNight.value != null)
        assertFalse("Opening More must not release the connection", ui.graph.sessions.released.value)
    }

    @Test fun idleHasNoPeerRequestOrReleaseMenu() = checkMore(tracked = false)

    @Test fun trackedMoreKeepsRecheckButNoPeerRequestOrRelease() = checkMore(tracked = true)

    @Test fun idleWithoutHandoverDoesNotOfferAnEmptyMoreMenu() {
        assertNull(ui.graph.repository.activeNight.value)
        ui.compose.onNode(hasText(ui.s(R.string.recovery_more)) and hasClickAction()).assertDoesNotExist()
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("bedjet")
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsStillReleasesAndTakesBackTheConnectionThroughTheOwner() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("bedjet")
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).performScrollTo().performClick()
        ui.waitUntil { ui.graph.sessions.released.value }
        ui.compose.onNodeWithText(ui.s(R.string.feature_take_back)).performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil { !ui.graph.sessions.released.value && ui.graph.actions.busy.value.runningLabel == null }
        ui.compose.onNodeWithText(ui.s(R.string.feature_release)).assertExists()
        assertNull(ui.graph.repository.activeNight.value)
    }

    @Test fun rememberedNameDoesNotClaimConnection() {
        val target = "ble:AA:BB:CC:DD:EE:FF"
        val rememberedName = "BEDJET-BEDROOM"
        ui.compose.runOnUiThread {
            ui.graph.sessions.suspendAutoConnect(untilMs = null)
            runBlocking {
                ui.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target) }
            }
            ReflectionHelpers.getField<DeviceNames>(ui.graph.bleLink, "names").display(target, rememberedName)
        }
        ui.waitUntil {
            !ui.model.settings.value.demoMode && ui.model.bedJetName() == rememberedName &&
                ui.graph.link.state.value != LinkState.READY
        }
        assertNull("A remembered selection is not a current device report", ui.graph.link.latestSourcedStatus())
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("bedjet")
        ui.compose.onNodeWithText(ui.s(R.string.settings_connected_unit, rememberedName))
            .performScrollTo().assertIsDisplayed().assertTextEquals("BedJet name: BEDJET-BEDROOM")
        ui.compose.onNodeWithText("Connected unit: BEDJET-BEDROOM").assertDoesNotExist()
        assertEquals(target, ui.graph.repository.settings.value.targetDevice)
        assertNotEquals("Viewing Settings must not establish a connection", LinkState.READY, ui.graph.link.state.value)
        assertNull(ui.graph.link.latestSourcedStatus())
    }

    @Test fun aSelectedUnnamedBedJetShowsTheNameFallbackWithoutLosingItsSelection() {
        val target = "ble:AA:BB:CC:DD:EE:01"
        ui.compose.runOnUiThread {
            ui.graph.sessions.suspendAutoConnect(untilMs = null)
            runBlocking {
                ui.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target) }
            }
        }
        ui.waitUntil {
            !ui.model.settings.value.demoMode && ui.model.settings.value.targetDevice == target &&
                ui.model.bedJetName() == null && ui.graph.link.state.value != LinkState.READY
        }
        assertNull("The selected target has no stored name", DeviceNames(ui.graph.context) { target }.displayName())
        assertNull("An unnamed selection is not a current device report", ui.graph.link.latestSourcedStatus())
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("bedjet")
        ui.compose.onNodeWithText(ui.s(R.string.settings_connected_unit, ui.s(R.string.settings_none_yet)))
            .performScrollTo().assertIsDisplayed().assertTextEquals("BedJet name: none yet")
        ui.compose.onNodeWithText("Connected unit: none yet").assertDoesNotExist()
        assertEquals(target, ui.graph.repository.settings.value.targetDevice)
        assertNotEquals("Viewing Settings must not establish a connection", LinkState.READY, ui.graph.link.state.value)
        assertNull(ui.model.bedJetName())
        assertNull(ui.graph.link.latestSourcedStatus())
    }
}
