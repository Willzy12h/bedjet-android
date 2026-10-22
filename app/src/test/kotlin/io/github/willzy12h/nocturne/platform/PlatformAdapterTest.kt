package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class PlatformAdapterTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context:Context=RuntimeEnvironment.getApplication()
    @Test fun reminderPermissionThenDuplicateSuppressionAndPreviewIntent() = runBlocking {
        val graph=context.graph
        graph.clock=Clock.fixed(Instant.parse("2026-10-22T22:00:00Z"),ZoneOffset.UTC)
        val repo=graph.repository; val profile=repo.profiles.value.first().copy(bedtimeMinute=22*60+30)
        repo.saveProfile(profile)
        repo.updateSettings {it.chooseTonight(profile.id,graph.now()).copy(platform=it.platform.copy(bedtimeReminder=true))}
        PlatformNotifications.channel(context)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val bedtime=graph.now().plusMinutes(30)
        assertFalse(BedtimeReminders.deliver(graph,profile.id,bedtime))
        assertTrue(repo.automation.value.claims.isEmpty())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(BedtimeReminders.deliver(graph,profile.id,bedtime))
        assertFalse(BedtimeReminders.deliver(graph,profile.id,bedtime))
        val notification=shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(PlatformNotifications.REMINDER_ID)
        assertEquals(PlatformActionActivity::class.java.name,shadowOf(notification.contentIntent).savedIntent.component!!.className)
        graph.clock=Clock.offset(graph.clock,Duration.ofHours(3))
        assertFalse(BedtimeReminders.deliver(graph,profile.id,bedtime))
    }
    @Test fun fullBatteryWithoutPowerIsNotCharging() {
        context.sendStickyBroadcast(android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
            .putExtra(android.os.BatteryManager.EXTRA_STATUS,android.os.BatteryManager.BATTERY_STATUS_FULL)
            .putExtra(android.os.BatteryManager.EXTRA_PLUGGED,0))
        assertFalse(ChargerJobs.charging(context))
    }
    @Test fun chargerSchedulerRequiresChargingIsPeriodicAndCancelsOnDisable() = runBlocking {
        // WorkManager is a runtime-only Glance transitive dependency, not a new compile dependency.
        val builder=Class.forName("androidx.work.Configuration\$Builder").getConstructor().newInstance()
        val workRange=builder.javaClass.getMethod("build").invoke(builder)
        val min=workRange.javaClass.getMethod("getMinJobSchedulerId").invoke(workRange) as Int
        val max=workRange.javaClass.getMethod("getMaxJobSchedulerId").invoke(workRange) as Int
        assertFalse(ChargerJobs.ID in min..max)
        val graph=context.graph;val scheduler=context.getSystemService(JobScheduler::class.java)
        graph.repository.updateSettings {it.copy(platform=it.platform.copy(chargerAutoStart=true))}
        ChargerJobs.sync(graph)
        val job=scheduler.getPendingJob(ChargerJobs.ID)!!
        assertTrue(job.isRequireCharging);assertTrue(job.isPeriodic);assertTrue(job.isPersisted)
        graph.repository.updateSettings {it.copy(platform=it.platform.copy(chargerAutoStart=false))}
        ChargerJobs.sync(graph)
        assertNull(scheduler.getPendingJob(ChargerJobs.ID))
    }
    @Test fun oldPeerRequestCannotReleaseNewTarget() = runBlocking {
        val graph=own(Graph(context,DemoBedJet()))
        val old="ble:AA:BB:CC:DD:EE:FF"
        graph.repository.updateSettings {it.copy(targetDevice="ble:11:22:33:44:55:66")}
        assertEquals(OutcomeKind.STALE,graph.platform.handover.peerRequest(old).kind)
        assertFalse(graph.sessions.released.value)
    }
    @Test fun handoverUsesCoordinatorSuspendResumeAndDeclinesBusyTransaction() = runBlocking {
        lateinit var graph:Graph
        graph=own(Graph(context,DemoBedJet(now={graph.now()})))
        graph.repository.updateSettings {it.copy(demoMode=true)}
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val operation=async {graph.actions.exclusive {entered.complete(Unit);release.await()}}
        entered.await()
        try {assertEquals(OutcomeKind.REFUSED,graph.platform.handover.peerRequest().kind);assertFalse(graph.sessions.released.value)}
        finally {release.complete(Unit);operation.await()}
        assertEquals(OutcomeKind.LOCAL,graph.platform.handover.release().kind)
        assertTrue(graph.sessions.released.value)
        assertEquals(OutcomeKind.LOCAL,graph.platform.handover.takeBack().kind)
        assertFalse(graph.sessions.released.value)
    }
}
