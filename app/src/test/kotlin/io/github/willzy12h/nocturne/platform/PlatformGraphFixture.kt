package io.github.willzy12h.nocturne.platform

import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The application and any replacement graph must finish before Robolectric removes their files. */
open class PlatformGraphFixture {
    private val owned = mutableListOf<Graph>()
    protected fun own(graph: Graph): Graph = graph.also { owned += it }
    @Before fun retainApplicationGraph() { own((RuntimeEnvironment.getApplication() as App).graph) }
    @After fun finishGraphs() {
        val jobs = owned.mapNotNull { it.scope.coroutineContext[Job] }.distinct()
        jobs.forEach { it.cancel() }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (jobs.any { !it.isCompleted } && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Fixture graph work must finish before storage teardown", jobs.all { it.isCompleted })
        runBlocking { jobs.forEach { it.join() } }
    }
}
