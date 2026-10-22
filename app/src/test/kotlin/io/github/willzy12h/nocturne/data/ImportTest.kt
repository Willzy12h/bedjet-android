package io.github.willzy12h.nocturne.data

import android.content.Context
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An import in the real app, with a night running on the simulated BedJet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graph get() = context.graph
    private var previousClock: java.time.Clock? = null

    @org.junit.Before
    fun fixedNightClock() {
        previousClock = graph.clock
        graph.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T22:30:00Z"), java.time.ZoneOffset.UTC)
    }

    @org.junit.After
    fun restoreClock() { previousClock?.let { graph.clock = it } }


    @Test
    fun anImportLeavesTheBedJetAndTheNightAlone() = runBlocking {
        val repo = graph.repository
        repo.updateSettings { it.copy(demoMode = true) }
        graph.actions.perform(Command.START_NIGHT)
        val night = repo.activeNight.value
        assertNotNull(night)
        val demo = graph.link as DemoBedJet
        val slots = demo.slots.toList()
        val status = demo.status.value

        repo.restore(
            BackupBundle(
                createdAt = graph.now().toString(),
                profiles = listOf(Presets.hotSleeper.copy(id = "imported")),
                settings = Settings(demoMode = false, targetDevice = "ble:11:22:33:44:55:66", workingSlot = 0),
            ),
        )

        assertEquals(listOf("imported"), repo.profiles.value.map { it.id })
        assertTrue(repo.settings.value.demoMode)
        assertSame(demo, graph.link)
        assertEquals(slots, demo.slots.toList())
        assertEquals(status, demo.status.value)
        assertEquals(night, repo.activeNight.value)
    }
}
