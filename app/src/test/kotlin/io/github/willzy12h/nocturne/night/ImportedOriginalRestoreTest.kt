package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

/**
 * The validator lets a saved original carry any temperature the codec holds, as the BedJet recorded it. An imported
 * original (from a file or a snapshot) may have been edited, so it is put back only within the mode's range.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedOriginalRestoreTest {
    @get:org.junit.Rule val graphs = io.github.willzy12h.nocturne.GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T12:30:00Z"), ZoneOffset.UTC)

    private val link: BedJetLink by lazy {
        demo = DemoBedJet(now = { clock.instant().atZone(ZoneOffset.UTC) })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
    }

    private suspend fun graph(): Graph {
        val graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = clock
        context.graph.clock = clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        return graph
    }

    /** Hotter than Heat allows, as a hand-edited file could say. */
    private val tooHot = StoredProgram("edited", listOf(ProgramStep(Mode.HEAT, 5, 200, 1, 0)))

    @Test fun anImportedOriginalOutsideItsModesRangeIsRefusedWithNothingSent() = runBlocking {
        val graph = graph()
        val repo = graph.repository
        repo.restore(repo.bundle().copy(slotBackups = listOf(SlotBackup("demo", 0, tooHot, clock.instant().toString()))))
        assertTrue(repo.originalSlot("demo", 0)!!.imported)
        val slots = demo.slots.toList()
        val count = sent.size

        val outcome = graph.actions.restoreOriginal(0)
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(context.getString(R.string.runtime_restore_imported_out_of_range), outcome.message)
        assertEquals("nothing sent to the BedJet", count, sent.size)
        assertEquals(slots, demo.slots.toList())
        assertNull(repo.runtime.value.slotWrite)
    }

    @Test fun aDeviceRecordedOriginalRestoresAsRecorded() = runBlocking {
        val graph = graph()
        graph.repository.recordOriginalSlot("demo", 0, tooHot)
        assertFalse(graph.repository.originalSlot("demo", 0)!!.imported)

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(tooHot, demo.slots[0])
    }

    @Test fun anImportedOriginalWithinItsModesRangeStillRestores() = runBlocking {
        val graph = graph()
        val repo = graph.repository
        val fine = StoredProgram("theirs", listOf(ProgramStep(Mode.HEAT, 5, 70, 1, 0)))
        repo.restore(repo.bundle().copy(slotBackups = listOf(SlotBackup("demo", 0, fine, clock.instant().toString()))))

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(fine, demo.slots[0])
    }
}
