package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.operationIntent
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.PlatformSettings
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

/** C8: observe the actual platform schedule, including loading new and legacy saved settings. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartSchedulingTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val device = "ble:AA:BB:CC:DD:EE:FF"

    private suspend fun setup(): Graph {
        val graph = fresh { Graph(context, DemoBedJet()) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T20:00:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device) }
        return graph
    }

    private fun pendingStarts() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .filter { shadowOf(it.operationIntent).savedIntent.component?.className ==
            "io.github.willzy12h.nocturne.platform.AutoStartReceiver" }

    private suspend fun explicit(graph: Graph, mode: String = "FIXED", fixed: Int = 1380, before: Int = 480) {
        val profile = graph.repository.profiles.value.first()
        val settings = BackupCodec.json.decodeFromString(PlatformSettings.serializer(), """
            {"autoStart":{"enabled":true,"mode":"$mode","fixedMinute":$fixed,
              "minutesBeforeWake":$before,"profileId":"${profile.id}","deviceId":"$device",
              "onlyIfCharging":false}}
        """.trimIndent())
        graph.repository.updateSettings { it.copy(platform = settings) }
    }

    @Test fun legacyChargingOptInSchedulesOneBedtimeAlarmInsteadOfPeriodicPolling() = runBlocking {
        val graph = setup()
        val profile = graph.repository.profiles.value.first().copy(bedtimeMinute = 22 * 60 + 30)
        graph.repository.saveProfile(profile)
        graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(
            platform = it.platform.copy(chargerAutoStart = true, chargerDevice = device)) }
        ChargerJobs.sync(graph)
        assertNull("The old periodic charging job must be cancelled",
            context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
        assertEquals("Exactly one alarm must represent this night", 1, pendingStarts().size)
        assertEquals(Instant.parse("2026-10-26T22:30:00Z").toEpochMilli(), pendingStarts().single().triggerAtMs)
    }

    @Test fun explicitFixedTimeSchedulesWithoutRequiringChargingAndReplacesItsOwnAlarm() = runBlocking {
        val graph = setup()
        explicit(graph)
        repeat(3) { ChargerJobs.sync(graph) }
        assertEquals(1, pendingStarts().size)
        assertEquals(Instant.parse("2026-10-26T23:00:00Z").toEpochMilli(), pendingStarts().single().triggerAtMs)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
    }

    @Test fun relativeStartUsesTheAcceptedNextAlarmEvenForAFixedWakeProfile() = runBlocking {
        val graph = setup()
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(useAlarm = false)))
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(Instant.parse("2026-10-27T07:30:00Z").toEpochMilli(), null),
            PendingIntent.getBroadcast(context, 919, Intent("test.next-alarm"), PendingIntent.FLAG_IMMUTABLE))
        explicit(graph, mode = "BEFORE_WAKE")
        ChargerJobs.sync(graph)
        assertEquals(1, pendingStarts().size)
        assertEquals(Instant.parse("2026-10-26T23:30:00Z").toEpochMilli(), pendingStarts().single().triggerAtMs)
    }

    @Test fun absentAlarmUsesTheChosenProfilesWakeFallback() = runBlocking {
        val graph = setup()
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(defaultWakeMinute = 7 * 60)))
        explicit(graph, mode = "BEFORE_WAKE")
        ChargerJobs.sync(graph)
        assertEquals(1, pendingStarts().size)
        assertEquals(Instant.parse("2026-10-26T23:00:00Z").toEpochMilli(), pendingStarts().single().triggerAtMs)
    }

    @Test fun enteringDemoCancelsTheRealAutomaticStart() = runBlocking {
        val graph = setup()
        explicit(graph)
        ChargerJobs.sync(graph)
        assertEquals(1, pendingStarts().size)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        ChargerJobs.sync(graph)
        assertTrue(pendingStarts().isEmpty())
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID))
        assertTrue(graph.repository.automation.value.claims.isEmpty())
    }
}
