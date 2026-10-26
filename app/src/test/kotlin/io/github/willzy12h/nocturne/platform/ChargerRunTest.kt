package io.github.willzy12h.nocturne.platform

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.CommandOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
        graph.actions.confirmMs = 1
        return graph
    }
    @Test fun eligibleJobShowsGraceStartsOnceAndPersistsAttempt() = runBlocking {
        val graph=graph(); var notices=0
        val run=ChargerRun(graph,{true},{_,_->notices++;true},{},{true})
        val outcome = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            graph.actions.outcomes.first()
        }
        run.execute()
        assertEquals(CommandOrigin.CHARGER, withTimeout(5_000) { outcome.await() }.origin)
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
    private suspend fun slotWrite(graph: Graph, confidence: io.github.willzy12h.nocturne.core.night.Confidence, device: String = "ble:AA:BB:CC:DD:EE:FF",
        slot: Int = graph.repository.settings.value.workingSlot) =
        graph.repository.updateRuntime { it.copy(slotWrite = io.github.willzy12h.nocturne.night.SlotWrite(device, slot,
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
    @Test fun anUnresolvedWriteOfAnotherSlotDoesNotBlockTheStart() = runBlocking {
        // A pinned slot the night never writes or starts: its uncertainty says nothing about the start.
        val graph=graph(); var notices=0
        slotWrite(graph, io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN, slot = 0)
        ChargerRun(graph,{true},{_,_->notices++;true},{graph.repository.updateSettings { it.copy(platform=it.platform.copy(chargerAutoStart=false)) }},{true}).execute()
        assertEquals(1,notices)
    }
    @Test fun anUnresolvedSlotWriteOnAnotherBedJetDoesNotBlockTheStart() = runBlocking {
        val graph=graph(); var notices=0
        slotWrite(graph, io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN, "ble:AA:BB:CC:DD:EE:01")
        ChargerRun(graph,{true},{_,_->notices++;true},{graph.repository.updateSettings { it.copy(platform=it.platform.copy(chargerAutoStart=false)) }},{true}).execute()
        assertEquals(1,notices)
    }

    // F04: the job has no screen, so what each attempt did is kept in the notification log.

    @Test fun aStartedAutoStartIsLogged() = runBlocking {
        val graph=graph()
        ChargerRun(graph,{true},{_,_->true},{},{true}).execute()
        assertNotNull(graph.repository.activeNight.value)
        val notice=graph.repository.notices.value.single { it.title == "Charger auto-start attempt" }
        assertEquals(io.github.willzy12h.nocturne.core.store.NoticeKind.INFO,notice.kind)
        assertTrue(notice.text,notice.text.contains("started"))
    }
    @Test fun anAutoStartRefusedAtItsFinalCheckIsLogged() = runBlocking {
        lateinit var demo: DemoBedJet
        // The last report the eligibility check sees is none; the job's own fresh read finds a manual run.
        val graph=graph { demo=it; object : BedJetLink by it { override fun latestSourcedStatus(): SourcedStatus? = null } }
        demo.connect(); demo.send(io.github.willzy12h.nocturne.core.protocol.Codec.setMode(Mode.COOL)!!)
        ChargerRun(graph,{true},{_,_->true},{},{true}).execute()
        assertNull(graph.repository.activeNight.value)
        val notice=graph.repository.notices.value.single { it.title == "Charger auto-start attempt" }
        assertEquals(io.github.willzy12h.nocturne.core.store.NoticeKind.PROBLEM,notice.kind)
        assertEquals("This action is no longer eligible.",notice.text)
    }

    @Test fun anAutoStartTimeoutAfterTheStartWriteLogsThatTheBedJetStateIsUnknown() = runBlocking {
        val graph = graph { demo -> object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                demo.send(command)
                if (command[0] == 0x01.toByte() && command[1].toInt() and 0xff in 0x80..0x85)
                    withTimeout(1) { awaitCancellation() }
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        } }
        try {
            ChargerRun(graph, { true }, { _, _ -> true }, {}, { true }).execute()
            fail("The accepted start write must time out")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(io.github.willzy12h.nocturne.core.night.Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        val notice = graph.repository.notices.value.single { it.title == "Charger auto-start attempt" }
        assertEquals(io.github.willzy12h.nocturne.core.store.NoticeKind.PROBLEM, notice.kind)
        assertTrue("The log must describe uncertain hardware, not simply a failed request: ${notice.text}",
            notice.text.contains("state is unknown", ignoreCase = true))
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
