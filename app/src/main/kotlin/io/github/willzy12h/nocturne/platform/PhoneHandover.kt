package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.Graph

/** Explicit local connection controls. No peer scanning or advertising is performed. */
class PhoneHandover(private val graph: Graph) {
    suspend fun release() = graph.actions.releaseForOtherPhone()
    suspend fun takeBack() = graph.actions.takeBack()
}
