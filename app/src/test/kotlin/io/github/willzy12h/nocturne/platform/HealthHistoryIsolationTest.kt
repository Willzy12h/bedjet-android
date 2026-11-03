package io.github.willzy12h.nocturne.platform

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class HealthHistoryIsolationTest : PlatformGraphFixture() {
    private var forbidHistoryWrite = false
    private suspend fun setup(): Graph {
        val context: Context = RuntimeEnvironment.getApplication()
        val graph = fresh { Graph(context, DemoBedJet()) { scope, now ->
            Repository(context, scope, writeFile = { file, bytes ->
                if (forbidHistoryWrite && file.name == "history.json") error("History must not be written")
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)
        return graph
    }
    private val ended = HistoryEvent("2026-10-27T07:15Z", EventKind.ENDED, "Ended")
    private fun record() = NightRecord("completed", "p", "Profile", "2026-10-26T22:30Z", "2026-10-27T07:00Z",
        false, deviceId = "ble:AA:BB:CC:DD:EE:FF", bedJetRunConfirmed = true)

    @Test fun demoCompletionDoesNotEvenRewriteTheExistingRealHistoryFile() = runBlocking {
        val graph = setup()
        graph.repository.addRecord(record())
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val before = graph.repository.history.value
        forbidHistoryWrite = true
        val result = runCatching { graph.repository.completeHistoryRecord("completed", ended, null) }
        assertTrue("Demo completion must avoid all History persistence: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(before, graph.repository.history.value)
    }

    @Test fun replayAfterDurableHistoryCompletionNeedsNoSecondHistoryWrite() = runBlocking {
        val graph = setup()
        graph.repository.addRecord(record())
        graph.repository.completeHistoryRecord("completed", ended, ended.at)
        val completed = graph.repository.history.value.single()
        assertTrue(completed.healthExportVersion > 0)
        forbidHistoryWrite = true
        val result = runCatching { graph.repository.completeHistoryRecord("completed", ended, ended.at) }
        assertTrue("Replay must complete without rewriting already durable History: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(completed, graph.repository.history.value.single())
    }
}
