package io.github.willzy12h.nocturne.ui

import android.provider.Settings as AndroidSettings
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.core.protocol.Mode
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class FinalRemoteLayoutTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT) { f, description ->
        runBlocking {
            f.graph.actions.confirmMs = 20
            assertEquals(OutcomeKind.CONFIRMED, f.graph.actions.execute(f.graph.actions.capture(Command.WARM)).kind)
            if (description.methodName.contains("NoTarget")) {
                // Returning to selection after removing the active target still shows the ordinary header.
                f.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null,
                    rememberedBedJets = listOf("ble:00:11:22:33:44:55")) }
                f.graph.context.getSharedPreferences("ble", android.content.Context.MODE_PRIVATE)
                    .edit().putString("name", "Previous BedJet").remove("nameDevice").apply()
            }
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun tonightOwnsTheCompactStatusAndRemoteReturnsToIt() {
        ui.compose.onAllNodesWithTag("status-header").assertCountEquals(1)
        ui.compose.onNodeWithTag("status-connection").assertTextEquals("Demo · no BedJet connected")
        ui.tab("Remote")
        ui.tab("Tonight")
        ui.compose.onAllNodesWithTag("status-header").assertCountEquals(1)
        ui.compose.onNodeWithTag("status-connection").assertTextEquals("Demo · no BedJet connected")
        ui.compose.onNodeWithTag("home-temperature").performScrollTo().assertIsDisplayed()
    }

    @Test fun demoExitIsReachableThroughTonightFromEveryMainDestination() {
        listOf("Remote", "Settings", "Profiles", "History", "Tonight").forEach { tab ->
            ui.tab(tab)
            ui.tab("Tonight")
            ui.compose.onNodeWithText("Demo · no BedJet connected").assertIsDisplayed()
            ui.checkControl(ui.compose.onNodeWithText("Exit demo"), "Exit demo", scroll = false)
        }
        ui.compose.onNodeWithText("Exit demo").performClick()
        ui.waitUntil { !ui.graph.repository.settings.value.demoMode }
        ui.compose.onNodeWithText("Demo · no BedJet connected").assertDoesNotExist()
    }

    @Test fun withNoTargetTheHeaderCannotReuseAPreviouslyConnectedName() {
        assertNull("A legacy display name cannot stand for an unselected target", ui.model.bedJetName())
        ui.compose.onNodeWithTag("status-connection")
            .assertTextEquals("No BedJet selected · offline")
            .assertContentDescriptionContains("No BedJet selected", substring = true)
        ui.compose.onNodeWithText("Previous BedJet", substring = true).assertDoesNotExist()
    }

    @Test fun remoteDisplaysBothMeasuredTemperaturesAtLargeText() {
        ui.tab("Remote")
        ui.waitUntil { ui.model.hasLiveStatus.value && ui.model.status.value != null }
        val state = ui.model.status.value!!
        val air = Format.temp(state.actualTemperature, false)
        val room = Format.temp(state.ambientTemperature, false)
        val value = ui.compose.onNodeWithTag("remote-temperature", useUnmergedTree = true).performScrollTo()
        ui.checkLayout(value, "Measured air")
        value.assertTextEquals(air).assertContentDescriptionEquals(ui.s(R.string.remote_air_room, air, room))
        val ambient = ui.compose.onNodeWithTag("remote-room", useUnmergedTree = true).performScrollTo()
        ui.checkLayout(ambient, "Measured room")
        ambient.assertTextEquals(ui.s(R.string.reading_room, room))
    }

    @Test fun modeIconOnlyMovesForRecentRealHeatOrCoolWithMotionEnabled() {
        val settings = mutableStateOf(ui.graph.repository.settings.value.copy(simpleView = false, demoMode = false))
        val report = mutableStateOf(ui.graph.link.status.value!!.copy(mode = Mode.HEAT))
        val recent = mutableStateOf(true)
        val resolver = ui.compose.activity.contentResolver
        val scale = AndroidSettings.Global.ANIMATOR_DURATION_SCALE
        val originalScale = AndroidSettings.Global.getFloat(resolver, scale, 1f)
        val clock = ui.compose.mainClock
        fun iconBounds() = ui.compose.onNodeWithTag("reading-mode-icon", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        fun moves(expected: Boolean) {
            clock.advanceTimeBy(32)
            val first = iconBounds()
            clock.advanceTimeBy(456)
            assertEquals("Mode ${report.value.mode}, recent=${recent.value}, simple=${settings.value.simpleView}",
                expected, first != iconBounds())
        }
        try {
            AndroidSettings.Global.putFloat(resolver, scale, 1f)
            clock.autoAdvance = false
            ui.compose.runOnUiThread { ui.compose.activity.setContent {
                NocturneTheme(settings.value, ui.graph.now()) {
                    MeasuredTemperatures(report.value, settings.value, recent.value, "test")
                }
            } }
            moves(true)
            ui.compose.runOnUiThread { report.value = report.value.copy(mode = Mode.TURBO) }
            moves(false)
            ui.compose.runOnUiThread { report.value = report.value.copy(mode = Mode.COOL) }
            moves(true)
            ui.compose.runOnUiThread { recent.value = false }
            moves(false)
            ui.compose.runOnUiThread { recent.value = true; report.value = report.value.copy(mode = Mode.STANDBY) }
            moves(false)
            ui.compose.runOnUiThread { report.value = report.value.copy(mode = Mode.COOL); settings.value = settings.value.copy(simpleView = true) }
            moves(false)
            ui.compose.runOnUiThread { settings.value = settings.value.copy(simpleView = false, demoMode = true) }
            moves(false)
            AndroidSettings.Global.putFloat(resolver, scale, 0f)
            ui.compose.runOnUiThread { settings.value = settings.value.copy(demoMode = false) }
            moves(false)
        } finally {
            clock.autoAdvance = true
            AndroidSettings.Global.putFloat(resolver, scale, originalScale)
        }
    }

    @Test fun fanScaleLabelsFitAndItsAccessibleValueCannotSendZero() {
        ui.tab("Remote")
        listOf("5%", "50%", "100%").forEachIndexed { index, text ->
            val tick = ui.compose.onNodeWithTag("fan-tick-$index")
            tick.assertTextEquals(text)
            ui.checkLayout(tick, text)
        }
        val slider = ui.compose.onNodeWithContentDescription("Fan")
        slider.performScrollTo()
        assertEquals(5f..100f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range)
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        ui.waitUntil { ui.graph.link.status.value?.fanPercent == 5 }
        assertTrue(slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("5"))
    }

    @Test fun temperatureScaleUsesTheReportedModeLimitsAndFitsAtTwoHundredPercent() {
        ui.tab("Remote")
        val status = ui.graph.link.status.value!!
        val scale = status.mode.temperatureRange
        val values = listOf(scale.first, (scale.first + scale.last) / 2, scale.last)
        values.forEachIndexed { index, value ->
            val tick = ui.compose.onNodeWithTag("temperature-tick-$index")
            tick.assertTextEquals(Format.temp(value, false, Format.Words { id, args -> ui.s(id, *args) }))
            ui.checkLayout(tick, "Temperature tick $index")
        }
        ui.compose.onNodeWithContentDescription("Temperature").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
    }
}
