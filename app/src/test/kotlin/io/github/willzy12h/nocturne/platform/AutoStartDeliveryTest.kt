package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.operationIntent
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.AutoStartMode
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartDeliveryTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private suspend fun setup(): Graph {
        val graph = fresh { Graph(context, DemoBedJet()) }
        ReflectionHelpers.setField(context as App, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T20:00:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true,
                profileId = graph.repository.profiles.value.first().id, deviceId = device, fixedMinute = 23 * 60))) }
        return graph
    }
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
        shadowOf(it.operationIntent).savedIntent.component?.className == AutoStartReceiver::class.java.name
    }

    @Test fun exactPermissionUsesOneExactImmutableAlarmWithoutARepeatingJob() = runBlocking {
        val graph = setup(); ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ChargerJobs.sync(graph); ChargerJobs.sync(graph)
        val alarm = alarms().single()
        assertEquals(0L, alarm.windowLengthMs)
        assertEquals(0L, alarm.intervalMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertTrue(alarm.operationIntent!!.isImmutable)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
    }

    @Test fun absentExactPermissionUsesTheDeclaredFifteenMinuteWindow() = runBlocking {
        val graph = setup(); ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ChargerJobs.sync(graph)
        val alarm = alarms().single()
        assertEquals(15 * 60 * 1000L, alarm.windowLengthMs)
        assertEquals(0L, alarm.intervalMs)
        assertTrue(alarm.operationIntent!!.isImmutable)
    }

    @Test fun aRelativeStartExactlyAtTheCurrentInstantIsNotMovedToTheFallback() = runBlocking {
        val graph = setup()
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(Instant.parse("2026-10-27T06:30:00Z").toEpochMilli(), null),
            PendingIntent.getBroadcast(context, 920, Intent("test.boundary.alarm"), PendingIntent.FLAG_IMMUTABLE))
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(mode = AutoStartMode.BEFORE_WAKE))) }
        ChargerJobs.sync(graph)
        assertEquals(Instant.parse("2026-10-26T22:30:00Z").toEpochMilli(), alarms().single().triggerAtMs)
    }

    @Test fun rebootTimeAndTimezoneBroadcastsRearmOneAlarmFromSavedSettings() = runBlocking {
        val graph = setup()
        val manager = context.getSystemService(AlarmManager::class.java)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            alarms().forEach { manager.cancel(it.operationIntent!!) }
            context.sendBroadcast(Intent(context, PlatformClockReceiver::class.java).setAction(action))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Re-arm on $action", 1, alarms().size)
            assertEquals(Instant.parse("2026-10-26T23:00:00Z").toEpochMilli(), alarms().single().triggerAtMs)
        }
        graph.clock = Clock.fixed(graph.now().toInstant(), ZoneId.of("Europe/Paris"))
        context.sendBroadcast(Intent(context, PlatformClockReceiver::class.java).setAction(Intent.ACTION_TIMEZONE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, alarms().size)
        assertEquals(Instant.parse("2026-10-26T22:00:00Z").toEpochMilli(), alarms().single().triggerAtMs)
    }

    @Test fun alarmDeliveryCreatesOneNativeOneShotJobAndRejectsDuplicateDelivery() = runBlocking {
        val graph = setup(); val ticket = AutoStartSchedule.next(graph)!!.ticket()
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T23:00:00Z"), ZoneOffset.UTC)
        assertTrue(ChargerJobs.enqueue(graph, ticket))
        assertFalse(ChargerJobs.enqueue(graph, ticket))
        val job = context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID)!!
        assertFalse(job.isPeriodic)
        assertFalse(job.isPersisted)
        assertEquals(ticket, AutoStartTicket.from(job.extras))
    }

    @Test fun importAdmissionPreventsAnAlarmFromAddingAPendingHardwareJob() = runBlocking {
        val graph = setup(); val ticket = AutoStartSchedule.next(graph)!!.ticket()
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T23:00:00Z"), ZoneOffset.UTC)
        val lease = graph.repository.operationAdmission.tryImport()!!
        try {
            assertFalse(ChargerJobs.enqueue(graph, ticket))
            assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
        } finally { lease.close() }
        assertTrue(ChargerJobs.enqueue(graph, ticket))
    }

    @Test fun tamperedExpiredAndOldTimezoneTicketsCannotEnqueueWork() = runBlocking {
        val graph = setup(); val ticket = AutoStartSchedule.next(graph)!!.ticket()
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T23:00:00Z"), ZoneOffset.UTC)
        assertFalse(ChargerJobs.enqueue(graph, ticket.copy(fingerprint = "0".repeat(64))))
        assertFalse(ChargerJobs.enqueue(graph, ticket.copy(key = "another night")))
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(16))
        assertFalse(ChargerJobs.enqueue(graph, ticket))
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T23:00:00Z"), ZoneId.of("Europe/Paris"))
        assertFalse(ChargerJobs.enqueue(graph, ticket))
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
    }

    @Test fun legacyChargerOptInMigratesPersistentlyToChosenBedtimeAndChargingRequired() = runBlocking {
        val graph = setup()
        val profile = graph.repository.profiles.value.last().copy(bedtimeMinute = 21 * 60 + 45)
        graph.repository.saveProfile(profile)
        graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(platform = it.platform.copy(
            autoStart = null, chargerAutoStart = true, chargerDevice = device)) }
        AutoStartSchedule.migrate(graph)
        val migrated = graph.repository.settings.value.platform.autoStart!!
        assertTrue(migrated.enabled)
        assertTrue(migrated.onlyIfCharging)
        assertEquals(AutoStartMode.FIXED, migrated.mode)
        assertEquals(profile.id, migrated.profileId)
        assertEquals(profile.bedtimeMinute, migrated.fixedMinute)
        assertEquals(device, migrated.deviceId)
        assertFalse(graph.repository.settings.value.platform.chargerAutoStart)
        val restarted = fresh { Graph(context, DemoBedJet()) }
        assertEquals(migrated, restarted.repository.settings.value.platform.autoStart)
        AutoStartSchedule.migrate(restarted)
        assertEquals(migrated, restarted.repository.settings.value.platform.autoStart)
    }

    private suspend fun acceptedAlarm(graph: Graph) {
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(defaultWakeMinute = 7 * 60)))
        context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(Instant.parse("2026-10-27T06:30:00Z").toEpochMilli(), null),
            PendingIntent.getBroadcast(context, 921, Intent("test.delivery.alarm"), PendingIntent.FLAG_IMMUTABLE))
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(mode = AutoStartMode.BEFORE_WAKE))) }
    }

    @Test fun aMissedAcceptedAlarmStartDoesNotSubstituteALaterFallbackOnTheSameNight() = runBlocking {
        val graph = setup(); acceptedAlarm(graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:31:00Z"), ZoneOffset.UTC)
        ChargerJobs.sync(graph)
        assertEquals("The next alarm belongs to the following night, not a second wake choice tonight",
            Instant.parse("2026-10-27T23:00:00Z").toEpochMilli(), alarms().single().triggerAtMs)
    }

    @Test fun aDeliveredOrAttemptedNightDoesNotScheduleAnotherAlarmForThatNight() = runBlocking {
        val graph = setup(); acceptedAlarm(graph)
        val ticket = AutoStartSchedule.next(graph)!!.ticket()
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        assertTrue(ChargerJobs.enqueue(graph, ticket))
        ChargerJobs.sync(graph)
        val nextNight = Instant.parse("2026-10-27T23:00:00Z").toEpochMilli()
        assertEquals("A pending job owns this night's delivered alarm", nextNight, alarms().single().triggerAtMs)
        context.getSystemService(JobScheduler::class.java).cancel(ChargerJobs.ID)
        graph.repository.claimAutomation("charger:${ticket.key}")
        ChargerJobs.sync(graph)
        assertEquals("An attempted night cannot be re-armed", nextNight, alarms().single().triggerAtMs)
        graph.clock = Clock.offset(graph.clock, Duration.ofSeconds(1))
        ChargerJobs.sync(graph)
        assertEquals("Passing the alarm instant must not select tonight's later profile fallback", nextNight, alarms().single().triggerAtMs)
    }

    @Test fun anAlarmOutsideTheCurrentWakeWindowIsPlannedFromTheFutureStartInstant() = runBlocking {
        val graph = setup()
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(Instant.parse("2026-10-27T08:00:00Z").toEpochMilli(), null),
            PendingIntent.getBroadcast(context, 923, Intent("test.future.alarm"), PendingIntent.FLAG_IMMUTABLE))
        graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = it.platform.autoStart!!.copy(mode = AutoStartMode.BEFORE_WAKE))) }
        val plan = AutoStartSchedule.next(graph)!!
        assertEquals("The preview must plan eight hours before the accepted morning alarm",
            Instant.parse("2026-10-27T00:00:00Z"), plan.startAt.toInstant())
        assertEquals(AutoStartWakeSource.ALARM, plan.wakeSource)
        ChargerJobs.sync(graph); ChargerJobs.sync(graph)
        assertEquals("Only the planned midnight alarm is armed", 1, alarms().size)
        assertEquals(plan.startAt.toInstant().toEpochMilli(), alarms().single().triggerAtMs)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
    }
}
