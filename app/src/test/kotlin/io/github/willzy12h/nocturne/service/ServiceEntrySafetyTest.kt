package io.github.willzy12h.nocturne.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.night.NightActions
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

/** Records the service-start boundary without launching a second Android service instance. */
internal class ServiceStartContext(base: Context) : ContextWrapper(base) {
    val requests = mutableListOf<Pair<Boolean, Intent>>()
    var denyStart = false
    var silentDenial = false
    val delivered: Intent? get() = requests.lastOrNull()?.second
    private fun record(intent: Intent, foreground: Boolean): ComponentName? {
        if (denyStart) throw IllegalStateException("Background service start denied")
        requests += foreground to Intent(intent)
        return if (silentDenial) null else intent.component
    }
    override fun startService(service: Intent): ComponentName? = record(service, false)
    override fun startForegroundService(service: Intent): ComponentName? = record(service, true)
}

/** Model a platform promotion rejection at the Android boundary, keeping service ownership real. */
@Implements(Service::class)
class RejectedForegroundShadow : ShadowService() {
    var attemptedType: Int? = null
    @Implementation
    public override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        attemptedType = foregroundServiceType
        throw SecurityException("Foreground promotion rejected")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceEntrySafetyTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var context: ServiceStartContext

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app, DemoBedJet()) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.actions.confirmMs = 1
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        context = ServiceStartContext(app)
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Service ownership did not finish", check())
    }

    @Test fun commandDeliveryDoesNotCreateAForegroundStartObligation() {
        assertTrue(BedJetService.start(context, NightActions.Command.OFF))
        assertEquals(1, context.requests.size)
        assertFalse("A rejected command must be able to stop without Android's FGS-start crash", context.requests.single().first)
        assertEquals(BedJetService::class.java.name, context.delivered!!.component!!.className)
    }

    @Test fun aNullServiceStartDoesNotReportCommandOwnership() {
        context.silentDenial = true
        assertFalse("A null ComponentName means no service accepted the command",
            BedJetService.start(context, NightActions.Command.OFF, notifyFailures = false))
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
    }

    @Test fun aNullServiceStartCompletesTheOwnerWithoutRunningWork() {
        context.silentDenial = true
        var invoked = false
        val caller = graph.scope.async { runCatching { BedJetService.runOwned(context, notifyFailures = false) { invoked = true } } }
        try {
            until { context.requests.isNotEmpty() }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("No service accepted ownership; the caller must fail without waiting for the operation timeout", caller.isCompleted)
            assertTrue(runBlocking { caller.await() }.exceptionOrNull() is IllegalStateException)
            assertFalse(invoked)
            assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(3))
            assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
        } finally { caller.cancel() }
    }

    @Test fun notificationActionUsesOrdinaryServiceDelivery() {
        val action = BedJetService.pendingIntent(app, NightActions.Command.OFF)
        assertTrue(shadowOf(action).isService)
        assertFalse("A stale notification action must not create a promotion obligation", shadowOf(action).isForegroundService)
    }

    @Test fun cancelledMissingHandoffStopsWithoutAForegroundStartObligation() {
        var invoked = false
        val caller = graph.scope.async { BedJetService.runOwned(context) { invoked = true } }
        until { context.delivered != null }
        caller.cancel(); until { caller.isCompleted }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(context.delivered, 0, 1)
            until { shadowOf(service).isStoppedBySelf }
            assertFalse(invoked)
            assertNull(shadowOf(service).lastForegroundNotification)
            assertFalse("Cancellation cannot leave Android waiting for foreground promotion", context.requests.single().first)
        } finally { controller.destroy() }
    }

    @Test fun revokedPermissionCompletesTheOwnerAndStopsWithoutForbiddenPromotion() {
        var invoked = false
        val caller = graph.scope.async { runCatching { BedJetService.runOwned(context, notifyFailures = false) { invoked = true } } }
        until { context.delivered != null }
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(context.delivered, 0, 1)
            until { caller.isCompleted && shadowOf(service).isStoppedBySelf }
            assertTrue(runBlocking { caller.await() }.exceptionOrNull() is SecurityException)
            assertFalse(invoked)
            assertNull("Revoked Bluetooth permission cannot be used to promote connectedDevice work", shadowOf(service).lastForegroundNotification)
            assertFalse("The service must have no unfulfilled FGS-start requirement", context.requests.single().first)
        } finally { controller.destroy(); caller.cancel() }
    }

    @Test
    @Config(shadows = [RejectedForegroundShadow::class])
    fun failedPromotionCompletesTheOwnerWithoutRunningWorkOrLeavingAnFgsObligation() {
        var invoked = false
        val caller = graph.scope.async { runCatching { BedJetService.runOwned(context, notifyFailures = false) { invoked = true } } }
        until { context.delivered != null }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(context.delivered, 0, 1)
            until { caller.isCompleted && shadowOf(service).isStoppedBySelf }
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                Shadow.extract<RejectedForegroundShadow>(service).attemptedType)
            assertTrue(runBlocking { caller.await() }.exceptionOrNull() is SecurityException)
            assertFalse(invoked)
            assertFalse("Catching promotion rejection is safe only without a preceding FGS-start request", context.requests.single().first)
        } finally { controller.destroy(); caller.cancel() }
    }

    @Test fun acceptedHardwareWorkIsPromotedBeforeItsOwnerRunsAndStopsWhenFinished() {
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        val service = controller.get()
        var observedType: Int? = null
        val caller = graph.scope.async {
            BedJetService.runOwned(context) {
                assertNotNull(shadowOf(service).lastForegroundNotification)
                observedType = service.foregroundServiceType
                "finished"
            }
        }
        try {
            until { context.delivered != null }
            service.onStartCommand(context.delivered, 0, 1)
            until { caller.isCompleted && shadowOf(service).isStoppedBySelf }
            assertEquals("finished", runBlocking { caller.await() })
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, observedType)
            assertFalse(context.requests.single().first)
        } finally { controller.destroy(); caller.cancel() }
    }
}
