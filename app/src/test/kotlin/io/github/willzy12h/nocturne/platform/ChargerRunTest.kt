package io.github.willzy12h.nocturne.platform

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.flow.MutableStateFlow
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
    private suspend fun graph(link: (DemoBedJet) -> BedJetLink = { it }): Graph {
        val context: Context=RuntimeEnvironment.getApplication()
        lateinit var graph: Graph
        graph=fresh { Graph(context,link(DemoBedJet(now={graph.now()}))) }
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
    @Test fun aRunningReportFromAnotherBedJetIsNotTheTargetsManualRun() = runBlocking {
        // The last report came from a session still on another unit: it says nothing about the chosen BedJet.
        val graph=graph { OtherUnitReport(it,"ble:AA:BB:CC:DD:EE:01") }; var notices=0
        ChargerRun(graph,{true},{_,_->notices++;true},{graph.repository.updateSettings { it.copy(platform=it.platform.copy(chargerAutoStart=false)) }},{true}).execute()
        assertEquals(1,notices)
    }
    @Test fun aRunningReportFromTheTargetStillBlocksTheStart() = runBlocking {
        val graph=graph { OtherUnitReport(it,"ble:AA:BB:CC:DD:EE:FF") }; var notices=0
        ChargerRun(graph,{true},{_,_->notices++;true},{},{true}).execute()
        assertEquals(0,notices)
        assertNull(graph.repository.activeNight.value)
    }

    // IR-06: only a current, unresolved slot write holds the charger start back.
    private suspend fun slotWrite(graph: Graph, confidence: io.github.willzy12h.nocturne.core.night.Confidence, device: String = "ble:AA:BB:CC:DD:EE:FF") =
        graph.repository.updateRuntime { it.copy(slotWrite = io.github.willzy12h.nocturne.night.SlotWrite(device, 0,
            null, confidence, "Restore")) }
    @Test fun anOldFailedSlotWriteDoesNotBlockTheStart() = runBlocking {
        val graph=graph(); var notices=0
        slotWrite(graph, io.github.willzy12h.nocturne.core.night.Confidence.FAILED)
        ChargerRun(graph,{true},{_,_->notices++;true},{graph.repository.updateSettings { it.copy(platform=it.platform.copy(chargerAutoStart=false)) }},{true}).execute()
        assertEquals("nothing was written, so it is not a reason to hold back",1,notices)
    }
    @Test fun anUnresolvedSlotWriteOnTheTargetStillBlocksTheStart() = runBlocking {
        val graph=graph(); var notices=0
        slotWrite(graph, io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN)
        ChargerRun(graph,{true},{_,_->notices++;true},{},{true}).execute()
        assertEquals(0,notices)
    }
    @Test fun anUnresolvedSlotWriteOnAnotherBedJetDoesNotBlockTheStart() = runBlocking {
        val graph=graph(); var notices=0
        slotWrite(graph, io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN, "ble:AA:BB:CC:DD:EE:01")
        ChargerRun(graph,{true},{_,_->notices++;true},{graph.repository.updateSettings { it.copy(platform=it.platform.copy(chargerAutoStart=false)) }},{true}).execute()
        assertEquals(1,notices)
    }

    /** A demo unit whose last report is a manual run, attributed to [source]. */
    private class OtherUnitReport(demo: DemoBedJet, source: String) : BedJetLink by demo {
        private val running = DeviceStatus(remainingSeconds = 600, actualTemperature = 60, targetTemperature = 70,
            mode = Mode.HEAT, fanStep = 7, maxRuntimeMinutes = 600, minTemperature = 38, maxTemperature = 80,
            ambientTemperature = 45, lastShutdownReason = 0,
            extra = DeviceStatus.Extra(ledEnabled = true, beepsMuted = false, programStep = 0, notification = 0))
        override val status = MutableStateFlow<DeviceStatus?>(running)
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(SourcedStatus(source, running))
        override fun latestSourcedStatus(): SourcedStatus? = sourcedStatus.value
    }
}
