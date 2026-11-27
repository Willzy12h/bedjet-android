package io.github.willzy12h.nocturne.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
import kotlin.math.abs
import kotlin.math.roundToInt

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
        val value = ui.compose.onNodeWithTag("remote-temperature", useUnmergedTree = true).performScrollTo()
        ui.checkLayout(value, "Measured air")
        // The simulated air warms while the test scrolls. Compare the rendered text and its TalkBack label
        // with the same current report, allowing a frame for the screen to collect the ViewModel update.
        ui.waitUntil {
            val state = ui.model.status.value ?: return@waitUntil false
            val air = Format.temp(state.actualTemperature, false)
            val room = Format.temp(state.ambientTemperature, false)
            val semantics = value.fetchSemanticsNode().config
            semantics[SemanticsProperties.Text].map { it.text } == listOf(air) &&
                semantics[SemanticsProperties.ContentDescription] == listOf(ui.s(R.string.remote_air_room, air, room))
        }
        val ambient = ui.compose.onNodeWithTag("remote-room", useUnmergedTree = true).performScrollTo()
        ui.checkLayout(ambient, "Measured room")
        val room = Format.temp(ui.model.status.value!!.ambientTemperature, false)
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
        fun renderedIconBounds(tag: String): Rect {
            // Sample the whole fixed slot. Accessibility bounds need not track drawing transforms.
            val slot = ui.compose.onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val width = slot.width.roundToInt()
            val height = slot.height.roundToInt()
            assertTrue("The reading icon slot is visible", width > 0 && height > 0)
            val root = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                ui.compose.runOnUiThread {
                    root.draw(Canvas(bitmap).apply { translate(-slot.left, -slot.top) })
                }
                val background = bitmap.getPixel(0, 0)
                val icon = Rect()
                for (y in 0 until height) for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    // Ignore weak antialiasing at the edge of the outline.
                    val difference = maxOf(abs(Color.red(pixel) - Color.red(background)),
                        abs(Color.green(pixel) - Color.green(background)), abs(Color.blue(pixel) - Color.blue(background)))
                    if (difference >= 48) icon.union(x, y, x + 1, y + 1)
                }
                assertFalse("The slot contains the rendered icon", icon.isEmpty)
                icon
            } finally { bitmap.recycle() }
        }
        fun moves(expected: Boolean) {
            // A paused Compose clock does not guarantee a platform-thread snapshot write has been observed.
            ui.compose.runOnUiThread { Snapshot.sendApplyNotifications() }
            val frameTag = "reading-mode-icon-slot-${report.value.mode.name}-$expected"
            ui.waitUntil { ui.compose.onAllNodesWithTag(frameTag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            // Sample a full animation cycle after the new mode is drawn. One pixel of edge rasterisation may vary.
            clock.advanceTimeBy(320)
            val bounds = (1..16).map {
                clock.advanceTimeBy(176)
                renderedIconBounds(frameTag)
            }
            val changed = listOf<(Rect) -> Int>({ it.left }, { it.top }, { it.right }, { it.bottom }).any { edge ->
                bounds.maxOf(edge) - bounds.minOf(edge) >= 2
            }
            assertEquals("Mode ${report.value.mode}, recent=${recent.value}, simple=${settings.value.simpleView}",
                expected, changed)
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
