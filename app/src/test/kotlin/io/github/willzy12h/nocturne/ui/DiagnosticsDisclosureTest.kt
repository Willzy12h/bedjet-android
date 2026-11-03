package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.onNodeWithText
import io.github.willzy12h.nocturne.R
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The visible consent copy must name personal alarm data that the diagnostics file actually contains. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class DiagnosticsDisclosureTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT) { fixture, _ ->
        val graph = fixture.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        val operation = PendingIntent.getBroadcast(graph.context, 1_247,
            Intent("test.diagnostics.exposed.phone.alarm"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(operation).setCreatorPackage(ALARM_SOURCE)
        graph.context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(ALARM_AT.toEpochMilli(), operation), operation)
    }
    @get:Rule val rules = ui.rules

    @Test fun settingsDiscloseExportedAlarmTimeAndSourceAt320DpAnd200Percent() {
        // A next phone alarm is exported even when there is no tracked night to describe it.
        assertNull(ui.graph.repository.activeNight.value)
        val output = ByteArrayOutputStream()
        ui.compose.runOnIdle { runBlocking {
            ui.graph.actions.exportDiagnostics(Uri.parse("content://test/diagnostics-disclosure")) { output }
        } }
        val saved = Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonNull, saved.getValue("activeNight"))
        val alarm = saved.getValue("alarm").jsonObject
        assertEquals(ALARM_AT, ZonedDateTime.parse(alarm.getValue("alarmAt").jsonPrimitive.content).toInstant())
        assertEquals(ALARM_SOURCE, alarm.getValue("creatorPackage").jsonPrimitive.content)

        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("about")
        val explanation = ui.s(R.string.settings_diagnostics_help)
        // This checks the actual visible node, every rendered character, and clipping at 320 dp / 200% text.
        ui.checkLayout(ui.compose.onNodeWithText(explanation), "Diagnostics export data disclosure")
        assertTrue("The export explanation must disclose the next phone alarm: $explanation",
            explanation.contains("next phone alarm", ignoreCase = true))
        assertTrue("The export explanation must disclose its time: $explanation",
            explanation.contains("alarm time", ignoreCase = true))
        assertTrue("The export explanation must disclose the source app: $explanation",
            explanation.contains("source app", ignoreCase = true))
        assertTrue("The export contains a package name, so that identifier must be disclosed: $explanation",
            explanation.contains("package", ignoreCase = true))
    }

    private companion object {
        val ALARM_AT: Instant = Instant.parse("2026-10-27T06:30:00Z")
        const val ALARM_SOURCE = "com.sec.android.app.clockpackage"
    }
}
