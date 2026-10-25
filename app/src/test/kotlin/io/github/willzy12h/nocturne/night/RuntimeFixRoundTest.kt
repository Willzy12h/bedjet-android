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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private var swallowOff = false
    @Volatile private var beforeFileWrite: ((File, ByteArray) -> Unit)? = null

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) {
                sent += command
                if (!swallowOff || !command.contentEquals(Codec.setMode(Mode.STANDBY)!!)) demo.send(command)
            }
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

    @Test fun boostOffersTheProgrammeItActuallyDisplacesWithANewExpiry() = runBlocking {
        val first = start()
        at("2026-10-21T23:00:00Z")
        val replacement = start()
        val olderOffer = graph.repository.runtime.value.resumeOffer!!
        assertEquals(first.recordId, olderOffer.night.recordId)
        assertEquals("2026-10-21T23:15Z", olderOffer.expiresAt)
        at("2026-10-21T23:14:00Z")

        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.BOOST).kind)

        assertNull(graph.repository.activeNight.value)
        val offered = graph.repository.runtime.value.resumeOffer!!
        assertEquals("Boost displaced the running programme, so it owns the new Resume choice", replacement, offered.night)
        assertEquals("2026-10-21T23:29Z", offered.expiresAt)
        assertEquals(graph.repository.runtime.value.generation, offered.commandVersion)
        assertEquals(1, graph.repository.history.value.single { it.id == replacement.recordId }.events.count { it.kind == EventKind.STOPPED })
        reload()
        assertEquals("The displaced programme and its new deadline must survive restart", offered, graph.repository.runtime.value.resumeOffer)
        at("2026-10-21T23:16:00Z")
        assertEquals("The new choice stays usable after the earlier programme's deadline", OutcomeKind.CONFIRMED, execute(NightActions.Command.RESUME).kind)
        assertEquals(replacement.recordId, graph.repository.activeNight.value?.recordId)
        assertFalse(graph.repository.activeNight.value!!.trackingManual)
        assertNull(graph.repository.runtime.value.resumeOffer)
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
        assertTrue(graph.repository.activeNight.value!!.trackingManual)
        assertEquals("2026-10-21T23:15Z", offer.expiresAt)
        at("2026-10-21T23:05:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.BOOST).kind)
        assertNull(graph.repository.activeNight.value)
        assertSameRecoveryChoice(offer)
        reload()
        assertSameRecoveryChoice(offer)
        at("2026-10-21T23:15:00Z")
        assertEquals("Boost must not renew the offer by displacing only a manual run", OutcomeKind.REFUSED, execute(NightActions.Command.RESUME).kind)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun theOfferRetainedByBoostOfAManualNightCanResumeBeforeItsOriginalExpiry() = runBlocking {
        val offer = manualWithOffer()
        at("2026-10-21T23:05:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.BOOST).kind)
        reload()
        at("2026-10-21T23:14:00Z")

        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.RESUME).kind)

        assertEquals(offer.night.recordId, graph.repository.activeNight.value?.recordId)
        assertFalse(graph.repository.activeNight.value!!.trackingManual)
        assertNull(graph.repository.runtime.value.resumeOffer)
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

    @Test fun boostWithNoTrackedNightKeepsTheOriginalOfferAndExpiryAfterRestart() = runBlocking {
        val offer = programmeDisplacedByQuick()
        assertNull(graph.repository.activeNight.value)
        assertEquals("2026-10-21T23:15Z", offer.expiresAt)
        at("2026-10-21T23:05:00Z")

        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.BOOST).kind)

        assertNull(graph.repository.activeNight.value)
        assertSameRecoveryChoice(offer)
        reload()
        assertSameRecoveryChoice(offer)
        at("2026-10-21T23:15:00Z")
        assertEquals("Boost displaces no programme when no night is tracked, so the original deadline still applies",
            OutcomeKind.REFUSED, execute(NightActions.Command.RESUME).kind)
        assertNull(graph.repository.activeNight.value)
    }

    @Test fun theOfferRetainedByBoostWithNoTrackedNightCanResumeBeforeItsOriginalExpiry() = runBlocking {
        val offer = programmeDisplacedByQuick()
        at("2026-10-21T23:05:00Z")
        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.BOOST).kind)
        reload()
        at("2026-10-21T23:14:00Z")

        assertEquals(OutcomeKind.CONFIRMED, execute(NightActions.Command.RESUME).kind)

        assertEquals(offer.night.recordId, graph.repository.activeNight.value?.recordId)
        assertFalse(graph.repository.activeNight.value!!.trackingManual)
        assertNull(graph.repository.runtime.value.resumeOffer)
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

    private suspend fun operationAfterConfirmedSaveFailure(command: NightActions.Command, fileName: String,
        cancel: Boolean = false, pendingEnd: Boolean = false, spentCleanupGrace: Boolean = false) {
        if (command != NightActions.Command.START_NIGHT || pendingEnd || spentCleanupGrace) {
            start()
            at("2026-10-21T23:00:00Z")
            if (command == NightActions.Command.RESUME) execute(NightActions.Command.SNOOZE_WARM)
            if (pendingEnd) {
                swallowOff = true
                assertEquals(OutcomeKind.UNKNOWN, execute(NightActions.Command.OFF).kind)
                swallowOff = false
                assertEquals(graph.repository.activeNight.value?.identity, graph.repository.runtime.value.endRequested)
            }
        }
        if (cancel) {
            val previous = graph.repository.activeNight.value
            val pending = PendingRetime("superseded-before-confirmation", previous?.identity ?: NightIdentity("old-night", 1),
                "demo", null, null, graph.now().plusHours(4).toString())
            graph.repository.updateRuntime { it.copy(retime = pending,
                slotWrite = SlotWrite("demo", graph.repository.settings.value.workingSlot, null,
                    Confidence.UNKNOWN, "Earlier slot write", displaced = previous?.identity)) }
            NightAlarms.scheduleRetry(app, pending, graph.now())
        }
        val deferredDelivery = if (spentCleanupGrace) LocalDelivery("delivery-awaiting-cleanup", NightAlarms.ACTION_WAKE_UP,
            graph.repository.activeNight.value!!.identity, graph.now().toString()) else null
        var elapsed = 0L
        if (deferredDelivery != null) {
            graph.repository.updateRuntime { it.copy(localDeliveries = it.localDeliveries + deferredDelivery) }
            graph.actions.elapsedMs = { elapsed }
        }
        val initialGeneration = graph.repository.runtime.value.generation
        val initialStarts = starts()
        var published: ActiveNight? = null
        var failed = false
        var failedClearingOldEnd = false
        val diskFailure = IOException("test local record disk failure")
        val cancellation = CancellationException("test owner cancellation after confirmation")
        val owner = Job(currentCoroutineContext()[Job])
        beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.toString(Charsets.UTF_8))
                    ?.takeIf { it.confidence == Confidence.CONFIRMED && !it.trackingManual }?.let { published = it }
            }
            val confirmed = published
            if (file.name == fileName && confirmed != null && graph.repository.activeNight.value == confirmed) {
                failed = true
                failedClearingOldEnd = graph.repository.runtime.value.endRequested?.let { it != confirmed.identity } == true
                if (cancel) {
                    // Cancellation is one owner-lifecycle event, not a permanently broken storage device.
                    beforeFileWrite = null
                    if (spentCleanupGrace) elapsed = 98_000L
                    owner.cancel(cancellation)
                    throw cancellation
                } else throw diskFailure
            }
        }
        var thrown: Throwable? = null
        val outcome = try { withContext(owner) { execute(command) } } catch (e: Throwable) { thrown = e; null }
        finally { beforeFileWrite = null; owner.complete() }
        assertTrue("The injected persistence failure must follow the exact CONFIRMED publication", failed)
        if (pendingEnd) assertTrue("The failure must occur while the confirmed publication clears the older Off marker", failedClearingOldEnd)
        assertEquals(published, graph.repository.activeNight.value)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        assertEquals("A local save failure must not repeat the hardware start", initialStarts + 1, starts())
        if (cancel) {
            assertTrue("Owner cancellation still propagates after confirmation", thrown is CancellationException)
            assertNull(outcome)
            if (deferredDelivery != null) {
                assertEquals("Spent cleanup grace must leave local deliveries for their own retry", listOf(deferredDelivery),
                    graph.repository.runtime.value.localDeliveries)
                assertTrue("The deferred local delivery must retain its retry", shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
                    shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_LOCAL_RETRY
                })
            }
            val confirmed = published!!
            val alarms = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
            listOf(NightAlarms.ACTION_WAKE_UP to confirmed.wakeUpStart, NightAlarms.ACTION_NIGHT_END to confirmed.endsAt).forEach { (action, at) ->
                val scheduled = alarms.singleOrNull { shadowOf(it.operation).savedIntent.action == action }
                assertNotNull("The confirmed $command must schedule $action even after owner cancellation", scheduled)
                assertEquals(confirmed.identity, NightAlarms.identity(shadowOf(scheduled!!.operation).savedIntent))
                assertEquals(ZonedDateTime.parse(at).toInstant().toEpochMilli(), scheduled.triggerAtTime)
            }
            assertNull("The superseded retime journal must be cleared", graph.repository.runtime.value.retime)
            assertNull("The confirmed slot write settles the earlier uncertain write", graph.repository.runtime.value.slotWrite)
            assertFalse("The old retime must not remain scheduled", retryScheduled())
            assertEquals(initialGeneration + 1, graph.repository.runtime.value.generation)
            if (pendingEnd) assertNull("The older Off marker must be settled", graph.repository.runtime.value.endRequested)
            val record = graph.repository.history.value.single { it.id == confirmed.recordId }
            assertEquals("History must retain the exact confirmed programme", confirmed.applied, record.revisions.lastOrNull())
            assertEquals(confirmed.wakeAt, record.wakeAt)
            assertEquals(if (command == NightActions.Command.START_NIGHT) EventKind.STARTED else EventKind.RETIMED, record.events.lastOrNull()?.kind)
            if (command == NightActions.Command.RESUME) {
                assertNull("A confirmed Resume consumes its offer despite cancellation", graph.repository.runtime.value.resumeOffer)
                val beforeSecondTap = programmeWrites()
                assertEquals(OutcomeKind.REFUSED, execute(NightActions.Command.RESUME).kind)
                assertEquals("A second Resume tap must not upload or restart", beforeSecondTap, programmeWrites())
            }
            val settledRuntime = graph.repository.runtime.value
            reload()
            assertEquals("The completed journal must survive restart", settledRuntime, graph.repository.runtime.value)
            assertEquals("The confirmed history revision must be durable", record,
                graph.repository.history.value.single { it.id == confirmed.recordId })
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
    @Test fun startKeepsConfirmationWhenClearingAnOlderOffMarkerFails() = runBlocking {
        operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "runtime.json", pendingEnd = true)
    }
    @Test fun startStillPropagatesCancellationWhenClearingAnOlderOffMarkerFails() = runBlocking {
        operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "runtime.json", cancel = true, pendingEnd = true)
    }
    @Test fun startStillPropagatesCancellationAfterConfirmation() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "history.json", cancel = true) }
    @Test fun reapplyStillPropagatesCancellationAfterConfirmation() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.REAPPLY, "history.json", cancel = true) }
    @Test fun resumeStillPropagatesCancellationAfterConfirmation() = runBlocking { operationAfterConfirmedSaveFailure(NightActions.Command.RESUME, "history.json", cancel = true) }
    @Test fun confirmedCancellationDefersLocalDeliveryWhenCleanupGraceIsSpent() = runBlocking {
        operationAfterConfirmedSaveFailure(NightActions.Command.START_NIGHT, "history.json", cancel = true, spentCleanupGrace = true)
    }
}
