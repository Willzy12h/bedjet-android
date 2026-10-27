package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class ImportedProgrammeSafetySecurityTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clock = Clock.fixed(Instant.parse("2026-10-27T22:30:00Z"), ZoneOffset.UTC)
    private val sent = mutableListOf<ByteArray>()
    private lateinit var demo: DemoBedJet
    private suspend fun graph(): Graph {
        demo = DemoBedJet(now = { clock.instant().atZone(ZoneOffset.UTC) })
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command.copyOf(); demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        return graphs.fresh { Graph(context, link) }.also {
            it.clock = clock; context.graph.clock = clock
            it.repository.updateSettings { settings -> settings.copy(demoMode = true) }
        }
    }
    private suspend fun imported(graph: Graph, step: ProgramStep) {
        val repo = graph.repository
        val original = SlotBackup(DeviceId.DEMO, 0, StoredProgram("edited original", listOf(step)), clock.instant().toString())
        assertNull("Fixture has no protected local original", repo.originalSlot(DeviceId.DEMO, 0))
        val parsed = repo.importFrom(BackupCodec.encode(repo.bundle().copy(slotBackups = listOf(original))).byteInputStream())
        val preview = repo.previewImport(parsed)
        assertTrue("Original remains inspectable", preview.canApply)
        repo.applyImport(preview)
        assertTrue(repo.originalSlot(DeviceId.DEMO, 0)!!.imported)
        assertEquals(original.program, repo.originalSlot(DeviceId.DEMO, 0)!!.program)
    }
    private suspend fun refused(step: ProgramStep) {
        val graph = graph()
        imported(graph, step)
        val before = sent.size
        assertEquals(OutcomeKind.REFUSED, graph.actions.restoreOriginal(0).kind)
        assertEquals("Unsafe restore must issue no hardware command", before, sent.size)
        assertNull(demo.slots[0])
        assertNull(graph.repository.runtime.value.slotWrite)
        assertEquals(step, graph.repository.originalSlot(DeviceId.DEMO, 0)!!.program!!.steps.single())
    }
    @Test fun previewApplyRestoreRefusesTurbo719WithoutDiscardingOriginal() = runBlocking {
        refused(ProgramStep(Mode.TURBO, 19, 86, 11, 59))
    }
    @Test fun previewApplyRestoreRefusesTurbo11WithoutDiscardingOriginal() = runBlocking {
        refused(ProgramStep(Mode.TURBO, 19, 86, 0, 11))
    }
    @Test fun clockEndingTurboCannotHideThirtyMinutes() = runBlocking {
        refused(ProgramStep(Mode.TURBO, 19, 86, 23, 0, endsAtClockTime = true))
    }
    @Test fun validTenMinuteTurboStillRestores() = runBlocking {
        val graph = graph()
        val step = ProgramStep(Mode.TURBO, 19, 86, 0, 10)
        imported(graph, step)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.restoreOriginal(0).kind)
        assertEquals(step, demo.slots[0]!!.steps.single())
    }
}
