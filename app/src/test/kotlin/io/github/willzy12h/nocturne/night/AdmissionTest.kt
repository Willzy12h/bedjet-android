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
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private val sent = mutableListOf<ByteArray>()
    /** A monotonic clock the test moves by hand. */
    private var elapsed = 0L

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
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
    private fun tooLate(outcome: CommandOutcome) {
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(context.getString(R.string.runtime_waited_too_long), outcome.message)
    }

    @Test fun aStartThatWaitedTooLongIsRefusedBeforeAnyWrite() = runBlocking {
        tooLate(afterWaiting(NightActions.OPERATION_MS - 25_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        })
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
        tooLate(afterWaiting(NightActions.OPERATION_MS - 20_000) { graph.actions.pin(profile, 0) })
        tooLate(afterWaiting(NightActions.OPERATION_MS - 20_000) { graph.actions.restoreOriginal(0) })
        tooLate(afterWaiting(NightActions.OPERATION_MS - 20_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.TURBO)) })
        tooLate(afterWaiting(NightActions.OPERATION_MS - 20_000) {
            graph.actions.remote("Temperature set", affectsProgramme = true) { it.setTemperature(50) } })
        assertTrue(sent.isEmpty())
    }

    @Test fun aWakeOverrideThatWaitedTooLongRewritesNothing() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val count = sent.size
        val before = graph.repository.activeNight.value
        tooLate(afterWaiting(NightActions.OPERATION_MS - 20_000) { graph.actions.setWakeOverride(graph.now().plusHours(6)) })
        assertEquals(count, sent.size)
        assertEquals(before, graph.repository.activeNight.value)
        assertNull(graph.repository.settings.value.tonightOverride)
    }

    @Test fun stopTrackingIsLocalAndNotHeldBackByTheWait() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val outcome = afterWaiting(NightActions.OPERATION_MS - 20_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING))
        }
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun aRetimeRetryThatWaitedTooLongKeepsItsChangeAndItsRetry() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        val fire = android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent("test"), android.app.PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(graph.now().plusHours(5).toInstant().toEpochMilli(), null), fire)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        // The observation's retry alarm fires later, and the retry then waits behind another command.
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(10)); context.graph.clock = graph.clock
        val count = sent.size
        val outcome = afterWaiting(NightActions.OPERATION_MS - 25_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        }
        tooLate(outcome)
        assertEquals(count, sent.size)
        assertTrue("the change is still waiting", graph.actions.retimePending)
        val again = graph.now().plusMinutes(10).toInstant().toEpochMilli()
        assertTrue("and it is tried again", org.robolectric.Shadows.shadowOf(alarms).scheduledAlarms.any {
            org.robolectric.Shadows.shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY && it.triggerAtTime == again })
    }

    @Test fun unrelatedQueueRefusalsDoNotMoveAPendingRetimeRetry() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val night = graph.repository.activeNight.value!!
        val pending = PendingRetime("waiting-change", night.identity, "demo", graph.now().plusHours(5).toString(), null, night.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        val originalTrigger = retryTrigger()
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(2)); context.graph.clock = graph.clock

        tooLate(afterWaiting(NightActions.OPERATION_MS - 25_000) {
            graph.actions.execute(graph.actions.capture(NightActions.Command.TURBO))
        })
        assertEquals("a refused Turbo must not postpone another command's retry", originalTrigger, retryTrigger())
        tooLate(afterWaiting(NightActions.OPERATION_MS - 25_000) {
            graph.actions.pin(graph.repository.profiles.value.first().id, 0)
        })
        assertEquals("a refused pin must not postpone another command's retry", originalTrigger, retryTrigger())
        assertEquals(pending, graph.repository.runtime.value.retime)
    }

    @Test fun anUnrelatedQueueRefusalDoesNotMoveAnAlarmObservationRetry() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        graph.actions.submitAlarmChange()
        val observed = graph.repository.runtime.value.alarmInbox
        val originalTrigger = retryTrigger()
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(2)); context.graph.clock = graph.clock

        tooLate(afterWaiting(NightActions.OPERATION_MS - 25_000) {
            graph.actions.remote("Lights off", confirms = { it.extra?.ledEnabled == false }) { it.setLed(false) }
        })
        assertEquals("a refused Remote tap must leave the observation's retry alone", originalTrigger, retryTrigger())
        assertEquals(observed, graph.repository.runtime.value.alarmInbox)
    }

    private fun retryTrigger(): Long = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        .scheduledAlarms.single { org.robolectric.Shadows.shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }.triggerAtTime

    @Test fun theUiWaitOutlastsTheServiceWhichOutlastsTheOwnerBudget() {
        assertTrue(BedJetService.SERVICE_MS > NightActions.OPERATION_MS)
        assertTrue("the UI's wait covers promotion, the service bound and a margin",
            BedJetService.RUN_OWNED_MS >= BedJetService.SERVICE_MS + 5_000)
    }
}
