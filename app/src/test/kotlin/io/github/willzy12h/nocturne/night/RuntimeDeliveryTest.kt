package io.github.willzy12h.nocturne.night

import android.app.*
import android.content.*
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.ShortcutActivity
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
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graph get() = context.graph
    @Before fun setup() = runBlocking {
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
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
    @Test fun missingConnectPermissionDoesNotEnterConnectedDeviceForeground() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false) }
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        service.onStartCommand(BedJetService.intent(context, NightActions.Command.OFF), 0, 1)
        assertFalse(shadowOf(service).isForegroundStopped == false && shadowOf(service).lastForegroundNotification != null)
        service.onDestroy()
    }
    @Test fun deniedRetryAtBoundaryExpiresObservationLocally() = runBlocking {
        graph.actions.perform(NightActions.Command.START_NIGHT)
        alarm(5); graph.actions.submitAlarmChange()
        val n = graph.repository.activeNight.value!!
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.wakeUpStart).toInstant(), ZoneOffset.UTC)
        val result = graph.actions.submitAlarmChange()
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertTrue(result.message.contains("expired"))
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
