package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.ble.AndroidBedJetLink
import io.github.willzy12h.nocturne.ble.FakeRadio
import io.github.willzy12h.nocturne.ble.assertThrows
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/** The real adapter remains intact; only its external radio is replaced so unintended BLE is observable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoReleaseIsolationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val radio = FakeRadio()
    private val writer = PausingDispatcher(Dispatchers.IO)
    private lateinit var graph: Graph
    private var now = Instant.parse("2026-10-22T22:30:00Z")

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(context, repositoryFactory = { scope, currentTime ->
            Repository(context, scope, writer = writer, now = currentTime)
        }).also { it.clock = Clock.fixed(now, ZoneOffset.UTC) } }
        ReflectionHelpers.setField(context as App, "graph", graph)
        val adapter = AndroidBedJetLink(context, radio.radio) { graph.repository.settings.value.deviceId() }
        ReflectionHelpers.setField(graph, "bleLink\$delegate", lazyOf(adapter))
        graph.repository.updateSettings { it.copy(targetDevice = REAL) }
        Unit
    }

    @After fun closeRealConnection() = runBlocking { graph.bleLink.disconnect() }

    @Test fun aReleasedRealBedJetDoesNotBlockDemoWarm() = runBlocking {
        assertEquals(OutcomeKind.LOCAL, graph.actions.releaseForOtherPhone().kind)
        assertEquals(OutcomeKind.LOCAL, graph.actions.setDemoMode(true).kind)

        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.WARM))

        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertTrue(graph.link is DemoBedJet)
        assertEquals(Mode.HEAT, graph.link.status.value?.mode)
        assertNoRealTraffic()
    }

    @Test fun aReleasedRealBedJetDoesNotBlockDemoStart() = runBlocking {
        graph.actions.releaseForOtherPhone()
        graph.actions.setDemoMode(true)

        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))

        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertEquals("demo", graph.repository.activeNight.value?.deviceId)
        assertNoRealTraffic()
    }

    @Test fun demoTakeBackDoesNotCancelTheRealRelease() = runBlocking {
        graph.actions.releaseForOtherPhone()
        val saved = realFiles()
        graph.actions.setDemoMode(true)
        assertEquals(OutcomeKind.LOCAL, graph.actions.takeBack().kind)
        assertFalse(graph.sessions.released.value)
        assertEquals(OutcomeKind.LOCAL, graph.actions.setDemoMode(false).kind)

        assertTrue("The real BedJet must stay released after a simulated Take back", graph.sessions.released.value)
        assertThrows<BedJetError.NotConnected> { graph.sessions.acquire() }
        assertEquals(saved, realFiles())
        assertNoRealTraffic()
    }

    @Test fun demoReleaseDoesNotReleaseTheRealBedJet() = runBlocking {
        val saved = realFiles()
        graph.actions.setDemoMode(true)
        assertEquals(OutcomeKind.LOCAL, graph.actions.releaseForOtherPhone().kind)
        assertTrue(graph.sessions.released.value)
        assertEquals(OutcomeKind.LOCAL, graph.actions.setDemoMode(false).kind)

        assertFalse("A simulated Release must not change the real connection choice", graph.sessions.released.value)
        assertEquals(saved, realFiles())
        assertNoRealTraffic()
    }

    @Test fun exitRestoresTheOriginalAbsoluteReleaseDeadline() = runBlocking {
        val realDeadline = now.toEpochMilli() + 60_000
        graph.sessions.suspendAutoConnect(realDeadline).join()
        graph.actions.setDemoMode(true)
        // A different simulated expiry must not overwrite or extend the real one.
        graph.sessions.suspendAutoConnect(realDeadline + 600_000).join()
        advanceSeconds(30)
        graph.actions.setDemoMode(false)
        assertTrue(graph.sessions.released.value)
        assertThrows<BedJetError.NotConnected> { graph.sessions.acquire() }
        assertNoRealTraffic()

        advanceSeconds(30)
        graph.sessions.withConnection { }

        assertFalse(graph.sessions.released.value)
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), radio.connects)
    }

    @Test fun aRealDeadlineThatPassesInDemoDoesNotExtendTheReleaseOnExit() = runBlocking {
        graph.sessions.suspendAutoConnect(now.toEpochMilli() + 60_000).join()
        graph.actions.setDemoMode(true)
        graph.actions.releaseForOtherPhone()
        advanceSeconds(61)
        graph.actions.setDemoMode(false)

        assertFalse("An expired real deadline must already be cleared on exit", graph.sessions.released.value)
        assertNoRealTraffic()
    }

    @Test fun aNewDemoSessionDoesNotRetainThePreviousDemoRelease() = runBlocking {
        graph.actions.setDemoMode(true)
        graph.actions.releaseForOtherPhone()
        graph.actions.setDemoMode(false)
        graph.actions.setDemoMode(true)

        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.WARM))

        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertNoRealTraffic()
    }

    @Test fun exitingDemoCannotReconnectTheRealBedJetBeforeItsReleaseIsRestored() = runBlocking {
        graph.actions.releaseForOtherPhone()
        graph.actions.setDemoMode(true)
        assertTrue(graph.repository.isDemoSession)
        val continuation = PausingDispatcher(Dispatchers.Default).apply { pauseWhen = { true } }
        writer.pauseWhen = { it[CoroutineName]?.name == "demo-exit" }
        // Repository publication completes on its writer before the owner continuation returns.
        val exit = async(continuation + CoroutineName("demo-exit"), start = CoroutineStart.UNDISPATCHED) {
            graph.actions.setDemoMode(false)
        }
        try {
            // Advance any preceding suspension, then hold the writer until the owner has suspended on it.
            withTimeout(2_000) { while (!writer.hasPaused) { continuation.runNext(); delay(1) } }
            writer.resume()
            withTimeout(2_000) { while (!continuation.hasPaused || graph.repository.isDemoSession) delay(1) }
            assertEquals(REAL, graph.repository.settings.value.deviceId())
            assertThrows<BedJetError.NotConnected> { graph.sessions.withConnection { } }
            assertNoRealTraffic()
        } finally {
            writer.resume()
            continuation.resume()
            exit.await()
        }
        assertTrue(graph.sessions.released.value)
        assertNoRealTraffic()
    }

    private fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
        graph.clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun assertNoRealTraffic() {
        assertTrue("Demo must not connect to the real BedJet", radio.connects.isEmpty())
        assertTrue("Demo must not scan for a real BedJet", radio.scans.isEmpty())
        assertTrue("Demo must not write real BLE packets", radio.ports.flatMap { it.writes }.isEmpty())
    }

    private fun realFiles(): Map<String, String> = context.filesDir.walkTopDown().filter { it.isFile &&
        it.name in setOf("settings.json", "runtime.json", "active-night.json", "history.json", "slot-backups.json")
    }.associate { it.name to it.readText() }

    private class PausingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var pauseWhen: (CoroutineContext) -> Boolean = { false }
        private val paused = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val hasPaused: Boolean get() = synchronized(this) { paused.isNotEmpty() }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (pauseWhen(context)) { paused.addLast(context to block); return }
            }
            delegate.dispatch(context, block)
        }
        fun runNext() {
            synchronized(this) { paused.removeFirstOrNull() }?.second?.run()
        }
        fun resume() {
            val tasks = synchronized(this) {
                pauseWhen = { false }
                paused.toList().also { paused.clear() }
            }
            tasks.forEach { delegate.dispatch(it.first, it.second) }
        }
    }

    companion object { private const val REAL = "ble:AA:BB:CC:DD:EE:01" }
}
