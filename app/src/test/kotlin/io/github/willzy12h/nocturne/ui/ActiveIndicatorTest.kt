package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.night.NightActions.Command
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the actual marker geometry; the real lifecycle, setting and broadcasts gate its finite pulse. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ActiveIndicatorTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private fun tab(id: Int) {
        ui.compose.onNode(hasText(ui.s(id)) and hasClickAction()).performClick()
        ui.compose.waitForIdle()
    }
    @After fun restoreClock() { ui.compose.mainClock.autoAdvance = true }
    private fun warm() {
        // Compact Tonight has no collapsible sections; scroll the actual preset into view.
        val control = ui.compose.onNodeWithTag("quick-mode-HEAT")
        control.performScrollTo()
        ui.compose.mainClock.autoAdvance = false
        control.performClick()
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.HEAT && ui.graph.actions.busy.value.runningLabel == null }
        ui.waitUntil { ui.compose.onAllNodesWithTag("quick-active-HEAT", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun legacyAction() = reportedQuickAction(ui.graph.link.status.value, ui.graph.link.state.value,
        ui.graph.repository.settings.value, ui.graph.repository.activeNight.value,
        ui.graph.repository.runtime.value.slotWrite, ui.model.tonightsProfile()?.limits ?: ProfileLimits())
    private fun width() = ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).fetchSemanticsNode().let { it.boundsInRoot.width / it.layoutInfo.density.density }

    @Test fun activeQuickMarkerHasAFinitePulseAndStopsWhenOffscreen() {
        AndroidSettings.Global.putFloat(ui.compose.activity.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f)
        warm()
        ui.compose.mainClock.advanceTimeBy(400)
        assertTrue("Active marker should have a subtle pulse", width() > 7.1f)
        ui.compose.mainClock.advanceTimeBy(3_000)
        assertEquals("The pulse settles; it never loops indefinitely", 7f, width(), .2f)
        ui.compose.mainClock.autoAdvance = true
        tab(R.string.tab_settings)
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertDoesNotExist()
        ui.compose.mainClock.autoAdvance = true
    }

    @Test fun removeAnimationsLeavesAnHonestStaticActiveMarker() {
        AndroidSettings.Global.putFloat(ui.compose.activity.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
        warm()
        ui.compose.mainClock.advanceTimeBy(400)
        assertEquals(7f, width(), .2f)
        ui.compose.mainClock.advanceTimeBy(800)
        assertEquals(7f, width(), .2f)
        ui.compose.mainClock.autoAdvance = true
    }

    @Test fun aCustomLongHeatPresetDoesNotFalselyMarkTheHeatTile() {
        val repository = ui.graph.repository
        ui.compose.runOnUiThread { kotlinx.coroutines.runBlocking {
            repository.updateSettings { it.copy(extendWarm = it.extendWarm.copy(mode = Mode.EXTENDED_HEAT)) }
        } }
        ui.compose.runOnUiThread { ui.model.run(Command.WARM) }
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.EXTENDED_HEAT }
        ui.compose.openRemote()
        ui.compose.onNode(hasText(ui.s(R.string.mode_long_heat)) and hasClickAction()).performScrollTo().assertIsSelected()
        ui.compose.navTab("Tonight")
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aTrackedNightTurboPhaseDoesNotClaimTheQuickBoostIsActive() {
        val profile = requireNotNull(ui.model.tonightsProfile())
        ui.compose.runOnUiThread { kotlinx.coroutines.runBlocking {
            ui.graph.repository.saveProfile(profile.copy(start = listOf(
                io.github.willzy12h.nocturne.core.plan.Phase(Mode.TURBO, 86, 100, 10))))
        } }
        ui.compose.onNodeWithText(ui.s(R.string.tonight_start_now)).performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.activeNight.value?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED &&
            ui.graph.link.status.value?.mode == Mode.TURBO }
        ui.compose.openTonightDetail()
        ui.compose.onNodeWithTag("night-active-phase", useUnmergedTree = true).assertExists()
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun manualTurboDoesNotClaimTheUploadedQuickBoostIsActive() {
        ui.compose.runOnUiThread { ui.model.run(io.github.willzy12h.nocturne.night.NightActions.Command.TURBO) }
        ui.waitUntil { ui.graph.link.status.value?.let { it.mode == Mode.TURBO && it.extra?.programStep == 0 } == true }
        assertNull("Manual Turbo must not invent the legacy uploaded Boost identity", legacyAction())
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertExists()
    }

    @Test fun aConfirmedBoostWithMatchingProgrammeProgressGetsTheQuickMarker() {
        ui.compose.runOnUiThread { ui.model.run(Command.BOOST) }
        ui.waitUntil { ui.graph.repository.runtime.value.slotWrite?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED &&
            ui.graph.link.status.value?.let { it.mode == Mode.TURBO && (it.extra?.programStep ?: 0) > 0 } == true }
        assertEquals(Command.BOOST, legacyAction())
        ui.compose.onNodeWithTag("quick-active-BOOST", useUnmergedTree = true).assertExists()
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aConfirmedSlotJournalDoesNotHideLaterManualReceiptsButUncertainWritesDo() {
        aConfirmedBoostWithMatchingProgrammeProgressGetsTheQuickMarker()
        val journal = requireNotNull(ui.graph.repository.runtime.value.slotWrite)
        ui.compose.runOnUiThread { ui.model.run(Command.OFF) }
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.STANDBY && ui.graph.actions.busy.value.runningLabel == null }
        ui.compose.onNodeWithTag("quick-active-STANDBY", useUnmergedTree = true).assertExists()
        ui.compose.onNodeWithTag("quick-active-BOOST", useUnmergedTree = true).assertDoesNotExist()
        ui.compose.runOnUiThread { ui.model.runCaptured(captureModeQuick(ui.model,
            io.github.willzy12h.nocturne.core.store.QuickDefaults.resolve(ui.model.settings.value, Mode.HEAT)).end) }
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.HEAT && ui.graph.actions.busy.value.runningLabel == null }
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertExists()
        assertEquals("Presentation must not erase recovery evidence", journal, ui.graph.repository.runtime.value.slotWrite)
        for (confidence in listOf(io.github.willzy12h.nocturne.core.night.Confidence.PENDING,
            io.github.willzy12h.nocturne.core.night.Confidence.FAILED, io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN)) {
            ui.compose.runOnUiThread { kotlinx.coroutines.runBlocking {
                ui.graph.repository.updateRuntime { it.copy(slotWrite = journal.copy(confidence = confidence)) }
            } }
            ui.compose.waitForIdle()
            ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun changingTheConfiguredBoostDurationDoesNotRelabelTheRunningProgrammeAsActive() {
        aConfirmedBoostWithMatchingProgrammeProgressGetsTheQuickMarker()
        ui.compose.runOnUiThread { kotlinx.coroutines.runBlocking {
            ui.graph.repository.updateSettings { it.copy(boostMinutes = if (it.boostMinutes == 15) 30 else 15) }
        } }
        assertNull("Changed defaults must not identify the running legacy programme", legacyAction())
        ui.compose.onNodeWithTag("quick-active-BOOST", useUnmergedTree = true).assertDoesNotExist()
        ui.compose.onNodeWithTag("quick-active-TURBO", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun identicalWarmAndCoolPresetsDoNotInventAnActiveRequest() {
        ui.compose.runOnUiThread { kotlinx.coroutines.runBlocking {
            ui.graph.repository.updateSettings { it.copy(extendCool = it.extendWarm) }
        } }
        ui.compose.onNodeWithTag("quick-mode-HEAT").performScrollTo().performClick()
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.HEAT && ui.graph.actions.busy.value.runningLabel == null }
        assertNull("Identical legacy presets cannot identify a Warm or Cool request", legacyAction())
        // The new mode grid has exactly one Heat button: it may show the actual reported manual mode.
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertExists()
        ui.compose.onNodeWithTag("quick-active-COOL", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun screenOffAndPausedLifecycleCancelThePulse() {
        AndroidSettings.Global.putFloat(ui.compose.activity.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f)
        warm()
        ui.compose.activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        ui.compose.mainClock.advanceTimeBy(500)
        assertEquals("A screen-off broadcast stops motion immediately", 7f, width(), .2f)
        ui.compose.activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val marker = ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).fetchSemanticsNode()
        ui.compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        ui.compose.mainClock.advanceTimeBy(500)
        assertEquals("A paused Activity keeps the active state without motion", 7f,
            marker.boundsInRoot.width / marker.layoutInfo.density.density, .2f)
        ui.compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.compose.mainClock.autoAdvance = true
    }
}
