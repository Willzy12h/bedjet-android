package io.github.willzy12h.nocturne.service

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.platform.PlatformActionActivity
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** D6: delivery surfaces keep their source through capture, the service and the owner's outcome stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandOriginDeliveryTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        graph = graphs.fresh { Graph(app, demo) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        App::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun origin(outcome: CommandOutcome): CommandOrigin = outcome.origin

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("Timed out pumping the main looper", check())
    }

    private suspend fun published(work: suspend () -> CommandOutcome): CommandOutcome = coroutineScope {
        val emitted = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        try {
            val returned = work()
            assertSame("The return and feed must preserve the same outcome for UI deduplication", returned, emitted.await())
            returned
        } finally { emitted.cancel() }
    }

    private fun nextOutcome(deliver: () -> Unit): CommandOutcome {
        val emitted = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        try {
            deliver()
            until { emitted.isCompleted }
            return runBlocking { emitted.await() }
        } finally { emitted.cancel() }
    }

    private fun deliverService() {
        var delivered: Intent? = null
        until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null }
        val service = Robolectric.buildService(BedJetService::class.java).create().get()
        try {
            service.onStartCommand(delivered, 0, 1)
            until { graph.actions.busy.value.runningLabel == null && shadowOf(service).isStoppedBySelf }
        } finally { service.onDestroy() }
    }

    @Test fun appCommandsPublishTheAppOriginOnTheirActualOutcome() = runBlocking {
        val result = published { graph.actions.execute(graph.actions.capture(Command.STOP_TRACKING)) }
        assertEquals(CommandOrigin.APP, origin(result))
    }

    @Test fun aStaleTargetRefusalKeepsItsCapturedAppOrigin() = runBlocking {
        val request = graph.actions.capture(Command.STOP_TRACKING)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val result = published { graph.actions.execute(request) }
        assertEquals(OutcomeKind.STALE, result.kind)
        assertEquals(CommandOrigin.APP, origin(result))
    }

    @Test fun routineSettingsSavesAndTheirFailuresPublishTheSettingsOrigin() = runBlocking {
        assertEquals(CommandOrigin.SETTINGS, origin(published { graph.actions.updateSettings { it.copy(simpleView = !it.simpleView) } }))
        assertEquals(CommandOrigin.SETTINGS, origin(published { graph.actions.updateSettings { error("save failed") } }))
    }

    @Test fun aWakeOverrideSavedWithoutHardwareHasTheSettingsOrigin() = runBlocking {
        assertEquals(CommandOrigin.SETTINGS, origin(published { graph.actions.setWakeOverride(graph.now().plusHours(8)) }))
    }

    @Test fun chargerEligibilityRefusalsRetainTheChargerOrigin() = runBlocking {
        val result = published { graph.actions.performJobOwned(graph.actions.capture(Command.START_NIGHT)) { false } }
        assertEquals(CommandOrigin.CHARGER, origin(result))
    }

    @Test fun alarmRetryAndSubmissionRefusalsRetainTheAlarmOrigin() = runBlocking {
        assertEquals(CommandOrigin.ALARM, origin(published { graph.actions.execute(graph.actions.capture(Command.RETRY_RETIME)) }))
        assertEquals(CommandOrigin.ALARM, origin(graph.actions.submitAlarmChange()))
    }

    @Test fun aLauncherShortcutKeepsItsOriginThroughTheServiceIntent() {
        val controller = Robolectric.buildActivity(ShortcutActivity::class.java,
            Intent(app, ShortcutActivity::class.java).putExtra("command", Command.STOP_TRACKING.name)).create()
        try {
            val result = nextOutcome {
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                deliverService()
            }
            assertEquals(CommandOrigin.SHORTCUT, origin(result))
        } finally { controller.destroy() }
    }

    @Test fun aNotificationActionKeepsItsOriginThroughThePendingIntent() {
        val result = nextOutcome {
            BedJetService.pendingIntent(app, Command.STOP_TRACKING).send()
            deliverService()
        }
        assertEquals(CommandOrigin.NOTIFICATION, origin(result))
    }

    @Test fun aNotificationConfirmationKeepsItsNotificationOrigin() {
        val request = graph.actions.capture(Command.STOP_TRACKING, requireIdentity = true)
        val controller = Robolectric.buildActivity(ShortcutActivity::class.java,
            ShortcutActivity.confirmationIntent(app, request)).create()
        try {
            val result = nextOutcome {
                ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                deliverService()
            }
            assertEquals(CommandOrigin.NOTIFICATION, origin(result))
        } finally { controller.destroy() }
    }

    @Test fun aWidgetKeepsItsOriginInDemoDelivery() {
        val request = graph.actions.capture(Command.STOP_TRACKING, requireIdentity = true)
        val controller = Robolectric.buildActivity(PlatformActionActivity::class.java, PlatformActionActivity.intent(app, request))
        try {
            val result = nextOutcome { controller.create() }
            assertEquals(CommandOrigin.WIDGET, origin(result))
        } finally { controller.destroy() }
    }

    @Test fun aWidgetKeepsItsOriginThroughTheRealServiceHandoff() {
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val request = graph.actions.capture(Command.STOP_TRACKING, requireIdentity = true)
        val controller = Robolectric.buildActivity(PlatformActionActivity::class.java, PlatformActionActivity.intent(app, request))
        try {
            val result = nextOutcome { controller.create(); deliverService() }
            assertEquals(CommandOrigin.WIDGET, origin(result))
        } finally { controller.destroy() }
    }

    @Test fun queuedWidgetAndAppRequestsKeepTheirOwnOrigins() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = graph.scope.launch { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        val outcomes = mutableListOf<CommandOutcome>()
        val collecting = graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.collect { outcomes += it } }
        val appRequest = graph.actions.capture(Command.STOP_TRACKING)
        val controller = Robolectric.buildActivity(PlatformActionActivity::class.java,
            PlatformActionActivity.intent(app, graph.actions.capture(Command.STOP_TRACKING, requireIdentity = true)))
        var appWork: Deferred<CommandOutcome>? = null
        try {
            until { entered.isCompleted }
            controller.create()
            until { graph.actions.busy.value.queuedCount == 1 }
            val work = graph.scope.async { graph.actions.execute(appRequest) }
            appWork = work
            until { graph.actions.busy.value.queuedCount == 2 }
            release.complete(Unit)
            until { work.isCompleted && outcomes.size == 2 }
            assertEquals(listOf(CommandOrigin.WIDGET, CommandOrigin.APP), outcomes.map(::origin))
            assertEquals(CommandOrigin.APP, origin(runBlocking { work.await() }))
        } finally {
            release.complete(Unit)
            collecting.cancel()
            appWork?.cancel()
            holder.cancel()
            controller.destroy()
        }
    }

    @Test fun anUnmarkedPlatformPreviewIsNotMislabelledAsAWidget() {
        val controller = Robolectric.buildActivity(PlatformActionActivity::class.java,
            Intent(app, PlatformActionActivity::class.java).putExtra("command", Command.STOP_TRACKING.name))
        try {
            val result = nextOutcome { controller.create() }
            assertEquals(CommandOrigin.BACKGROUND, origin(result))
        } finally { controller.destroy() }
    }
}
