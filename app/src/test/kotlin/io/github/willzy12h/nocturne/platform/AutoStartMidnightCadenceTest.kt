package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.operationIntent
import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
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
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartMidnightCadenceTest : PlatformGraphFixture() {
    @Test fun aMidnightRelativeStartDoesNotSuppressTheFollowingEveningsNight() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val device = "ble:AA:BB:CC:DD:EE:FF"
        lateinit var graph: Graph
        graph = fresh { Graph(context, DemoBedJet(now = { graph.now() })) }
        ReflectionHelpers.setField(context as App, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC)
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(wakeSource = profile.wakeSource.copy(defaultWakeMinute = 7 * 60)))
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true, mode = AutoStartMode.BEFORE_WAKE,
                profileId = profile.id, deviceId = device, minutesBeforeWake = 480))) }
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(Instant.parse("2026-10-27T08:00:00Z").toEpochMilli(), null),
            PendingIntent.getBroadcast(context, 924, Intent("test.midnight.alarm"), PendingIntent.FLAG_IMMUTABLE))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.actions.confirmMs = 1
        ChargerRun(graph, { false }, { _, _ -> true }, {}, { true }).execute()
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        ChargerJobs.sync(graph)
        val pending = shadowOf(alarms).scheduledAlarms.filter {
            shadowOf(it.operationIntent).savedIntent.component?.className == AutoStartReceiver::class.java.name
        }.single()
        assertEquals("A midnight run belongs to the morning it serves, not the following evening's night",
            Instant.parse("2026-10-27T23:00:00Z").toEpochMilli(), pending.triggerAtMs)
        assertTrue(graph.repository.automation.value.claims.contains("charger:$device:2026-10-26"))
        assertFalse(graph.repository.automation.value.claims.contains("charger:$device:2026-10-27"))
    }
}
