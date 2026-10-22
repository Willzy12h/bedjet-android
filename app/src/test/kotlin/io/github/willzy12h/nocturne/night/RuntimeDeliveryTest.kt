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
