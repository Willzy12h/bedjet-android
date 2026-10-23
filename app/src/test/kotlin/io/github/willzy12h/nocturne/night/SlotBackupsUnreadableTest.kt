package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

/** IR-03: while the saved slot originals can't be read, no slot is written and nothing new is saved as an original. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlotBackupsUnreadableTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)

    /** Every app-owned command to the demo unit is counted. */
    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { clock.instant().atZone(ZoneOffset.UTC) })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
        }
    }

    /** A Graph that loads what is on disk now, as after a restart. */
    private suspend fun graph(): Graph {
        val graph = Graph(context, link) { scope, now -> Repository(context, scope, now = now) }
        graph.clock = clock
        context.graph.clock = clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        return graph
    }

    private fun corruptSlotBackups() = File(context.filesDir, "slot-backups.json").writeText("{garbage")
    private fun refusal() = context.getString(R.string.runtime_slot_backups_unreadable)

    @Test fun startWithUnreadableSlotBackupsIsRefusedBeforeAnyWrite() = runBlocking {
        corruptSlotBackups()
        val graph = graph()
        assertEquals(listOf("slot-backups.json"), graph.repository.unreadable.value)

        val outcome = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(refusal(), outcome.message)
        assertEquals("nothing sent to the BedJet", 0, sent.size)
        assertNull(graph.repository.activeNight.value)
        assertNull(graph.repository.runtime.value.slotWrite)
        assertEquals(emptyList<Any>(), graph.repository.slotBackups.value)

        val restore = graph.actions.restoreOriginal(2)
        assertEquals(OutcomeKind.REFUSED, restore.kind)
        assertEquals(refusal(), restore.message)
        assertEquals(0, sent.size)
    }

    @Test fun everySlotWriteIsRefusedAndARunningNightIsLeftAsItWas() = runBlocking {
        val first = graph()
        assertTrue(first.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        val profileId = first.repository.activeNight.value!!.profileId

        corruptSlotBackups()
        val graph = graph()
        val night = graph.repository.activeNight.value!!
        assertEquals(Confidence.CONFIRMED, night.confidence)
        val runtime = graph.repository.runtime.value
        val slots = demo.slots.toList()
        val count = sent.size

        val outcomes = listOf(
            graph.actions.perform(NightActions.Command.START_NIGHT, replacementConfirmed = true),
            graph.actions.execute(graph.actions.capture(NightActions.Command.REAPPLY)).message,
            graph.actions.perform(NightActions.Command.BOOST, replacementConfirmed = true),
            graph.actions.pin(profileId, 0).message,
            graph.actions.setWakeOverride(ZonedDateTime.parse(night.wakeAt).plusMinutes(30)).message,
        )
        assertEquals(List(outcomes.size) { refusal() }, outcomes)
        assertEquals("no read or write sent to the BedJet", count, sent.size)
        assertEquals(slots, demo.slots.toList())
        assertEquals("the night, and its confidence, are unchanged", night, graph.repository.activeNight.value)
        assertEquals(runtime, graph.repository.runtime.value)
        assertNull(graph.repository.settings.value.tonightOverride)
        assertEquals(emptyList<Any>(), graph.repository.slotBackups.value)
    }

    private fun retryScheduled() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .any { shadowOf(it.operation).savedIntent.action == NightAlarms.ACTION_RETRY }

    @Test fun aRefusedRetimeRetryIsDroppedWithItsReasonAndTheUserIsTold() = runBlocking {
        val first = graph()
        assertTrue(first.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        val fire = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        val at = clock.instant().plus(Duration.ofHours(5))
        context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), null), fire)
        assertEquals(OutcomeKind.LOCAL, first.actions.submitAlarmChange().kind)
        assertTrue(retryScheduled())

        corruptSlotBackups()
        val graph = graph()
        val night = graph.repository.activeNight.value!!
        val count = sent.size
        assertTrue("an alarm change is waiting to be applied", graph.actions.retimePending)

        assertEquals(refusal(), graph.actions.retryPendingRetime())
        assertEquals(count, sent.size)
        assertEquals(night, graph.repository.activeNight.value)
        assertFalse(graph.actions.retimePending)
        assertNull(graph.repository.runtime.value.retime)
        assertEquals(refusal(), graph.repository.runtime.value.reason)
        assertFalse("retry alarm cancelled", retryScheduled())
        // The error notice is shown, so the user isn't left on the old wake time without a word.
        withTimeout(5_000) { context.graph.repository.notices.first { list ->
            list.any { it.kind == NoticeKind.PROBLEM && it.title == context.getString(R.string.runtime_retime_refused) && it.text == refusal() } } }
        Unit
    }

    @Test fun afterDismissingTheLossSlotWritesGoAheadAndOriginalsAreRecordedAfresh() = runBlocking {
        corruptSlotBackups()
        val graph = graph()
        graph.repository.dismissUnreadable()

        assertTrue(graph.actions.perform(NightActions.Command.START_NIGHT).contains("started"))
        assertEquals(Confidence.CONFIRMED, graph.repository.activeNight.value!!.confidence)
        val recorded = graph.repository.originalSlot("demo", 2)
        assertNotNull(recorded)
        assertTrue("the slot was empty before the first write", recorded!!.empty)
    }
}
