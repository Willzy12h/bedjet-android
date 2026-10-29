package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P8ConsumedSettingsRequestTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT) { fixture, _ ->
        val graph = fixture.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
        runBlocking {
            val profile = graph.repository.tonightsProfile()!!.let {
                it.copy(wakeSource = it.wakeSource.copy(useAlarm = true, defaultWakeMinute = 7 * 60))
            }
            graph.repository.saveProfile(profile)
            graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(acceptUnknownAlarmSource = false) }
        }
        val app = RuntimeEnvironment.getApplication() as App
        val operation = PendingIntent.getBroadcast(app, 91, Intent("test.alarm.settings"), PendingIntent.FLAG_IMMUTABLE)
        app.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(Instant.parse("2026-10-26T06:30:00Z").toEpochMilli(), null), operation)
    }
    private val timezone = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val previous = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try { base.evaluate() } finally { TimeZone.setDefault(previous) }
        }
    } }
    @get:Rule val rules = RuleChain.outerRule(timezone).around(ui.rules)

    @Test fun handledAlarmLinkDoesNotReopenWhenReturningToSettings() {
        ui.compose.onNodeWithText("Alarm 06:30 ignored: source unknown — tap to change").performScrollTo().assertIsDisplayed().performClick()
        ui.compose.onNodeWithText(ui.s(R.string.settings_alarms), ignoreCase = true).performScrollTo().assertIsDisplayed()
        ui.compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        ui.compose.onNodeWithTag("settings-page-night").assertExists()
        ui.tab(ui.s(R.string.tab_tonight))
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.onNodeWithTag("settings-page-night").assertExists()
        ui.compose.onNodeWithText(ui.s(R.string.settings_alarms), ignoreCase = true).assertDoesNotExist()
    }
    @Test fun handledSetupRequestDoesNotNavigateAgainAfterRecreation() {
        ui.tab("Settings")
        ui.compose.openSettingsPage("permissions")
        ui.compose.onNodeWithTag("open-setup-checklist").performScrollTo().performClick()
        ui.compose.onNodeWithTag("setup-checklist").assertExists()
        ui.compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasAnyAncestor(hasTestTag("setup-checklist")))
            .performScrollTo().performClick()
        ui.tab("Profiles")
        ui.compose.onNodeWithTag("profiles-new").assertIsDisplayed()
        ui.compose.activityRule.scenario.recreate()
        ui.compose.onNodeWithTag("profiles-new").assertIsDisplayed()
        ui.compose.onNodeWithTag("tonight-card").assertDoesNotExist()
    }

}
