package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
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
                f.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
                f.graph.context.getSharedPreferences("ble", android.content.Context.MODE_PRIVATE)
                    .edit().putString("name", "Previous BedJet").remove("nameDevice").apply()
            }
        }
    }
    @get:Rule val rules = ui.rules

    @Test fun oneStatusHeaderStaysFixedAcrossScrollingAndAllMainTabs() {
        val header = ui.compose.onNodeWithTag("status-header")
        header.assertIsDisplayed()
        val bounds = header.getUnclippedBoundsInRoot()
        listOf("Remote", "Settings", "Profiles", "History", "Tonight").forEach { tab ->
            ui.tab(tab)
            ui.compose.onAllNodesWithTag("status-header").assertCountEquals(1)
            header.assertIsDisplayed()
            assertEquals("The status header must remain above the scrolling screen", bounds.top, header.getUnclippedBoundsInRoot().top)
            if (tab == "Settings") {
                ui.compose.openSettingsPage("about")
                ui.compose.onNodeWithText("About").performScrollTo()
                assertEquals(bounds, header.getUnclippedBoundsInRoot())
            }
        }
        ui.compose.onNodeWithTag("status-connection").assertTextEquals("Demo")
        ui.compose.onNodeWithTag("status-device").assertTextContains("Demo")
        ui.compose.onNodeWithTag("status-temperature").assertIsDisplayed()
    }

    @Test fun demoExitStaysVisibleOnEveryMainTab() {
        listOf("Remote", "Settings", "Profiles", "History", "Tonight").forEach { tab ->
            ui.tab(tab)
            ui.compose.onNodeWithText("Demo — no BedJet connected").assertIsDisplayed()
            ui.checkControl(ui.compose.onNodeWithText("Exit demo"), "Exit demo", scroll = false)
        }
    }

    @Test fun withNoTargetTheHeaderCannotReuseAPreviouslyConnectedName() {
        assertNull("A legacy display name cannot stand for an unselected target", ui.model.bedJetName())
        ui.compose.onNodeWithTag("status-device").assertTextEquals("No BedJet selected")
        ui.compose.onNodeWithTag("status-connection").assertTextEquals("Disconnected")
        ui.compose.onNodeWithText("Previous BedJet").assertDoesNotExist()
    }

    @Test fun fanScaleLabelsFitAndItsAccessibleValueCannotSendZero() {
        ui.tab("Remote")
        listOf("0%", "50%", "100%").forEachIndexed { index, text ->
            val tick = ui.compose.onNodeWithTag("fan-tick-$index")
            tick.assertTextEquals(text)
            ui.checkLayout(tick, text)
        }
        val slider = ui.compose.onNodeWithContentDescription("Fan")
        slider.performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        ui.compose.waitForIdle()
        assertTrue("The minimum running fan speed remains 5%", ui.graph.link.status.value!!.fanPercent >= 5)
        assertTrue(slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("5"))
    }

    @Test fun temperatureScaleUsesTheReportedModeLimitsAndFitsAtTwoHundredPercent() {
        ui.tab("Remote")
        val status = ui.graph.link.status.value!!
        val values = listOf(status.minTemperature, (status.minTemperature + status.maxTemperature) / 2, status.maxTemperature)
        values.forEachIndexed { index, value ->
            val tick = ui.compose.onNodeWithTag("temperature-tick-$index")
            tick.assertTextEquals(Format.temp(value, false, Format.Words { id, args -> ui.s(id, *args) }))
            ui.checkLayout(tick, "Temperature tick $index")
        }
        ui.compose.onNodeWithContentDescription("Temperature").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
    }
}
