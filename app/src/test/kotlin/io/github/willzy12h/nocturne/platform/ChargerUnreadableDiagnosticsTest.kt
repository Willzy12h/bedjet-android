package io.github.willzy12h.nocturne.platform

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
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

/** Saved-data refusal must be visible even though the charger job never reaches a command. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargerUnreadableDiagnosticsTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var writes = 0

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

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Expected the durable notice to be published", check())
    }

    private suspend fun assertFallbackNoticeDoesNotRepeat(graph: Graph) {
        val message = "Charger start skipped: saved data couldn't be read."
        val manager = app.getSystemService(NotificationManager::class.java)
        until { graph.repository.notices.value.any { it.text == message } }
        assertEquals(1, graph.repository.notices.value.count { it.text == message })
        manager.cancel(3)

        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(15))
        ChargerRun(graph, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()

        assertNull("A repeated job must not repost the dismissed saved-data notice", shadowOf(manager).getNotification(3))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A repeated job must keep one durable saved-data notice", 1,
            graph.repository.notices.value.count { it.text == message })
        val reloaded = graph()
        reloaded.clock = Clock.offset(graph.clock, Duration.ofMinutes(15))
        assertEquals("The saved-data notice survives restart", 1,
            reloaded.repository.notices.value.count { it.text == message })

        ChargerRun(reloaded, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()

        assertNull("A restarted job must not repost the dismissed saved-data notice", shadowOf(manager).getNotification(3))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Restart cannot duplicate the durable saved-data notice", 1,
            reloaded.repository.notices.value.count { it.text == message })
        assertTrue("A notice-only claim cannot invent a History night", reloaded.repository.history.value.isEmpty())
        assertTrue("The notice must not consume a real charger attempt",
            reloaded.repository.automation.value.claims.none { it.startsWith("charger:") })
        assertNull(reloaded.repository.activeNight.value)
        assertEquals(0, writes)

        reloaded.clock = Clock.offset(reloaded.clock, Duration.ofDays(1))
        ChargerRun(reloaded, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()
        assertNotNull("Persistently unreadable data may be reported again on the next date", shadowOf(manager).getNotification(3))
        until { reloaded.repository.notices.value.count { it.text == message } == 2 }
    }

    @Test fun unreadableSavedDataPostsOneNoticeAndKeepsItInHistoryBeforeAnyStart() = runBlocking {
        graphs.finish()
        File(app.filesDir, "slot-backups.json").writeText("{broken")
        val graph = graph()
        graph.repository.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:FF", demoMode = false,
            platform = it.platform.copy(chargerAutoStart = true, chargerDevice = "ble:AA:BB:CC:DD:EE:FF"))
            .chooseTonight("preset-summer", graph.now()) }
        assertEquals(listOf("slot-backups.json"), graph.repository.unreadable.value)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        var graceNotices = 0
        var graceRuns = 0

        ChargerRun(graph, { true }, { _, _ -> graceNotices++; true }, { graceRuns++ }, { true }).execute()

        assertEquals("A skipped charger attempt must be recorded in History", 1, graph.repository.history.value.size)
        val record = graph.repository.history.value.single()
        val message = "Charger start skipped: saved data couldn't be read."
        assertEquals("preset-summer", record.profileId)
        assertEquals("Summer Cool", record.profileName)
        assertEquals("ble:AA:BB:CC:DD:EE:FF", record.deviceId)
        assertEquals("2026-10-22T22:30Z", record.startedAt)
        assertEquals("2026-10-23T07:00Z", record.wakeAt)
        assertFalse(record.wakeFromAlarm)
        assertEquals(listOf(EventKind.ERROR), record.events.map { it.kind })
        assertEquals(message, record.events.single().detail)
        assertEquals(graph.now().toString(), record.events.single().at)
        assertTrue("A skipped attempt has no applied programme steps", record.steps.isEmpty())
        assertTrue("A skipped attempt has no applied revisions", record.revisions.isEmpty())
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3)
        assertNotNull("Unreadable saved data must explain the skipped charger start", notification)
        assertEquals(message, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        until { graph.repository.notices.value.any { it.text == message } }
        val notices = graph.repository.notices.value.filter { it.text == message }
        assertEquals("The visible failure is logged once", 1, notices.size)
        assertEquals(NoticeKind.PROBLEM, notices.single().kind)
        assertEquals(graph.now().toString(), notices.single().at)
        assertEquals(0, graceNotices)
        assertEquals(0, graceRuns)
        assertEquals(0, writes)
        assertNull(graph.repository.activeNight.value)
        assertTrue(graph.repository.automation.value.claims.isEmpty())
        ChargerRun(graph, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A repeated job in the same bedtime window cannot duplicate the skipped attempt", listOf(record),
            graph.repository.history.value)
        assertEquals(1, graph.repository.notices.value.count { it.text == message })
        val reloaded = graph()
        assertEquals("The skipped attempt remains in History after a process restart", listOf(record),
            reloaded.repository.history.value)
        ChargerRun(reloaded, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The one refusal notice survives restart without being logged again", 1,
            reloaded.repository.notices.value.count { it.text == message })
    }

    @Test fun unreadableSettingsExplainTheSkipEvenWhenTheirFallbackHasNoChargerCandidate() = runBlocking {
        graphs.finish()
        File(app.filesDir, "settings.json").writeText("{broken")
        val graph = graph()
        assertEquals(listOf("settings.json"), graph.repository.unreadable.value)
        assertNull(graph.repository.settings.value.targetDevice)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        ChargerRun(graph, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()

        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3)
        assertNotNull("Unreadable settings must be reported before resolving the charger candidate", notification)
        assertEquals("Charger start skipped: saved data couldn't be read.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, writes)
        assertTrue("Unknown profile and device metadata cannot create a fictitious History night",
            graph.repository.history.value.isEmpty())
        assertFallbackNoticeDoesNotRepeat(graph)
    }

    @Test fun unreadableProfilesDoNotInventHistoryFromFallbackPresets() = runBlocking {
        graphs.finish()
        File(app.filesDir, "profiles.json").writeText("{broken")
        val graph = graph()
        graph.repository.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:FF", demoMode = false,
            platform = it.platform.copy(chargerAutoStart = true, chargerDevice = "ble:AA:BB:CC:DD:EE:FF")) }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        ChargerRun(graph, { true }, { _, _ -> fail("No grace notice is eligible"); true }, {}, { true }).execute()

        assertTrue("Fallback presets do not identify the damaged saved profile", graph.repository.history.value.isEmpty())
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3)
        assertNotNull(notification)
        assertEquals("Charger start skipped: saved data couldn't be read.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, writes)
        assertFallbackNoticeDoesNotRepeat(graph)
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
