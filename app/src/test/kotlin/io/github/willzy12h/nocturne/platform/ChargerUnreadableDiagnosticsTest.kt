package io.github.willzy12h.nocturne.platform

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.SlotWrite
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** An otherwise eligible saved-data refusal belongs only in the notification log, never in History. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargerUnreadableDiagnosticsTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var writes = 0
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private val message = "Charger start skipped: saved data couldn't be read."

    private suspend fun graph(): Graph {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 1
        return graph
    }

    private suspend fun unreadableGraph(file: String = "slot-backups.json"): Graph {
        graphs.finish()
        File(app.filesDir, file).writeText("{broken")
        val graph = graph()
        graph.repository.updateSettings { it.copy(targetDevice = device, demoMode = false,
            platform = it.platform.copy(chargerAutoStart = true, chargerDevice = device))
            .chooseTonight("preset-summer", graph.now()) }
        assertEquals(listOf(file), graph.repository.unreadable.value)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        return graph
    }

    private suspend fun executeSkipped(graph: Graph, charging: Boolean = true, permitted: Boolean = true) {
        ChargerRun(graph, { charging }, { _, _ -> fail("A skipped start cannot post a grace notice"); true },
            { fail("A skipped start cannot enter grace") }, { permitted }).execute()
    }

    private fun assertHistoryUnchanged(graph: Graph, before: List<NightRecord>, csvBefore: String) {
        assertEquals("A skipped start cannot create or change a History night", before, graph.repository.history.value)
        assertEquals("A skipped start cannot add a night or event to the CSV", csvBefore,
            HistoryCsv.encode(graph.repository.history.value, fahrenheit = true, identifiers = true))
    }

    private suspend fun assertSilent(graph: Graph, charging: Boolean = true, permitted: Boolean = true) {
        val historyBefore = graph.repository.history.value
        val csvBefore = HistoryCsv.encode(historyBefore, fahrenheit = true, identifiers = true)
        val noticesBefore = graph.repository.notices.value
        val claimsBefore = graph.repository.automation.value.claims
        val activeBefore = graph.repository.activeNight.value
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertTrue(manager.allNotifications.isEmpty())

        executeSkipped(graph, charging, permitted)

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("An ineligible charger job must not post an unreadable-data notification", manager.allNotifications.isEmpty())
        assertEquals("An ineligible charger job must not log an unreadable-data notice", noticesBefore, graph.repository.notices.value)
        assertEquals("An ineligible charger job must not claim a notice or an attempt", claimsBefore, graph.repository.automation.value.claims)
        assertHistoryUnchanged(graph, historyBefore, csvBefore)
        assertEquals(activeBefore, graph.repository.activeNight.value)
        assertEquals(0, writes)
    }

    @Test fun unreadableSavedDataLogsOnceWithoutChangingHistoryOrCsvBeforeAnyStart() = runBlocking {
        val graph = unreadableGraph()
        val previousNight = NightRecord("already-recorded-night", "preset-summer", "Summer Cool",
            "2026-10-21T22:30Z", "2026-10-22T07:00Z", false,
            events = listOf(HistoryEvent("2026-10-22T07:20Z", EventKind.ENDED, "Programme ended.")), deviceId = device)
        graph.repository.addRecord(previousNight)
        val historyBefore = listOf(previousNight)
        val csvBefore = HistoryCsv.encode(historyBefore, fahrenheit = true, identifiers = true)
        val historyFile = File(app.filesDir, "history.json")
        val historySavedBefore = historyFile.readText()

        executeSkipped(graph)

        assertHistoryUnchanged(graph, historyBefore, csvBefore)
        assertEquals("The saved History file must also be unchanged", historySavedBefore, historyFile.readText())
        val notices = graph.repository.notices.value.filter { it.text == message }
        assertEquals("The notice must be durable before the charger job returns", 1, notices.size)
        assertEquals(NoticeKind.PROBLEM, notices.single().kind)
        assertEquals(app.getString(R.string.feature_charger_outcome), notices.single().title)
        assertEquals(graph.now().toString(), notices.single().at)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals("The saved-data refusal posts one notification", 1, shadowOf(manager).allNotifications.size)
        val notification = shadowOf(manager).getNotification(3)
        assertNotNull(notification)
        assertEquals(message, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(app.getString(R.string.feature_charger_outcome),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(listOf("charger-unreadable:ble:AA:BB:CC:DD:EE:FF:2026-10-22"), graph.repository.automation.value.claims)
        assertNull(graph.repository.activeNight.value)
        assertEquals(0, writes)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Posting must not queue a second durable notice", 1, graph.repository.notices.value.count { it.text == message })

        manager.cancel(3)
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(15))
        executeSkipped(graph)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("A repeated job must not repost a dismissed notice", shadowOf(manager).allNotifications.isEmpty())
        assertEquals(1, graph.repository.notices.value.count { it.text == message })
        assertHistoryUnchanged(graph, historyBefore, csvBefore)

        val reloaded = graph()
        reloaded.clock = Clock.offset(graph.clock, Duration.ofMinutes(15))
        assertEquals("The one notice survives reload", 1, reloaded.repository.notices.value.count { it.text == message })
        executeSkipped(reloaded)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Reload cannot repost a dismissed notice in the same window", shadowOf(manager).allNotifications.isEmpty())
        assertEquals("Reload cannot log the refusal again", 1, reloaded.repository.notices.value.count { it.text == message })
        assertEquals(listOf("charger-unreadable:ble:AA:BB:CC:DD:EE:FF:2026-10-22"), reloaded.repository.automation.value.claims)
        assertHistoryUnchanged(reloaded, historyBefore, csvBefore)
        assertEquals(historySavedBefore, historyFile.readText())
        assertNull(reloaded.repository.activeNight.value)
        assertEquals(0, writes)
    }

    @Test fun unreadableDataCanReportAgainOnlyInTheNextEligibleBedtimeWindow() = runBlocking {
        val graph = unreadableGraph()
        executeSkipped(graph)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancel(3)
        graph.clock = Clock.offset(graph.clock, Duration.ofHours(2))

        executeSkipped(graph)

        assertTrue("A new date outside the bedtime window must stay silent", shadowOf(manager).allNotifications.isEmpty())
        assertEquals(1, graph.repository.notices.value.count { it.text == message })
        graph.clock = Clock.offset(graph.clock, Duration.ofHours(22))
        executeSkipped(graph)
        assertNotNull(shadowOf(manager).getNotification(3))
        assertEquals(2, graph.repository.notices.value.count { it.text == message })
        assertEquals(listOf("charger-unreadable:ble:AA:BB:CC:DD:EE:FF:2026-10-22",
            "charger-unreadable:ble:AA:BB:CC:DD:EE:FF:2026-10-23"), graph.repository.automation.value.claims)
        assertTrue(graph.repository.history.value.isEmpty())
        assertNull(graph.repository.activeNight.value)
        assertEquals(0, writes)
    }

    @Test fun unreadableDataOutsideTheBedtimeWindowIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.clock = Clock.offset(graph.clock, Duration.ofHours(-3))
        assertSilent(graph)
    }

    @Test fun unreadableDataWhileNotChargingIsSilent() = runBlocking {
        assertSilent(unreadableGraph(), charging = false)
    }

    @Test fun unreadableDataInDemoModeIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.updateSettings { it.copy(demoMode = true) }
        assertSilent(graph)
    }

    @Test fun unreadableDataWithAnActiveNightIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.setActiveNight(ActiveNight("existing-active", "preset-summer", "2026-10-22T22:00Z",
            "2026-10-23T07:00Z", false, 1, "2026-10-23T06:40Z", "2026-10-23T07:20Z", deviceId = device))
        assertSilent(graph)
    }

    @Test fun unreadableDataWithAnotherTargetIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:01") }
        assertSilent(graph)
    }

    @Test fun unreadableDataAfterAnAlreadyClaimedAttemptIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.claimAutomation("charger:ble:AA:BB:CC:DD:EE:FF:2026-10-22")
        assertSilent(graph)
    }

    @Test fun unreadableDataAfterCancellationIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.claimAutomation("cancel:ble:AA:BB:CC:DD:EE:FF:2026-10-22")
        assertSilent(graph)
    }

    @Test fun unreadableSettingsWithNoChargerCandidateAreSilent() = runBlocking {
        graphs.finish()
        File(app.filesDir, "settings.json").writeText("{broken")
        val graph = graph()
        assertEquals(listOf("settings.json"), graph.repository.unreadable.value)
        assertNull(graph.repository.settings.value.targetDevice)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertSilent(graph)
    }

    @Test fun unreadableDataWithNoProfilesIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.profiles.value.forEach { graph.repository.deleteProfile(it.id) }
        assertSilent(graph)
    }

    @Test fun unreadableProfilesWithAnOtherwiseEligibleCandidateUseOnlyTheNoticeLog() = runBlocking {
        val graph = unreadableGraph("profiles.json")
        val csvBefore = HistoryCsv.encode(emptyList(), fahrenheit = true, identifiers = true)

        executeSkipped(graph)

        assertHistoryUnchanged(graph, emptyList(), csvBefore)
        assertEquals(1, graph.repository.notices.value.count { it.text == message })
        assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
        assertEquals(listOf("charger-unreadable:ble:AA:BB:CC:DD:EE:FF:2026-10-22"), graph.repository.automation.value.claims)
        assertEquals(0, writes)
    }

    @Test fun unreadableDataWhenAutoStartIsDisabledIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(chargerAutoStart = false)) }
        assertSilent(graph)
    }

    @Test fun unreadableDataWithoutPermissionIsSilent() = runBlocking {
        assertSilent(unreadableGraph(), permitted = false)
    }

    @Test fun unreadableDataWithInvalidPlatformSettingsIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(reminderMinutes = 121)) }
        assertSilent(graph)
    }

    @Test fun unreadableDataWithTooShortANightIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.updateSettings { it.setWakeOverride(graph.now().plusHours(2), graph.now()) }
        assertSilent(graph)
    }

    @Test fun unreadableDataWithAnUnresolvedSlotWriteIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite(device, graph.repository.settings.value.workingSlot,
            null, Confidence.UNKNOWN, "Restore")) }
        assertSilent(graph)
    }

    @Test fun unreadableDataWhileReleasedForAnotherPhoneIsSilent() = runBlocking {
        val graph = unreadableGraph()
        graph.sessions.suspendAutoConnect(untilMs = null).join()
        assertSilent(graph)
    }

    @Test fun diagnosticsExposeUnreadableFilesOriginalsBarrierRuntimeReasonAndNightConfidence() = runBlocking {
        graph()
        graph.repository.updateSettings { it.copy(demoMode = true) }
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val night = graph.repository.activeNight.value!!
        graphs.finish()
        File(app.filesDir, "profiles.json").writeText("{broken")
        File(app.filesDir, "slot-backups.json").writeText("{broken")
        graph()
        graph.repository.setActiveNight(night.copy(confidence = Confidence.UNKNOWN))
        graph.repository.updateRuntime { it.copy(reason = "The saved programme could not be checked.") }

        val diagnostics = Json.parseToJsonElement(graph.actions.diagnostics()).jsonObject

        assertEquals("The export must name the files keeping charger starts blocked",
            setOf("profiles.json", "slot-backups.json"),
            diagnostics["unreadable"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet())
        assertEquals(true, diagnostics["originalsBlocked"]?.jsonPrimitive?.boolean)
        assertEquals("The saved programme could not be checked.", diagnostics["runtimeReason"]?.jsonPrimitive?.content)
        assertEquals("UNKNOWN", diagnostics["nightConfidence"]?.jsonPrimitive?.content)
        assertEquals("UNKNOWN", diagnostics["activeNight"]?.jsonObject?.get("confidence")?.jsonPrimitive?.content)

        graph.repository.dismissUnreadable()
        val dismissed = Json.parseToJsonElement(graph.actions.diagnostics()).jsonObject
        assertTrue(dismissed["unreadable"]!!.jsonArray.isEmpty())
        assertEquals("Dismissing the file notice cannot hide the surviving originals barrier", true,
            dismissed["originalsBlocked"]?.jsonPrimitive?.boolean)
    }
}
