package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.store.AlarmApps
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/** Real navigation, saved setting and exported diagnostics, beyond an explanation-helper test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class AlarmSettingsNavigationTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT) { fixture, _ ->
        val graph = fixture.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
        runBlocking {
            val profile = graph.repository.tonightsProfile()!!.let {
                it.copy(wakeSource = it.wakeSource.copy(useAlarm = true, defaultWakeMinute = 7 * 60))
            }
            graph.repository.saveProfile(profile)
            graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()) }
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
    @get:Rule val rules: TestRule = RuleChain.outerRule(timezone).around(ui.rules)

    @Test fun anIgnoredUnknownAlarmOpensTheAlarmAppChoiceAndStaysIgnored() {
        ui.compose.onNodeWithText("Alarm 06:30 ignored: source unknown — tap to change")
            .performScrollTo().assertIsDisplayed().performClick()
        ui.compose.onNodeWithTag("alarm-apps-dialog").assertExists()
        // There is no longer a way to accept alarms from an app Android doesn't name.
        ui.compose.onNodeWithText("Next known alarm, from any app").assertDoesNotExist()
        ui.compose.onNodeWithTag("alarm-apps-any").performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.settings.value.alarmAppsChosen }
        assertEquals(AlarmApps.CLOCK_APPS, ui.graph.repository.settings.value.alarmApps)
        assertFalse(ui.graph.repository.settings.value.acceptUnknownAlarmSource)
        assertEquals(false, ui.model.nextAlarm.value?.counts)
    }

    @Test fun settingsAlarmsFromRowChangesTheChoice() {
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("night")
        ui.compose.onNodeWithTag("alarm-apps-change").performScrollTo().performClick()
        ui.compose.onNodeWithTag("alarm-apps-none").performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.settings.value.alarmApps == AlarmApps.NONE }
        assertTrue(ui.graph.repository.settings.value.alarmAppsChosen)
    }

    @Test fun exportedDiagnosticsIncludeTheReasonAnAlarmWasIgnored() {
        val output = ByteArrayOutputStream()
        ui.compose.runOnIdle { runBlocking {
            ui.graph.actions.exportDiagnostics(Uri.parse("content://test/alarm-diagnostics")) { output }
        } }
        val saved = Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
        val alarm = saved["alarm"]?.jsonObject
        assertNotNull("Diagnostics export must include the alarm selection decision", alarm)
        assertEquals("UNKNOWN_SOURCE", alarm!!["reason"]?.jsonPrimitive?.content)
        assertEquals("Alarm 06:30 ignored: source unknown — tap to change", alarm["explanation"]?.jsonPrimitive?.content)
    }
}
