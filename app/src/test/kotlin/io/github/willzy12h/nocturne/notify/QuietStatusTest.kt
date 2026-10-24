package io.github.willzy12h.nocturne.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P5: the quiet status notification says what Tonight says while a wake-up change is pending. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuietStatusTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val night = ActiveNight("n1", "p", "2026-10-22T22:30Z", "2026-10-23T07:00Z", false, 2,
        "2026-10-23T06:30Z", "2026-10-23T07:30Z", deviceId = "demo", confidence = Confidence.CONFIRMED)
    private fun title(): String? = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        .lastOrNull()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test fun aConfirmedNightShowsItsWake() {
        Notifications.showQuiet(context, night, Settings(quietNightNotification = true))
        assertEquals(context.getString(R.string.runtime_quiet, "07:00"), title())
    }

    @Test fun aPendingWakeChangeIsShownAsPending() {
        Notifications.showQuiet(context, night, Settings(quietNightNotification = true), wakePending = true)
        assertEquals(context.getString(R.string.quiet_pending, "07:00"), title())
    }

    @Test fun aPendingRetimeRepostsTheQuietStatusAsPendingAndBackAgain() = kotlinx.coroutines.runBlocking {
        val graph = (context as io.github.willzy12h.nocturne.App).graph
        graph.repository.updateSettings { it.copy(demoMode = true, quietNightNotification = true) }
        graph.repository.setActiveNight(night)
        idle()
        assertEquals(context.getString(R.string.runtime_quiet, "07:00"), title())
        graph.repository.updateRuntime { it.copy(retime = io.github.willzy12h.nocturne.night.PendingRetime("r", night.identity, "demo",
            "2026-10-23T06:30Z", null, "2026-10-23T06:00Z")) }
        idle()
        assertEquals(context.getString(R.string.quiet_pending, "07:00"), title())
        graph.repository.updateRuntime { it.copy(retime = null) }
        idle()
        assertEquals(context.getString(R.string.runtime_quiet, "07:00"), title())
        graph.repository.setActiveNight(null)
        graph.repository.updateRuntime { io.github.willzy12h.nocturne.night.RuntimeState() }
        idle()
    }
    private fun idle() = repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(5) }
}
