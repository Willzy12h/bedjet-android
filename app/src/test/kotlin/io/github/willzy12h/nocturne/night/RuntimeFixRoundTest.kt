package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.widget.widgetWake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.nullable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** GPT-2r: outcomes, recovery choices and restart presentation follow the actual published programme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeFixRoundTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app: App = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private lateinit var link: BedJetLink
    private val sent = mutableListOf<ByteArray>()
    private var beforeQuery: suspend (Int) -> Unit = {}
    @Volatile private var beforeFileWrite: ((File, ByteArray) -> Unit)? = null

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            override suspend fun query(request: Int): ByteArray { beforeQuery(request); return demo.query(request) }
        }
        reload(Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC))
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun reload(clock: Clock = graph.clock) {
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = clock
        graph.actions.confirmMs = 1
    }

    private suspend fun execute(command: NightActions.Command): CommandOutcome =
        graph.actions.execute(graph.actions.capture(command, requireIdentity = true).copy(replacementConfirmed = true))

    private suspend fun start(): ActiveNight {
        val outcome = execute(NightActions.Command.START_NIGHT)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        return graph.repository.activeNight.value!!
    }

    private fun at(time: String) {
        graph.clock = Clock.fixed(ZonedDateTime.parse(time).toInstant(), ZoneOffset.UTC)
        demo.tick()
    }

    private fun programmeWrites() = sent.count {
        it[0] == 0x40.toByte() || (it[0] == 0x01.toByte() && it[1].toInt() and 0xff in 0x80..0x85)
    }
    private fun starts() = sent.count { it[0] == 0x01.toByte() && it[1].toInt() and 0xff in 0x80..0x82 }
    private fun retryScheduled() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
        shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY
    }
    private fun wakeText(night: ActiveNight) = app.getString(R.string.feature_wake,
        ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("EEE HH:mm")))

    @Test fun lateOriginalsBarrierReturnsThePublishedFailedStart() = runBlocking {
        beforeQuery = { request ->
            if (request == Codec.Query.PROGRAM_NAMES) {
                // The initial admission check passed; recordOriginalSlot must still enforce its final barrier.
                ReflectionHelpers.getField<MutableStateFlow<Boolean>>(graph.repository, "_originalsBlocked").value = true
            }
        }
        val outcome = execute(NightActions.Command.START_NIGHT)
        val published = graph.repository.activeNight.value!!
        assertEquals(Confidence.FAILED, published.confidence)
        assertEquals(ChangeKind.START, published.pending?.kind)
        assertEquals("Every branch must agree with this request's published failure", OutcomeKind.FAILED, outcome.kind)
        assertEquals(app.getString(R.string.runtime_slot_backups_unreadable), outcome.message)
        assertEquals("The originals barrier must precede every programme write and start", 0, programmeWrites())
    }

    @Test fun takeBackMarksItsTakeoverNoticeAsHandled() = runBlocking {
        val night = start()
        at("2026-10-21T23:00:00Z")
        demo.send(Codec.setMode(Mode.COOL)!!)
        val outcome = graph.actions.takeBack()
        assertEquals(OutcomeKind.UNKNOWN, outcome.kind)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value?.confidence)
        assertEquals(night.identity, graph.repository.activeNight.value?.identity)
        assertTrue("Take back already posted the takeover notice; delivery must not post it again", outcome.notificationHandled)
        assertEquals(1, graph.repository.history.value.single { it.id == night.recordId }.events.count { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test fun initialiseClearsTheJournalLeftBehindAConfirmedRevisionAfterRestart() = runBlocking {
        val old = start()
        val pending = PendingRetime("pre-crash-retime", old.identity, "demo", null, null, old.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        NightAlarms.scheduleRetry(app, pending, graph.now())
        val confirmed = old.copy(revision = old.revision + 1)
        // Model the durable crash boundary: active-night.json committed, runtime.json still names the old revision.
        graph.repository.setActiveNight(confirmed)
        val writes = programmeWrites()
        reload()

        graph.actions.initialise()

        assertEquals(confirmed, graph.repository.activeNight.value)
        assertNull("Startup must clear the obsolete durable request, not only its alarm", graph.repository.runtime.value.retime)
        assertFalse(graph.actions.retimePending)
        assertFalse(retryScheduled())
        assertEquals(wakeText(confirmed), widgetWake(app, confirmed))
        assertEquals("Restart cleanup is local and must never replay the programme", writes, programmeWrites())
    }

    @Test fun initialiseRetainsARequestForTheStillCurrentConfirmedIdentity() = runBlocking {
        val night = start()
        val pending = PendingRetime("current-retime", night.identity, "demo", null, null, night.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        reload()
        graph.actions.initialise()
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertTrue(retryScheduled())
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
    }

    @Test fun initialiseKeepsConfirmationIfTheObsoleteJournalCannotYetBeCleared() = runBlocking {
        val old = start()
        val pending = PendingRetime("unavailable-old-journal", old.identity, "demo", null, null, old.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        val confirmed = old.copy(revision = old.revision + 1)
        graph.repository.setActiveNight(confirmed)
        reload()
        var attempted = false
        beforeFileWrite = { file, _ ->
            if (file.name == "runtime.json") { attempted = true; throw IOException("journal storage unavailable") }
        }
        var failure: Throwable? = null
        try { graph.actions.initialise() } catch (e: Throwable) { failure = e }
        finally { beforeFileWrite = null }
        assertTrue("Startup must attempt durable obsolete-journal cleanup", attempted)
        assertNull("Unavailable local storage must not invalidate confirmed startup", failure)
        assertEquals(confirmed, graph.repository.activeNight.value)
        assertEquals(pending, graph.repository.runtime.value.retime)
        assertFalse(retryScheduled())
        assertEquals(wakeText(confirmed), widgetWake(app, confirmed))
    }

    @Test fun widgetIgnoresAnObsoleteJournalJustAsTheCoordinatorDoes() = runBlocking {
        val night = start()
        val pending = PendingRetime("old-retime", night.identity.copy(revision = night.revision - 1), "demo", null, null, night.wakeUpStart)
        graph.repository.updateRuntime { it.copy(retime = pending) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Confidence.CONFIRMED, graph.actions.status.value.confidence)
        assertEquals("An obsolete journal cannot label a confirmed wake pending", wakeText(night), widgetWake(app, night))
        assertEquals("The widget must not mutate the retained journal while rendering", pending, graph.repository.runtime.value.retime)
    }

    @Test fun widgetPendingPresentationUsesTheCurrentNightAndTarget() = runBlocking {
        val night = start()
        graph.repository.updateRuntime { it.copy(alarmInbox = AlarmObservation("other-night", "another-night", "demo", graph.now().toString())) }
        assertEquals(wakeText(night), widgetWake(app, night))
        graph.repository.updateRuntime { it.copy(alarmInbox = AlarmObservation("other-target", night.recordId, "ble:AA:BB:CC:DD:EE:FF", graph.now().toString())) }
        assertEquals(wakeText(night), widgetWake(app, night))
        graph.repository.updateRuntime { it.copy(alarmInbox = AlarmObservation("current", night.recordId, "demo", graph.now().toString())) }
        assertEquals(app.getString(R.string.feature_pending_wake), widgetWake(app, night))
        graph.repository.updateRuntime { it.copy(alarmInbox = null) }
        assertEquals(app.getString(R.string.feature_pending_wake), widgetWake(app, night.copy(confidence = Confidence.PENDING)))
    }

    @Test fun snoozeAt2314OffersTheNewlyDisplacedProgrammeBeyondTheOld2315Expiry() = runBlocking {
        val first = start()
        at("2026-10-21T23:00:00Z")
        val replacement = start()
        assertEquals(first.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
        assertEquals("2026-10-21T23:15Z", graph.repository.runtime.value.resumeOffer?.expiresAt)
        at("2026-10-21T23:14:00Z")

        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.SNOOZE_WARM).kind)

        val offered = graph.repository.runtime.value.resumeOffer!!
        assertEquals("The programme displaced now owns the newest Resume offer", replacement.recordId, offered.night.recordId)
        assertFalse(offered.night.trackingManual)
        assertEquals("2026-10-21T23:29Z", offered.expiresAt)
        at("2026-10-21T23:16:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.RESUME).kind)
        assertEquals(replacement.recordId, graph.repository.activeNight.value?.recordId)
        assertFalse(graph.repository.activeNight.value!!.trackingManual)
    }

    @Test fun keepTrackingOfAProgrammeOffersThatNewlyDisplacedProgramme() = runBlocking {
        val first = start()
        at("2026-10-21T23:00:00Z")
        val replacement = start()
        assertEquals(first.recordId, graph.repository.runtime.value.resumeOffer?.night?.recordId)
        at("2026-10-21T23:14:00Z")
        // Keep tracking is offered for an uncertain night; its saved snapshot still describes a programme.
        graph.repository.setActiveNight(replacement.copy(confidence = Confidence.UNKNOWN))
        assertEquals(OutcomeKind.LOCAL, execute(NightActions.Command.KEEP_TRACKING).kind)
        val offer = graph.repository.runtime.value.resumeOffer!!
        assertEquals("Keep tracking also displaces the current programme", replacement.recordId, offer.night.recordId)
        assertEquals("2026-10-21T23:29Z", offer.expiresAt)
        assertFalse(offer.night.trackingManual)
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
    }

    private suspend fun manualWithOffer(): ResumeOffer {
        start()
        at("2026-10-21T23:00:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.SNOOZE_WARM).kind)
        return graph.repository.runtime.value.resumeOffer!!.also { assertFalse(it.night.trackingManual) }
    }
    private fun assertSameRecoveryChoice(offer: ResumeOffer) {
        val actual = graph.repository.runtime.value.resumeOffer!!
        assertEquals("No programme was displaced; its original Resume snapshot and expiry must survive", offer.copy(commandVersion = actual.commandVersion), actual)
        assertEquals(graph.repository.runtime.value.generation, actual.commandVersion)
    }

    @Test fun aQuickAdjustmentOfAnAlreadyManualNightKeepsTheOriginalOfferAndExpiry() = runBlocking {
        val offer = manualWithOffer()
        at("2026-10-21T23:05:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.TURBO).kind)
        assertNull(graph.repository.activeNight.value)
        assertSameRecoveryChoice(offer)
        at("2026-10-21T23:15:00Z")
        assertEquals("The manual adjustment must not renew the original expiry", OutcomeKind.REFUSED, execute(NightActions.Command.RESUME).kind)
    }

    @Test fun aModeAdjustmentOfAnAlreadyManualNightKeepsTheOriginalOfferAndExpiry() = runBlocking {
        val offer = manualWithOffer()
        at("2026-10-21T23:05:00Z")
        val outcome = graph.actions.remote("Cool", endsNight = true, confirms = { it.mode == Mode.COOL }) { it.setMode(Mode.COOL) }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertNull(graph.repository.activeNight.value)
        assertSameRecoveryChoice(offer)
    }

    @Test fun boostOfAnAlreadyManualNightKeepsTheOriginalOfferAndExpiry() = runBlocking {
        val offer = manualWithOffer()
        at("2026-10-21T23:05:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.BOOST).kind)
        assertNull(graph.repository.activeNight.value)
        assertSameRecoveryChoice(offer)
    }

    private suspend fun programmeDisplacedByQuick(): ResumeOffer {
        val night = start()
        at("2026-10-21T23:00:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.TURBO).kind)
        assertNull(graph.repository.activeNight.value)
        return graph.repository.runtime.value.resumeOffer!!.also { assertEquals(night.recordId, it.night.recordId) }
    }

    @Test fun anotherQuickCommandKeepsTheOfferWhenTheManualRunIsNoLongerTracked() = runBlocking {
        val offer = programmeDisplacedByQuick()
        at("2026-10-21T23:05:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.COOL).kind)
        assertNotNull("No new programme was displaced by the second Quick command", graph.repository.runtime.value.resumeOffer)
        assertSameRecoveryChoice(offer)
        reload()
        assertSameRecoveryChoice(offer)
        at("2026-10-21T23:15:00Z")
        assertEquals("Untracked manual adjustments must not renew the expiry", OutcomeKind.REFUSED, execute(NightActions.Command.RESUME).kind)
    }

    @Test fun aModeChipKeepsTheOfferWhenTheManualRunIsNoLongerTracked() = runBlocking {
        val offer = programmeDisplacedByQuick()
        at("2026-10-21T23:05:00Z")
        val outcome = graph.actions.remote("Cool", endsNight = true, confirms = { it.mode == Mode.COOL }) { it.setMode(Mode.COOL) }
        assertEquals(OutcomeKind.CONFIRMED, outcome.kind)
        assertNotNull("A mode chip displaces no programme after Quick already ended tracking", graph.repository.runtime.value.resumeOffer)
        assertSameRecoveryChoice(offer)
    }

    @Test fun manualSnoozeExtendKeepTrackingAndAuxiliariesDoNotRenewTheOffer() = runBlocking {
        val offer = manualWithOffer()
        at("2026-10-21T23:01:00Z")
        for (command in listOf(NightActions.Command.SNOOZE_COOL, NightActions.Command.EXTEND_WARM,
            NightActions.Command.EXTEND_COOL, NightActions.Command.KEEP_TRACKING)) {
            assertTrue(execute(command).succeeded)
            assertSameRecoveryChoice(offer)
        }
        graph.actions.remote("Lights off", confirms = { it.extra?.ledEnabled == false }) { it.setLed(false) }
        assertSameRecoveryChoice(offer)
    }

    private suspend fun invalidManualOffer(change: (ResumeOffer) -> ResumeOffer) {
        val offer = manualWithOffer()
        at("2026-10-21T23:05:00Z")
        graph.repository.updateRuntime { it.copy(resumeOffer = change(offer)) }
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.TURBO).kind)
        assertNull("A manual-only adjustment must not revive an invalid programme offer", graph.repository.runtime.value.resumeOffer)
        assertEquals(OutcomeKind.REFUSED, execute(NightActions.Command.RESUME).kind)
    }
    @Test fun anExpiredManualOfferIsNotRevived() = runBlocking { invalidManualOffer { it.copy(expiresAt = "2026-10-21T23:05Z") } }
    @Test fun aWrongTargetManualOfferIsNotRevived() = runBlocking { invalidManualOffer { it.copy(night = it.night.copy(deviceId = "ble:AA:BB:CC:DD:EE:FF")) } }
    @Test fun aWrongGenerationManualOfferIsNotRevived() = runBlocking { invalidManualOffer { it.copy(commandVersion = it.commandVersion - 1) } }
    @Test fun aPastWakeManualOfferIsNotRevived() = runBlocking { invalidManualOffer { it.copy(night = it.night.copy(wakeAt = "2026-10-21T23:04Z")) } }

    private suspend fun operationAfterConfirmedSaveFailure(command: NightActions.Command, fileName: String, cancel: Boolean = false) {
        if (command != NightActions.Command.START_NIGHT) {
            start()
            at("2026-10-21T23:00:00Z")
            if (command == NightActions.Command.RESUME) execute(NightActions.Command.SNOOZE_WARM)
        }
        val initialStarts = starts()
        var published: ActiveNight? = null
        var failed = false
        val diskFailure = IOException("test local record disk failure")
        val cancellation = CancellationException("test owner cancellation after confirmation")
        beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.toString(Charsets.UTF_8))
                    ?.takeIf { it.confidence == Confidence.CONFIRMED && !it.trackingManual }?.let { published = it }
            }
            val confirmed = published
            if (file.name == fileName && confirmed != null && graph.repository.activeNight.value == confirmed) {
                failed = true
                if (cancel) throw cancellation else throw diskFailure
            }
        }
        var thrown: Throwable? = null
        val outcome = try { execute(command) } catch (e: Throwable) { thrown = e; null }
        finally { beforeFileWrite = null }
        assertTrue("The injected persistence failure must follow the exact CONFIRMED publication", failed)
        assertEquals(published, graph.repository.activeNight.value)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        assertEquals("A local save failure must not repeat the hardware start", initialStarts + 1, starts())
        if (cancel) {
            assertTrue("Owner cancellation still propagates after confirmation", thrown is CancellationException)
            assertNull(outcome)
        } else {
            assertEquals("$command confirmed on the BedJet before $fileName failed; report that confirmation", OutcomeKind.CONFIRMED, outcome?.kind)
            assertNull("A local record exception cannot replace the confirmed outcome", thrown)
            assertTrue("The separate local-record problem must remain visible", outcome!!.message.contains("record"))
            assertTrue(outcome.message.contains(diskFailure.message!!))
            assertFalse(retryScheduled())
        }
    }

    @Test fun startKeepsConfirmationAfterHistorySaveFailure() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "history.json") }
    @Test fun startKeepsConfirmationAfterRuntimeSaveFailure() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "runtime.json") }
    @Test fun reapplyKeepsConfirmationAfterHistorySaveFailure() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.REAPPLY, "history.json") }
    @Test fun reapplyKeepsConfirmationAfterRuntimeSaveFailure() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.REAPPLY, "runtime.json") }
    @Test fun resumeKeepsConfirmationAfterHistorySaveFailure() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.RESUME, "history.json") }
    @Test fun resumeKeepsConfirmationAfterRuntimeSaveFailure() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.RESUME, "runtime.json") }
    @Test fun startStillPropagatesCancellationAfterConfirmation() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "history.json", cancel = true) }
    @Test fun reapplyStillPropagatesCancellationAfterConfirmation() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.REAPPLY, "history.json", cancel = true) }
    @Test fun resumeStillPropagatesCancellationAfterConfirmation() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.RESUME, "history.json", cancel = true) }
}
