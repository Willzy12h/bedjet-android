package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.ui.savedSlotLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** IR-16 and IR-31: originals from a backup are put back only when chosen, and uncertain ones say so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedOriginalRestoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var demo: DemoBedJet
    private val sent = mutableListOf<ByteArray>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
    private val mine = StoredProgram("Mine", listOf(ProgramStep(Mode.HEAT, 5, 64, 1, 0)))

    private val link: BedJetLink by lazy {
        demo = DemoBedJet(listOf(null, mine, null), now = { clock.instant().atZone(ZoneOffset.UTC) })
        object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { sent += command; demo.send(command) }
        }
    }

    private suspend fun graph(): Graph {
        val graph = Graph(context, link) { scope, now -> Repository(context, scope, now = now) }
        graph.clock = clock
        context.graph.clock = clock
        graph.repository.updateSettings { it.copy(demoMode = true) }
        return graph
    }

    private val version1 = """{"schema":1,"createdAt":"2026-10-20T22:30Z","profiles":[],"settings":{},
        "slotBackups":[{"slot":1,"program":{"name":"Theirs","steps":[{"mode":"COOL","fanStep":5,"temperature":44,"hours":2,"minutes":0}]},"savedAt":"2026-10-20T22:30Z"}]}"""

    @Test fun anOriginalFromABackupIsPutBackOnlyWhenChosen() = runBlocking {
        val graph = graph()
        val repo = graph.repository
        repo.restore(repo.importFrom(ByteArrayInputStream(version1.toByteArray())))
        val fromBackup = repo.slotBackups.value.single()
        assertTrue(fromBackup.fromBackup)

        val plain = graph.actions.restoreOriginal(1)
        assertEquals(OutcomeKind.REFUSED, plain.kind)
        assertEquals(context.getString(R.string.runtime_no_original), plain.message)
        assertEquals("nothing sent", 0, sent.size)
        assertEquals(mine, demo.slots[1])

        val chosen = graph.actions.restoreOriginal(1, fromBackup)
        assertEquals(chosen.message, OutcomeKind.CONFIRMED, chosen.kind)
        assertEquals(context.getString(R.string.runtime_restored_from_backup, 2), chosen.message)
        assertEquals("Theirs", demo.slots[1]?.name)
        // What the BedJet really held was saved first, as for any slot write.
        assertEquals(mine, repo.originalSlot("demo", 1)?.program)
        assertEquals(fromBackup, repo.slotBackups.value.single { it.fromBackup })
    }

    @Test fun aChosenRecordThatIsNotSavedIsRefused() = runBlocking {
        val graph = graph()
        val made = SlotBackup("unknown", 1, StoredProgram("Made up", emptyList()), "2026-10-20T22:30Z", fromBackup = true)
        val outcome = graph.actions.restoreOriginal(1, made)
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(0, sent.size)
        assertEquals(mine, demo.slots[1])
    }

    @Test fun anUncertainOriginalSaysSoWhenPutBack() = runBlocking {
        // A version 1 original on disk is marked uncertain when loaded.
        File(context.filesDir, "slot-backups.json").writeText(
            """[{"slot":1,"program":{"name":"Old","steps":[{"mode":"COOL","fanStep":5,"temperature":44,"hours":2,"minutes":0}]},"savedAt":"2026-10-20T22:30Z"}]""",
        )
        val migrated = graph().repository.slotBackups.value.single()
        assertTrue("migrated from version 1", migrated.uncertain)
        assertTrue(savedSlotLabel(migrated, migrated.program!!).contains("older version"))
        assertTrue(savedSlotLabel(migrated.copy(uncertain = false, fromBackup = true), migrated.program!!).contains("imported backup"))
        val plain = savedSlotLabel(migrated.copy(uncertain = false), migrated.program!!)
        assertEquals("Saved from slot 2: Old (1 steps)", plain)

        // The same flag on the demo unit's original, put back through Restore original.
        File(context.filesDir, "slot-backups.json").writeText(
            """[{"deviceId":"demo","slot":1,"program":{"name":"Old","steps":[{"mode":"COOL","fanStep":5,"temperature":44,"hours":2,"minutes":0}]},"savedAt":"2026-10-20T22:30Z","empty":false,"uncertain":true}]""",
        )
        val graph = graph()
        val outcome = graph.actions.restoreOriginal(1)
        assertEquals(outcome.message, OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(context.getString(R.string.runtime_restored_uncertain, 2), outcome.message)
        assertEquals("Old", demo.slots[1]?.name)
    }
}
