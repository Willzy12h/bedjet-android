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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

/**
 * P3b: a command sent to the BedJet is reported as done only when the BedJet's own report, received after it was
 * sent, shows it. A write the link accepted is not that proof: here [swallow] lets such a write through the link
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
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; if (!swallow(command)) demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
    }

    @Before fun setup() = runBlocking {
        graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
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
