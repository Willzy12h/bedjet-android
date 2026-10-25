package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Runtime reasons are visible in the app even when cancellation returns through a friendly service wrapper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeTimeoutFeedbackTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var afterSend: suspend (ByteArray) -> Unit = {}
    @Volatile private var beforeFileWrite: ((File, ByteArray) -> Unit)? = null
    private var writes = 0

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                demo.send(command)
                writes++
                afterSend(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                send(command)
            }
        }
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 1
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun assertReadableTimeoutReason() {
        val reason = graph.repository.runtime.value.reason.orEmpty()
        assertFalse("A visible runtime reason must not expose coroutine timeout internals", reason.contains("Timed out waiting for"))
        assertTrue("The persisted reason should still identify the timeout", reason.contains("timed out", ignoreCase = true))
    }

    @Test fun manualPacketTimeoutPreservesCancellationAndReadableUnknownReason() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val night = graph.repository.activeNight.value!!
        val before = writes
        afterSend = { command ->
            if (command.contentEquals(Codec.setMode(Mode.COOL)!!)) withTimeout(1) { awaitCancellation() }
        }
        val failure = runCatching {
            graph.actions.execute(graph.actions.capture(NightActions.Command.COOL).copy(replacementConfirmed = true))
        }.exceptionOrNull()
        assertTrue("A real accepted mode packet must precede cancellation", writes > before)
        assertTrue("Timeout must keep propagating as cancellation", failure is TimeoutCancellationException)
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertReadableTimeoutReason()
    }

    @Test fun slotUploadTimeoutPreservesCancellationAndReadableUnknownReason() = runBlocking {
        afterSend = { command -> if (command[0] == 0x40.toByte()) withTimeout(1) { awaitCancellation() } }
        val failure = runCatching { graph.actions.pin("preset-summer", 0) }.exceptionOrNull()
        assertTrue("Exercise cancellation after a real upload packet", writes > 0)
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(Confidence.UNKNOWN, graph.repository.runtime.value.slotWrite?.confidence)
        assertNull(graph.repository.activeNight.value)
        assertReadableTimeoutReason()
    }

    @Test fun slotBackupTimeoutPreservesCancellationAndReadableNoWriteReason() = runBlocking {
        var backingUp = false
        beforeFileWrite = { file, _ ->
            if (file.name == "slot-backups.json") {
                backingUp = true
                runBlocking { withTimeout(1) { awaitCancellation() } }
            }
        }
        val failure = runCatching { graph.actions.pin("preset-summer", 0) }.exceptionOrNull()
        assertTrue("Exercise the durable original-slot barrier", backingUp)
        assertTrue(failure is TimeoutCancellationException)
        assertEquals("A failed backup must still prevent every write", 0, writes)
        assertNull(graph.repository.runtime.value.slotWrite)
        assertNull(graph.repository.activeNight.value)
        assertReadableTimeoutReason()
    }
}
