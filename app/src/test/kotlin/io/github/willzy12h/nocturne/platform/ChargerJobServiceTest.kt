package io.github.willzy12h.nocturne.platform

import android.app.NotificationManager
import android.app.job.JobParameters
import android.os.Looper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class ChargerJobServiceTest : PlatformGraphFixture() {
    private fun params(id: Int = ChargerJobs.ID) = ReflectionHelpers.newInstance(JobParameters::class.java).also {
        ReflectionHelpers.setField(it,"jobId",id)
        assertEquals(id,it.jobId)
    }
    private fun pumpUntil(check: () -> Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while (!check() && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();Thread.yield()}
        assertTrue("Owned operation did not reach expected state",check())
    }
    private fun stopsAtBoundary(inFlight: Boolean) {
        val controller=Robolectric.buildService(ChargerJobService::class.java).create()
        val service=controller.get();val grace=CompletableDeferred<Unit>();var phase="";var cancelled=false
        service.ownedOperation={
            try { phase="grace";grace.await();phase="work";awaitCancellation() }
            finally {cancelled=true}
        }
        try {
            val start=params();val stop=params();assertNotSame(start,stop)
            assertTrue(service.onStartJob(start));pumpUntil {phase=="grace"}
            if (inFlight) {grace.complete(Unit);pumpUntil {phase=="work"}}
            assertTrue(service.onStopJob(stop));pumpUntil {cancelled}
            assertFalse(Shadow.extract<ShadowJobService>(service).isJobFinished)
        } finally {controller.destroy();shadowOf(Looper.getMainLooper()).idle()}
    }
    @Test fun distinctStopParametersCancelGraceWithoutFinishingStoppedJob()=stopsAtBoundary(false)
    @Test fun distinctStopParametersCancelInFlightOwnedWorkWithoutFinishingStoppedJob()=stopsAtBoundary(true)
    @Test fun delayedOldCleanupBlocksReplacementThenNextExecutionStartsFreshGrace() {
        val controller=Robolectric.buildService(ChargerJobService::class.java).create();val service=controller.get()
        val cleanup=CompletableDeferred<Unit>();var cancelled=false;var runs=0
        val manager=service.getSystemService(NotificationManager::class.java)
        service.ownedOperation={
            runs++
            manager.notify(PlatformNotifications.CHARGER_ID,android.app.Notification.Builder(service,"test").setContentTitle("grace $runs").build())
            try {awaitCancellation()} finally {
                cancelled=true
                if (runs==1) withContext(NonCancellable) {cleanup.await()}
            }
        }
        try {
            assertTrue(service.onStartJob(params()));pumpUntil {runs==1}
            service.onStopJob(params());pumpUntil {cancelled}
            assertFalse("Old cleanup must retain ownership",service.onStartJob(params()))
            assertEquals(1,runs)
            cleanup.complete(Unit);shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(manager).getNotification(PlatformNotifications.CHARGER_ID))
            assertTrue(service.onStartJob(params()));pumpUntil {runs==2}
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("grace 2",shadowOf(manager).getNotification(PlatformNotifications.CHARGER_ID).extras.getString("android.title"))
            assertFalse(Shadow.extract<ShadowJobService>(service).isJobFinished)
            cancelled=false;service.onStopJob(params(123));shadowOf(Looper.getMainLooper()).idle();assertFalse(cancelled)
            service.onStopJob(params());pumpUntil {cancelled}
        } finally {cleanup.complete(Unit);controller.destroy();shadowOf(Looper.getMainLooper()).idle()}
    }
}
