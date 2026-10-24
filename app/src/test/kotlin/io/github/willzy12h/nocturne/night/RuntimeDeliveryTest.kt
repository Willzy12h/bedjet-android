package io.github.willzy12h.nocturne.night

import android.app.*
import android.content.*
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeDeliveryTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graph get() = context.graph
    @Before fun setup() = runBlocking {
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 1
    }
    private fun alarm(hours: Long) {
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(graph.clock.millis() + hours * 3600000, null),
            PendingIntent.getBroadcast(context, 99, Intent("test"), PendingIntent.FLAG_IMMUTABLE))
    }
    @Test fun clearingEqualTimeOverrideFollowsSubsequentAlarmChange() = runBlocking {
        alarm(6)
        graph.actions.setWakeOverride(graph.now().plusHours(6))
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val n = graph.repository.activeNight.value!!
        assertEquals(WakeOrigin.OVERRIDE, n.wakeOrigin)
        val result = graph.actions.setWakeOverride(null)
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertNull(graph.repository.settings.value.tonightOverride)
        assertEquals(WakeOrigin.ALARM, graph.repository.activeNight.value!!.wakeOrigin)
        assertEquals(n.applied, graph.repository.activeNight.value!!.applied)
        assertEquals(n.wakeAt, graph.repository.activeNight.value!!.wakeAt)
        alarm(7); graph.actions.submitAlarmChange(); graph.actions.retryPendingRetime()
        assertEquals(graph.now().plusHours(7).toInstant(), ZonedDateTime.parse(graph.repository.activeNight.value!!.wakeAt).toInstant())
    }
    private fun quiet() = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(6)
    @Test fun wakeNotificationIsWithdrawnWhenItsNightBecomesUncertainOrChangesRevision() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).plusMinutes(1).toInstant(), ZoneOffset.UTC)
        graph.repository.updateRuntime { it.copy(wakeShown = night.identity) }
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        Notifications.showWakeUp(context, night, graph.repository.settings.value)
        Notifications.reconcileWakeResume(context, night, null, night.identity)
        assertNotNull(manager.getNotification(2))
        Notifications.reconcileWakeResume(context, night.copy(confidence = Confidence.UNKNOWN), null, night.identity)
        assertNull(manager.getNotification(2))
        Notifications.showWakeUp(context, night, graph.repository.settings.value)
        Notifications.reconcileWakeResume(context, night.copy(revision = night.revision + 1), null, night.identity)
        assertNull(manager.getNotification(2))
    }

    @Test fun resumeNotificationIsWithdrawnWhenItsOfferIsRemoved() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        graph.actions.perform(NightActions.Command.START_NIGHT, replacementConfirmed = true)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNotNull(manager.getNotification(7))
        Notifications.reconcileWakeResume(context, graph.repository.activeNight.value, null, null)
        assertNull(manager.getNotification(7))
    }
    @Test fun manualSnoozeWakeNoticeSurvivesTheBackgroundReconcilerAndTrackingConfirmation() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val snoozed = graph.repository.activeNight.value!!
        assertEquals(Confidence.UNKNOWN, snoozed.confidence)
        assertTrue(snoozed.trackingManual)
        assertTrue(graph.repository.runtime.value.manualWakeShown)
        repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.yield() }
        assertNotNull(manager.getNotification(2))

        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.KEEP_TRACKING)).kind)
        repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.yield() }
        assertEquals(graph.repository.activeNight.value!!.identity, graph.repository.runtime.value.wakeShown)
        assertNotNull(manager.getNotification(2))
    }

    @Test fun resumeNoticeExpiresOnAClockTickWithoutAnyRepositoryChange() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        graph.actions.perform(NightActions.Command.START_NIGHT, replacementConfirmed = true)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val offer = graph.repository.runtime.value.resumeOffer!!
        assertNotNull(manager.getNotification(7))
        val ticks = MutableSharedFlow<Unit>(replay = 1)
        ticks.emit(Unit)
        val observer = launch(Dispatchers.Default) { Notifications.observeWakeResume(context, ticks) }
        repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.yield() }
        graph.clock = Clock.fixed(ZonedDateTime.parse(offer.expiresAt).plusSeconds(1).toInstant(), ZoneOffset.UTC)
        ticks.emit(Unit)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (manager.getNotification(7) != null && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.yield()
        }
        assertNull(manager.getNotification(7))
        assertEquals(offer, graph.repository.runtime.value.resumeOffer)
        observer.cancelAndJoin()
    }
    @Test fun quietNotificationIsCancelledOnTakeoverAndManualTracking() = runBlocking {
        graph.actions.updateSettings { it.copy(quietNightNotification = true) }
        graph.actions.perform(NightActions.Command.START_NIGHT)
        assertNotNull(quiet())
        graph.clock = Clock.offset(graph.clock, Duration.ofHours(1))
        graph.controller().setMode(io.github.willzy12h.nocturne.core.protocol.Mode.COOL)
        graph.actions.observeStatus(graph.link.status.value!!, "demo")
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertNull(quiet())
        graph.actions.perform(NightActions.Command.KEEP_TRACKING)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        assertNull(quiet())
    }
    @Test fun quietNotificationIsCancelledOnRemoteFailureAndSettingChange() = runBlocking {
        graph.actions.updateSettings { it.copy(quietNightNotification = true) }
        graph.actions.perform(NightActions.Command.START_NIGHT)
        assertNotNull(quiet())
        graph.actions.updateSettings { it.copy(quietNightNotification = false) }
        assertNull(quiet())
        graph.actions.updateSettings { it.copy(quietNightNotification = true) }
        assertNotNull(quiet())
        graph.actions.remote("Failed fan change") { throw java.io.IOException("GATT failed") }
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertNull(quiet())
    }
    @Test fun quietNotificationIsCancelledOnTakeBackAndSnooze() = runBlocking {
        graph.actions.updateSettings { it.copy(quietNightNotification = true) }
        graph.actions.perform(NightActions.Command.START_NIGHT)
        assertNotNull(quiet())
        graph.controller().stop()
        graph.actions.takeBack()
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertNull(quiet())
        graph.actions.perform(NightActions.Command.STOP_TRACKING)
        graph.actions.perform(NightActions.Command.START_NIGHT)
        assertNotNull(quiet())
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        assertNull(quiet())
    }
    @Test fun quietNotificationRefreshSurvivesSameSettingsRoundTripWithStableNight() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.yield() }
        val stable = graph.repository.activeNight.value!!
        val original = graph.repository.settings.value
        assertFalse(original.quietNightNotification); assertNull(quiet())
        // Do not run the UI observer between the synchronous show and restoring the identical state.
        graph.actions.updateSettings { it.copy(quietNightNotification = true) }
        assertNotNull(quiet())
        graph.repository.updateSettings { original }
        assertEquals(original, graph.repository.settings.value)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (quiet() != null && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); kotlinx.coroutines.yield()
        }
        assertEquals(stable, graph.repository.activeNight.value)
        assertNull(quiet())
    }
    @Test fun quietNotificationAlsoFollowsRestoredSettingsOutsideCommandPath() = runBlocking {
        graph.actions.updateSettings { it.copy(quietNightNotification = true) }
        graph.actions.perform(NightActions.Command.START_NIGHT)
        assertNotNull(quiet())
        graph.repository.updateSettings { it.copy(quietNightNotification = false) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (quiet() != null && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.yield()
        }
        assertNull(quiet())
    }
    @Test fun programmeReplacementPostsResumeActionLeadingToRealConfirmation() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        graph.actions.perform(NightActions.Command.START_NIGHT, replacementConfirmed = true)
        val current = graph.repository.activeNight.value!!
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(7)
        assertNotNull(notification)
        val action = notification.actions.single().actionIntent
        assertTrue(shadowOf(action).isActivityIntent)
        action.send()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java, intent).setup().get()
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertEquals(current.identity, graph.repository.activeNight.value!!.identity)
        graph.repository.setActiveNight(current.copy(revision = current.revision + 1))
        action.send()
        val staleActivity = Robolectric.buildActivity(ShortcutActivity::class.java,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity).setup().get()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val serviceIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(current.identity, NightAlarms.identity(serviceIntent))
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(serviceIntent, 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(current.revision + 1, graph.repository.activeNight.value!!.revision)
        assertTrue(BedJetService.lastResult!!.contains("older night"))
        assertTrue(staleActivity.isFinishing)
        service.onDestroy()
    }
    @Test fun retimeIsDurableCoalescedAndFallbackDoesNoHardwareWork() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val before = graph.controller().programSteps(2)
        alarm(5); graph.actions.recordPendingRetime()
        val first = graph.repository.runtime.value.alarmInbox
        assertNotNull(first)
        alarm(6); graph.actions.recordPendingRetime()
        val newest = graph.repository.runtime.value.alarmInbox!!
        assertNotEquals(first!!.id, newest.id)
        assertEquals(graph.repository.activeNight.value!!.recordId, newest.nightId)
        assertEquals(before, graph.controller().programSteps(2))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any { it.alarmClockInfo == null && it.triggerAtTime <= graph.clock.millis() + 600000 })
        graph.actions.retryPendingRetime()
        assertFalse(graph.actions.retimePending)
        assertEquals(graph.now().plusHours(6).toInstant(), ZonedDateTime.parse(graph.repository.activeNight.value!!.wakeAt).toInstant())
    }
    @Test fun pendingRetimeExpiresAtConfirmedWakeBoundaryWithoutWrite() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val old = graph.repository.activeNight.value!!
        alarm(5); graph.actions.recordPendingRetime()
        graph.clock = Clock.fixed(ZonedDateTime.parse(old.wakeUpStart).toInstant(), ZoneOffset.UTC)
        val result = graph.actions.retryPendingRetime()
        assertNotNull(result); assertTrue(result!!.contains("expired")); assertFalse(graph.actions.retimePending)
        assertEquals(old.applied, graph.repository.activeNight.value!!.applied)
    }
    @Test fun routineAlarmChangeAfterWakeIsQuietNoOp() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val night = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(night.wakeUpStart).toInstant(), ZoneOffset.UTC)
        alarm(7)
        val result = graph.actions.submitAlarmChange()
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertFalse(graph.actions.retimePending)
        assertNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(3))
    }
    @Test fun wakeDeliveryAndSnoozeNeverDuplicateWakeNotice() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        val n = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.wakeUpStart).toInstant(), ZoneOffset.UTC)
        graph.actions.wakeUp(n.identity); graph.actions.wakeUp(n.identity)
        assertEquals(n.identity, graph.repository.runtime.value.wakeShown)
        graph.actions.perform(NightActions.Command.SNOOZE_WARM)
        val snoozed = graph.repository.activeNight.value!!
        graph.actions.wakeUp(n.identity)
        assertEquals(snoozed.identity, graph.repository.runtime.value.wakeShown)
        assertFalse(graph.actions.expire(n.identity))
        assertNotNull(graph.repository.activeNight.value)
    }
    @Test fun externalShortcutShowsConfirmationBeforeStartingService() {
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java, Intent(context, ShortcutActivity::class.java).putExtra("command", "OFF")).setup().get()
        assertFalse(activity.isFinishing)
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
    }
    @Test fun connectPermissionRevokedAfterOrdinaryDeliveryStopsWithoutAnFgsObligation() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false) }
        val delivery = io.github.willzy12h.nocturne.service.ServiceStartContext(context)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(BedJetService.start(delivery, NightActions.Command.OFF))
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(delivery.delivered, 0, 1)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse("Stopping without promotion is safe only on ordinary service delivery", delivery.requests.single().first)
        service.onDestroy()
    }
    @Test fun expectedBackgroundRetryDenialKeepsItsResultWithoutAnErrorNotice() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false) }
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(3)
        BedJetService.lastResult = null
        assertFalse(BedJetService.start(context, NightActions.Command.RETRY_RETIME, notifyFailures = false))
        assertNotNull(BedJetService.lastResult)
        assertNull(manager.getNotification(3))
    }
    @Test fun deniedRetryAtBoundaryExpiresObservationLocally() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        alarm(5); graph.actions.submitAlarmChange()
        val n = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.wakeUpStart).toInstant(), ZoneOffset.UTC)
        val result = graph.actions.submitAlarmChange()
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertTrue(result.message.contains("unchanged", ignoreCase = true))
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertFalse(graph.actions.retimePending)
        assertEquals(n.applied, graph.repository.activeNight.value!!.applied)
    }
    @Test fun themeResolverHonoursExplicitPaletteAndSystem() {
        assertTrue(Settings(themeMode = ThemeMode.DARK).isDarkTheme(graph.now(), false))
        assertFalse(Settings(themeMode = ThemeMode.LIGHT).isDarkTheme(graph.now(), true))
        assertTrue(Settings().isDarkTheme(graph.now(), true))
    }
}
