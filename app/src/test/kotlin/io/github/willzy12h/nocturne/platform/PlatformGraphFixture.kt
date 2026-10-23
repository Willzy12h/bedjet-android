package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import org.junit.Rule

/** The application and any replacement graph must finish before Robolectric removes their files; see [GraphLifecycle]. */
open class PlatformGraphFixture {
    @get:Rule val graphs = GraphLifecycle()
    /** Builds a replacement or restarted graph once every graph before it has finished. */
    protected fun fresh(build: () -> Graph): Graph = graphs.fresh(build)
}
