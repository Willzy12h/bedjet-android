package io.github.willzy12h.nocturne

import android.os.Looper
import android.os.SystemClock
import android.Manifest
import android.app.NotificationManager
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.ResumeOffer
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Exercise the production default timers, without substituting a test tick flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleProcessTimersTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var clock: CountingClock

    @Before fun setup() = runBlocking {
        val repositoryTime = Instant.parse("2026-10-26T22:30:00Z").atZone(ZoneOffset.UTC)
        graph = graphs.fresh {
            Graph(app, repositoryFactory = { scope, _ ->
                // Snapshot timestamps must not contaminate the notification/demo clock measurement.
                Repository(app, scope, now = { repositoryTime })
            })
        }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        idle()
        clock = CountingClock()
        graph.clock = clock
    }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun awaitInitialReconciliation() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (clock.reads == 0 && System.nanoTime() < deadline) {
            idle()
            Thread.yield()
        }
        assertEquals("The real observer must reconcile once before measuring idle time", 1, clock.reads)
    }

    @Test fun notificationExpiryDoesNotWakeTheIdleProcessEveryMinute() {
        graph.scope.launch { Notifications.observeWakeResume(app) }
        awaitInitialReconciliation()
        val before = clock.reads
        idle(180_001)
        assertEquals("No night or offer means no clock timer", before, clock.reads)
    }

    @Test fun aResumeNoticeStillExpiresWithoutARepositoryChangeThenStopsItsTimer() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = graph.now()
        val night = ActiveNight("notice", "preset-summer", now.toString(), now.plusHours(8).toString(), false,
            2, now.plusHours(7).toString(), now.plusHours(9).toString(), deviceId = "demo")
        graph.repository.updateRuntime { it.copy(resumeOffer = ResumeOffer(night, emptyList(), now.toString(),
            now.plusSeconds(3).toString(), it.generation)) }
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        Notifications.showResume(app)
        assertNotNull(manager.getNotification(7))
        graph.scope.launch { Notifications.observeWakeResume(app) }
        idle(3_001)
        assertNull(manager.getNotification(7))
        val reads = clock.reads
        idle(180_001)
        assertEquals("A retained expired offer must not keep a minute loop alive", reads, clock.reads)
    }

    @Test fun theDemoClockSleepsWhileHiddenAndIdleThenRefreshesWhenVisible() = runBlocking {
        graph.link.connect()
        idle()
        val before = graph.link.status.value
        val reads = clock.reads
        idle(5_001)
        assertEquals("An idle hidden demo must not tick", reads, clock.reads)
        assertEquals(before, graph.link.status.value)
        graph.sessions.holdForScreen(true)
        idle(1_001)
        assertTrue("The visible demo still advances", clock.reads > reads)
        assertEquals(45, graph.link.status.value?.actualTemperature)
        graph.sessions.holdForScreen(false)
        idle()
        val hiddenReads = clock.reads
        idle(5_001)
        assertEquals("The grace period must not restart the demo clock", hiddenReads, clock.reads)
    }

    @Test fun aRunningDemoProgrammeStillEndsWhileTheScreenIsHidden() = runBlocking {
        graph.link.connect()
        graph.controller().setMode(Mode.COOL)
        graph.controller().setRuntime(1)
        idle(60_001)
        assertEquals(Mode.STANDBY, graph.link.status.value?.mode)
        val reads = clock.reads
        idle(5_001)
        assertEquals("Once the programme ends, its timer must stop too", reads, clock.reads)
    }

    private class CountingClock : Clock() {
        var reads = 0
        private val elapsedStart = SystemClock.elapsedRealtime()
        private val start = Instant.parse("2026-10-26T22:30:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant {
            reads++
            return start.plusMillis(SystemClock.elapsedRealtime() - elapsedStart)
        }
    }
}
