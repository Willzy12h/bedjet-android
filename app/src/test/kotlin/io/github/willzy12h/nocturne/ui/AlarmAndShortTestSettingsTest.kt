package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class AlarmAndShortTestSettingsTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT) { f, _ ->
        f.graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
    }
    @get:Rule val rules = ui.rules

    @Test fun recheckReadsTheNewAndroidAlarmEveryTimeAndStatesSourceAndFallback() {
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("night")
        val app = RuntimeEnvironment.getApplication() as App
        val fire = PendingIntent.getBroadcast(app, 613, Intent("test.ux.alarm"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(fire).setCreatorPackage("com.sec.android.app.clockpackage")
        app.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(ui.graph.clock.millis() + 5 * 60_000, fire), fire)
        ui.control("Re-check alarm").performClick()
        ui.waitUntil { ui.model.nextAlarm.value?.creator == "com.sec.android.app.clockpackage" }
        ui.compose.onNodeWithTag("alarm-check-result").assertTextContains("Samsung Clock", substring = true)
            .assertTextContains("too soon", substring = true).assertTextContains("Profile wake", substring = true)
        app.getSystemService(AlarmManager::class.java).cancel(fire)
        ui.control("Re-check alarm").performClick()
        ui.waitUntil { ui.model.nextAlarm.value == null }
        ui.compose.onNodeWithTag("alarm-check-result").assertTextContains("No alarm set", substring = true)
    }

    @Test fun shortTestPreviewNamesTargetAndPhasesAndCancelDoesNotStart() {
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("night")
        ui.compose.onNodeWithText("Preview five-minute test").performScrollTo().performClick()
        ui.compose.onNodeWithText("Start five-minute test?").assertIsDisplayed()
        ui.compose.onNodeWithText("Demo BedJet · BedJet slot 3").assertExists()
        ui.compose.onNodeWithText("2 minutes", substring = true).assertExists()
        assertNull(ui.graph.repository.activeNight.value)
        ui.control("Cancel", scroll = false).performClick()
        assertNull(ui.graph.repository.activeNight.value)
        assertTrue(ui.graph.repository.history.value.none { it.isTestRun })
    }

    @Test fun onlyThePositiveConfirmationStartsAMarkedTestThroughTheOwner() {
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("night")
        ui.compose.onNodeWithText("Preview five-minute test").performScrollTo().performClick()
        assertNull(ui.graph.repository.activeNight.value)
        ui.control("Start test", scroll = false).performClick()
        ui.waitUntil { ui.graph.repository.activeNight.value?.confidence ==
            io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED && ui.graph.actions.busy.value.runningLabel == null }
        assertTrue(ui.graph.repository.activeNight.value!!.isTestRun)
        assertTrue(ui.graph.repository.history.value.single().isTestRun)
        assertTrue(ui.model.history.value.isEmpty())
    }
}
