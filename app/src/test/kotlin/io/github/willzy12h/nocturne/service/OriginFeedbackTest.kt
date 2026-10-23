package io.github.willzy12h.nocturne.service

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class OriginFeedbackTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val app:App=RuntimeEnvironment.getApplication() as App
    private fun until(check:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!check() && System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));Thread.yield()}
        assertTrue("Timed out pumping looper",check())
    }
    private fun prepare()=runBlocking {
        app.graph.repository.updateSettings {it.copy(demoMode=false,targetDevice="ble:AA:BB:CC:DD:EE:FF")}
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BedJetService.lastResult=null
    }
    private fun noDuplicate() {
        assertNotNull(BedJetService.lastResult)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
    }
    @Test fun prePromotionDenialReturnsExceptionWithoutDuplicateNotification() {
        prepare();shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val error=runBlocking {runCatching {BedJetService.runOwned(app,notifyFailures=false){Unit}}.exceptionOrNull()}
        assertTrue(error is SecurityException);noDuplicate()
    }
    private fun serviceFailure(work:suspend ()->Unit):Throwable? {
        prepare();shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var delivered:Intent?=null;var error:Throwable?=null
        val caller=app.graph.scope.launch {try{BedJetService.runOwned(app,notifyFailures=false,work=work)}catch(e:Exception){error=e}}
        until {delivered=delivered ?: shadowOf(app).nextStartedService;delivered!=null}
        val service=Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(delivered,0,1)
        until {caller.isCompleted}
        service.onDestroy();noDuplicate()
        return error
    }
    @Test fun serviceExceptionReturnsOriginalFailureWithoutDuplicateNotification() {
        assertEquals("service failure",serviceFailure {throw IOException("service failure")}?.message)
    }
    @Test fun serviceTimeoutRemainsATimeoutWithoutDuplicateNotification() {
        assertTrue(serviceFailure {withTimeout(1){awaitCancellation()}} is TimeoutCancellationException)
    }
}
