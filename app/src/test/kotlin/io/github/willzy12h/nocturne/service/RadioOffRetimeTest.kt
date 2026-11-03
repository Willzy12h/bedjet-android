package io.github.willzy12h.nocturne.service

import io.github.willzy12h.nocturne.operationIntent
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.NightAlarms
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.ui.AppViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A persisted real-target plan can be resolved locally even when Android's radio is disabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioOffRetimeTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var night: ActiveNight
    private var connects = 0
    private var reads = 0
    private var writes = 0
    private val target = "ble:AA:BB:CC:DD:EE:FF"
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before fun setup() = runBlocking {
        val link = object : BedJetLink {
            override val state = MutableStateFlow(LinkState.SEARCHING)
            override val status = MutableStateFlow<DeviceStatus?>(null)
            override suspend fun connect() { connects++; throw BedJetError.NotConnected() }
            override suspend fun disconnect() {}
            override suspend fun send(command: ByteArray) { writes++; throw BedJetError.NotConnected() }
            override suspend fun query(request: Int): ByteArray { reads++; throw BedJetError.NotConnected() }
        }
        graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target,
            alarmPackages = setOf("approved.clock"), acceptUnknownAlarmSource = false) }
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(adapter).setState(android.bluetooth.BluetoothAdapter.STATE_OFF)
        val profile = graph.repository.profiles.value.first { it.id == "preset-summer" }
        val start = graph.now()
        val wake = start.plusDays(1).withHour(7).withMinute(0)
        val plan = PlanCompiler.compile(profile, start, wake)
        night = ActiveNight("real-radio-night", profile.id, start.toString(), wake.toString(), true, 2,
            plan.wakeUpStart.toString(), plan.end.toString(), deviceId = target, profile = profile,
            applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED)
        graph.repository.setActiveNight(night)
        graph.repository.addRecord(NightRecord(night.recordId, profile.id, profile.name, start.toString(), wake.toString(), true,
            events = listOf(HistoryEvent(start.toString(), EventKind.STARTED, "Persisted fixture")), deviceId = target))
        graph.repository.updateRuntime { it.copy(reason = "Existing tracking reason") }
        alarm(7)
        BedJetService.lastResult = null
        while (shadowOf(app).nextStartedService != null) { /* discard startup delivery from the application fixture */ }
    }

    private fun alarm(hour: Int, creator: String = "approved.clock") {
        val fire = PendingIntent.getBroadcast(app, 173, Intent("test.real.radio.alarm"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(fire).setCreatorPackage(creator)
        val at = graph.now().plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), fire), fire)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var advanced = System.nanoTime()
        while (!check() && System.nanoTime() < deadline) {
            val elapsed = (System.nanoTime() - advanced) / 1_000_000
            if (elapsed > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsed))
                advanced += elapsed * 1_000_000
            } else idle()
            Thread.yield()
        }
        assertTrue("The real-target alarm operation must finish", check())
    }
    private fun noHardware() {
        assertEquals("No connection is needed before genuine hardware work", 0, connects)
        assertEquals("Metadata resolution must not read the BedJet", 0, reads)
        assertEquals("Metadata resolution must not write the BedJet", 0, writes)
    }
    private fun retryScheduled() = shadowOf(alarms).scheduledAlarms.any {
        shadowOf(it.operationIntent).savedIntent.action == NightAlarms.ACTION_RETRY
    }
    private fun radioReceivers() = shadowOf(app).registeredReceivers.count {
        it.intentFilter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
    private fun switchRadio(enabled: Boolean) {
        shadowOf(adapter).setEnabled(enabled)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,
            if (enabled) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF))
        idle()
    }
    private suspend fun assertQuiet(outcome: CommandOutcome, history: List<NightRecord>) {
        assertEquals("A locally unchanged alarm must not fail because Bluetooth is off", OutcomeKind.LOCAL, outcome.kind)
        assertEquals(night, graph.repository.activeNight.value)
        assertEquals(history, graph.repository.history.value)
        assertEquals("Existing tracking reason", graph.repository.runtime.value.reason)
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
        noHardware()
        idle()
        graph.repository.updateRuntime { it }
        assertNull("An unchanged observation has no Bluetooth problem", manager.getNotification(8))
        assertNull("An unchanged observation has no re-time problem", manager.getNotification(3))
        assertTrue(graph.repository.notices.value.none { it.kind == NoticeKind.PROBLEM })
        assertEquals("Local completion releases the temporary admission receiver", 0, radioReceivers())
    }
    private suspend fun deliver(context: ServiceStartContext): CommandOutcome {
        val result = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            controller.get().onStartCommand(context.delivered, 0, 1)
            until { shadowOf(controller.get()).isStoppedBySelf }
            idle()
            assertTrue("Service delivery must reach the metadata owner", result.isCompleted)
            return result.await()
        } finally { result.cancel(); controller.destroy() }
    }
    private suspend fun background(): CommandOutcome {
        val context = ServiceStartContext(app)
        assertTrue("Alarm metadata resolution must be handed off with the radio off",
            BedJetService.start(context, Command.RETRY_RETIME, notifyFailures = false))
        return deliver(context)
    }

    @Test fun anUnchangedRealAlarmInboxSettlesLocallyWithBluetoothOff() = runBlocking {
        val history = graph.repository.history.value
        graph.actions.submitAlarmChange()
        assertQuiet(graph.actions.execute(graph.actions.capture(Command.RETRY_RETIME)), history)
    }

    @Test fun aFilteredRealAlarmInboxSettlesLocallyWithBluetoothOff() = runBlocking {
        night = night.copy(confidence = Confidence.UNKNOWN)
        graph.repository.setActiveNight(night)
        alarm(6, "ignored.clock")
        val history = graph.repository.history.value
        graph.actions.submitAlarmChange()
        assertQuiet(graph.actions.execute(graph.actions.capture(Command.RETRY_RETIME)), history)
    }

    @Test fun anExplicitUnchangedRetimeDoesNotRequireTheRadio() = runBlocking {
        val history = graph.repository.history.value
        assertQuiet(graph.actions.execute(graph.actions.capture(Command.RETIME)), history)
    }

    @Test fun theServiceConsumesAnUnchangedInboxWithoutARadioProblem() = runBlocking {
        val history = graph.repository.history.value
        graph.actions.submitAlarmChange()
        assertQuiet(background(), history)
    }

    @Test fun theServiceConsumesAFilteredInboxWithoutARadioProblem() = runBlocking {
        alarm(6, "ignored.clock")
        val history = graph.repository.history.value
        graph.actions.submitAlarmChange()
        assertQuiet(background(), history)
    }

    private suspend fun assertTerminalRadioFailure(outcome: CommandOutcome, message: String) {
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(message, outcome.message)
        assertEquals(night, graph.repository.activeNight.value)
        assertFalse("A radio refusal must not silently retain this request for replay", graph.actions.retimePending)
        assertFalse("No radio-refused retry remains scheduled", retryScheduled())
        noHardware()
        assertEquals(0, radioReceivers())
    }
    @Test fun aChangedWakeWithBluetoothOffFailsClearlyAndDiscardsItsAttempt() = runBlocking {
        alarm(6)
        graph.actions.submitAlarmChange()
        assertTerminalRadioFailure(graph.actions.execute(graph.actions.capture(Command.RETRY_RETIME)), "Bluetooth is off")
    }

    @Test fun aChangedWakeFromTheServiceFailsOnceWithoutLeavingARetry() = runBlocking {
        alarm(6)
        graph.actions.submitAlarmChange()
        assertTerminalRadioFailure(background(), "Bluetooth is off")
        until { graph.repository.notices.value.any { it.kind == NoticeKind.PROBLEM } }
        assertEquals("One background radio notice owns this failure", 1,
            graph.repository.notices.value.count { it.kind == NoticeKind.PROBLEM })
        assertNotNull(manager.getNotification(8))
        assertNull("No duplicate generic re-time notice", manager.getNotification(3))
        switchRadio(true)
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
        noHardware()
    }

    private suspend fun queued(changed: Boolean): CommandOutcome {
        switchRadio(true)
        if (changed) alarm(6)
        graph.actions.submitAlarmChange()
        val lock = ReflectionHelpers.getField<Mutex>(graph.actions, "lock")
        lock.lock()
        var held = true
        val result = graph.scope.async { graph.actions.execute(graph.actions.capture(Command.RETRY_RETIME)) }
        try {
            until { graph.actions.busy.value.queuedCount == 1 }
            switchRadio(false)
            switchRadio(true)
            lock.unlock(); held = false
            until { result.isCompleted }
            return result.await()
        } finally { if (held) lock.unlock(); result.cancel() }
    }
    @Test fun anUnchangedQueuedAlarmCanResolveAfterTheRadioWasSwitchedOffAndOn() = runBlocking {
        val history = graph.repository.history.value
        assertQuiet(queued(changed = false), history)
    }
    @Test fun aChangedQueuedAlarmCannotReplayAfterTheRadioWasSwitchedOffAndOn() = runBlocking {
        assertTerminalRadioFailure(queued(changed = true), "Bluetooth was switched off. Try the action again.")
    }

    @Test fun aRadioOnUnreachableBedJetKeepsTheExistingRetryPolicy() = runBlocking {
        switchRadio(true)
        alarm(6)
        graph.actions.submitAlarmChange()
        val outcome = graph.actions.execute(graph.actions.capture(Command.RETRY_RETIME))
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(app.getString(R.string.runtime_retime_pending), outcome.message)
        assertNotNull(graph.repository.runtime.value.retime)
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertTrue(retryScheduled())
        assertEquals(1, connects)
        assertEquals(0, reads)
        assertEquals(0, writes)
        assertEquals(night, graph.repository.activeNight.value)
    }

    private suspend fun uiRecheck(command: Command? = null) {
        val history = graph.repository.history.value
        val model = AppViewModel(app)
        val store = ViewModelStore().also { it.put("radio-retime", model) }
        val messages = mutableListOf<String>()
        val ownerResult = graph.scope.async(start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val listener = graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { model.messages.collect { messages += it } }
        var controller: org.robolectric.android.controller.ServiceController<BedJetService>? = null
        try {
            if (command == Command.RETRY_RETIME) graph.actions.submitAlarmChange()
            if (command == null) model.recheckAlarm() else model.run(command)
            idle()
            shadowOf(app).nextStartedService?.let { intent ->
                controller = Robolectric.buildService(BedJetService::class.java).create()
                controller!!.get().onStartCommand(intent, 0, 1)
            }
            until { messages.isNotEmpty() }
            assertFalse("A locally unchanged UI alarm check must not claim Bluetooth failure: ${messages.last()}", messages.last().contains("Bluetooth"))
            assertTrue("The local result still comes through the command owner", ownerResult.isCompleted)
            assertEquals(OutcomeKind.LOCAL, ownerResult.await().kind)
            if (command == Command.RETRY_RETIME) assertEquals(app.getString(R.string.runtime_unchanged), messages.last())
            else assertTrue("The explicit check retains the found-alarm explanation", messages.last().contains("07:00"))
            assertEquals(history, graph.repository.history.value)
            assertFalse(graph.actions.retimePending)
            noHardware()
        } finally { ownerResult.cancel(); listener.cancel(); store.clear(); controller?.destroy() }
    }
    @Test fun theExplicitAlarmRecheckUiAllowsAnUnchangedWakeWithBluetoothOff() = runBlocking { uiRecheck() }
    @Test fun theRetimeCommandUiAllowsAnUnchangedWakeWithBluetoothOff() = runBlocking { uiRecheck(Command.RETIME) }
    @Test fun theRetryCommandUiAllowsAnUnchangedInboxWithBluetoothOff() = runBlocking { uiRecheck(Command.RETRY_RETIME) }
}
