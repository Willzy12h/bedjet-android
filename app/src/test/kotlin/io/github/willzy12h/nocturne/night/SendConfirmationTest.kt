package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

/**
 * A requested value already reported by the target is confirmed after sending, even when the identical report
 * is conflated. A changed value needs a later report or fresh read: here [swallow] lets a write through the link
 * without the unit acting on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendConfirmationTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    /** A write this matches is accepted by the link and never reaches the unit. */
    private var swallow: (ByteArray) -> Boolean = { false }
    private var foreign: SourcedStatus? = null
    private var headsOnly = false
    private var disconnects = 0
    private var afterSend: (ByteArray) -> Unit = {}
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                sent += command
                if (!swallow(command)) demo.send(command)
                afterSend(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            override suspend fun disconnect() { disconnects++; demo.disconnect() }
            override val sourcedStatus: kotlinx.coroutines.flow.Flow<SourcedStatus?>
                get() = foreign?.let { kotlinx.coroutines.flow.MutableStateFlow<SourcedStatus?>(it) }
                    ?: demo.sourcedStatus.map { report -> if (headsOnly) report?.copy(status = report.status.copy(extra = null)) else report }
            override fun latestSourcedStatus(): SourcedStatus? = foreign ?: demo.latestSourcedStatus()
        }
    }

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 40
    }
    private suspend fun start(): ActiveNight {
        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        return graph.repository.activeNight.value!!
    }
    private fun advance(minutes: Long) {
        graph.clock = Clock.fixed(graph.now().plusMinutes(minutes).toInstant(), ZoneOffset.UTC); context.graph.clock = graph.clock
        demo.tick()
    }
    private suspend fun run(command: NightActions.Command) =
        graph.actions.execute(graph.actions.capture(command, requireIdentity = true).copy(replacementConfirmed = true))
    private fun press(mode: Mode): (ByteArray) -> Boolean = { it.contentEquals(Codec.setMode(mode)!!) }
    private fun events(n: ActiveNight) = graph.repository.history.value.first { it.id == n.recordId }.events
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    // Off and End night

    @Test fun offTheBedJetReportsEndsTheNight() = runBlocking {
        val n = start(); advance(30)
        val outcome = run(NightActions.Command.OFF)
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(text(R.string.runtime_off), outcome.message)
        assertNull(graph.repository.activeNight.value)
        assertTrue(events(n).any { it.kind == EventKind.STOPPED })
    }

    @Test fun offTheBedJetNeverReportsLeavesTheNightUnknownAndOffCanBeSentAgain() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        val outcome = run(NightActions.Command.OFF)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(text(R.string.runtime_off_unconfirmed), outcome.message)
        val kept = graph.repository.activeNight.value
        assertEquals("the night is not closed on the write alone", n.identity, kept?.identity)
        assertEquals("nor shown as still running its programme", Confidence.UNKNOWN, kept!!.confidence)
        assertEquals(text(R.string.runtime_off_unconfirmed), graph.repository.runtime.value.reason)
        assertTrue(events(n).none { it.kind == EventKind.STOPPED })
        assertTrue(events(n).any { it.kind == EventKind.ERROR && it.detail == text(R.string.runtime_off_unconfirmed) })
        // Off is always executable; once the BedJet reports standby, the night ends.
        swallow = { false }
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.OFF).kind)
        assertNull(graph.repository.activeNight.value)
        assertTrue(events(n).any { it.kind == EventKind.STOPPED })
    }

    @Test fun endNightFollowsTheSameRule() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.END_NIGHT).kind)
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
    }

    @Test fun offWithNoNightSaysWhetherTheBedJetConfirmed() = runBlocking {
        demo.connect()
        demo.send(Codec.setMode(Mode.COOL)!!)
        swallow = press(Mode.STANDBY)
        val unconfirmed = run(NightActions.Command.OFF)
        assertEquals(OutcomeKind.ACCEPTED, unconfirmed.kind)
        assertEquals(text(R.string.runtime_off_unconfirmed), unconfirmed.message)
        swallow = { false }
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.OFF).kind)
    }

    @Test fun aLateStandbyReportClosesAnUnconfirmedOffAsStoppedWithoutAnotherWrite() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        val writes = sent.size
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertNull("a later target Standby confirms the pending Off", graph.repository.activeNight.value)
        assertEquals(writes, sent.size)
        assertEquals(1, events(n).count { it.kind == EventKind.STOPPED })
        assertTrue(events(n).none { it.kind == EventKind.ENDED })
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertEquals("repeated reports cannot duplicate the end", 1, events(n).count { it.kind == EventKind.STOPPED })
    }

    @Test fun lateOffConfirmationNeedsTheTargetButNotProgrammeProgress() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertNotNull("running is not an Off confirmation", graph.repository.activeNight.value)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val head = demo.status.value!!.copy(extra = null)
        graph.actions.observeStatus(head, "demo", source = "ble:AA:BB:CC:DD:EE:FF")
        assertNotNull("another BedJet cannot confirm Off", graph.repository.activeNight.value)
        assertTrue(events(n).none { it.kind == EventKind.STOPPED })
        graph.actions.observeStatus(head, "demo")
        assertNull("Standby is in the status head", graph.repository.activeNight.value)
        assertEquals(1, events(n).count { it.kind == EventKind.STOPPED })
    }

    @Test fun aPendingEndSurvivesRestartAndFreshStandbyWinsBeforeScheduledExpiry() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.END_NIGHT).kind)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        val savedClock = Clock.fixed(ZonedDateTime.parse(n.endsAt).plusSeconds(1).toInstant(), ZoneOffset.UTC)
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = savedClock
        graph.actions.freshStatusMs = 40
        val writes = sent.size
        graph.actions.reconcile()
        assertNull(graph.repository.activeNight.value)
        assertEquals(writes, sent.size)
        assertEquals("Off is stopped even when its old end time has passed", 1, events(n).count { it.kind == EventKind.STOPPED })
        assertTrue(events(n).none { it.kind == EventKind.ENDED })
    }

    @Test fun reconcileCannotUseAnotherUnitsStandbyToConfirmOff() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        val running = demo.status.value!!
        foreign = SourcedStatus("ble:AA:BB:CC:DD:EE:FF", running.copy(mode = Mode.STANDBY))
        graph.actions.reconcile()
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertTrue(events(n).none { it.kind == EventKind.STOPPED })
        foreign = null
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        graph.actions.reconcile()
        assertNull(graph.repository.activeNight.value)
        assertEquals(1, events(n).count { it.kind == EventKind.STOPPED })
    }

    @Test fun reconcileAcceptsFreshTargetStandbyWithoutWaitingForAProgressTail() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        headsOnly = true
        graph.actions.reconcile()
        assertNull("a fresh Standby head is enough to confirm the pending end", graph.repository.activeNight.value)
        assertEquals(1, events(n).count { it.kind == EventKind.STOPPED })
    }

    @Test fun takeBackSettlesAPendingOffFromItsFreshStandbyRead() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        // The unit stops without an observer delivery. Take back must use its own fresh report.
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        graph.actions.releaseForOtherPhone()
        headsOnly = true
        val writes = sent.size
        val result = graph.actions.takeBack()
        assertEquals(result.message, OutcomeKind.LOCAL, result.kind)
        assertNull(graph.repository.activeNight.value)
        assertEquals(writes, sent.size)
        assertEquals(1, events(n).count { it.kind == EventKind.STOPPED })
        assertTrue(events(n).none { it.kind == EventKind.REMOTE_CHANGE || it.kind == EventKind.ENDED })
    }

    @Test fun refusedStartAndReapplyCannotEraseThePendingOff() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        val writes = sent.size
        assertEquals(OutcomeKind.REFUSED, run(NightActions.Command.REAPPLY).kind)
        graph.repository.updateSettings { it.setWakeOverride(graph.now().plusMinutes(1), graph.now()) }
        assertEquals(OutcomeKind.REFUSED, run(NightActions.Command.START_NIGHT).kind)
        assertEquals(writes, sent.size)
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertNull("nothing superseded the ending intention", graph.repository.activeNight.value)
        assertEquals(1, events(n).count { it.kind == EventKind.STOPPED })
    }

    @Test fun anUnconfirmedOffDoesNotExpireAsAnEndedProgramme() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        graph.clock = Clock.fixed(ZonedDateTime.parse(n.endsAt).plusSeconds(1).toInstant(), ZoneOffset.UTC)
        assertFalse("an unresolved Off must not be relabelled as natural completion", graph.actions.expire(n.identity))
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
        assertTrue(events(n).none { it.kind == EventKind.ENDED || it.kind == EventKind.STOPPED })
    }

    @Test fun aReplacementCannotInheritThePreviousNightsUnconfirmedOff() = runBlocking {
        val old = start(); advance(30)
        swallow = press(Mode.STANDBY)
        assertEquals(OutcomeKind.UNKNOWN, run(NightActions.Command.OFF).kind)
        swallow = { false }
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val replacement = graph.repository.activeNight.value!!
        assertNotEquals(old.identity, replacement.identity)
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        assertEquals(replacement.identity, graph.repository.activeNight.value?.identity)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertTrue(events(replacement).none { it.kind == EventKind.STOPPED })
    }

    @Test fun anUnknownNightWithoutAnEndingCommandIsNotClosedByStandby() = runBlocking {
        val n = start(); advance(30)
        graph.repository.setActiveNight(n.copy(confidence = Confidence.UNKNOWN))
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        graph.actions.observeStatus(demo.status.value!!, "demo")
        graph.actions.reconcile()
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
        assertTrue(events(n).none { it.kind == EventKind.STOPPED })
    }

    @Test fun aRemoteEndingCommandWithoutConfirmationIsRefusedBeforeSending() = runBlocking {
        val n = start(); advance(30)
        val writes = sent.size
        val outcome = graph.actions.remote("Off", endsNight = true, expected = n.identity) { it.stop() }
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(writes, sent.size)
        assertEquals(n, graph.repository.activeNight.value)
        assertTrue(events(n).none { it.kind == EventKind.STOPPED })
    }

    // Quick actions

    @Test fun turboTheBedJetReportsEndsTheNight() = runBlocking {
        start(); advance(30)
        val outcome = run(NightActions.Command.TURBO)
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(text(R.string.runtime_turbo), outcome.message)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun turboTheBedJetNeverReportsDoesNotEndTheNight() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.TURBO)
        val outcome = run(NightActions.Command.TURBO)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(text(R.string.runtime_sent_unconfirmed, text(R.string.runtime_turbo)), outcome.message)
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
    }

    @Test fun aSnoozeIsConfirmedOnlyByTheBedJetsReport() = runBlocking {
        start(); advance(30)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.SNOOZE_WARM).kind)
        swallow = press(Mode.COOL)
        val outcome = run(NightActions.Command.SNOOZE_COOL)
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
        assertTrue(outcome.message, outcome.message.startsWith(text(R.string.runtime_sent_unconfirmed, "")))
    }

    // Remote

    @Test fun anUnchangedRemoteValueIsSentAndConfirmedWithoutReconnecting() = runBlocking {
        demo.connect()
        demo.send(Codec.setMode(Mode.HEAT)!!)
        demo.send(Codec.setTemperature(70))
        val before = sent.size

        val outcome = graph.actions.remote("Temperature set", confirms = { it.targetTemperature == 70 }) {
            it.setTemperature(70)
        }

        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals("an unchanged request is still sent", before + 1, sent.size)
        assertTrue(sent.last().contentEquals(Codec.setTemperature(70)))
        assertEquals("the target already reports the requested value", 0, disconnects)
    }

    @Test fun aChangedRemoteValueWaitsForItsLaterTargetReport() = runBlocking {
        demo.connect()
        demo.send(Codec.setMode(Mode.HEAT)!!)
        demo.send(Codec.setTemperature(70))
        val written = CompletableDeferred<Unit>()
        swallow = { it.contentEquals(Codec.setTemperature(72)) }
        afterSend = { if (it.contentEquals(Codec.setTemperature(72))) written.complete(Unit) }
        graph.actions.confirmMs = 10_000
        val outcome = async {
            graph.actions.remote("Temperature set", confirms = { it.targetTemperature == 72 }) { it.setTemperature(72) }
        }
        try {
            withTimeout(5_000) { written.await() }
            assertFalse("a different current value cannot confirm an accepted write", outcome.isCompleted)
            demo.send(Codec.setTemperature(72))
            assertEquals(OutcomeKind.CONFIRMED, withTimeout(5_000) { outcome.await() }.kind)
            assertEquals("the later target report avoids the fallback read", 0, disconnects)
        } finally { outcome.cancel() }
    }

    @Test fun anotherUnitsAlreadyMatchingValueCannotConfirmTheWrite() = runBlocking {
        demo.connect()
        foreign = SourcedStatus("ble:AA:BB:CC:DD:EE:FF", demo.status.value!!.copy(targetTemperature = 70))
        val outcome = graph.actions.remote("Temperature set", confirms = { it.targetTemperature == 70 }) {
            it.setTemperature(70)
        }
        assertEquals(OutcomeKind.ACCEPTED, outcome.kind)
        assertTrue("the guarded request is still sent", sent.single().contentEquals(Codec.setTemperature(70)))
    }

    @Test fun anAlreadyMatchingStatusCannotHideASendFailure() = runBlocking {
        demo.connect()
        val outcome = graph.actions.remote("Off", confirms = { it.mode == Mode.STANDBY }) {
            throw BedJetError.NotConnected()
        }
        assertEquals(OutcomeKind.FAILED, outcome.kind)
    }

    @Test fun aFailedSendStillMakesTheTrackedNightUnknownWhenItsCurrentStatusMatches() = runBlocking {
        val night = start()
        val mode = demo.status.value!!.mode
        val outcome = graph.actions.remote("Mode set", endsNight = true, expected = night.identity,
            confirms = { it.mode == mode }) { throw BedJetError.NotConnected() }
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertTrue(events(night).none { it.kind == EventKind.STOPPED })
    }

    @Test fun aRemoteChangeIsConfirmedOnlyByAMatchingReport() = runBlocking {
        demo.connect()
        demo.send(Codec.setMode(Mode.HEAT)!!)
        val set = graph.actions.remote("Temperature set", confirms = { it.targetTemperature == 70 }) { it.setTemperature(70) }
        assertEquals(OutcomeKind.CONFIRMED, set.kind)
        assertEquals("Temperature set", set.message)
        swallow = { it[0] == 0x03.toByte() }
        val unseen = graph.actions.remote("Temperature set", confirms = { it.targetTemperature == 72 }) { it.setTemperature(72) }
        assertEquals(OutcomeKind.ACCEPTED, unseen.kind)
        assertEquals(text(R.string.runtime_sent_unconfirmed, "Temperature set"), unseen.message)
        val unchecked = graph.actions.remote("Reminder cleared") { it.acknowledge() }
        assertEquals("with nothing to compare, it is only sent", OutcomeKind.ACCEPTED, unchecked.kind)
    }

    @Test fun aRemoteModeChangeTheBedJetNeverReportsKeepsTheNightUnknown() = runBlocking {
        val n = start(); advance(30)
        swallow = press(Mode.HEAT)
        val outcome = graph.actions.remote("Heat", endsNight = true, expected = n.identity, confirms = { it.mode == Mode.HEAT }) { it.setMode(Mode.HEAT) }
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(n.identity, graph.repository.activeNight.value?.identity)
        swallow = { false }
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.remote("Heat", endsNight = true, expected = n.identity,
            confirms = { it.mode == Mode.HEAT }) { it.setMode(Mode.HEAT) }.kind)
        assertNull(graph.repository.activeNight.value)
    }

    // Boost

    /** Not reproduced: the start's own confirmation is a report after the start showing step 1. Kept as a guard. */
    @Test fun aBoostWhoseStartTheBedJetNeverReportsIsUnknown() = runBlocking {
        swallow = { it.contentEquals(Codec.startProgram(2)) }
        val outcome = run(NightActions.Command.BOOST)
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(Confidence.UNKNOWN, graph.repository.runtime.value.slotWrite?.confidence)
    }
}
