package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.platform.AutoStartSchedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xhdpi")
class TonightScheduledStartTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT)
    @get:Rule val rules = ui.rules

    @Test fun skipAndUndoAreAvailableForTheSameNamedNight() {
        val unit = "ble:AA:BB:CC:DD:EE:FF"
        val profile = ui.graph.repository.profiles.value.first()
        ui.compose.runOnUiThread { runBlocking {
            ui.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = unit,
                platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true,
                    profileId = profile.id, deviceId = unit, fixedMinute = 23 * 60))) }
        } }
        val plan = AutoStartSchedule.nextEditable(ui.graph)!!
        ui.compose.onNodeWithText("Skip this start").performScrollTo().performClick()
        ui.waitUntil { plan.key in ui.graph.repository.automation.value.skippedStarts }
        ui.compose.onNodeWithText("Skipping ${profile.name}", substring = true).assertExists()
        ui.compose.onNodeWithText("Undo skip").performScrollTo().performClick()
        ui.waitUntil { plan.key !in ui.graph.repository.automation.value.skippedStarts }
        assertEquals(plan, AutoStartSchedule.nextScheduled(ui.graph))
    }
}
