package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The UI tap precedes its save wait; a radio interruption in that wait invalidates that same tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiQueuedBluetoothTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private val blockSave = AtomicBoolean(false)
    private val saveEntered = CountDownLatch(1)
    private val releaseSave = CountDownLatch(1)
    private val writes = AtomicInteger()
    private val messages = CopyOnWriteArrayList<String>()
    private lateinit var graph: Graph
    private lateinit var model: AppViewModel

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet()
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                writes.incrementAndGet()
                demo.send(command)
            }
        }
        graph = graphs.fresh {
            Graph(app, link, repositoryFactory = { scope, now ->
                Repository(app, scope, writeFile = { file, bytes ->
                    if (file.name == "settings.json" && blockSave.compareAndSet(true, false)) {
                        saveEntered.countDown()
                        check(releaseSave.await(10, TimeUnit.SECONDS)) { "The test must release its settings write" }
                    }
                    Repository.writeDurably(file, bytes)
                }, now = now)
            })
        }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings {
            it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF")
        }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(android.bluetooth.BluetoothAdapter.STATE_ON)
        model = AppViewModel(app)
        models.put("queued-radio", model)
        graph.scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.messages.collect { messages += it }
        }
        Unit
    }

    @After fun finish() {
        releaseSave.countDown()
        models.clear()
    }

    private fun until(description: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            Thread.yield()
        }
        assertTrue(description, check())
    }

    private fun switchRadio(enabled: Boolean) {
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(enabled)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,
            if (enabled) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun lastTapResult(): CommandOutcome? =
        ReflectionHelpers.getField<ArrayDeque<CommandOutcome>>(model, "shownOutcomes")
            .lastOrNull { it.origin == CommandOrigin.APP }

    private fun submitBehindRealSettingsWrite(interruptRadio: Boolean): CommandOutcome {
        val oldUnits = graph.repository.settings.value.fahrenheit
        blockSave.set(true)
        model.updateSettings { it.copy(fahrenheit = !oldUnits) }
        until("The actual durable settings write must be in progress") { saveEntered.count == 0L }
        assertEquals("The new setting has not been published before its write", oldUnits, graph.repository.settings.value.fahrenheit)
        model.remote("Queued Heat", confirms = { it.mode == Mode.HEAT }) { it.setMode(Mode.HEAT) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("The UI action must still be waiting for the real save", lastTapResult())
        assertEquals(0, writes.get())
        assertNull("No service delivery precedes the save", shadowOf(app).nextStartedService)
        if (interruptRadio) {
            switchRadio(false)
            switchRadio(true)
        }
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            releaseSave.countDown()
            until("The submitted action must produce a result after the save") {
                shadowOf(app).nextStartedService?.let { controller.get().onStartCommand(it, 0, 1) }
                lastTapResult() != null
            }
            assertEquals("The original settings operation still completes", !oldUnits, graph.repository.settings.value.fahrenheit)
            return lastTapResult()!!
        } finally { controller.destroy() }
    }

    @Test fun aRadioInterruptionWhileWaitingForTheSettingsSaveDoesNotReplayTheTap() {
        val result = submitBehindRealSettingsWrite(interruptRadio = true)
        assertEquals("The interrupted pre-service tap must never write to the BedJet", 0, writes.get())
        assertEquals(OutcomeKind.FAILED, result.kind)
        assertEquals(app.getString(R.string.bluetooth_interrupted), result.message)
        assertEquals(listOf(result.message), messages.toList())
    }

    @Test fun anUninterruptedTapStillRunsAfterTheSameSettingsSaveCompletes() {
        val result = submitBehindRealSettingsWrite(interruptRadio = false)
        assertTrue("The unchanged-radio control must reach the actual transport", writes.get() > 0)
        assertEquals(OutcomeKind.CONFIRMED, result.kind)
        assertEquals(Mode.HEAT, graph.link.status.value?.mode)
        assertEquals(listOf(result.message), messages.toList())
    }
}
