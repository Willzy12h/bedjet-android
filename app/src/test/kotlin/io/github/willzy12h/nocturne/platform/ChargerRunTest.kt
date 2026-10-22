package io.github.willzy12h.nocturne.platform

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class ChargerRunTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private suspend fun graph(): Graph {
        val context: Context=RuntimeEnvironment.getApplication()
        lateinit var graph: Graph
        graph=own(Graph(context,DemoBedJet(now={graph.now()})))
        graph.clock=Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"),ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(targetDevice="ble:AA:BB:CC:DD:EE:FF",demoMode=false,
            platform=it.platform.copy(chargerAutoStart=true,chargerDevice="ble:AA:BB:CC:DD:EE:FF")) }
        return graph
    }
    @Test fun eligibleJobShowsGraceStartsOnceAndPersistsAttempt() = runBlocking {
        val graph=graph(); var notices=0
        val run=ChargerRun(graph,{true},{_,_->notices++;true},{},{true})
        run.execute()
        assertEquals(1,notices)
        assertNotNull(graph.repository.activeNight.value)
        assertTrue(graph.repository.automation.value.claims.any { it.startsWith("charger:") })
        run.execute()
        assertEquals(1,notices)
    }
    @Test fun disableDuringGracePreventsWrite() = runBlocking {
        val graph=graph()
        val run=ChargerRun(graph,{true},{_,_->true},{graph.repository.updateSettings { it.copy(platform=it.platform.copy(chargerAutoStart=false)) }},{true})
        run.execute()
        assertNull(graph.repository.activeNight.value)
    }
    @Test fun stoppedGraceDoesNotSurviveAndNextAttemptGetsFreshGrace() = runBlocking {
        val graph=graph(); val entered=CompletableDeferred<Unit>(); var grace=0
        val first=async { ChargerRun(graph,{true},{_,_->true},{grace++; entered.complete(Unit);awaitCancellation()},{true}).execute() }
        entered.await();first.cancelAndJoin()
        assertNull(graph.repository.activeNight.value)
        ChargerRun(graph,{true},{_,_->true},{grace++},{true}).execute()
        assertEquals(2,grace)
        assertNotNull(graph.repository.activeNight.value)
    }
    @Test fun durableCancelDuringGraceSurvivesFreshRunner() = runBlocking {
        val graph=graph();var notices=0
        val key="ble:AA:BB:CC:DD:EE:FF:2026-10-22"
        ChargerRun(graph,{true},{_,_->notices++;true},{graph.repository.claimAutomation("cancel:$key")},{true}).execute()
        ChargerRun(graph,{true},{_,_->notices++;true},{},{true}).execute()
        assertNull(graph.repository.activeNight.value)
        assertEquals(1,notices)
    }
}
