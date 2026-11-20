package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.CrashLog
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
        // No crash yet, so the field is there and empty; the explanation names it either way.
        assertEquals(JsonNull, saved.getValue("lastCrash"))
        val alarm = saved.getValue("alarm").jsonObject
        assertTrue("Precise alarm times are opt-in", "alarmAt" !in alarm)
        val detailed = Json.parseToJsonElement(ui.graph.actions.diagnostics(includeAlarmTimes = true)).jsonObject
        assertEquals(ALARM_AT, ZonedDateTime.parse(detailed.getValue("alarm").jsonObject.getValue("alarmAt").jsonPrimitive.content).toInstant())
        assertTrue(saved.getValue("settings").jsonObject.keys.none { it in setOf("targetDevice", "rememberedBedJets", "manualControls") })
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
        assertTrue("The export can include the last crash, so that must be disclosed: $explanation",
            explanation.contains("crash", ignoreCase = true))
    }


    @Test fun cancellingThePreviewLeavesTheDocumentPickerClosed() {
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("about")
        ui.control(ui.s(R.string.settings_diagnostics_export)).performClick()
        ui.waitUntil { ui.compose.onAllNodesWithTag("diagnostics-preview-content").fetchSemanticsNodes().isNotEmpty() }
        ui.compose.onNodeWithText(ui.s(R.string.feature_cancel)).performClick()
        ui.compose.onNodeWithTag("diagnostics-preview-content").assertDoesNotExist()
        assertNull(shadowOf(ui.compose.activity).nextStartedActivityForResult)
    }

    @Test fun exportOnlyContainsReviewedDataEvenWhenTheReportChangesWhileThePickerIsOpen() {
        val graph = ui.graph
        val repo = graph.repository
        val at = graph.now().toString()
        val address = "ble:11:22:33:44:55:66"
        val profileName = "Synthetic private profile"
        val eventDetail = "Synthetic event detail"
        val noticeTitle = "Synthetic notice title"
        val noticeBody = "Synthetic notice body"
        val path = "/synthetic/private/path"
        val message = "Synthetic exception message"
        val threadName = "synthetic-private-thread"
        val appClass = "io.github.willzy12h.nocturne.night.NightActions"
        val failure = IllegalStateException("$message $path").apply {
            stackTrace = arrayOf(StackTraceElement(appClass, "execute", "$path/Private.kt", 12))
        }
        ui.compose.runOnIdle {
            runBlocking {
                repo.addRecord(NightRecord("record", "profile", profileName, at, at, false, deviceId = address,
                    events = listOf(HistoryEvent(at, EventKind.ERROR, eventDetail))))
                repo.addNotice(LoggedNotice(at, NoticeKind.PROBLEM, noticeTitle, noticeBody))
            }
            CrashLog.record(graph.context.filesDir, "local", threadName, failure, graph.now())
            val trace = requireNotNull(CrashLog.read(graph.context.filesDir))
            assertTrue(listOf(message, path, threadName).all { it in trace })
        }
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("about")
        val marker = "diagnostics-preview-content"
        val privateValues = listOf(address, profileName, eventDetail, noticeTitle, noticeBody, threadName, path, message)
        for (includeTimes in listOf(false, true)) {
            if (includeTimes) ui.compose.onNodeWithText(ui.s(R.string.diagnostics_times)).performScrollTo().performClick()
            ui.control(ui.s(R.string.settings_diagnostics_export)).performClick()
            ui.waitUntil { ui.compose.onAllNodesWithTag(marker).fetchSemanticsNodes().isNotEmpty() }
            val reviewed = ui.compose.onNodeWithTag(marker).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            val report = Json.parseToJsonElement(reviewed).jsonObject
            assertEquals(includeTimes, "alarmAt" in report.getValue("alarm").jsonObject)
            assertTrue(report.getValue("events").toString().contains(EventKind.ERROR.name))
            assertTrue(report.getValue("notices").toString().contains(NoticeKind.PROBLEM.name))
            assertTrue(report.getValue("lastCrash").toString().contains("$appClass.execute"))
            privateValues.forEach { assertTrue("Found private text in preview", it !in reviewed) }
            assertNull(shadowOf(ui.compose.activity).nextStartedActivityForResult)
            ui.compose.onNodeWithTag("diagnostics-save").performClick()
            val request = requireNotNull(shadowOf(ui.compose.activity).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            val uri = Uri.parse("content://diagnostics.test/$includeTimes.json")
            val output = ByteArrayOutputStream()
            shadowOf(graph.context.contentResolver).registerOutputStream(uri, output)
            ui.compose.runOnUiThread {
                graph.clock = Clock.offset(graph.clock, java.time.Duration.ofMinutes(8))
                assertTrue(ui.compose.activity.activityResultRegistry.dispatchResult(request.requestCode,
                    android.app.Activity.RESULT_OK, Intent().setData(uri)))
            }
            ui.waitUntil { output.size() > 0 }
            assertTrue(reviewed.toByteArray(Charsets.UTF_8).contentEquals(output.toByteArray()))
        }
    }

    private companion object {
        val ALARM_AT: Instant = Instant.parse("2026-10-27T06:30:00Z")
        const val ALARM_SOURCE = "com.sec.android.app.clockpackage"
    }
}
