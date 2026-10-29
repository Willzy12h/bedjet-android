package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.operationIntent
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
import java.io.IOException
import java.time.*

/**
 * P3b: what the app records and reports after the supervised fan check, Take back, a wake override, Forget and a
 * manual run is what the BedJet showed, and a resume offer outlives the manual change it is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TruthfulStateTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    /** A write this matches is accepted by the link and never reaches the unit. */
    private var swallow: (ByteArray) -> Boolean = { false }
    /** A write this matches fails at the link, after earlier writes went out. */
    private var fail: (ByteArray) -> Boolean = { false }
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                if (fail(command)) throw IOException("GATT write failed")
                sent += command; if (!swallow(command)) demo.send(command)
            }
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
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun isFan(b: ByteArray) = b[0] == 0x07.toByte()

    // Supervised fan check

    @Test fun theFanCheckIsConfirmedOnlyWhenTheChangeAndTheRestoreAreBothReported() = runBlocking {
        demo.connect()
        val original = demo.status.value!!.fanStep
        val outcome = graph.actions.setupFanCheck()
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(text(R.string.runtime_fan_checked), outcome.message)
        assertEquals(original, demo.status.value!!.fanStep)
    }

    @Test fun aRestoreTheBedJetNeverReportsIsNotCalledRestored() = runBlocking {
        demo.connect()
        var fans = 0
        swallow = { isFan(it) && ++fans == 2 }
        val outcome = graph.actions.setupFanCheck()
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(text(R.string.runtime_fan_restore_unconfirmed), outcome.message)
    }

    @Test fun aFanChangeTheBedJetNeverReportsIsNotACheck() = runBlocking {
        demo.connect()
        var fans = 0
        swallow = { isFan(it) && ++fans == 1 }
        val outcome = graph.actions.setupFanCheck()
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(text(R.string.runtime_fan_change_unseen), outcome.message)
    }

    // Take back

    @Test fun takeBackSaysWhenItFoundTheNightChanged() = runBlocking {
        val n = start(); advance(30)
        demo.send(Codec.setMode(Mode.COOL)!!)
        val outcome = graph.actions.takeBack()
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(text(R.string.runtime_taken_back_takeover), outcome.message)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertEquals(n.identity.nightId, graph.repository.activeNight.value!!.identity.nightId)
    }

    @Test fun takeBackOfANightStillRunningSaysItChecked() = runBlocking {
        start(); advance(30)
        val outcome = graph.actions.takeBack()
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertEquals(text(R.string.runtime_taken_back), outcome.message)
    }

    @Test fun takeBackCancelsThePendingRetryWhenItFindsATakeover() = runBlocking {
        val night = start(); advance(30)
        val pending = PendingRetime("pending-takeover", night.identity, "demo", graph.now().plusHours(5).toString(), null, night.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        NightAlarms.scheduleRetry(context, pending, graph.now())
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        fun hasRetry() = alarms.scheduledAlarms.any {
            org.robolectric.Shadows.shadowOf(it.operationIntent).savedIntent.action == NightAlarms.ACTION_RETRY
        }
        assertTrue(hasRetry())
        demo.send(Codec.setMode(Mode.COOL)!!)

        val outcome = graph.actions.takeBack()

        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(text(R.string.runtime_taken_back_takeover), outcome.message)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertNull(graph.repository.runtime.value.retime)
        assertFalse("Take back must cancel the obsolete re-time alarm as every takeover does", hasRetry())
        assertEquals(1, graph.repository.history.value.first { it.id == night.recordId }.events.count { it.kind == EventKind.REMOTE_CHANGE })
    }

    // Wake override on a running night

    @Test fun aWakeOverrideWhoseRewriteFailsIsNotSaved() = runBlocking {
        val n = start(); advance(30)
        fail = { it[0] == 0x40.toByte() }
        val outcome = graph.actions.setWakeOverride(graph.now().plusHours(5), n.identity)
        assertNotEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertNull("the button never shows a time the night doesn't keep", graph.repository.settings.value.tonightOverride)
        assertEquals(n.wakeAt, graph.repository.activeNight.value!!.wakeAt)
    }

    @Test fun aWakeOverrideTheBedJetRunsIsSavedWithItsExpiry() = runBlocking {
        val n = start(); advance(30)
        val wake = graph.now().plusHours(5).withSecond(0).withNano(0)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.setWakeOverride(wake, n.identity).kind)
        val saved = graph.repository.settings.value.tonightOverride!!
        assertEquals(wake.toInstant(), ZonedDateTime.parse(saved.wakeAt).toInstant())
        assertEquals("it lapses at its own wake time", saved.wakeAt, saved.expiresAt)
    }

    // Forget

    @Test fun forgettingTheBedJetSaysSoExactly() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val outcome = graph.actions.forgetTarget()
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertEquals("Forgot this BedJet. Choose a BedJet to connect to.", outcome.message)
    }

    // Resume

    @Test fun theProgrammeCanBeResumedAfterARemoteAdjust() = runBlocking {
        val n = start(); advance(30)
        graph.actions.remote("Temperature set", affectsProgramme = true, expected = n.identity) { it.setTemperature(50) }
        val manual = graph.repository.activeNight.value!!
        assertTrue(manual.trackingManual)
        assertEquals(n.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
        val outcome = run(NightActions.Command.RESUME)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        val resumed = graph.repository.activeNight.value!!
        assertFalse(resumed.trackingManual)
        assertEquals(Confidence.CONFIRMED, resumed.confidence)
    }

    @Test fun theProgrammeCanBeResumedAfterASnoozeKeptAndALaterAdjust() = runBlocking {
        val n = start(); advance(30)
        run(NightActions.Command.SNOOZE_WARM)
        assertEquals(OutcomeKind.LOCAL, run(NightActions.Command.KEEP_TRACKING).kind)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        graph.actions.remote("Lights off") { it.setLed(false) }
        assertEquals("the offer is still the programme's", n.applied, graph.repository.runtime.value.resumeOffer?.night?.applied)
        val outcome = run(NightActions.Command.RESUME)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        assertFalse(graph.repository.activeNight.value!!.trackingManual)
    }

    @Test fun aReplacedNightCanBeResumedWhileItsOfferLasts() = runBlocking {
        val first = start(); advance(5)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val offer = graph.repository.runtime.value.resumeOffer!!
        assertEquals(first.recordId, offer.night.recordId)
        assertEquals(graph.repository.runtime.value.generation, offer.commandVersion)
        val outcome = run(NightActions.Command.RESUME)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(first.recordId, graph.repository.activeNight.value!!.recordId)
    }

    @Test fun aNewlyDisplacedProgrammeSupersedesTheOlderResumeOffer() = runBlocking {
        val first = start(); advance(5)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val replacement = graph.repository.activeNight.value!!
        val offer = graph.repository.runtime.value.resumeOffer!!
        advance(1)

        graph.actions.remote("Temperature set", affectsProgramme = true, expected = replacement.identity,
            confirms = { it.targetTemperature == 50 }) { it.setTemperature(50) }

        val retained = graph.repository.runtime.value.resumeOffer!!
        assertEquals(first.recordId, offer.night.recordId)
        assertEquals("the newly displaced programme owns the newest recovery choice", replacement.recordId, retained.night.recordId)
        assertEquals(replacement.applied, retained.night.applied)
        assertEquals(graph.now().toString(), retained.stoppedAt)
        assertEquals("displacing another programme starts that programme's own offer", graph.now().plusMinutes(15).toString(), retained.expiresAt)
        assertEquals(graph.repository.runtime.value.generation, retained.commandVersion)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.RESUME).kind)
        assertEquals(replacement.recordId, graph.repository.activeNight.value?.recordId)
    }

    @Test fun snoozeOffersTheNewlyDisplacedProgrammeAndKeepTrackingPreservesThatExpiry() = runBlocking {
        val first = start(); advance(5)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val replacement = graph.repository.activeNight.value!!
        val offer = graph.repository.runtime.value.resumeOffer!!
        assertEquals(first.recordId, offer.night.recordId)
        advance(1)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.SNOOZE_WARM).kind)
        val snoozedOffer = graph.repository.runtime.value.resumeOffer!!
        assertEquals("snooze displaces the current programme", replacement.recordId, snoozedOffer.night.recordId)
        assertEquals(graph.now().plusMinutes(15).toString(), snoozedOffer.expiresAt)
        assertEquals(OutcomeKind.LOCAL, run(NightActions.Command.KEEP_TRACKING).kind)
        val retained = graph.repository.runtime.value.resumeOffer!!
        assertEquals("keeping an already manual night preserves the programme offer", replacement.recordId, retained.night.recordId)
        assertEquals("manual-only changes must not extend an offer", snoozedOffer.expiresAt, retained.expiresAt)
        assertEquals(graph.repository.runtime.value.generation, retained.commandVersion)
    }

    @Test fun anExpiredReplacedProgrammeOfferGivesWayToTheCurrentProgramme() = runBlocking {
        val first = start(); advance(5)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val replacement = graph.repository.activeNight.value!!
        assertEquals(first.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
        advance(16)
        graph.actions.remote("Temperature set", affectsProgramme = true, expected = replacement.identity,
            confirms = { it.targetTemperature == 50 }) { it.setTemperature(50) }
        assertEquals("the current programme gets its own offer when displaced", replacement.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
    }

    @Test fun aNewExplicitProgrammeReplacementSupersedesTheOlderOffer() = runBlocking {
        start(); advance(5)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        val second = graph.repository.activeNight.value!!
        advance(1)
        assertEquals(OutcomeKind.CONFIRMED, run(NightActions.Command.START_NIGHT).kind)
        assertEquals("the latest explicit replacement owns the Resume choice", second.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
    }

    // Keep tracking

    @Test fun aKeptManualRunIsShownAsRunningUntilItsEndNotAsAWake() = runBlocking {
        start(); advance(30)
        run(NightActions.Command.SNOOZE_WARM)
        run(NightActions.Command.KEEP_TRACKING)
        val kept = graph.repository.activeNight.value!!
        assertTrue(kept.trackingManual)
        assertEquals(Confidence.CONFIRMED, kept.confidence)
        val end = ZonedDateTime.parse(kept.endsAt).format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm"))
        assertEquals("Manual run until $end", io.github.willzy12h.nocturne.widget.widgetWake(context, kept))
        assertEquals(R.string.feature_manual_running, io.github.willzy12h.nocturne.platform.NightTileService.subtitle(kept, false))
        assertEquals(R.string.feature_running, io.github.willzy12h.nocturne.platform.NightTileService.subtitle(kept.copy(trackingManual = false), false))
    }

    // P3a carry: an unresolved write of the working slot with no night

    @Test fun anUnresolvedWorkingSlotWriteWithNoNightShowsTheIdleStatusUnknown() = runBlocking {
        graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite("demo", graph.repository.settings.value.workingSlot,
            null, label = "Boost", confidence = Confidence.UNKNOWN)) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(graph.repository.activeNight.value)
        assertEquals(Confidence.UNKNOWN, graph.actions.status.value.confidence)
    }
}
