package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsBoostCopyTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:org.junit.Rule val rules = ui.rules

    @Test fun everyBoostLengthDescribesItsActualTurboAndHeatDurations() {
        ui.compose.onNode(hasText(ui.s(R.string.tab_settings)) and hasClickAction()).performClick()
        ui.compose.openSettingsPage("notifications")
        for (minutes in listOf(10, 15, 20, 25, 30)) {
            ui.compose.runOnUiThread { runBlocking {
                ui.graph.repository.updateSettings { it.copy(boostMinutes = minutes) }
            } }
            ui.waitUntil { ui.model.settings.value.boostMinutes == minutes }
            val description = if (minutes == 10) "Turbo heat for 10 minutes"
                else "Turbo heat for 10 minutes, then Heat for ${minutes - 10} minutes"
            ui.compose.onNodeWithText(description).performScrollTo().assertIsDisplayed()
            ui.compose.onAllNodesWithText("Boost: Turbo, then Heat, for $minutes minutes").assertCountEquals(0)
        }
    }
}
