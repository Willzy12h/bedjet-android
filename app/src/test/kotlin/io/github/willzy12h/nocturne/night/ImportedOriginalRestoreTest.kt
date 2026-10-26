package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupCodec
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
 * original (from a file or a snapshot) may have been edited, so heating/cooling steps must stay within their mode's
 * range. Standby and Wait do not use their temperature byte; recorded originals keep every byte as recorded.
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
    private val idleBytes = StoredProgram("idle bytes", listOf(
        ProgramStep(Mode.STANDBY, 5, 255, 0, 1),
        ProgramStep(Mode.WAIT, 5, 1, 0, 1),
        ProgramStep(Mode.HEAT, 5, 70, 0, 1),
    ))

    private suspend fun importOriginal(graph: Graph, program: StoredProgram) {
        val repo = graph.repository
        val bundle = repo.bundle().copy(slotBackups = listOf(SlotBackup("demo", 0, program, clock.instant().toString())))
        repo.restore(repo.importFrom(BackupCodec.encode(bundle).byteInputStream()))
        assertTrue(repo.originalSlot("demo", 0)!!.imported)
    }

    private suspend fun assertImportedRefused(program: StoredProgram) {
        val graph = graph()
        val repo = graph.repository
        importOriginal(graph, program)
        val slots = demo.slots.toList()
        val count = sent.size

        val outcome = graph.actions.restoreOriginal(0)
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(context.getString(R.string.runtime_restore_imported_out_of_range), outcome.message)
        assertEquals("nothing sent to the BedJet", count, sent.size)
        assertEquals(slots, demo.slots.toList())
        assertNull(repo.runtime.value.slotWrite)
    }

    @Test fun anImportedOriginalOutsideItsModesRangeIsRefusedWithNothingSent() = runBlocking {
        assertImportedRefused(tooHot)
    }

    @Test fun anImportedBackupRestoresStandbyAndWaitBytesAsRecorded() = runBlocking {
        val graph = graph()
        importOriginal(graph, idleBytes)

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(idleBytes, demo.slots[0])
    }

    @Test fun anImportedSnapshotRestoresStandbyAndWaitBytesAsRecorded() = runBlocking {
        val graph = graph()
        val repo = graph.repository
        val bundle = repo.bundle().copy(slotBackups = listOf(SlotBackup("demo", 0, idleBytes, clock.instant().toString())))
        val snapshot = repo.snapshots.save(bundle, "Original slots", manual = true)
        assertTrue(repo.restoreSnapshot(snapshot.id))
        assertTrue(repo.originalSlot("demo", 0)!!.imported)

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(idleBytes, demo.slots[0])
    }

    @Test fun anImportedTurbo255IsRefusedEvenAlongsideStandbyAndWait() = runBlocking {
        assertImportedRefused(idleBytes.copy(steps = idleBytes.steps + ProgramStep(Mode.TURBO, 5, 255, 0, 1)))
    }

    @Test fun anImportedExtendedHeatBelowItsRangeIsRefusedWithNothingSent() = runBlocking {
        assertImportedRefused(StoredProgram("edited", listOf(ProgramStep(Mode.EXTENDED_HEAT, 5, 37, 0, 1))))
    }

    @Test fun anImportedCoolAboveItsRangeIsRefusedWithNothingSent() = runBlocking {
        assertImportedRefused(StoredProgram("edited", listOf(ProgramStep(Mode.COOL, 5, 63, 0, 1))))
    }

    @Test fun anImportedDryAboveItsRangeIsRefusedWithNothingSent() = runBlocking {
        assertImportedRefused(StoredProgram("edited", listOf(ProgramStep(Mode.DRY, 5, 81, 0, 1))))
    }

    @Test fun anImportedTurboAtItsFixedTemperatureStillRestores() = runBlocking {
        val graph = graph()
        val turbo = StoredProgram("turbo", listOf(ProgramStep(Mode.TURBO, 5, 86, 0, 1)))
        importOriginal(graph, turbo)

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(turbo, demo.slots[0])
    }

    @Test fun aDeviceRecordedOriginalRestoresAsRecorded() = runBlocking {
        val graph = graph()
        val recorded = tooHot.copy(steps = tooHot.steps + idleBytes.steps + ProgramStep(Mode.TURBO, 5, 255, 0, 1))
        graph.repository.recordOriginalSlot("demo", 0, recorded)
        assertFalse(graph.repository.originalSlot("demo", 0)!!.imported)

        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(recorded, demo.slots[0])
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
