package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.*

/** IR-05, IR-06 and IR-23: outcomes and the runtime journal say what actually happened to the BedJet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TruthfulOutcomesTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    private var beforeSend: suspend (ByteArray) -> Unit = {}
    private var beforeQuery: suspend (Int) -> Unit = {}
    private var failBackups = false
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { graph.now() })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { beforeSend(command); sent += command; demo.send(command) }
            // The start byte goes through the guarded write, which must reach this link's own send.
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
            override suspend fun query(request: Int): ByteArray { beforeQuery(request); return demo.query(request) }
        }
    }

    @Before fun setup() = runBlocking { graph = load() }

    /** A Graph that loads what is on disk now, as after a restart. */
    private suspend fun load(): Graph {
        val clock = if (::graph.isInitialized) graph.clock else Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        val g = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, writeFile = { file, bytes ->
            if (failBackups && file.name == "slot-backups.json") throw IOException("Backup disk failure")
            Repository.writeDurably(file, bytes)
        }, now = now) } }
        g.clock = clock
        context.graph.clock = clock
        g.repository.updateSettings { it.copy(demoMode = true) }
        return g
    }
    private suspend fun start(): ActiveNight {
        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        return graph.repository.activeNight.value!!
    }
    private fun advance(minutes: Long) = at(graph.now().plusMinutes(minutes))
    private fun at(time: ZonedDateTime) {
        graph.clock = Clock.fixed(time.toInstant(), ZoneOffset.UTC); context.graph.clock = graph.clock
        demo.tick()
    }
    /** The saved originals are gone (not unreadable), so the next slot write reads the BedJet's slot again first. */
    private suspend fun forgetOriginals() {
        File(context.filesDir, "slot-backups.json").delete()
        graph = load()
        assertNull(graph.repository.originalSlot("demo", 2))
    }
    private fun moveAlarm(hours: Long): ZonedDateTime {
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = graph.now().plusHours(hours).withSecond(0).withNano(0)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), null), fire)
        return at
    }
    private fun retryScheduled() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .any { shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }
    /** [NightActions.status] is derived on the main looper. */
    private fun status(): Confidence? { shadowOf(android.os.Looper.getMainLooper()).idle(); return graph.actions.status.value.confidence }
    private fun isSlotWriteOrStart(b: ByteArray) = b[0] == 0x40.toByte() || (b[0] == 0x01.toByte() && b[1].toInt() and 0xff in 0x80..0x85)
    private fun events(n: ActiveNight) = graph.repository.history.value.first { it.id == n.recordId }.events

    // IR-05

    @Test fun aRetimeWhoseBackupReadFailsLeavesTheNightConfirmedAndItsWakeUpStillComes() = runBlocking {
        val n = start(); advance(30)
        forgetOriginals()
        moveAlarm(5)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val count = sent.size
        beforeQuery = { if (it == Codec.Query.PROGRAM_NAMES) throw BedJetError.BadResponse() }
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals(BedJetError.BadResponse().message, outcome.message)
        assertEquals("nothing written", count, sent.size)
        assertEquals("the night is exactly as it was", n, graph.repository.activeNight.value)
        assertTrue("a failure with nothing written is tried again", retryScheduled())
        assertTrue(graph.actions.retimePending)
        // The wake-up still comes for the night the BedJet is running.
        beforeQuery = {}
        at(ZonedDateTime.parse(n.wakeUpStart).plusSeconds(1))
        graph.actions.wakeUp(n.identity)
        assertEquals(n.identity, graph.repository.runtime.value.wakeShown)
    }

    @Test fun aRetimeRetriedAfterAFailureWithNothingWrittenAppliesTheChange() = runBlocking {
        val n = start(); advance(30)
        forgetOriginals()
        val alarm = moveAlarm(5)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        beforeQuery = { if (it == Codec.Query.PROGRAM_NAMES) throw BedJetError.BadResponse() }
        assertEquals(OutcomeKind.FAILED, graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)).kind)
        beforeQuery = {}
        advance(1)
        graph.actions.retryPendingRetime()
        val after = graph.repository.activeNight.value!!
        assertEquals(n.recordId, after.recordId)
        assertEquals(Confidence.CONFIRMED, after.confidence)
        assertEquals(alarm.toInstant(), ZonedDateTime.parse(after.wakeAt).toInstant())
        assertFalse(graph.actions.retimePending)
    }

    @Test fun aRetimeWhoseNewWakeUpBeganBeforeItsWriteIsRefusedOnceAndNotRetried() = runBlocking {
        val n = start()
        at(ZonedDateTime.parse("2026-10-22T04:00:00Z"))
        forgetOriginals()
        // An earlier alarm: the new wake-up starts well before the old one.
        val alarm = ZonedDateTime.parse("2026-10-22T04:45:00Z")
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(alarm.toInstant().toEpochMilli(), null), fire)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        val count = sent.size
        // The backup read is slow: by the final check before writing, the new wake-up has begun.
        beforeQuery = { if (it == Codec.Query.PROGRAM_NAMES) {
            graph.clock = Clock.fixed(alarm.minusMinutes(5).toInstant(), ZoneOffset.UTC); context.graph.clock = graph.clock } }
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME))
        beforeQuery = {}
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals("nothing written", count, sent.count())
        assertEquals(n.identity, graph.repository.activeNight.value!!.identity)
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        assertFalse("a change that can never apply is not retried", graph.actions.retimePending)
        assertFalse(retryScheduled())
    }

    @Test fun aBoostWhoseBackupFailsLeavesTheNightConfirmedAndReconcileLeavesItAlone() = runBlocking {
        val n = start(); advance(30)
        forgetOriginals()
        failBackups = true
        val writes = sent.count(::isSlotWriteOrStart)
        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.BOOST, requireIdentity = true).copy(replacementConfirmed = true))
        assertEquals(OutcomeKind.FAILED, outcome.kind)
        assertEquals("Backup disk failure", outcome.message)
        assertEquals("no slot write or start", writes, sent.count(::isSlotWriteOrStart))
        assertEquals(n.applied.programSteps, demo.slots[2]!!.steps)
        assertEquals(n, graph.repository.activeNight.value)
        assertNull("nothing was written, so no slot write is in question", graph.repository.runtime.value.slotWrite)
        failBackups = false
        graph.actions.reconcile()
        assertEquals(n, graph.repository.activeNight.value)
        // Re-timing is not blocked by a replacement that never happened.
        moveAlarm(5)
        assertEquals(OutcomeKind.LOCAL, graph.actions.submitAlarmChange().kind)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.RETRY_RETIME)).kind)
    }

    // IR-06

    @Test fun aFailedPinDoesNotColourALaterConfirmedNight() = runBlocking {
        failBackups = true
        assertEquals(OutcomeKind.FAILED, graph.actions.pin(graph.repository.profiles.value.first().id, 0).kind)
        failBackups = false
        start()
        assertEquals(Confidence.CONFIRMED, status())
        assertNull(graph.repository.runtime.value.slotWrite)
    }

    @Test fun anOldFailedSlotWriteInTheJournalIsIgnored() = runBlocking {
        val n = start()
        // A journal written before IR-06 could hold a FAILED entry for this night.
        graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite("demo", 2, StoredProgram("Boost", emptyList()),
            Confidence.FAILED, "Boost", startRequested = true, displaced = n.identity)) }
        assertEquals(Confidence.CONFIRMED, status())
        repeat(2) { graph.actions.reconcile() }
        assertEquals("an old pre-write failure never flips the night", n, graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.reason)
    }

    @Test fun anUnknownPinIsClearedByALaterSuccessfulWriteOfThatSlot() = runBlocking {
        val profile = graph.repository.profiles.value.first().id
        beforeSend = { if (it[0] == 0x40.toByte()) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.pin(profile, 0).kind)
        assertEquals(Confidence.UNKNOWN, graph.repository.runtime.value.slotWrite?.confidence)
        // A later, unrelated failure is reported as what it is, not as the old pin's uncertainty.
        beforeSend = { if (it.contentEquals(Codec.setMode(Mode.TURBO)!!)) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.FAILED, graph.actions.execute(graph.actions.capture(NightActions.Command.TURBO)).kind)
        beforeSend = {}
        graph.controller().stop()
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.pin(profile, 0).kind)
        assertNotEquals(Confidence.UNKNOWN, graph.repository.runtime.value.slotWrite?.confidence)
        assertNull(status())
    }

    @Test fun anUnknownSlotWriteIsClearedWhenTheUserMarksItChecked() = runBlocking {
        beforeSend = { if (it[0] == 0x40.toByte()) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.pin(graph.repository.profiles.value.first().id, 0).kind)
        assertNotNull(graph.repository.runtime.value.unresolvedSlotWrite)
        assertEquals(OutcomeKind.LOCAL, graph.actions.acknowledgeSlotWrite().kind)
        assertNull(graph.repository.runtime.value.unresolvedSlotWrite)
        assertNull(status())
    }

    @Test fun anUnknownPinOfAnotherSlotDoesNotColourTheIdleStatusAfterANight() = runBlocking {
        beforeSend = { if (it[0] == 0x40.toByte()) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.pin(graph.repository.profiles.value.first().id, 0).kind)
        beforeSend = {}
        graph.controller().stop()
        start()
        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING)).kind)
        assertEquals("slot 1 is still in question", 0, graph.repository.runtime.value.unresolvedSlotWrite?.slot)
        assertNull("but nothing tracked or about to run depends on it", status())
    }

    @Test fun anUnknownPinIsNotSettledByANightOnAnotherSlot() = runBlocking {
        beforeSend = { if (it[0] == 0x40.toByte()) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.pin(graph.repository.profiles.value.first().id, 0).kind)
        beforeSend = {}
        graph.controller().stop()
        start()
        assertEquals("slot 1 is still in question", 0, graph.repository.runtime.value.unresolvedSlotWrite?.slot)
        assertEquals("but it does not colour the night, which runs slot 3", Confidence.CONFIRMED, status())
    }

    @Test fun anUnknownBoostEndsWithItsNightAndIsNotReportedForTheNext() = runBlocking {
        val n = start(); advance(30)
        beforeSend = { if (it[0] == 0x01.toByte() && it[1].toInt() and 0xff in 0x80..0x82) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.BOOST)
            .copy(replacementConfirmed = true)).kind)
        assertEquals(Confidence.UNKNOWN, status())
        beforeSend = {}
        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING)).kind)
        assertNull("its night ended, so the Boost is no longer in question", graph.repository.runtime.value.slotWrite)
        start()
        assertEquals(Confidence.CONFIRMED, status())
        assertTrue(events(n).any { it.kind == EventKind.STOPPED })
    }

    @Test fun keepingAFreshRunSettlesTheBoostThatInterruptedTheNight() = runBlocking {
        start(); advance(30)
        beforeSend = { if (it[0] == 0x01.toByte() && it[1].toInt() and 0xff in 0x80..0x82) throw IOException("GATT write lost") }
        assertEquals(OutcomeKind.UNKNOWN, graph.actions.execute(graph.actions.capture(NightActions.Command.BOOST)
            .copy(replacementConfirmed = true)).kind)
        beforeSend = {}
        demo.send(Codec.setMode(Mode.HEAT)!!)
        assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(NightActions.Command.KEEP_TRACKING)).kind)
        assertNull(graph.repository.runtime.value.slotWrite)
        assertEquals(Confidence.CONFIRMED, status())
    }

    // IR-23

    @Test fun anInterruptedFirstStartKeepsItsHistory() = runBlocking {
        beforeSend = { if (it[0] == 0x40.toByte()) throw kotlinx.coroutines.CancellationException("process dies") }
        try { start(); fail("cancel expected") } catch (_: kotlinx.coroutines.CancellationException) {}
        beforeSend = {}
        graph = load()
        graph.actions.reconcile()
        val n = graph.repository.activeNight.value!!
        assertEquals(Confidence.UNKNOWN, n.confidence)
        val record = graph.repository.history.value.firstOrNull { it.id == n.recordId }
        assertNotNull("the record was made before the first write", record)
        assertEquals("with the steps the night was to run", n.pending!!.intended!!.applied.steps, record!!.steps)
        assertTrue(record.steps.isNotEmpty())
        graph.actions.perform(NightActions.Command.STOP_TRACKING)
        assertTrue(events(n).any { it.kind == EventKind.STOPPED })
    }

    @Test fun aFailedFirstStartRecordsItsError() = runBlocking {
        failBackups = true
        assertEquals(OutcomeKind.FAILED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        val n = graph.repository.activeNight.value!!
        assertEquals(Confidence.FAILED, n.confidence)
        assertTrue(events(n).any { it.kind == EventKind.ERROR })
        assertTrue(events(n).none { it.kind == EventKind.STARTED })
    }

    @Test fun aConfirmedFirstStartIsRecordedOnceAsStarted() = runBlocking {
        val n = start()
        val record = graph.repository.history.value.single { it.id == n.recordId }
        assertEquals(listOf(EventKind.STARTED), record.events.map { it.kind })
        assertEquals(listOf(n.applied), record.revisions)
        assertEquals(n.applied.steps, record.steps)
    }
}
