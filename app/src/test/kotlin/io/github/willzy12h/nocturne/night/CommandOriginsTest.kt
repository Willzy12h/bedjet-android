package io.github.willzy12h.nocturne.night

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.data.SlotBackupsUnreadable
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.platform.PlatformActionActivity
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import kotlinx.coroutines.*
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
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandOriginsTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App
    private val graph get() = app.graph

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val isolated = graphs.fresh { Graph(app, demo) }
        ReflectionHelpers.setField(app, "graph", isolated)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 1
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var lastTick = System.nanoTime()
        var remainder = 0L
        while (!check() && System.nanoTime() < deadline) {
            val tick = System.nanoTime()
            val elapsed = tick - lastTick + remainder
            lastTick = tick
            remainder = elapsed % 1_000_000
            // Main's scheduler follows elapsed real time; the graph's fixed policy clock stays untouched.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed / 1_000_000))
            Thread.yield()
        }
        assertTrue("Timed out driving the real entry point", check())
    }

    private suspend fun emittedOutcome(work: suspend () -> CommandOutcome): CommandOutcome = coroutineScope {
        val emitted = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            graph.actions.outcomes.first()
        }
        try {
            val returned = work()
            assertSame("The origin-tagged return and completion feed share one object", returned,
                withTimeout(5_000) { emitted.await() })
            returned
        } finally {
            emitted.cancel()
        }
    }

    private fun nextServiceIntent(): Intent {
        var delivered: Intent? = null
        until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null }
        return delivered!!
    }

    private suspend fun serviceOutcome(intent: Intent): CommandOutcome {
        val emitted = graph.scope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            graph.actions.outcomes.first()
        }
        val service = Robolectric.buildService(BedJetService::class.java).create()
        try {
            service.get().onStartCommand(intent, 0, 1)
            until { emitted.isCompleted && shadowOf(service.get()).isStoppedBySelf }
            return emitted.await()
        } finally {
            emitted.cancel()
            service.destroy()
        }
    }

    @Test fun appRequestRefusalKeepsAppOriginAndOneCompletionObject() = runBlocking {
        val request = graph.actions.capture(NightActions.Command.STOP_TRACKING)
        val result = emittedOutcome { graph.actions.execute(request) }
        assertEquals(CommandOrigin.APP, request.origin)
        assertEquals(CommandOrigin.APP, result.origin)
        assertEquals(OutcomeKind.REFUSED, result.kind)
    }

    @Test fun anOutcomeWithoutAnEntryPointDoesNotClaimTheAppInitiatedIt() {
        val outcome = CommandOutcome(OutcomeKind.UNKNOWN, "No entry point supplied")
        assertNotEquals("Missing attribution must remain distinguishable from a known app command",
            CommandOrigin.APP, outcome.origin)
    }

    @Test fun serviceContextTagsAnUnspecifiedOwnedResultBeforeItIsReturnedAndEmitted() = runBlocking {
        for (origin in listOf(CommandOrigin.WIDGET, CommandOrigin.NOTIFICATION, CommandOrigin.CHARGER)) {
            val outcome = emittedOutcome {
                withContext(CommandOriginContext(origin)) {
                    graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING))
                }
            }
            assertEquals(OutcomeKind.REFUSED, outcome.kind)
            assertEquals(origin, outcome.origin)
        }
    }

    @Test fun anExplicitSettingsResultIsNotReattributedToTheServiceContext() = runBlocking {
        val outcome = emittedOutcome {
            withContext(CommandOriginContext(CommandOrigin.NOTIFICATION)) {
                graph.actions.setWakeOverride(graph.now().plusHours(8))
            }
        }
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertEquals(CommandOrigin.SETTINGS, outcome.origin)
    }

    @Test fun aDirectSlotWriteAcknowledgementKeepsAppOrigin() = runBlocking {
        graph.repository.updateRuntime {
            it.copy(slotWrite = SlotWrite(graph.repository.settings.value.deviceId()!!, 0, null,
                confidence = io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN, label = "Restore"))
        }
        val outcome = graph.actions.acknowledgeSlotWrite()
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertNull("Acknowledgement clears the checked slot write locally", graph.repository.runtime.value.slotWrite)
        assertEquals("This direct app entry does not pass through the owned outcome tagger", CommandOrigin.APP, outcome.origin)
    }

    @Test fun explicitRequestOriginSurvivesStaleTargetAdmission() = runBlocking {
        val request = graph.actions.capture(NightActions.Command.STOP_TRACKING)
            .copy(deviceId = "ble:11:22:33:44:55:66", origin = CommandOrigin.WIDGET)
        val result = emittedOutcome { graph.actions.execute(request) }
        assertEquals(OutcomeKind.STALE, result.kind)
        assertEquals(CommandOrigin.WIDGET, result.origin)
    }

    @Test fun settingsSaveIsTaggedBeforeItIsReturnedAndEmitted() = runBlocking {
        val result = emittedOutcome { graph.actions.updateSettings { it.copy(fahrenheit = !it.fahrenheit) } }
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertEquals(CommandOrigin.SETTINGS, result.origin)
    }

    @Test fun ownedExceptionKeepsTheSettingsSource() = runBlocking {
        val result = emittedOutcome {
            graph.actions.updateSettings { throw IllegalStateException("Settings change failed") }
        }
        assertEquals(OutcomeKind.FAILED, result.kind)
        assertEquals(CommandOrigin.SETTINGS, result.origin)
        assertEquals("Settings change failed", result.message)
    }

    @Test fun ownedSlotBackupRefusalKeepsTheSettingsSource() = runBlocking {
        val result = emittedOutcome { graph.actions.updateSettings { throw SlotBackupsUnreadable() } }
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(CommandOrigin.SETTINGS, result.origin)
    }

    @Test fun anIdleWakeOverrideIsASettingsSave() = runBlocking {
        val result = emittedOutcome { graph.actions.setWakeOverride(graph.now().plusHours(8)) }
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertEquals(CommandOrigin.SETTINGS, result.origin)
    }

    @Test fun anActiveWakeOverrideResultKeepsAppOrigin() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED,
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val result = emittedOutcome { graph.actions.setWakeOverride(null) }
        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertEquals(CommandOrigin.APP, result.origin)
    }

    @Test fun retimeCommandsDefaultToAlarmAtCaptureAndLegacyServiceDelivery() = runBlocking {
        for (command in listOf(NightActions.Command.RETIME, NightActions.Command.RETRY_RETIME)) {
            assertEquals(CommandOrigin.ALARM, graph.actions.capture(command).origin)
            if (command == NightActions.Command.RETRY_RETIME) {
                // An empty ended-night retry now settles before the owner. Use real outstanding
                // tracking that the owner must refuse, so its legacy origin is still exercised.
                assertEquals(OutcomeKind.CONFIRMED,
                    graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
                graph.repository.setActiveNight(graph.repository.activeNight.value!!.copy(confidence = Confidence.UNKNOWN))
            }
            val legacyIntent = Intent(app, BedJetService::class.java)
                .putExtra(BedJetService.EXTRA_COMMAND, command.name)
                .putExtra("target", graph.repository.settings.value.deviceId())
            val result = serviceOutcome(legacyIntent)
            assertEquals(OutcomeKind.REFUSED, result.kind)
            assertEquals(CommandOrigin.ALARM, result.origin)
        }
    }

    @Test fun directAlarmSubmissionAndItsAliasTagEarlyRefusals() = runBlocking {
        val submitted = graph.actions.submitAlarmChange()
        val recorded = graph.actions.recordPendingRetime()
        assertEquals(OutcomeKind.REFUSED, submitted.kind)
        assertEquals(CommandOrigin.ALARM, submitted.origin)
        assertEquals(OutcomeKind.REFUSED, recorded.kind)
        assertEquals(CommandOrigin.ALARM, recorded.origin)
    }

    @Test fun pendingAlarmRetryEmitsAlarmOrigin() = runBlocking {
        graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        val submitted = graph.actions.submitAlarmChange()
        assertEquals(OutcomeKind.LOCAL, submitted.kind)
        assertEquals(CommandOrigin.ALARM, submitted.origin)
        val emitted = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            graph.actions.outcomes.first()
        }
        try {
            val message = graph.actions.retryPendingRetime()
            val outcome = withTimeout(5_000) { emitted.await() }
            assertEquals(message, outcome.message)
            assertEquals(CommandOrigin.ALARM, outcome.origin)
        } finally {
            emitted.cancel()
        }
    }

    @Test fun chargerEligibilityRefusalCarriesChargerOrigin() = runBlocking {
        val result = emittedOutcome {
            graph.actions.performJobOwned(graph.actions.capture(NightActions.Command.START_NIGHT)) { false }
        }
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(CommandOrigin.CHARGER, result.origin)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun busyPeerHandoverRefusalCarriesPeerOrigin() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            graph.actions.exclusive { entered.complete(Unit); release.await() }
        }
        try {
            withTimeout(5_000) { entered.await() }
            val result = graph.platform.handover.peerRequest()
            assertEquals(OutcomeKind.REFUSED, result.kind)
            assertEquals(CommandOrigin.PEER, result.origin)
        } finally {
            release.complete(Unit)
            holder.await()
        }
    }

    @Test fun peerHandoverCompletionKeepsPeerOriginWhileManualReleaseKeepsApp() = runBlocking {
        val peer = emittedOutcome { graph.platform.handover.peerRequest() }
        assertEquals(OutcomeKind.LOCAL, peer.kind)
        assertEquals(CommandOrigin.PEER, peer.origin)
        val manual = emittedOutcome { graph.platform.handover.release() }
        assertEquals(OutcomeKind.LOCAL, manual.kind)
        assertEquals(CommandOrigin.APP, manual.origin)
    }

    @Test fun aCapturedOriginSurvivesTheServiceIntentRoundTrip() = runBlocking {
        val request = graph.actions.capture(NightActions.Command.STOP_TRACKING)
            .copy(origin = CommandOrigin.WIDGET)
        assertTrue(BedJetService.start(app, request, notifyFailures = false))
        val result = serviceOutcome(nextServiceIntent())
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(CommandOrigin.WIDGET, result.origin)
    }

    @Test fun notificationPendingIntentCarriesNotificationOrigin() = runBlocking {
        BedJetService.pendingIntent(app, NightActions.Command.STOP_TRACKING).send()
        val result = serviceOutcome(nextServiceIntent())
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(CommandOrigin.NOTIFICATION, result.origin)
    }

    @Test fun widgetEntryCarriesItsOriginAcrossTheServiceHandoff() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val rendered = PlatformActionActivity.intent(app,
            graph.actions.capture(NightActions.Command.STOP_TRACKING, requireIdentity = true))
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java, rendered).setup()
        try {
            val result = serviceOutcome(nextServiceIntent())
            assertEquals(OutcomeKind.REFUSED, result.kind)
            assertEquals(CommandOrigin.WIDGET, result.origin)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun reminderPreviewCarriesReminderOriginThroughTheDemoEntry() = runBlocking {
        val emitted = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            graph.actions.outcomes.first()
        }
        val reminder = Intent(app, PlatformActionActivity::class.java)
            .putExtra("profile", graph.repository.profiles.value.first().id)
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java, reminder).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until { emitted.isCompleted && graph.actions.busy.value.runningLabel == null }
            val result = emitted.await()
            assertEquals(OutcomeKind.CONFIRMED, result.kind)
            assertEquals(CommandOrigin.REMINDER, result.origin)
        } finally {
            emitted.cancel()
            activity.pause().stop().destroy()
        }
    }

    @Test fun widgetUriIdentifiesTheEntryWhenTheRenderedFlagIsAbsent() = runBlocking {
        val emitted = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            graph.actions.outcomes.first()
        }
        val widget = PlatformActionActivity.intent(app, graph.actions.capture(NightActions.Command.STOP_TRACKING))
            .apply { removeExtra("rendered") }
        val activity = Robolectric.buildActivity(PlatformActionActivity::class.java, widget).setup()
        try {
            until { emitted.isCompleted && graph.actions.busy.value.runningLabel == null }
            assertEquals(CommandOrigin.WIDGET, emitted.await().origin)
        } finally {
            emitted.cancel()
            activity.pause().stop().destroy()
        }
    }

    @Test fun confirmedShortcutCarriesShortcutOriginThroughTheService() = runBlocking {
        val shortcut = Intent(app, ShortcutActivity::class.java)
            .putExtra("command", NightActions.Command.STOP_TRACKING.name)
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java, shortcut).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val result = serviceOutcome(nextServiceIntent())
            assertEquals(OutcomeKind.REFUSED, result.kind)
            assertEquals(CommandOrigin.SHORTCUT, result.origin)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun resumeNotificationUsesNotificationOriginAfterConfirmation() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED,
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        assertEquals(OutcomeKind.CONFIRMED,
            graph.actions.execute(graph.actions.capture(NightActions.Command.OFF)).kind)
        assertNotNull(graph.repository.runtime.value.resumeOffer)
        Notifications.showResume(app)
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(7)
        assertNotNull(notification)
        notification.actions.single().actionIntent.send()
        val activity = Robolectric.buildActivity(ShortcutActivity::class.java,
            shadowOf(app).nextStartedActivity).setup()
        try {
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val result = serviceOutcome(nextServiceIntent())
            assertEquals(OutcomeKind.CONFIRMED, result.kind)
            assertEquals(CommandOrigin.NOTIFICATION, result.origin)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
