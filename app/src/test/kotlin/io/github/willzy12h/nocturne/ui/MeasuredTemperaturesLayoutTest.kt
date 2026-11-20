package io.github.willzy12h.nocturne.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MeasuredTemperaturesLayoutTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun theRoomAndModePositionsStayFixedInTheTwoColumnLayout() = compareReadings(379.dp, stacked = false)

    @Test fun theRoomAndModePositionsStayFixedInTheStackedLayout() = compareReadings(288.dp, stacked = true)

    private fun compareReadings(width: Dp, stacked: Boolean) {
        val initial = DeviceStatus(600, 48, 44, Mode.COOL, 7, 600, 38, 86, 44, 0, null)
        val reading = mutableStateOf(initial)
        val fahrenheit = mutableStateOf(false)
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            NocturneTheme {
                Box {
                    Box(Modifier.width(width).testTag("reading-frame")) {
                        MeasuredTemperatures(reading.value, Settings(fahrenheit = fahrenheit.value), recent = false, tag = "reading")
                    }
                }
            }
        } }
        fun bounds(tag: String) = ui.compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val room = bounds("reading-room")
        val context = bounds("reading-reading-context")
        val icon = bounds("reading-mode-icon")
        val frame = bounds("reading-frame")
        assertEquals("Fixture width", width.value, (frame.right - frame.left).value, .5f)
        if (stacked) assertTrue("Room $context should stack below the reading icon $icon in $frame", context.top >= icon.bottom)
        else assertTrue("Room $context should sit to the right of the reading icon $icon in $frame", context.left > icon.right)
        ui.compose.runOnIdle { reading.value = initial.copy(actualTemperature = 49, mode = Mode.TURBO) }
        ui.compose.onNodeWithTag("reading-temperature").assertTextEquals("24.5°C")
        assertEquals(room, bounds("reading-room"))
        assertEquals(context, bounds("reading-reading-context"))
        assertEquals(icon, bounds("reading-mode-icon"))
        ui.compose.runOnIdle { fahrenheit.value = true }
        ui.compose.onNodeWithTag("reading-temperature").assertTextEquals("76°F")
        assertEquals(room.left, bounds("reading-room").left)
        assertEquals(context, bounds("reading-reading-context"))
        assertEquals(icon, bounds("reading-mode-icon"))
        ui.compose.runOnIdle { reading.value = initial.copy(actualTemperature = 74) }
        ui.compose.onNodeWithTag("reading-temperature").assertTextEquals("99°F")
        ui.compose.runOnIdle { reading.value = initial.copy(actualTemperature = 75) }
        ui.compose.onNodeWithTag("reading-temperature").assertTextEquals("100°F")
        assertEquals(room.left, bounds("reading-room").left)
        assertEquals(context, bounds("reading-reading-context"))
        assertEquals(icon, bounds("reading-mode-icon"))
    }
}
