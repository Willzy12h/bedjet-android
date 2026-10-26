package io.github.willzy12h.nocturne.ui

import android.os.Looper
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The screen's haptic sink, driven by actual owner admission and completion, not matching message text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticsTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private lateinit var model: AppViewModel

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { app.graph.now() })
        val graph = graphs.fresh { Graph(app, demo) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-25T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false) }
        graph.actions.confirmMs = 1
        graph.actions.freshStatusMs = 1
        model = AppViewModel(app)
        models.put("haptics", model)
        model.onScreenVisible()
    }

    @After fun finish() { models.clear() }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            Thread.yield()
        }
        assertTrue("Timed out driving the haptic request", check())
    }

    private suspend fun collecting(pulses: MutableList<OutcomeKind>, work: suspend () -> Unit) = coroutineScope {
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.haptics.collect {
                // MainActivity maps only these two signals to View.performHapticFeedback.
                if (it == OutcomeKind.ACCEPTED || it == OutcomeKind.CONFIRMED) pulses += it
            }
        }
        try { work() } finally { collector.cancelAndJoin() }
    }

    @Test fun appTapTicksAtOwnerAdmissionThenConfirmsOnce() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        collecting(pulses) {
            model.remote("Matching wording", endsNight = true, confirms = { it.mode == Mode.HEAT }) {
                entered.complete(Unit)
                release.await()
                it.setMode(Mode.HEAT)
            }
            try {
                until { entered.isCompleted }
                assertEquals("Admission must tick before hardware completion", listOf(OutcomeKind.ACCEPTED), pulses)
                release.complete(Unit)
                until { !model.busy.value }
                assertEquals("One admission tick and one confirmation for the same tap",
                    listOf(OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED), pulses)
                val outcome = ReflectionHelpers.getField<ArrayDeque<io.github.willzy12h.nocturne.night.CommandOutcome>>(model, "shownOutcomes").last()
                model.report(outcome)
                assertEquals("Displaying the owner's outcome again cannot replay feedback", 2, pulses.size)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun aTapWaitingForTheOwnerDoesNotTickUntilAdmitted() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async(Dispatchers.Default) { app.graph.actions.exclusive { held.complete(Unit); release.await() } }
        try {
            until { held.isCompleted }
            collecting(pulses) {
                model.run(NightActions.Command.OFF)
                until { app.graph.actions.busy.value.queuedCount == 1 }
                assertTrue("A queued tap has not been admitted", pulses.isEmpty())
                release.complete(Unit)
                until { !model.busy.value }
                assertEquals(listOf(OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED), pulses)
            }
        } finally { release.complete(Unit); owner.cancelAndJoin() }
    }

    @Test fun everyNonAppOriginIsSilentEvenWhenItsOutcomeIsConfirmed() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            CommandOrigin.entries.filter { it != CommandOrigin.APP }.forEach { origin ->
                val outcome = app.graph.actions.execute(app.graph.actions.capture(NightActions.Command.OFF).copy(origin = origin))
                assertEquals(origin.name, OutcomeKind.CONFIRMED, outcome.kind)
                assertTrue("$origin did not originate from this screen's tap", pulses.isEmpty())
            }
        }
    }

    @Test fun anAppOutcomeWithoutThisScreensRequestIsAlsoSilent() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            assertEquals(OutcomeKind.CONFIRMED,
                app.graph.actions.execute(app.graph.actions.capture(NightActions.Command.OFF)).kind)
            assertTrue("APP attribution alone is not this view's request identity", pulses.isEmpty())
        }
    }

    @Test fun refusedAndFailedRequestsNeverConfirm() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            model.restoreSlot(SlotBackup("ble:11:22:33:44:55:66", 0, null, app.graph.now().toString()))
            until { ReflectionHelpers.getField<ArrayDeque<io.github.willzy12h.nocturne.night.CommandOutcome>>(model, "shownOutcomes").isNotEmpty() }
            assertTrue("A refusal before admission has no feedback", pulses.isEmpty())
            model.remote("Failed request") { throw IllegalStateException("Test send failure") }
            until { !model.busy.value }
            assertEquals("Failure may follow admission, but must never confirm", listOf(OutcomeKind.ACCEPTED), pulses)
        }
    }

    @Test fun aRecreatedScreenCannotReceiveTheOldTapsCompletionOrReplay() = runBlocking {
        val before = mutableListOf<OutcomeKind>()
        val after = mutableListOf<OutcomeKind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        collecting(before) {
            model.remote("Matching wording", endsNight = true, confirms = { it.mode == Mode.HEAT }) {
                entered.complete(Unit); release.await(); it.setMode(Mode.HEAT)
            }
            until { entered.isCompleted }
        }
        collecting(after) {
            release.complete(Unit)
            until { !model.busy.value }
            assertTrue("Recreation cannot adopt the previous view's request", after.isEmpty())
            model.run(NightActions.Command.OFF)
            until { !model.busy.value }
            assertEquals("The new screen can receive its own new request", listOf(OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED), after)
        }
        assertEquals("The departed screen only received its admission", listOf(OutcomeKind.ACCEPTED), before)
    }

    @Test fun twoTapsWithIdenticalMessagesHaveIndependentRequestIdentity() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            repeat(2) {
                model.run(NightActions.Command.OFF)
                until { !model.busy.value }
            }
            assertEquals(listOf(OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED, OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED), pulses)
        }
    }

    @Test fun hidingAndShowingTheSameViewDoesNotAdoptItsOldRequest() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        collecting(pulses) {
            model.remote("Matching wording", endsNight = true, confirms = { it.mode == Mode.HEAT }) {
                entered.complete(Unit); release.await(); it.setMode(Mode.HEAT)
            }
            until { entered.isCompleted }
            model.onScreenHidden()
            model.onScreenVisible()
            release.complete(Unit)
            until { !model.busy.value }
            assertEquals("A stopped view cannot regain an old tap when it returns", listOf(OutcomeKind.ACCEPTED), pulses)
            model.run(NightActions.Command.OFF)
            until { !model.busy.value }
            assertEquals(listOf(OutcomeKind.ACCEPTED, OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED), pulses)
        }
    }

    @Test fun localTrackingAndSettingsChangesDoNotProduceHaptics() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            model.setTonightWake(app.graph.now().plusHours(8))
            until { !model.busy.value }
            model.run(NightActions.Command.STOP_TRACKING)
            until { !model.busy.value }
            assertTrue("Purely local requests never produce hardware feedback", pulses.isEmpty())
        }
    }

    @Test fun theServiceRetainsTheTappedViewsRequestUntilOwnerAdmission() = runBlocking {
        app.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            var delivered: Intent? = null
            model.run(NightActions.Command.OFF)
            until { delivered = delivered ?: shadowOf(app).nextStartedService; delivered != null }
            assertTrue("Submitting to the service is not command admission", pulses.isEmpty())
            val service = Robolectric.buildService(BedJetService::class.java).create().get()
            try {
                service.onStartCommand(delivered, 0, 1)
                until { !model.busy.value }
                assertEquals("The service must preserve the original view's request identity",
                    listOf(OutcomeKind.ACCEPTED, OutcomeKind.CONFIRMED), pulses)
            } finally { service.onDestroy() }
        }
    }

    @Test fun aViewThatAppearsAfterTheTapCannotAdoptItsCompletion() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        model.remote("Matching wording", endsNight = true, confirms = { it.mode == Mode.HEAT }) {
            entered.complete(Unit); release.await(); it.setMode(Mode.HEAT)
        }
        until { entered.isCompleted }
        collecting(pulses) {
            release.complete(Unit)
            until { !model.busy.value }
            assertTrue("There was no view-bound haptic request at the tap", pulses.isEmpty())
        }
    }

    @Test fun anUnconfirmedAcceptedOutcomeDoesNotProduceASecondTick() = runBlocking {
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            model.remote("Unchecked auxiliary write") { it.setLed(true) }
            until { !model.busy.value }
            assertEquals(OutcomeKind.ACCEPTED,
                ReflectionHelpers.getField<ArrayDeque<io.github.willzy12h.nocturne.night.CommandOutcome>>(model, "shownOutcomes").last().kind)
            assertEquals("An ACCEPTED result is not a second admission", listOf(OutcomeKind.ACCEPTED), pulses)
        }
    }

    @Test fun anUnknownOutcomeNeverConfirmsAnAdmittedTap() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED,
            app.graph.actions.execute(app.graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val pulses = mutableListOf<OutcomeKind>()
        collecting(pulses) {
            model.remote("Uncertain mode", endsNight = true, confirms = { it.mode == Mode.HEAT }) {
                throw IllegalStateException("Test interrupted mode change")
            }
            until { !model.busy.value }
            assertEquals(OutcomeKind.UNKNOWN,
                ReflectionHelpers.getField<ArrayDeque<io.github.willzy12h.nocturne.night.CommandOutcome>>(model, "shownOutcomes").last().kind)
            assertEquals("An UNKNOWN result has no completion haptic", listOf(OutcomeKind.ACCEPTED), pulses)
        }
    }
}
