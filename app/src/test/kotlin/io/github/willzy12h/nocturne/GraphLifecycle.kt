package io.github.willzy12h.nocturne

import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Keeps at most one live [Graph] over the application's storage, as a phone does, and finishes every graph before
 * Robolectric removes that storage.
 *
 * Every [Graph] builds a Repository over the same files directory, and a new Repository's snapshot store sweeps
 * away any `snap-*.json.tmp` file it finds. A graph still running, such as the application's own one with its
 * start-up snapshot under way, then loses the temporary file it is about to publish, and its save fails on a
 * writer thread. The failure surfaces as an uncaught exception in whichever later test reports them.
 *
 * Build a replacement or restarted graph with [fresh], and reload a Repository with [afterFinishing], never while
 * another graph runs.
 */
class GraphLifecycle : ExternalResource() {
    private val live = mutableListOf<Graph>()

    override fun before() {
        track((RuntimeEnvironment.getApplication() as App).graph)
    }

    override fun after() = finish()

    /** Tracks [graph] so it is finished before storage teardown. */
    fun track(graph: Graph): Graph = graph.also { if (live.none { g -> g === it }) live += it }

    /**
     * Finishes every graph tracked so far, as a process ends, then builds the next one over the same storage. Use
     * it for each graph a test builds.
     */
    fun fresh(build: () -> Graph): Graph = track(afterFinishing(build))

    /** Finishes every tracked graph, then runs [build]; for a bare Repository reloaded over the same storage. */
    fun <T> afterFinishing(build: () -> T): T {
        finish()
        return build()
    }

    /** Cancels every tracked graph's work and waits for it; a write under way completes first. */
    fun finish() {
        val jobs = live.mapNotNull { it.scope.coroutineContext[Job] }.distinct()
        live.clear()
        finishJobs(jobs)
    }

    companion object {
        fun finishJobs(jobs: List<Job>) {
            jobs.forEach { it.cancel() }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (jobs.any { !it.isCompleted } && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("Graph work must finish before another graph or storage teardown", jobs.all { it.isCompleted })
            runBlocking { jobs.forEach { it.join() } }
        }
    }
}
