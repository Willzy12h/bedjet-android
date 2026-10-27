package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.AutoStartMode
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.description
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartExecutionTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private suspend fun setup(link: (DemoBedJet) -> BedJetLink = { it }): Graph {
        lateinit var graph: Graph
        graph = fresh { Graph(context, link(DemoBedJet(now = { graph.now() }))) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        val profile = graph.repository.profiles.value.first()
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true,
                profileId = profile.id, deviceId = device, fixedMinute = 22 * 60 + 30))) }
        graph.actions.confirmMs = 1
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PlatformNotifications.channel(context)
        return graph
    }
    private fun alarm(at: String) {
        context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(Instant.parse(at).toEpochMilli(), null),
            PendingIntent.getBroadcast(context, 922, Intent("test.auto.alarm"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    @Test fun chargingOffByDefaultStartsTheChosenProfileWithoutAGraceDelay() = runBlocking {
        val graph = setup()
        val chosen = graph.repository.profiles.value.first()
        val other = graph.repository.profiles.value.last()
        graph.repository.updateSettings { it.chooseTonight(other.id, graph.now()) }
        var grace = 0
        var countdown = 0
        ChargerRun(graph, { false }, { _, _ -> countdown++; true }, { grace++ }, { true }).execute()
        val night = graph.repository.activeNight.value
        assertNotNull("A fixed start must work when the charging option is off", night)
        assertEquals(chosen.id, night!!.profileId)
        assertEquals(Confidence.CONFIRMED, night.confidence)
        assertEquals(0, grace)
        assertEquals(0, countdown)
        val messages = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(messages.any { it.extras.getString("android.text").orEmpty().contains("confirmed", ignoreCase = true) })
    }

    @Test fun chargingOptInSkipsUnpluggedAndRetainsTheCancelGrace() = runBlocking {
        val graph = setup()
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(onlyIfCharging = true))) }
        var grace = 0
        ChargerRun(graph, { false }, { _, _ -> true }, { grace++ }, { true }).execute()
        assertNull(graph.repository.activeNight.value)
        assertEquals(0, grace)
        ChargerRun(graph, { true }, { _, _ -> true }, {
            grace++
            graph.repository.claimAutomation("cancel:$device:2026-10-26")
        }, { true }).execute()
        assertEquals(1, grace)
        assertNull(graph.repository.activeNight.value)
        assertTrue(graph.repository.history.value.isEmpty())
    }

    @Test fun beforeWakeCapturesTheAcceptedAlarmForAFixedProfileWithoutInventingAUserOverride() = runBlocking {
        val graph = setup()
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(useAlarm = false, defaultWakeMinute = 7 * 60)))
        alarm("2026-10-27T06:30:00Z")
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(mode = AutoStartMode.BEFORE_WAKE))) }
        ChargerRun(graph, { false }, { _, _ -> true }, {}, { true }).execute()
        val night = graph.repository.activeNight.value
        assertNotNull(night)
        assertEquals(Instant.parse("2026-10-27T06:30:00Z"), java.time.ZonedDateTime.parse(night!!.wakeAt).toInstant())
        assertEquals("AUTO_START", night.wakeOrigin.name)
        assertNull(graph.repository.settings.value.tonightOverride)
        val description = AlarmExplanation.resolve(graph.repository.settings.value, profile, graph.now(),
            AlarmSource.describe(context, graph.repository.settings.value), night).description(context)
        assertTrue(description, description.contains("Auto-start", ignoreCase = true))
        assertFalse(description, description.contains("override", ignoreCase = true))
        graph.actions.perform(Command.RETIME)
        assertEquals(night.wakeAt, graph.repository.activeNight.value!!.wakeAt)
        assertEquals(night.revision, graph.repository.activeNight.value!!.revision)
        assertFalse(graph.repository.profile(profile.id)!!.wakeSource.useAlarm)
    }

    @Test fun actualTonightOverrideKeepsItsUserProvenance() = runBlocking {
        val graph = setup()
        alarm("2026-10-27T07:30:00Z")
        val wake = graph.now().plusHours(8)
        graph.repository.updateSettings { it.setWakeOverride(wake, graph.now()).copy(platform = it.platform.copy(
            autoStart = it.platform.autoStart!!.copy(mode = AutoStartMode.BEFORE_WAKE))) }
        ChargerRun(graph, { false }, { _, _ -> true }, {}, { true }).execute()
        val night = graph.repository.activeNight.value
        assertNotNull(night)
        assertEquals(wake.toString(), night!!.wakeAt)
        assertEquals("OVERRIDE", night.wakeOrigin.name)
        assertEquals(wake.toString(), graph.repository.settings.value.tonightOverride!!.wakeAt)
    }

    @Test fun anAlarmChangeDuringChargingGraceCannotStartTheStalePlan() = runBlocking {
        val graph = setup()
        alarm("2026-10-27T06:30:00Z")
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(
            mode = AutoStartMode.BEFORE_WAKE, onlyIfCharging = true))) }
        var grace = 0
        ChargerRun(graph, { true }, { _, _ -> true }, { grace++; alarm("2026-10-27T07:30:00Z") }, { true }).execute()
        assertEquals(1, grace)
        assertNull(graph.repository.activeNight.value)
        assertTrue(graph.repository.history.value.isEmpty())
    }

    @Test fun settingsChangedAtTheFinalTransportPreparationCannotStartAnOldProgramme() = runBlocking {
        lateinit var graph: Graph
        var prepared = false
        var starts = 0
        graph = setup { demo -> object : BedJetLink by demo {
            override suspend fun prepareForStart() {
                demo.prepareForStart()
                prepared = true
                graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(enabled = false))) }
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                if (command[0] == 0x01.toByte() && command[1].toInt() and 0xff in 0x80..0x85) starts++
                demo.send(command)
            }
        } }
        ChargerRun(graph, { false }, { _, _ -> true }, {}, { true }).execute()
        assertTrue("The test must reach the final preparation boundary", prepared)
        assertEquals("The last transport guard must recheck the captured auto-start plan", 0, starts)
        assertNotEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        assertTrue(graph.repository.history.value.none { it.bedJetRunConfirmed })
    }

    @Test fun anAttemptCannotRepeatThatNightAfterLocalStopTracking() = runBlocking {
        val graph = setup()
        val run = ChargerRun(graph, { false }, { _, _ -> true }, {}, { true })
        run.execute()
        assertNotNull(graph.repository.activeNight.value)
        graph.actions.perform(Command.STOP_TRACKING)
        run.execute()
        assertNull(graph.repository.activeNight.value)
        assertEquals(1, graph.repository.history.value.size)
        assertTrue(graph.repository.automation.value.claims.contains("charger:$device:2026-10-26"))
    }

    @Test fun aRunningNightAndDemoBothRefuseAutomaticControl() = runBlocking {
        val graph = setup()
        graph.actions.perform(Command.START_NIGHT, graph.repository.profiles.value.first().id)
        val original = graph.repository.activeNight.value
        ChargerRun(graph, { false }, { _, _ -> fail("No grace while a night is running"); true }, {}, { true }).execute()
        assertEquals(original, graph.repository.activeNight.value)
        assertTrue(graph.repository.automation.value.claims.none { it.startsWith("charger:") })
        graph.actions.perform(Command.STOP_TRACKING)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val history = graph.repository.history.value
        ChargerRun(graph, { false }, { _, _ -> fail("No demo countdown"); true }, {}, { true }).execute()
        assertNull(graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
        assertTrue(graph.repository.automation.value.claims.none { it.startsWith("charger:") })
    }

    @Test fun anUnreachableBedJetProducesAFailedOutcomeWithoutConfirmedHistory() = runBlocking {
        val graph = setup { demo -> object : BedJetLink by demo {
            override suspend fun connect() { throw IllegalStateException("Unavailable test transport") }
        } }
        ChargerRun(graph, { false }, { _, _ -> true }, {}, { true }).execute()
        assertNull(graph.repository.activeNight.value)
        assertTrue(graph.repository.history.value.isEmpty())
        assertTrue(graph.repository.notices.value.any { it.text.contains("failed", ignoreCase = true) })
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.any {
            it.extras.getString("android.text").orEmpty().contains("failed", ignoreCase = true)
        })
    }
    @Test fun explicitlyReapplyingAnAutomaticNightKeepsItsCapturedAlarmAttribution() = runBlocking {
        val graph = setup()
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(useAlarm = false)))
        alarm("2026-10-27T06:30:00Z")
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(mode = AutoStartMode.BEFORE_WAKE))) }
        ChargerRun(graph, { false }, { _, _ -> true }, {}, { true }).execute()
        val original = graph.repository.activeNight.value!!
        assertEquals(Confidence.CONFIRMED, original.confidence)
        assertTrue(original.wakeFromAlarm)
        graph.repository.saveProfile(graph.repository.profile(profile.id)!!.copy(name = profile.name + " updated"))
        val outcome = graph.actions.perform(Command.REAPPLY, replacementConfirmed = true)
        val reapplied = graph.repository.activeNight.value!!
        assertEquals(outcome, Confidence.CONFIRMED, reapplied.confidence)
        assertEquals(original.wakeAt, reapplied.wakeAt)
        assertEquals("AUTO_START", reapplied.wakeOrigin.name)
        assertTrue("Reapply must retain the provenance of the captured alarm", reapplied.wakeFromAlarm)
    }

}
