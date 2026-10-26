package io.github.willzy12h.nocturne.night

import android.content.Context
import android.net.Uri
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoSessionIsolationTest {
    @get:Rule val graphs = GraphLifecycle()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
    private lateinit var graph: Graph

    @Before fun setup() = runBlocking {
        restartGraph()
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = REAL, simpleView = false) }
        val realProfile = graph.repository.profiles.value.first().copy(id = "real-personal-profile", name = "Real personal profile")
        graph.repository.saveProfile(realProfile)
        graph.repository.addRecord(NightRecord("real-night", realProfile.id, realProfile.name,
            graph.now().minusHours(10).toString(), graph.now().minusHours(2).toString(), false, deviceId = REAL))
        graph.repository.recordOriginalSlot(REAL, 2, null)
        Unit
    }

    @Test fun enteringDemoDoesNotExposeRealHistoryOrOriginals() = runBlocking {
        assertEquals(OutcomeKind.LOCAL, graph.actions.setDemoMode(true).kind)
        assertTrue(graph.repository.settings.value.demoMode)
        assertTrue("Explicit demo must use the simulator", graph.link is DemoBedJet)
        assertTrue("Demo must not expose real History", graph.repository.history.value.isEmpty())
        assertTrue("Demo must not expose real originals", graph.repository.slotBackups.value.isEmpty())
        assertFalse("Demo profiles must not edit the real originals", graph.repository.profiles.value.any { it.id == "real-personal-profile" })
    }

    @Test fun demoCommandsAndEditsLeaveRealUserFilesUnchanged() = runBlocking {
        val before = userFiles()
        graph.actions.setDemoMode(true)
        val start = graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        assertEquals(start.message, OutcomeKind.CONFIRMED, start.kind)
        graph.repository.saveProfile(graph.repository.profiles.value.first().copy(name = "Demo edit"))
        graph.repository.updateSettings { it.copy(fahrenheit = true) }
        assertTrue("Demo nights do not enter History", graph.repository.history.value.isEmpty())
        assertTrue("Simulated originals do not enter the backups screen", graph.repository.slotBackups.value.isEmpty())
        assertEquals("Demo work cannot alter persisted user data", before, userFiles())
    }

    @Test fun oneTapExitAbandonsTheDemoNightAndRestoresTheRealFlow() = runBlocking {
        val before = graph.repository.bundle()
        graph.actions.setDemoMode(true)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        assertNotNull(graph.repository.activeNight.value)
        val exit = graph.actions.setDemoMode(false)
        assertEquals(exit.message, OutcomeKind.LOCAL, exit.kind)
        assertFalse(graph.repository.settings.value.demoMode)
        assertEquals(REAL, graph.repository.settings.value.deviceId())
        assertNull(graph.repository.activeNight.value)
        assertEquals(before.profiles, graph.repository.profiles.value)
        assertEquals(before.history, graph.repository.history.value)
        assertEquals(before.slotBackups, graph.repository.slotBackups.value)
        assertEquals(before.settings, graph.repository.settings.value)
    }

    @Test fun processRestartDuringDemoReturnsToThePersistedRealFlow() = runBlocking {
        val before = graph.repository.bundle()
        graph.actions.setDemoMode(true)
        graph.repository.saveProfile(graph.repository.profiles.value.first().copy(name = "Demo edit"))
        restartGraph()
        assertFalse(graph.repository.settings.value.demoMode)
        assertEquals(REAL, graph.repository.settings.value.deviceId())
        assertEquals(before.profiles, graph.repository.profiles.value)
        assertEquals(before.history, graph.repository.history.value)
        assertEquals(before.slotBackups, graph.repository.slotBackups.value)
    }

    @Test fun demoExitPreservesTheRealRecoveryJournalExactly() = runBlocking {
        val recovery = RuntimeState(generation = 42, reason = "A real slot needs checking",
            slotWrite = SlotWrite(REAL, 1, null, label = "Real saved original"))
        graph.repository.updateRuntime { recovery }
        val before = userFiles()
        graph.actions.setDemoMode(true)
        graph.actions.execute(graph.actions.capture(NightActions.Command.WARM))
        graph.actions.setDemoMode(false)
        assertEquals("Demo must not consume or rewrite real recovery state", recovery, graph.repository.runtime.value)
        assertEquals(before, userFiles())
    }

    @Test fun demoCannotCreateOrDeleteRealSnapshots() = runBlocking {
        graph.repository.snapshotNow("Real snapshot")
        val original = graph.repository.snapshotList.value.first()
        val before = userFiles()
        graph.actions.setDemoMode(true)
        assertTrue("Real snapshots are hidden from demo", graph.repository.snapshotList.value.isEmpty())
        assertNotNull("Demo cannot create a backup", runCatching { graph.repository.snapshotNow("Demo snapshot") }.exceptionOrNull())
        assertNotNull("Demo cannot delete a real backup", runCatching { graph.repository.deleteSnapshot(original.id) }.exceptionOrNull())
        assertNotNull("Demo data cannot become an export bundle", runCatching { graph.repository.bundle() }.exceptionOrNull())
        assertEquals(before, userFiles())
    }

    @Test fun reenteringDemoStartsWithACleanSimulator() = runBlocking {
        graph.actions.setDemoMode(true)
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.WARM)).kind)
        assertEquals(Mode.HEAT, graph.link.status.value!!.mode)
        graph.actions.setDemoMode(false)
        graph.actions.setDemoMode(true)
        graph.link.connect()
        assertEquals("A fresh demo must not resume a previous simulation", Mode.STANDBY, graph.link.status.value!!.mode)
    }

    @Test fun explicitDemoRefusesImportParsing() = runBlocking {
        val source = BackupCodec.encode(graph.repository.bundle()).byteInputStream()
        graph.actions.setDemoMode(true)
        assertNotNull("Demo cannot read a real backup", runCatching { graph.repository.importFrom(source) }.exceptionOrNull())
    }

    @Test fun explicitDemoRefusesRestoreBeforeCreatingASnapshot() = runBlocking {
        val source = graph.repository.bundle()
        val before = userFiles()
        graph.actions.setDemoMode(true)
        assertNotNull("Demo cannot restore a real backup", runCatching { graph.repository.restore(source) }.exceptionOrNull())
        assertEquals(before, userFiles())
    }

    @Test fun explicitDemoRefusesExportBeforeOpeningTheOutput() = runBlocking {
        val output = File(context.cacheDir, "demo-export.json")
        graph.actions.setDemoMode(true)
        assertNotNull("Demo cannot export a backup", runCatching { graph.repository.exportTo(Uri.fromFile(output)) }.exceptionOrNull())
        assertFalse(output.exists())
    }

    @Test fun legacyDemoSelectionMigratesToNoTargetWithoutAdoptingTheOldAddress() = runBlocking {
        context.getSharedPreferences("ble", 0).edit().putString("address", "AA:BB:CC:DD:EE:99").commit()
        graph.repository.updateSettings { it.copy(demoMode = true) }
        restartGraph()
        graph.startRuntime()
        assertFalse("A legacy demo selection must not start a new demo session", graph.repository.settings.value.demoMode)
        assertNull("Migration asks the person to pair rather than picking an old address", graph.repository.settings.value.deviceId())
    }

    @Test fun aFailedStartupMigrationCannotExposeTheOldTargetOrStartDemo() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph = graphs.fresh { Graph(context, repositoryFactory = { scope, now ->
            Repository(context, scope, writeFile = { file, bytes ->
                if (file.name == "settings.json") throw IOException("Storage unavailable")
                Repository.writeDurably(file, bytes)
            }, now = now)
        }).also { it.clock = clock } }
        ReflectionHelpers.setField(context as App, "graph", graph)
        graph.startRuntime()
        assertFalse(graph.repository.settings.value.demoMode)
        assertNull(graph.repository.settings.value.deviceId())
        assertTrue("The unsaved migration must be reported accurately", graph.repository.notices.value.any {
            it.kind == NoticeKind.PROBLEM && it.text.contains("demo", ignoreCase = true)
        })
        assertEquals("Storage must recover before a failed startup can select a real target", OutcomeKind.REFUSED,
            graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:02", useRealDevice = true).kind)
    }

    @Test fun legacyDemoNightCannotBlockPairingAfterMigration() = runBlocking {
        val originals = graph.repository.slotBackups.value.filter { it.deviceId == REAL }
        graph.repository.updateSettings { it.copy(demoMode = true) }
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT)).kind)
        restartGraph()
        graph.startRuntime()
        assertNull("A discarded legacy simulation must not block real pairing", graph.repository.activeNight.value)
        assertEquals(originals, graph.repository.slotBackups.value.filter { it.deviceId == REAL })
        assertEquals(OutcomeKind.LOCAL, graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:02", useRealDevice = true).kind)
    }

    @Test fun unfinishedMigrationRollsForwardBeforeRuntimeStarts() = runBlocking {
        graph.repository.updateSettings { it.copy(demoMode = true) }
        graph.actions.execute(graph.actions.capture(NightActions.Command.START_NIGHT))
        var settingsWrites = 0
        graph = graphs.fresh { Graph(context, repositoryFactory = { scope, now ->
            Repository(context, scope, writeFile = { file, bytes ->
                if (file.name == "active-night.json" ||
                    (file.name == "settings.json" && ++settingsWrites > 1)) throw IOException("Interrupted migration")
                Repository.writeDurably(file, bytes)
            }, now = now)
        }).also { it.clock = clock } }
        ReflectionHelpers.setField(context as App, "graph", graph)
        graph.startRuntime()
        assertFalse(graph.repository.settings.value.demoMode)
        assertNull(graph.repository.settings.value.deviceId())
        assertTrue("An incomplete atomic migration remains recoverable", graph.repository.restoreUnfinished.value)
        restartGraph()
        graph.startRuntime()
        assertFalse(graph.repository.settings.value.demoMode)
        assertNull(graph.repository.settings.value.deviceId())
        assertNull(graph.repository.activeNight.value)
        assertFalse(graph.repository.restoreUnfinished.value)
        assertTrue(graph.repository.slotBackups.value.any { it.deviceId == REAL })
    }

    private fun userFiles(): Map<String, String> = context.filesDir.walkTopDown().filter { it.isFile &&
        (it.name in setOf("profiles.json", "settings.json", "history.json", "slot-backups.json", "runtime.json",
            "active-night.json", "automation.json", "notices.json") || it.parentFile.name == "snapshots")
    }.associate { it.relativeTo(context.filesDir).path to it.readText() }

    private fun restartGraph() {
        graph = graphs.fresh { Graph(context).also { it.clock = clock } }
        ReflectionHelpers.setField(context as App, "graph", graph)
    }

    companion object { private const val REAL = "ble:AA:BB:CC:DD:EE:01" }
}
