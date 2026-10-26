package io.github.willzy12h.nocturne.night

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.ui.AppViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Imports really may replace an uncertain post-loss original; a queued confirmation cannot authorise that new record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreOriginalCaptureTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private val models = ViewModelStore()
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private lateinit var previewed: SlotBackup
    private val programmeA = StoredProgram("Preview A", listOf(ProgramStep(Mode.HEAT, 5, 62, 0, 30)))
    private val programmeB = StoredProgram("Imported B", listOf(ProgramStep(Mode.COOL, 13, 44, 1, 0)))
    private val sent = mutableListOf<ByteArray>()
    private var connects = 0
    private var beforeConnect: suspend () -> Unit = {}
    @Volatile private var beforeFileWrite: ((File, ByteArray) -> Unit)? = null

    @Before fun setup() = runBlocking {
        graphs.afterFinishing { File(app.filesDir, "slot-backups.json").writeText("[{ broken") }
        demo = DemoBedJet(now = { graph.now() })
        val link = object : BedJetLink by demo {
            override suspend fun connect() { connects++; beforeConnect(); demo.connect() }
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true, autoUpdateCheck = false) }
        assertTrue(graph.repository.originalsBlocked.value)
        graph.repository.dismissUnreadable(acceptLostOriginals = true)
        previewed = graph.repository.recordOriginalSlot("demo", 0, programmeA)
        assertTrue("Use the actual record created after accepted original loss", previewed.uncertain)
        assertFalse(previewed.imported)
    }

    @After fun finish() { models.clear() }

    private suspend fun importReplacement(slot: Int = 0): SlotBackup {
        val repo = graph.repository
        val incoming = SlotBackup("demo", slot, programmeB, graph.now().minusDays(1).toString())
        val file = BackupCodec.encode(repo.bundle().copy(slotBackups = listOf(incoming))).byteInputStream()
        repo.restore(repo.importFrom(file))
        val imported = repo.originalSlot("demo", slot)!!
        assertEquals("Exercise the real post-loss replacement policy", incoming.copy(imported = true), imported)
        return imported
    }

    private fun assertChangedRefusal(outcome: CommandOutcome, slotsBefore: List<StoredProgram?>) {
        assertEquals("A changed original requires a new review", OutcomeKind.REFUSED, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("original", ignoreCase = true))
        assertTrue(outcome.message, outcome.message.contains("review", ignoreCase = true))
        assertTrue("An unreviewed replacement must not reach the hardware", sent.isEmpty())
        assertEquals(slotsBefore, demo.slots.toList())
        assertNull("Refusal must not leave a slot write pending", graph.repository.runtime.value.slotWrite)
    }

    @Test fun aQueuedSlotRestoreCannotRebindToAnImportedReplacement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val slotsBefore = demo.slots.toList()
        val restore = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.restoreOriginal(0) }
        try {
            assertEquals(1, graph.actions.busy.value.queuedCount)
            importReplacement()
            release.complete(Unit)
            holder.await()
            assertChangedRefusal(restore.await(), slotsBefore)
        } finally { release.complete(Unit); holder.cancelAndJoin(); restore.cancelAndJoin() }
    }

    @Test fun theViewModelsCapturedPreviewSurvivesWaitingForTheOwner() = runBlocking {
        val model = AppViewModel(app)
        models.put("restore", model)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val outcome = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { graph.actions.outcomes.first() }
        val slotsBefore = demo.slots.toList()
        try {
            model.restoreSlot(previewed)
            until { graph.actions.busy.value.queuedCount == 1 }
            // The ViewModel pre-check has succeeded. Only command-owner validation can now reject this replacement.
            importReplacement()
            release.complete(Unit)
            holder.await()
            until { outcome.isCompleted && !model.busy.value }
            assertChangedRefusal(outcome.await(), slotsBefore)
        } finally { release.complete(Unit); holder.cancelAndJoin(); outcome.cancelAndJoin() }
    }

    @Test fun anImportDuringFreshStatusPreparationIsRefusedBeforeWriting() = runBlocking {
        val slotsBefore = demo.slots.toList()
        var replaced = false
        beforeConnect = {
            if (connects == 2) {
                importReplacement()
                replaced = true
            }
        }

        val outcome = graph.actions.restoreOriginal(0)

        assertTrue("The replacement must occur inside the fresh-status preparation", replaced)
        assertChangedRefusal(outcome, slotsBefore)
    }

    @Test fun anImportQueuedDuringTheSlotJournalSaveIsRefusedAfterBackupPreparation() = runBlocking {
        val repo = graph.repository
        val incoming = SlotBackup("demo", 0, programmeB, graph.now().minusDays(1).toString())
        val bundle = repo.importFrom(BackupCodec.encode(repo.bundle().copy(slotBackups = listOf(incoming))).byteInputStream())
        val slotsBefore = demo.slots.toList()
        val scope = this
        var replacement: Deferred<Unit>? = null
        beforeFileWrite = { file, bytes ->
            if (file.name == "runtime.json") {
                val intent = BackupCodec.json.decodeFromString(RuntimeState.serializer(), bytes.decodeToString()).slotWrite
                if (intent?.confidence == Confidence.PENDING && intent.intended == programmeA) {
                    beforeFileWrite = null
                    // Queue the real import on the same serial writer while the slot intention is saving. It runs
                    // before backup's next writer turn, after restore's fresh-status and generation comparisons.
                    replacement = scope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { repo.restore(bundle) }
                }
            }
        }

        val outcome = try { graph.actions.restoreOriginal(0) } finally { beforeFileWrite = null }

        assertNotNull("The import must be queued from the pending slot journal", replacement)
        withTimeout(5_000) { replacement!!.await() }
        assertEquals(incoming.copy(imported = true), repo.originalSlot("demo", 0))
        assertChangedRefusal(outcome, slotsBefore)
    }

    @Test fun aMissingOriginalCapturedBeforeQueuingCannotBindToANewImport() = runBlocking {
        assertNull(graph.repository.originalSlot("demo", 1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { graph.actions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val restore = async(start = CoroutineStart.UNDISPATCHED) { graph.actions.restoreOriginal(1) }
        try {
            importReplacement(slot = 1)
            release.complete(Unit)
            holder.await()
            assertEquals(OutcomeKind.REFUSED, restore.await().kind)
            assertTrue(sent.isEmpty())
            assertNull(demo.slots[1])
            assertNull(graph.repository.runtime.value.slotWrite)
        } finally { release.complete(Unit); holder.cancelAndJoin(); restore.cancelAndJoin() }
    }

    @Test fun anUnchangedOriginalStillRestoresItsPreviewedProgramme() = runBlocking {
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(programmeA, demo.slots[0])
        assertTrue(sent.isNotEmpty())
        assertEquals(previewed, graph.repository.originalSlot("demo", 0))
    }

    @Test fun aNewlyReviewedImportedOriginalStillRestores() = runBlocking {
        val imported = importReplacement()
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(programmeB, demo.slots[0])
        assertEquals(imported, graph.repository.originalSlot("demo", 0))
    }

    private fun until(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var advancedAt = System.nanoTime()
        while (!check() && System.nanoTime() < deadline) {
            val elapsedMs = (System.nanoTime() - advancedAt) / 1_000_000
            if (elapsedMs > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(elapsedMs))
                advancedAt += elapsedMs * 1_000_000
            } else shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("The queued restore did not reach its expected boundary", check())
    }
}
