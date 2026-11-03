package io.github.willzy12h.nocturne.platform

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartMigrationRaceTest : PlatformGraphFixture() {
    private class PausingWriter : CoroutineDispatcher() {
        @Volatile var pauseNext = false
        @Volatile private var paused: Pair<CoroutineContext, Runnable>? = null
        val hasPaused get() = paused != null
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (pauseNext) { pauseNext = false; paused = context to block; return }
            }
            Dispatchers.IO.dispatch(context, block)
        }
        fun resume() {
            val task = synchronized(this) { paused.also { paused = null } } ?: return
            Dispatchers.IO.dispatch(task.first, task.second)
        }
    }
    @Test fun startupMigrationCannotReenableAnOptInClearedBeforeItsWriterAdmission() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val writer = PausingWriter()
        val graph = fresh { Graph(context, DemoBedJet()) { scope, now -> Repository(context, scope, writer = writer, now = now) } }
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF",
            platform = it.platform.copy(autoStart = null, chargerAutoStart = true, chargerDevice = "ble:AA:BB:CC:DD:EE:FF")) }
        writer.pauseNext = true
        val migrating = async(Dispatchers.Default) { AutoStartSchedule.migrate(graph) }
        try {
            withTimeout(2_000) { while (!writer.hasPaused) delay(1) }
            // A completed import or explicit opt-out clears the legacy opt-in before migration gets the writer.
            graph.repository.updateSettings { it.copy(platform = it.platform.copy(chargerAutoStart = false, chargerDevice = null)) }
            writer.resume(); migrating.await()
            assertFalse("A stale startup read must not recreate an opted-out schedule", AutoStartSchedule.settings(graph).enabled)
            assertFalse(graph.repository.settings.value.platform.chargerAutoStart)
        } finally { writer.resume() }
    }
}
