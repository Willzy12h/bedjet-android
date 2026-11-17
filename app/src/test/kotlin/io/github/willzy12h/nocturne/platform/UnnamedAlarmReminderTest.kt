package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.core.store.UnnamedAlarms
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The bedtime reminder's one-tap use of an alarm Android didn't name ("Ask each night"). */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class UnnamedAlarmReminderTest : PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graph get() = context.graph

    private fun prepare(mode: UnnamedAlarms, alarmAt: String = "2026-10-23T06:30:00Z"): String = runBlocking {
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:00:00Z"), ZoneOffset.UTC)
        val repo = graph.repository
        val profile = repo.profiles.value.first().let {
            it.copy(bedtimeMinute = 22 * 60 + 30, wakeSource = it.wakeSource.copy(useAlarm = true, defaultWakeMinute = 7 * 60))
        }
        repo.saveProfile(profile)
        repo.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(unnamedAlarms = mode,
            platform = it.platform.copy(bedtimeReminder = true)) }
        PlatformNotifications.channel(context)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setAlarm(alarmAt)
        profile.id
    }

    /** An alarm with no show intent, so Android can't name its app. */
    private fun setAlarm(at: String) {
        val operation = PendingIntent.getBroadcast(context, 77, Intent("test.unnamed.alarm"), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(Instant.parse(at).toEpochMilli(), null), operation)
    }

    private fun reminder() = shadowOf(context.getSystemService(NotificationManager::class.java))
        .getNotification(PlatformNotifications.REMINDER_ID)

    private fun tapUse() {
        reminder().actions!!.single().actionIntent.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
    }

    @Test fun askOffersTheUnnamedAlarmAndATapSavesItForTonight() = runBlocking {
        val profile = prepare(UnnamedAlarms.ASK)
        assertTrue(BedtimeReminders.deliver(graph, profile, graph.now().plusMinutes(30)))
        tapUse()
        waitFor { graph.repository.settings.value.tonightOverride != null }
        assertEquals(Instant.parse("2026-10-23T06:30:00Z"),
            ZonedDateTime.parse(graph.repository.settings.value.tonightOverride!!.wakeAt).toInstant())
        assertEquals(UnnamedAlarms.ASK, graph.repository.settings.value.unnamedAlarms)
    }

    @Test fun anAlarmThatMovedSinceTheReminderIsNotUsed() = runBlocking {
        val profile = prepare(UnnamedAlarms.ASK)
        assertTrue(BedtimeReminders.deliver(graph, profile, graph.now().plusMinutes(30)))
        setAlarm("2026-10-23T05:45:00Z")
        tapUse()
        waitFor { reminder() == null }
        assertNull(graph.repository.settings.value.tonightOverride)
    }

    @Test fun ignoreAndAlwaysAddNoActionToTheReminder() = runBlocking {
        for (mode in listOf(UnnamedAlarms.IGNORE, UnnamedAlarms.ALWAYS)) {
            val profile = prepare(mode)
            graph.repository.updateSettings { it }
            context.getSystemService(NotificationManager::class.java).cancelAll()
            graph.clock = Clock.fixed(Instant.parse(if (mode == UnnamedAlarms.IGNORE) "2026-10-22T22:00:00Z" else "2026-10-23T22:00:00Z"), ZoneOffset.UTC)
            setAlarm(if (mode == UnnamedAlarms.IGNORE) "2026-10-23T06:30:00Z" else "2026-10-24T06:30:00Z")
            assertTrue(BedtimeReminders.deliver(graph, profile, graph.now().plusMinutes(30)))
            assertTrue("$mode", reminder().actions.isNullOrEmpty())
        }
    }
}
