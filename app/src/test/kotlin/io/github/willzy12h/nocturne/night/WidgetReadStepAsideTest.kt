package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The widget's background read steps aside for demo, a missing target and the open app, and never writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetReadStepAsideTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private var connects = 0
    private var writes = 0

    private fun graph(): Graph {
        val link = object : BedJetLink {
            override val state = MutableStateFlow(LinkState.SEARCHING)
            override val status = MutableStateFlow<DeviceStatus?>(null)
            override suspend fun connect() { connects++ }
            override suspend fun disconnect() { }
            override suspend fun send(command: ByteArray) { writes++ }
            override suspend fun query(request: Int): ByteArray = ByteArray(0)
        }
        return graphs.fresh { Graph(context, link) }
    }

    @Test fun demoNeverReadsInTheBackground() = runBlocking {
        val graph = graph()
        graph.repository.updateSettings { it.copy(demoMode = true, targetDevice = "AA:BB:CC:DD:EE:FF") }
        assertFalse(graph.actions.readForWidget())
        assertEquals(0, connects); assertEquals(0, writes)
    }

    @Test fun noTargetMeansNoRead() = runBlocking {
        val graph = graph()
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) }
        assertFalse(graph.actions.readForWidget())
        assertEquals(0, connects); assertEquals(0, writes)
    }

    @Test fun theOpenAppKeepsTheConnection() = runBlocking {
        val graph = graph()
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "AA:BB:CC:DD:EE:FF") }
        // Holding for the screen connects by itself; the widget read must add nothing to it.
        graph.sessions.holdForScreen(true)
        try {
            val before = connects
            assertFalse(graph.actions.readForWidget())
            assertEquals(before, connects)
        } finally { graph.sessions.holdForScreen(false) }
        assertEquals(0, writes)
    }
}
