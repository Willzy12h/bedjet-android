package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

/** IR-11, admission: a request that waited for the command lock until too little of its budget is left changes nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdmissionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private val sent = mutableListOf<ByteArray>()
    /** A monotonic clock the test moves by hand. */
    private var elapsed = 0L

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
        }
        graph = Graph(context, link) { scope, now -> Repository(context, scope, now = now) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.elapsedMs = { elapsed }
    }

    /** Holds the command lock while [queued] waits for it, and moves the monotonic clock on by [waitMs] meanwhile. */
    private suspend fun <T> afterWaiting(waitMs: Long, queued: suspend () -> T): T = coroutineScope {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val result = async(start = CoroutineStart.UNDISPATCHED) { queued() }
        elapsed += waitMs
        release.complete(Unit); holder.await()
        result.await()
    }

    @Test fun aStartThatWaitedTooLongIsRefusedBeforeAnyWrite() = runBlocking {
        val outcome = afterWaiting(NightActions.OPERATION_MS - 25_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        }
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(context.getString(R.string.runtime_waited_too_long), outcome.message)
        assertTrue("nothing sent to the BedJet", sent.isEmpty())
        assertNull("nothing published", graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.slotWrite)
        assertEquals(0, graph.actions.busy.value.queuedCount)
        // A fresh request still runs.
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
    }

    @Test fun aRequestWithEnoughBudgetLeftStillRuns() = runBlocking {
        val outcome = afterWaiting(NightActions.OPERATION_MS - 35_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
    }

    @Test fun slotWritesAndManualCommandsAreAdmittedTheSameWay() = runBlocking {
        val profile = graph.repository.profiles.value.first().id
        assertEquals(OutcomeKind.REFUSED, afterWaiting(NightActions.OPERATION_MS - 20_000) { graph.actions.pin(profile, 0) }.kind)
        assertEquals(OutcomeKind.REFUSED, afterWaiting(NightActions.OPERATION_MS - 20_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.TURBO)) }.kind)
        assertTrue(sent.isEmpty())
    }

    @Test fun stopTrackingIsLocalAndNotHeldBackByTheWait() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val outcome = afterWaiting(NightActions.OPERATION_MS - 20_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING))
        }
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun theUiWaitOutlastsTheServiceWhichOutlastsTheOwnerBudget() {
        assertTrue(BedJetService.SERVICE_MS > NightActions.OPERATION_MS)
        assertTrue("the UI's wait covers promotion, the service bound and a margin",
            BedJetService.RUN_OWNED_MS >= BedJetService.SERVICE_MS + 5_000)
    }
}
