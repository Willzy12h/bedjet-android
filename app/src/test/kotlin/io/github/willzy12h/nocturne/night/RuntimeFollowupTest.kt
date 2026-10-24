package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.data.SlotBackupsUnreadable
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

/** Admission, takeover and resume policy follow-ups, through the real command owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeFollowupTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private var elapsed = 0L
    private var beforeQuery: suspend (Int) -> Unit = {}
    private val sent = mutableListOf<ByteArray>()

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            override suspend fun query(request: Int): ByteArray { beforeQuery(request); return demo.query(request) }
        }
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.elapsedMs = { elapsed }
    }

    private fun advance(minutes: Long) {
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(minutes))
        context.graph.clock = graph.clock
        demo.tick()
    }
    private suspend fun start(replace: Boolean = false): ActiveNight {
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)
            .copy(replacementConfirmed = replace))
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        return graph.repository.activeNight.value!!
    }
    private fun retryAt(): Long? = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .singleOrNull { shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }?.triggerAtTime
    private suspend fun afterWaiting(queued: suspend () -> CommandOutcome): CommandOutcome = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        try {
            val result = async(start = CoroutineStart.UNDISPATCHED) { queued() }
            elapsed += NightActions.OPERATION_MS - 20_000
            release.complete(Unit)
            holder.await()
            result.await()
        } finally { release.complete(Unit); holder.cancelAndJoin() }
    }

    @Test fun aRefusedTurboDoesNotPostponeAnAlarmObservationRetry() = runBlocking {
        start()
        graph.actions.submitAlarmChange()
        val scheduled = retryAt()
        assertNotNull(scheduled)
        val observed = graph.repository.runtime.value.alarmInbox
        advance(1)
        val writes = sent.size
        val result = afterWaiting { graph.actions.execute(graph.actions.capture(NightActions.Command.TURBO)) }
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(writes, sent.size)
        assertEquals(observed, graph.repository.runtime.value.alarmInbox)
        assertEquals("an unrelated refusal must not postpone the existing retry", scheduled, retryAt())
    }

    @Test fun aRefusedPinDoesNotPostponeAPreparedRetimeRetry() = runBlocking {
        val night = start()
        val pending = PendingRetime("queued-change", night.identity, "demo", graph.now().plusHours(5).toString(),
            null, night.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        val scheduled = retryAt()
        assertNotNull(scheduled)
        advance(1)
        val writes = sent.size
        val result = afterWaiting { graph.actions.pin(night.profileId, 0) }
        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(writes, sent.size)
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertEquals("a pin's refusal must not re-arm another operation", scheduled, retryAt())
    }

    @Test fun takeBackCancelsTheRetimeRetryWhenItFindsATakeover() = runBlocking {
        val night = start()
        val pending = PendingRetime("take-back-change", night.identity, "demo", graph.now().plusHours(5).toString(),
            null, night.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        assertNotNull(retryAt())
        advance(2)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val result = graph.actions.takeBack()
        assertEquals(OutcomeKind.UNKNOWN, result.kind)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertNull(graph.repository.runtime.value.retime)
        assertNull("a taken-over programme must not retain its re-time retry", retryAt())
    }

    @Test fun aRemoteAdjustmentKeepsTheValidOfferForTheReplacedProgramme() = runBlocking {
        val first = start()
        advance(1)
        val replacement = start(replace = true)
        val offer = graph.repository.runtime.value.resumeOffer!!
        assertEquals(first.recordId, offer.night.recordId)
        val outcome = graph.actions.remote("Temperature set", affectsProgramme = true,
            target = "demo", expected = replacement.identity, confirms = { it.targetTemperature == 50 }) { it.setTemperature(50) }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        val kept = graph.repository.runtime.value.resumeOffer!!
        assertEquals("manual adjustment must not replace the still-valid recovery choice", first.recordId, kept.night.recordId)
        assertEquals(offer.expiresAt, kept.expiresAt)
        assertEquals(offer.remaining, kept.remaining)
        assertEquals(graph.repository.runtime.value.generation, kept.commandVersion)
        val resumed = graph.actions.execute(graph.actions.capture(NightActions.Command.RESUME)
            .copy(replacementConfirmed = true))
        assertEquals(resumed.message, OutcomeKind.CONFIRMED, resumed.kind)
        assertEquals(first.recordId, graph.repository.activeNight.value!!.recordId)
    }

    @Test fun snoozeAndKeepTrackingRetainTheReplacedProgrammeOffer() = runBlocking {
        val first = start()
        advance(1)
        start(replace = true)
        val offer = graph.repository.runtime.value.resumeOffer!!
        assertEquals(OutcomeKind.CONFIRMED,
            graph.actions.execute(graph.actions.capture(NightActions.Command.SNOOZE_WARM)).kind)
        assertEquals("snooze retains the recovery choice", first.recordId, graph.repository.runtime.value.resumeOffer!!.night.recordId)
        assertEquals(OutcomeKind.LOCAL,
            graph.actions.execute(graph.actions.capture(NightActions.Command.KEEP_TRACKING)).kind)
        val kept = graph.repository.runtime.value.resumeOffer!!
        assertEquals(first.recordId, kept.night.recordId)
        assertEquals(offer.expiresAt, kept.expiresAt)
        assertEquals(graph.repository.runtime.value.generation, kept.commandVersion)
    }

    @Test fun aFurtherExplicitProgrammeReplacementSupersedesTheOldOffer() = runBlocking {
        val first = start()
        advance(1)
        val second = start(replace = true)
        assertEquals(first.recordId, graph.repository.runtime.value.resumeOffer!!.night.recordId)
        advance(1)
        start(replace = true)
        assertEquals("an explicit replacement offers the programme it displaced", second.recordId,
            graph.repository.runtime.value.resumeOffer!!.night.recordId)
    }

    @Test fun anExpiredReplacedProgrammeOfferYieldsToTheCurrentProgramme() = runBlocking {
        start()
        advance(1)
        val current = start(replace = true)
        val expired = graph.repository.runtime.value.resumeOffer!!
        advance(16)
        graph.actions.remote("Temperature set", affectsProgramme = true, target = "demo", expected = current.identity) {
            it.setTemperature(50)
        }
        val offered = graph.repository.runtime.value.resumeOffer!!
        assertEquals(current.recordId, offered.night.recordId)
        assertNotEquals(expired.expiresAt, offered.expiresAt)
    }

    @Test fun anOfferFromAnOlderGenerationIsNotRevivedByAManualAdjustment() = runBlocking {
        start()
        advance(1)
        val current = start(replace = true)
        graph.repository.updateRuntime { it.copy(generation = it.generation + 1) }
        graph.actions.remote("Temperature set", affectsProgramme = true, target = "demo", expected = current.identity) {
            it.setTemperature(50)
        }
        assertEquals(current.recordId, graph.repository.runtime.value.resumeOffer!!.night.recordId)
    }

    @Test fun anOfferForAnotherBedJetIsNotCarriedIntoAManualAdjustment() = runBlocking {
        start()
        advance(1)
        val current = start(replace = true)
        graph.repository.updateRuntime { runtime -> runtime.copy(resumeOffer = runtime.resumeOffer!!.let {
            it.copy(night = it.night.copy(deviceId = "ble:AA:BB:CC:DD:EE:FF"))
        }) }
        graph.actions.remote("Temperature set", affectsProgramme = true, target = "demo", expected = current.identity) {
            it.setTemperature(50)
        }
        assertEquals(current.recordId, graph.repository.runtime.value.resumeOffer!!.night.recordId)
    }

    @Test fun aFirstStartThatPublishesFailedReturnsFailedInsteadOfRefused() = runBlocking {
        // The original-slot read outlives the request; the core publishes this new start FAILED before any write.
        beforeQuery = { if (it == Codec.Query.PROGRAM_NAMES) advance(120) }
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        val failed = graph.repository.activeNight.value!!
        assertEquals(Confidence.FAILED, failed.confidence)
        assertEquals("the result must agree with this request's published night", OutcomeKind.FAILED, result.kind)
        assertTrue("nothing was written", sent.isEmpty())
        assertEquals(result.message, graph.repository.runtime.value.reason)
    }

    @Test fun aFirstStartThatMeetsTheOriginalsBarrierAfterAdmissionReturnsFailed() = runBlocking {
        // The final original-slot barrier can fail after admission and after the core saved this start's intent.
        beforeQuery = { if (it == Codec.Query.PROGRAM_NAMES) throw SlotBackupsUnreadable() }
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        assertEquals(Confidence.FAILED, graph.repository.activeNight.value!!.confidence)
        assertEquals("the late barrier must report the failure already published for this request", OutcomeKind.FAILED, result.kind)
        assertTrue(sent.isEmpty())
    }
}
