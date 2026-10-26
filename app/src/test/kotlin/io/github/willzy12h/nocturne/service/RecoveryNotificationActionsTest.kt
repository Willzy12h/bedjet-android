package io.github.willzy12h.nocturne.service

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.ui.ShortcutActivity
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryNotificationActionsTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 1
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false, quietNightNotification = true) }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        Unit
    }

    private fun action(id: Int, label: String): Intent {
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(id)!!
        val action = notification.actions.single { it.title.toString() == label }
        assertTrue("$label must ask before changing a tracked night", shadowOf(action.actionIntent).isActivityIntent)
        return shadowOf(action.actionIntent).savedIntent
    }

    private fun deliverAndWait(condition: () -> Boolean) {
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadowOf(app).nextStartedService
        assertNotNull("Acceptance should deliver one owned service request", intent)
        val service = Robolectric.buildService(BedJetService::class.java).create()
        try {
            service.get().onStartCommand(intent, 0, 1)
            val deadline = System.nanoTime() + 5_000_000_000L
            var lastTick = System.nanoTime()
            var remainder = 0L
            fun settled() = condition() && shadowOf(service.get()).isStoppedBySelf
            while (!settled() && System.nanoTime() < deadline) {
                // Disk publication runs on a real worker. Do not advance the 100-second service
                // deadline hundreds of times faster than that worker can finish its actual I/O.
                val tick = System.nanoTime()
                val elapsed = tick - lastTick + remainder
                lastTick = tick
                remainder = elapsed % 1_000_000
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed / 1_000_000))
                Thread.yield()
            }
            assertTrue("The accepted action must settle through the command owner: result=${BedJetService.lastResult}, " +
                "night=${graph.repository.activeNight.value?.identity}, reason=${graph.repository.runtime.value.reason}, " +
                "mode=${graph.link.status.value?.mode}, stopped=${shadowOf(service.get()).isStoppedBySelf}", settled())
        } finally { service.destroy() }
    }

    @Test fun stopNotificationCancelsWithoutSendingAndAcceptanceEndsOnlyItsCapturedNight() {
        val original = graph.repository.activeNight.value!!.identity
        val mode = graph.link.status.value!!.mode
        val intent = action(6, "Stop night")
        val cancelled = Robolectric.buildActivity(ShortcutActivity::class.java, intent).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Stop night", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(original, graph.repository.activeNight.value?.identity)
            assertEquals(mode, graph.link.status.value?.mode)
            assertNull(shadowOf(app).nextStartedService)
        } finally { cancelled.pause().stop().destroy() }
        val accepted = Robolectric.buildActivity(ShortcutActivity::class.java, intent).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            deliverAndWait { graph.repository.activeNight.value == null }
            assertEquals(Mode.STANDBY, graph.link.status.value?.mode)
        } finally { accepted.pause().stop().destroy() }
    }

    @Test fun resumeNotificationKeepsItsWorkingRouteAndNamesItsPositiveAction() = runBlocking {
        graph.actions.execute(graph.actions.capture(NightActions.Command.SNOOZE_WARM))
        Notifications.showResume(app)
        val controller = Robolectric.buildActivity(ShortcutActivity::class.java, action(7, "Resume night")).setup()
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Resume night", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            deliverAndWait { graph.repository.activeNight.value?.let { !it.trackingManual && it.confidence == Confidence.CONFIRMED } == true &&
                graph.repository.runtime.value.resumeOffer == null }
        } finally { controller.pause().stop().destroy() }
    }
}
