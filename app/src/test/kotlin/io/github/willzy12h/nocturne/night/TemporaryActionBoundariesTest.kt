package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Real owner, repository and demo boundaries. No later reconciliation repairs these assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryActionBoundariesTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private lateinit var link: BedJetLink
    private val sent = mutableListOf<ByteArray>()
    private var connects = 0
    private var queries = 0
    private var beforeConnect: suspend () -> Unit = {}
    private var afterSend: suspend (ByteArray) -> Unit = {}
    @Volatile private var beforeFileWrite: ((File, ByteArray) -> Unit)? = null
    @Volatile private var afterFileWrite: ((File, ByteArray) -> Unit)? = null
    private var holdNextStartReports = false
    private var heldStatus: MutableStateFlow<DeviceStatus?>? = null
    private var heldReports: MutableStateFlow<SourcedStatus?>? = null
    private val warm = ActionPreset(Mode.HEAT, 64, 30, 15)
    private val turbo = ActionPreset(Mode.TURBO, 86, 100, 10)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val alarmIntent by lazy {
        PendingIntent.getBroadcast(app, 175, Intent("test.temporary.alarm"), PendingIntent.FLAG_IMMUTABLE)
    }

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        link = object : BedJetLink by demo {
            override suspend fun connect() { connects++; beforeConnect(); demo.connect() }
            override suspend fun query(request: Int): ByteArray { queries++; return demo.query(request) }
            override suspend fun send(command: ByteArray) {
                sent += command.copyOf()
                demo.send(command)
                afterSend(command)
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
                beforeWrite()
                send(command)
            }
            override val status: StateFlow<DeviceStatus?> get() = heldStatus ?: demo.status
            override val sourcedStatus: Flow<SourcedStatus?> get() = heldReports ?: demo.sourcedStatus
            override fun latestSourcedStatus(): SourcedStatus? =
                heldReports?.value ?: demo.latestSourcedStatus()
            override suspend fun prepareForStart() {
                if (holdNextStartReports) {
                    holdNextStartReports = false
                    val cached = demo.latestSourcedStatus()!!
                    heldStatus = MutableStateFlow(cached.status)
                    heldReports = MutableStateFlow(cached)
                }
                demo.prepareForStart()
            }
        }
        installGraph(Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC))
        graph.repository.updateSettings { it.copy(demoMode = true,
            alarmPackages = setOf("approved.clock"), acceptUnknownAlarmSource = false) }
    }

    private fun installGraph(clock: Clock) {
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
                afterFileWrite?.invoke(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = clock
        graph.actions.confirmMs = 60_000
        graph.actions.freshStatusMs = 60_000
    }

    private suspend fun start(profile: Profile = Presets.summerCool): ActiveNight {
        graph.repository.saveProfile(profile)
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT, profileId = profile.id))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(30))
        demo.tick()
        sent.clear()
        connects = 0
        queries = 0
        return graph.repository.activeNight.value!!
    }

    private fun fragmentedProfile() = Presets.summerCool.copy(id = "fragmented-night",
        middle = Presets.summerCool.middle.copy(rampSteps = 8))

    private fun diskNight(): ActiveNight? = BackupCodec.json.decodeFromString(
        ActiveNight.serializer().nullable, File(app.filesDir, "active-night.json").readText())

    private fun diskRuntime(): RuntimeState = BackupCodec.json.decodeFromString(
        RuntimeState.serializer(), File(app.filesDir, "runtime.json").readText())

    private fun diskHistory(): List<NightRecord> = BackupCodec.json.decodeFromString(
        ListSerializer(NightRecord.serializer()), File(app.filesDir, "history.json").readText())

    private fun assertDurableNight(expected: ActiveNight?) {
        assertEquals(expected, graph.repository.activeNight.value)
        assertEquals("The caller must see a durable result before any reconciliation", expected, diskNight())
    }

    private fun starts(slot: Int) = sent.count { it.contentEquals(Codec.startProgram(slot)) }
    private fun isProgrammeFragment(command: ByteArray) = command.size > 2 &&
        (command[0].toInt() and 0xff) == 0x40 && (command[1].toInt() and 0xff) in 7 until 19

    private fun record(night: ActiveNight) = graph.repository.history.value.single { it.id == night.recordId }

    @Test fun cancellingAsTemporaryPendingPublishesRestoresTheDurablePriorNight() = runBlocking {
        val before = start()
        val history = graph.repository.history.value
        val owner = Job(currentCoroutineContext()[Job])
        var savedPending = false
        afterFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                val next = BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.decodeToString())
                if (next?.confidence == Confidence.PENDING && next.pending?.kind == ChangeKind.TEMPORARY) {
                    savedPending = diskNight() == next
                    afterFileWrite = null
                    owner.cancel(CancellationException("cancel after durable temporary PENDING"))
                }
            }
        }
        val failure = try { runCatching {
            withContext(owner) { graph.actions.execute(graph.actions.captureTemporary(warm)) }
        }.exceptionOrNull() } finally { afterFileWrite = null; owner.complete() }

        assertTrue("Exercise cancellation after PENDING really reached disk", savedPending)
        assertTrue(failure is CancellationException)
        assertTrue("No programme or clock packet belongs before this boundary", sent.isEmpty())
        assertDurableNight(before)
        assertEquals(history, graph.repository.history.value)
        assertEquals(history, diskHistory())
    }

    @Test fun cancellingAfterAnAcceptedProgrammeFragmentSettlesUnknownWithoutStarting() = runBlocking {
        val before = start(fragmentedProfile())
        val request = graph.actions.captureTemporary(warm)
        val preview = graph.actions.previewTemporary(request)
        assertNull("The multi-fragment fixture must be eligible before cancellation", preview.refusal)
        assertNotNull(preview.plan)
        assertTrue("Use a real multi-fragment upload", preview.plan!!.steps.size > 8)
        val slots = demo.slots.toList()
        val history = graph.repository.history.value
        val fragmentWritten = CompletableDeferred<Unit>()
        afterSend = { command -> if (isProgrammeFragment(command)) {
            afterSend = {}
            fragmentWritten.complete(Unit)
            awaitCancellation()
        } }
        val work = async { graph.actions.execute(request) }
        try {
            withTimeout(5_000) { fragmentWritten.await() }
            assertEquals(1, sent.count(::isProgrammeFragment))
            assertEquals("The first fragment has not completed the demo slot", slots, demo.slots.toList())
            work.cancelAndJoin()

            val settled = graph.repository.activeNight.value!!
            assertEquals(before.identity, settled.identity)
            assertEquals(before.applied, settled.applied)
            assertEquals("An accepted partial upload cannot remain PENDING or confirmed", Confidence.UNKNOWN, settled.confidence)
            assertDurableNight(settled)
            assertEquals(0, starts(before.slot))
            assertEquals(history, graph.repository.history.value)
        } finally { afterSend = {}; work.cancelAndJoin() }
    }

    @Test fun cancellationAsConfirmedTemporaryPublishesKeepsOneExactRevisionAndStart() = runBlocking {
        val before = start()
        val owner = Job(currentCoroutineContext()[Job])
        var published: ActiveNight? = null
        afterFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                val next = BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.decodeToString())
                if (next?.confidence == Confidence.CONFIRMED && next.temporary != null && next.revision == before.revision + 1) {
                    published = diskNight()
                    afterFileWrite = null
                    owner.cancel(CancellationException("cancel after durable temporary confirmation"))
                }
            }
        }
        val failure = try { runCatching {
            withContext(owner) { graph.actions.execute(graph.actions.captureTemporary(warm)) }
        }.exceptionOrNull() } finally { afterFileWrite = null; owner.complete() }

        assertNotNull("The exact confirmed programme must have reached disk", published)
        assertTrue(failure is CancellationException)
        val confirmed = published!!
        assertDurableNight(confirmed)
        assertEquals(Confidence.CONFIRMED, confirmed.confidence)
        assertNull(confirmed.pending)
        assertEquals(before.recordId, confirmed.recordId)
        assertEquals(before.wakeAt, confirmed.wakeAt)
        assertEquals(confirmed.applied.programSteps, demo.slots[confirmed.slot]!!.steps)
        assertEquals("Local repair must not restart the programme", 1, starts(before.slot))
        assertEquals(1, record(confirmed).events.count { it.kind == EventKind.TEMPORARY })
        assertEquals(1, record(confirmed).revisions.count { it.revision == confirmed.revision })
        assertEquals(graph.repository.history.value, diskHistory())
        assertEquals(graph.repository.runtime.value, diskRuntime())
    }

    @Test fun aPhysicalOffBeforeTemporaryPreparationCannotBeUndone() = runBlocking {
        val before = start()
        val request = graph.actions.captureTemporary(warm)
        val slots = demo.slots.toList()
        demo.send(Codec.setMode(Mode.STANDBY)!!)

        val result = graph.actions.execute(request)

        assertEquals(OutcomeKind.UNKNOWN, result.kind)
        assertTrue("A fresh physical Off must prevent even the slot upload", sent.isEmpty())
        assertEquals(slots, demo.slots.toList())
        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        assertEquals(Confidence.UNKNOWN, graph.repository.activeNight.value!!.confidence)
        assertEquals(before.identity, graph.repository.activeNight.value!!.identity)
        assertDurableNight(graph.repository.activeNight.value)
        assertTrue(record(before).events.none { it.kind == EventKind.TEMPORARY })
    }

    @Test fun aPhysicalOffDuringTheUploadCannotBeUndoneByItsFinalStart() = runBlocking {
        val before = start(fragmentedProfile())
        var physicalOff = false
        afterSend = { command -> if (isProgrammeFragment(command) && !physicalOff) {
            physicalOff = true
            demo.send(Codec.setMode(Mode.STANDBY)!!)
        } }

        val result = try { graph.actions.execute(graph.actions.captureTemporary(warm)) } finally { afterSend = {} }

        assertTrue("Exercise Off after the demo accepted a real upload fragment", physicalOff)
        assertEquals(OutcomeKind.UNKNOWN, result.kind)
        assertEquals(0, starts(before.slot))
        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        val settled = graph.repository.activeNight.value!!
        assertEquals(Confidence.UNKNOWN, settled.confidence)
        assertEquals(before.identity, settled.identity)
        assertEquals(before.applied, settled.applied)
        assertDurableNight(settled)
        assertTrue(record(before).events.none { it.kind == EventKind.TEMPORARY })
    }

    /** The injected link has no automatic tick. Advance the unit independently of app command delivery. */
    private suspend fun advanceAndTick(at: ZonedDateTime) {
        val elapsed = Duration.between(graph.now(), at)
        require(!elapsed.isNegative)
        graph.clock = Clock.fixed(at.toInstant(), ZoneOffset.UTC)
        shadowOf(Looper.getMainLooper()).idleFor(elapsed)
        demo.tick()
        // A local observation of the simulated unit is not an app-owned programme command.
        demo.connect()
    }

    @Test fun theStoredProgrammeReturnsToTheOriginalTimelineWithoutAPhoneCommand() = runBlocking {
        val before = start()
        val result = graph.actions.execute(graph.actions.captureTemporary(warm))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val temporary = graph.repository.activeNight.value!!
        val returnsAt = ZonedDateTime.parse("2026-10-21T23:15:00Z")
        assertEquals(returnsAt.toInstant(), ZonedDateTime.parse(temporary.temporary!!.until).toInstant())
        assertEquals(before.applied, temporary.temporary!!.base)
        assertEquals(Instant.parse("2026-10-22T07:00:00Z"), ZonedDateTime.parse(temporary.wakeAt).toInstant())
        assertEquals(1, starts(before.slot))
        assertTrue("The phone must not schedule a return command at the temporary boundary",
            shadowOf(alarms).scheduledAlarms.none { it.triggerAtTime == returnsAt.toInstant().toEpochMilli() })
        val history = graph.repository.history.value
        sent.clear()

        advanceAndTick(returnsAt)
        assertEquals("The saved middle phase resumes on the unit", Mode.COOL, demo.status.value!!.mode)
        assertEquals(44, demo.status.value!!.targetTemperature)
        advanceAndTick(ZonedDateTime.parse("2026-10-22T06:40:00Z"))
        assertEquals("The original wake-up phase retains its absolute start", Mode.HEAT, demo.status.value!!.mode)
        assertEquals(60, demo.status.value!!.targetTemperature)
        advanceAndTick(ZonedDateTime.parse("2026-10-22T07:00:00Z"))
        assertEquals("The original after-wake phase retains the saved wake", Mode.COOL, demo.status.value!!.mode)
        assertEquals(44, demo.status.value!!.targetTemperature)
        assertTrue("Returning and reaching wake must not send another phone command", sent.isEmpty())
        assertEquals(before.wakeAt, graph.repository.activeNight.value!!.wakeAt)
        assertEquals("Clock passage alone cannot invent an observed resume event", history, graph.repository.history.value)
    }

    @Test fun aPhysicalOffAfterTemporaryConfirmationCannotBeRestartedAtThePlannedReturn() = runBlocking {
        start()
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val until = ZonedDateTime.parse(graph.repository.activeNight.value!!.temporary!!.until)
        graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(3))
        demo.tick()
        demo.send(Codec.setMode(Mode.STANDBY)!!)
        demo.disconnect() // The phone does not observe the physical Off before the planned return.
        sent.clear()

        advanceAndTick(until.plusMinutes(2))

        assertEquals(Mode.STANDBY, demo.status.value!!.mode)
        assertEquals(0, demo.status.value!!.extra!!.programStep)
        assertTrue("No delayed phone action may restart a programme after an unobserved Off", sent.isEmpty())
    }

    @Test fun aQueuedTemporaryRequestCannotFollowAChangedTarget() = runBlocking {
        val before = start()
        val request = graph.actions.captureTemporary(turbo)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val connectionsBefore = connects
        val work = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.execute(request) }
        try {
            assertEquals(1, graph.actions.busy.value.queuedCount)
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = DeviceId.ble("AA:BB:CC:DD:EE:09")) }
            release.complete(Unit)
            holder.await()
            assertEquals(OutcomeKind.STALE, work.await().kind)
            assertEquals(connectionsBefore, connects)
            assertTrue(sent.isEmpty())
            assertDurableNight(before)
        } finally { release.complete(Unit); holder.cancelAndJoin(); work.cancelAndJoin() }
    }

    @Test fun aQueuedTemporaryRequestCannotReplaceADifferentNightWithTheSameRevision() = runBlocking {
        val before = start()
        val request = graph.actions.captureTemporary(turbo)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val work = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.execute(request) }
        try {
            assertEquals(1, graph.actions.busy.value.queuedCount)
            val newer = before.copy(recordId = "different-night-same-revision")
            graph.repository.setActiveNight(newer)
            release.complete(Unit)
            holder.await()
            assertEquals(OutcomeKind.STALE, work.await().kind)
            assertTrue(sent.isEmpty())
            assertDurableNight(newer)
        } finally { release.complete(Unit); holder.cancelAndJoin(); work.cancelAndJoin() }
    }

    @Test fun invalidCapturedPresetsAreRefusedBeforeConnectionOrPublication() = runBlocking {
        val before = start()
        val history = graph.repository.history.value
        val invalid = listOf(
            warm.copy(mode = Mode.STANDBY), warm.copy(mode = Mode.WAIT),
            warm.copy(temperature = 37), warm.copy(temperature = 87),
            warm.copy(fanPercent = 4), warm.copy(fanPercent = 101),
            warm.copy(minutes = 0), warm.copy(minutes = 121), turbo.copy(minutes = 31),
        )
        for (preset in invalid) {
            val request = graph.actions.captureTemporary(preset)
            assertNotNull("Invalid captured action: $preset", graph.actions.previewTemporary(request).refusal)
            assertEquals("Invalid captured action: $preset", OutcomeKind.REFUSED, graph.actions.execute(request).kind)
            assertDurableNight(before)
        }
        assertEquals(0, connects)
        assertEquals(0, queries)
        assertTrue(sent.isEmpty())
        assertEquals(history, graph.repository.history.value)
    }

    @Test fun unreadableOriginalsRefuseTemporaryBeforeAnyConnectionOrPendingNight() = runBlocking {
        val before = start()
        val clock = graph.clock
        val slots = demo.slots.toList()
        val history = graph.repository.history.value
        graphs.afterFinishing { File(app.filesDir, "slot-backups.json").writeText("{ broken") }
        installGraph(clock)
        assertTrue(graph.repository.originalsBlocked.value)
        assertNull(graph.repository.originalSlot("demo", before.slot))

        val result = graph.actions.execute(graph.actions.captureTemporary(warm))

        assertEquals(OutcomeKind.REFUSED, result.kind)
        assertEquals(app.getString(R.string.runtime_slot_backups_unreadable), result.message)
        assertEquals(0, connects)
        assertEquals(0, queries)
        assertTrue(sent.isEmpty())
        assertEquals(slots, demo.slots.toList())
        assertDurableNight(before)
        assertEquals(history, graph.repository.history.value)
    }

    private fun alarm(at: String) {
        shadowOf(alarmIntent).setCreatorPackage("approved.clock")
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(Instant.parse(at).toEpochMilli(), alarmIntent), alarmIntent)
    }

    /** Reach a real prepared re-time through the owner, then lose the connection before any rewrite. */
    private suspend fun pendingWakeChange(at: String = "2026-10-22T06:30:00Z"): PendingRetime {
        alarm(at)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        beforeConnect = { beforeConnect = {}; throw IOException("test radio unavailable before re-time upload") }
        val result = try { graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)) }
        finally { beforeConnect = {} }
        assertEquals(OutcomeKind.FAILED, result.kind)
        val pending = graph.repository.runtime.value.retime!!
        assertEquals(Instant.parse(at), ZonedDateTime.parse(pending.alarmAt).toInstant())
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertTrue("The pending fixture must not have uploaded or started anything", sent.isEmpty())
        return pending
    }

    private fun assertObservationHandoff(night: ActiveNight) {
        val runtime = graph.repository.runtime.value
        assertNull("The previous revision's prepared request cannot survive confirmation", runtime.retime)
        val observed = runtime.alarmInbox!!
        assertEquals(night.recordId, observed.nightId)
        assertEquals(night.deviceId, observed.deviceId)
        assertEquals(runtime, diskRuntime())
        val retry = shadowOf(alarms).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY
        }
        assertNull("The retry must read the current revision, not deliver the obsolete identity",
            NightAlarms.identity(shadowOf(retry.operation).savedIntent))
    }

    private suspend fun applyObservedWake(expected: String): ActiveNight {
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val updated = graph.repository.activeNight.value!!
        assertEquals(Instant.parse(expected), ZonedDateTime.parse(updated.wakeAt).toInstant())
        assertNull(graph.repository.runtime.value.retime)
        assertNull(graph.repository.runtime.value.alarmInbox)
        assertDurableNight(updated)
        return updated
    }

    @Test fun aPendingWakeChangeIsReobservedForTheConfirmedTemporaryRevision() = runBlocking {
        val before = start()
        val pending = pendingWakeChange()
        assertEquals(before.identity, pending.expected)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val temporary = graph.repository.activeNight.value!!
        assertEquals(before.revision + 1, temporary.revision)
        assertEquals(before.wakeAt, temporary.wakeAt)
        assertObservationHandoff(temporary)

        val updated = applyObservedWake("2026-10-22T06:30:00Z")

        assertEquals(temporary.revision + 1, updated.revision)
        assertEquals(ZonedDateTime.parse(temporary.temporary!!.until).toInstant(),
            ZonedDateTime.parse(updated.temporary!!.until).toInstant())
        assertEquals(2, starts(before.slot))
        assertEquals(1, record(updated).events.count { it.kind == EventKind.TEMPORARY })
    }

    @Test fun aNewerAlarmInboxSurvivesTemporaryConfirmationAlongsideAnOlderPendingRetime() = runBlocking {
        val before = start()
        pendingWakeChange()
        alarm("2026-10-22T06:15:00Z")
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val newer = graph.repository.runtime.value.alarmInbox!!
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val temporary = graph.repository.activeNight.value!!
        assertEquals("Do not replace a newer observation with the old prepared alarm", newer, graph.repository.runtime.value.alarmInbox)
        assertObservationHandoff(temporary)

        val updated = applyObservedWake("2026-10-22T06:15:00Z")

        assertEquals(temporary.revision + 1, updated.revision)
        assertEquals(2, starts(before.slot))
        assertEquals(1, record(updated).events.count { it.kind == EventKind.TEMPORARY })
    }

    @Test fun successfulTemporaryKeepsTheQuietNotificationPendingUntilItsAlarmObservationIsApplied() = runBlocking {
        graph.repository.updateSettings { it.copy(quietNightNotification = true) }
        val before = start()
        pendingWakeChange()
        alarm("2026-10-22T06:15:00Z")
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val observed = graph.repository.runtime.value.alarmInbox!!
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        fun quietTitle() = notifications.getNotification(6)?.extras?.getString(Notification.EXTRA_TITLE)
        val pendingTitle = app.getString(R.string.quiet_pending,
            ZonedDateTime.parse(before.wakeAt).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
        assertTrue(graph.actions.wakeChangePending())
        graph.actions.refreshQuietNotification()
        assertEquals(pendingTitle, quietTitle())

        val result = graph.actions.execute(graph.actions.captureTemporary(warm))

        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        val temporary = graph.repository.activeNight.value!!
        assertEquals(before.wakeAt, temporary.wakeAt)
        assertEquals(observed, graph.repository.runtime.value.alarmInbox)
        assertObservationHandoff(temporary)
        assertTrue(graph.actions.wakeChangePending())
        // The pending flag remains true across this normal completion. A later observer refresh
        // must not be required to repair a newly posted but misleading plain wake-up title.
        assertEquals("The successful temporary action must still describe the unconfirmed alarm change",
            pendingTitle, quietTitle())
        assertEquals(1, starts(before.slot))
    }

    @Test fun cancellationAsConfirmedAlarmHandoffPublishesKeepsItsObservationAndOneTemporaryEvent() = runBlocking {
        val before = start()
        pendingWakeChange()
        val owner = Job(currentCoroutineContext()[Job])
        var handedOff: AlarmObservation? = null
        var confirmed: ActiveNight? = null
        afterFileWrite = { file, bytes ->
            if (file.name == "runtime.json") {
                val next = BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.decodeToString())
                val night = graph.repository.activeNight.value
                if (night?.temporary != null && night.confidence == Confidence.CONFIRMED &&
                    next.retime == null && next.alarmInbox != null) {
                    handedOff = diskRuntime().alarmInbox
                    confirmed = night
                    afterFileWrite = null
                    owner.cancel(CancellationException("cancel after durable confirmed alarm handoff"))
                }
            }
        }
        val failure = try { runCatching {
            withContext(owner) { graph.actions.execute(graph.actions.captureTemporary(warm)) }
        }.exceptionOrNull() } finally { afterFileWrite = null; owner.complete() }

        assertNotNull("Cancel only after the confirmed revision's alarm handoff is durable", handedOff)
        assertTrue(failure is CancellationException)
        assertDurableNight(confirmed!!)
        assertEquals(handedOff, graph.repository.runtime.value.alarmInbox)
        assertObservationHandoff(confirmed!!)
        assertEquals(1, starts(before.slot))
        assertEquals(1, record(before).events.count { it.kind == EventKind.TEMPORARY })
        assertEquals(graph.repository.history.value, diskHistory())

        assertEquals(confirmed!!.revision + 1, applyObservedWake("2026-10-22T06:30:00Z").revision)
        assertEquals("Settling the handoff cannot duplicate the temporary start", 2, starts(before.slot))
    }

    @Test fun restartingAfterConfirmedTemporaryPublicationRetainsThePendingAlarmObservation() = runBlocking {
        val before = start()
        val pending = pendingWakeChange()
        val clock = graph.clock
        var confirmed: ActiveNight? = null
        var crashFiles: Map<String, ByteArray>? = null
        afterFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                val next = BackupCodec.json.decodeFromString(ActiveNight.serializer().nullable, bytes.decodeToString())
                if (next?.confidence == Confidence.CONFIRMED && next.temporary != null && next.revision == before.revision + 1) {
                    confirmed = next
                    crashFiles = listOf("active-night.json", "runtime.json", "history.json").associateWith {
                        File(app.filesDir, it).readBytes()
                    }
                    afterFileWrite = null
                }
            }
        }
        val result = try { graph.actions.execute(graph.actions.captureTemporary(warm)) }
        finally { afterFileWrite = null }
        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertNotNull("Capture the durable confirmation before subsequent runtime/history settlement", crashFiles)
        val captured = crashFiles!!
        val interruptedRuntime = BackupCodec.json.decodeFromString(RuntimeState.serializer(),
            captured.getValue("runtime.json").decodeToString())
        assertEquals("The crash snapshot still contains the preceding revision's prepared alarm", pending, interruptedRuntime.retime)
        assertNotEquals(pending.expected, confirmed!!.identity)

        // Let normal completion finish first, then discard all its later local writes. This exercises process
        // recovery from the captured files, rather than the in-process cancellation repair above.
        graphs.afterFinishing {
            captured.forEach { (name, bytes) -> Repository.writeDurably(File(app.filesDir, name), bytes) }
            // Do not let the normal completion's later retry alarm make this recovery assertion pass.
            NightAlarms.cancel(app)
        }
        installGraph(clock)
        sent.clear()
        assertDurableNight(confirmed)
        val interruptedHistory = record(confirmed!!)
        assertFalse("The crash is before History receives the confirmed temporary revision",
            confirmed!!.applied in interruptedHistory.revisions)
        graph.actions.reconcile()

        assertDurableNight(confirmed)
        assertTrue("History must recover the programme already confirmed before the crash",
            confirmed!!.applied in record(confirmed!!).revisions)
        assertEquals("Recovery cannot invent a new command event", interruptedHistory.events, record(confirmed!!).events)
        assertEquals(graph.repository.history.value, diskHistory())
        val observation = graph.repository.runtime.value.alarmInbox
        assertNotNull("A durably confirmed temporary revision must retain the pending alarm across restart", observation)
        assertEquals(confirmed!!.recordId, observation!!.nightId)
        assertEquals(confirmed!!.deviceId, observation.deviceId)
        assertEquals(graph.repository.runtime.value, diskRuntime())
        val retry = shadowOf(alarms).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY
        }
        assertNull("Recovery must reobserve the new revision rather than replay the old request",
            NightAlarms.identity(shadowOf(retry.operation).savedIntent))
        assertTrue("Recovering a confirmed programme must not send a second temporary START", sent.isEmpty())
        val repairedHistory = graph.repository.history.value
        graph.actions.reconcile()
        assertEquals("The recovered revision is recorded only once", repairedHistory, graph.repository.history.value)
        assertEquals(repairedHistory, diskHistory())
        assertTrue(sent.isEmpty())

        val updated = applyObservedWake("2026-10-22T06:30:00Z")

        assertEquals(confirmed!!.revision + 1, updated.revision)
        assertEquals(ZonedDateTime.parse(confirmed!!.temporary!!.until).toInstant(),
            ZonedDateTime.parse(updated.temporary!!.until).toInstant())
        assertEquals("Only the actual alarm re-time starts a new programme after recovery", 1, starts(before.slot))
    }

    @Test fun failedHistoryRecoveryStillRestoresTheConfirmedNightAndAlarmObservationSchedules() = runBlocking {
        val original = start()
        val originalRecord = record(original)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val confirmed = graph.repository.activeNight.value!!
        graph.repository.addRecord(originalRecord)
        val interruptedHistory = graph.repository.history.value
        assertFalse(confirmed.applied in record(confirmed).revisions)
        alarm("2026-10-22T06:30:00Z")
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val observation = graph.repository.runtime.value.alarmInbox!!
        assertEquals(confirmed.recordId, observation.nightId)
        assertEquals(confirmed.deviceId, observation.deviceId)
        NightAlarms.cancel(app)
        val ownedAlarmActions = setOf(NightAlarms.ACTION_WAKE_UP, NightAlarms.ACTION_NIGHT_END, NightAlarms.ACTION_RETRY)
        assertTrue(shadowOf(alarms).scheduledAlarms.none { shadowOf(it.operation).savedIntent.action in ownedAlarmActions })
        sent.clear()
        connects = 0
        queries = 0
        var historyWriteAttempts = 0
        beforeFileWrite = { file, _ ->
            if (file.name == "history.json") {
                historyWriteAttempts++
                throw IOException("test history storage unavailable before durable write")
            }
        }

        val failure = try { runCatching { graph.actions.reconcile() }.exceptionOrNull() }
        finally { beforeFileWrite = null }

        assertEquals("Exercise the missing revision's attempted durable repair", 1, historyWriteAttempts)
        assertNull("A failed history repair must not abort restoration of the confirmed night's alarms", failure)
        assertDurableNight(confirmed)
        assertEquals(interruptedHistory, graph.repository.history.value)
        assertEquals(interruptedHistory, diskHistory())
        assertEquals(observation, graph.repository.runtime.value.alarmInbox)
        assertEquals(graph.repository.runtime.value, diskRuntime())
        fun scheduled(action: String) = shadowOf(alarms).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == action
        }
        val wake = scheduled(NightAlarms.ACTION_WAKE_UP)
        assertEquals(confirmed.identity, NightAlarms.identity(shadowOf(wake.operation).savedIntent))
        assertEquals(ZonedDateTime.parse(confirmed.wakeUpStart).toInstant().toEpochMilli(), wake.triggerAtTime)
        val end = scheduled(NightAlarms.ACTION_NIGHT_END)
        assertEquals(confirmed.identity, NightAlarms.identity(shadowOf(end.operation).savedIntent))
        assertEquals(ZonedDateTime.parse(confirmed.endsAt).toInstant().toEpochMilli(), end.triggerAtTime)
        val retry = scheduled(NightAlarms.ACTION_RETRY)
        assertNull("The preserved inbox must reobserve the current revision",
            NightAlarms.identity(shadowOf(retry.operation).savedIntent))
        assertEquals(graph.now().plusMinutes(10).toInstant().toEpochMilli(), retry.triggerAtTime)
        assertTrue(sent.isEmpty())
        assertEquals(0, connects)
        assertEquals(0, queries)

        graph.actions.reconcile()

        val repairedHistory = graph.repository.history.value
        assertEquals(1, record(confirmed).revisions.count { it == confirmed.applied })
        assertTrue(record(confirmed).revisions.containsAll(originalRecord.revisions))
        assertEquals("Recovery cannot invent a new command event", originalRecord.events, record(confirmed).events)
        assertEquals(repairedHistory, diskHistory())
        graph.actions.reconcile()
        assertEquals("The recovered revision is recorded only once", repairedHistory, graph.repository.history.value)
        assertEquals(repairedHistory, diskHistory())
        assertDurableNight(confirmed)
        assertEquals(observation, graph.repository.runtime.value.alarmInbox)
        assertTrue("Local history recovery cannot resend a confirmed programme", sent.isEmpty())
        assertEquals(0, connects)
        assertEquals(0, queries)
    }

    @Test fun reconcileDoesNotRecreateAnAbsentHistoryRecord() = runBlocking {
        start()
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val confirmed = graph.repository.activeNight.value!!
        val clock = graph.clock
        graphs.afterFinishing {
            Repository.writeDurably(File(app.filesDir, "history.json"), "[]".toByteArray())
        }
        installGraph(clock)
        sent.clear()
        connects = 0
        queries = 0

        graph.actions.reconcile()

        assertDurableNight(confirmed)
        assertTrue(graph.repository.history.value.isEmpty())
        assertTrue(diskHistory().isEmpty())
        assertTrue(sent.isEmpty())
        assertEquals(0, connects)
        assertEquals(0, queries)
    }

    private suspend fun assertUntrustedSnapshotIsNotBackfilled(confidence: Confidence, manual: Boolean) {
        val original = start()
        val originalRecord = record(original)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val confirmed = graph.repository.activeNight.value!!
        val excluded = confirmed.copy(confidence = confidence, trackingManual = manual)
        graph.repository.addRecord(originalRecord)
        graph.repository.setActiveNight(excluded)
        val history = graph.repository.history.value
        assertFalse(confirmed.applied in record(excluded).revisions)
        sent.clear()
        connects = 0
        queries = 0

        graph.actions.reconcile()

        assertDurableNight(excluded)
        assertEquals(history, graph.repository.history.value)
        assertEquals(history, diskHistory())
        assertFalse(confirmed.applied in record(excluded).revisions)
        assertTrue(sent.isEmpty())
        assertEquals(0, connects)
        assertEquals(0, queries)
    }

    @Test fun reconcileDoesNotBackfillAnUnknownTemporarySnapshot() = runBlocking {
        assertUntrustedSnapshotIsNotBackfilled(Confidence.UNKNOWN, manual = false)
    }

    @Test fun reconcileDoesNotBackfillAManuallyTrackedSnapshot() = runBlocking {
        assertUntrustedSnapshotIsNotBackfilled(Confidence.CONFIRMED, manual = true)
    }

    @Test fun theSameWakeRemainsAQuietObservationDuringATemporaryProgramme() = runBlocking {
        start()
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.captureTemporary(warm)).kind)
        val before = graph.repository.activeNight.value!!
        val history = graph.repository.history.value
        alarm("2026-10-22T07:00:00Z")
        sent.clear()
        connects = 0
        queries = 0

        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val result = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))

        assertEquals(OutcomeKind.LOCAL, result.kind)
        assertEquals(app.getString(R.string.runtime_unchanged), result.message)
        assertDurableNight(before)
        assertEquals(history, graph.repository.history.value)
        assertFalse(graph.actions.retimePending)
        assertEquals(0, connects)
        assertEquals(0, queries)
        assertTrue(sent.isEmpty())
    }

    @Test fun aCachedMatchingFirstStepCannotConfirmTheTemporaryStart() = runBlocking {
        val profile = Presets.summerCool.copy(id = "matching-first-step",
            start = listOf(Phase(Mode.HEAT, 64, 30, 90)))
        val before = start(profile)
        assertEquals(1, demo.status.value!!.extra!!.programStep)
        assertEquals(Mode.HEAT, demo.status.value!!.mode)
        holdNextStartReports = true
        val started = CompletableDeferred<Unit>()
        afterSend = { if (it.contentEquals(Codec.startProgram(before.slot))) started.complete(Unit) }
        val work = async { graph.actions.execute(graph.actions.captureTemporary(warm)) }
        try {
            withTimeout(5_000) { started.await() }
            yield()
            val cached = heldReports!!.value!!
            assertEquals(1, cached.status.extra!!.programStep)
            assertEquals(Mode.HEAT, cached.status.mode)
            assertFalse("A matching cached report cannot confirm the new START", work.isCompleted)
            assertEquals(Confidence.PENDING, graph.repository.activeNight.value!!.confidence)
            assertTrue(record(before).events.none { it.kind == EventKind.TEMPORARY })
            val fresh = demo.latestSourcedStatus()!!
            assertTrue("Release the unit's actual post-START report", fresh.receiptOrder > cached.receiptOrder)
            heldReports!!.value = fresh
            heldStatus!!.value = fresh.status

            assertEquals(OutcomeKind.CONFIRMED, withTimeout(5_000) { work.await() }.kind)
            assertSame("Presentation or test delivery cannot stamp a replacement receipt", fresh, link.latestSourcedStatus())
            assertEquals(1, starts(before.slot))
            assertEquals(1, record(before).events.count { it.kind == EventKind.TEMPORARY })
        } finally { afterSend = {}; work.cancelAndJoin() }
    }

    @Test fun aCapturedFiveMinuteTurboEndChoiceRunsAndDescribesFiveMinutes() = runBlocking {
        // This case asserts durable real History through the existing injected single-device transport.
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val before = start()
        val request = graph.actions.capture(NightActions.Command.TURBO, requireIdentity = true)
            .copy(replacementConfirmed = true, actionPreset = turbo.copy(minutes = 5))

        val result = graph.actions.execute(request)

        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertDurableNight(null)
        assertEquals(Mode.TURBO, demo.status.value!!.mode)
        assertEquals(300, demo.status.value!!.remainingSeconds)
        assertTrue(result.message, result.message.contains("Turbo", ignoreCase = true))
        assertTrue(result.message, result.message.contains("5 min", ignoreCase = true))
        assertFalse("A captured shorter action must not repeat the legacy ten-minute claim",
            result.message.contains("10 min", ignoreCase = true))
        assertEquals(1, record(before).events.count { it.kind == EventKind.STOPPED })
    }

    @Test fun aCapturedCoolingEndChoiceUsesItsActualModeRatherThanTheWarmEntryLabel() = runBlocking {
        start()
        val cooling = ActionPreset(Mode.COOL, 44, 30, 5)
        val request = graph.actions.capture(NightActions.Command.WARM, requireIdentity = true)
            .copy(replacementConfirmed = true, actionPreset = cooling)

        val result = graph.actions.execute(request)

        assertEquals(result.message, OutcomeKind.CONFIRMED, result.kind)
        assertDurableNight(null)
        assertEquals(Mode.COOL, demo.status.value!!.mode)
        assertEquals(300, demo.status.value!!.remainingSeconds)
        assertTrue(result.message, result.message.contains("Cool", ignoreCase = true))
        assertTrue(result.message, result.message.contains("5 min", ignoreCase = true))
        assertFalse(result.message, result.message.contains("Warm", ignoreCase = true))
    }
}
