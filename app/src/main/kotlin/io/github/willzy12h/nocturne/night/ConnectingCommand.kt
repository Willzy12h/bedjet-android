package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.StatusReceiptOrder
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.store.deviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One transient UI tap. The existing service and command owner retain the operation and its timeout. */
internal class ConnectingCommand(
    val label: String,
    val off: Boolean,
    private val target: String?,
    private val seen: DeviceStatus?,
    private val current: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConnectingCommand>
    enum class Phase { CONNECTING, REPLACE, SENDING, CANCELLED }
    private val _phase = MutableStateFlow(Phase.CONNECTING)
    val phase = _phase.asStateFlow()
    private val receiptAfter = if (seen == null) StatusReceiptOrder.next() else Long.MIN_VALUE
    private val consent = CompletableDeferred<Unit>()
    private var owner: Job? = null

    @Synchronized fun attach(job: Job) {
        owner = job
        if (_phase.value == Phase.CANCELLED) job.cancel()
    }
    @Synchronized fun cancelWaiting(): Boolean {
        if (_phase.value !in setOf(Phase.CONNECTING, Phase.REPLACE)) return false
        _phase.value = Phase.CANCELLED
        owner?.cancel()
        return true
    }
    fun replace() { if (_phase.value == Phase.REPLACE) consent.complete(Unit) }

    /** Under command ownership, after connecting, before dispatch. A stale screen never grants takeover consent. */
    suspend fun admit(graph: Graph) {
        if (_phase.value == Phase.SENDING) return
        fun changed() = IllegalStateException(graph.context.getString(R.string.runtime_connection_changed))
        fun beginSending() = synchronized(this) {
            if (_phase.value == Phase.CANCELLED) throw CancellationException()
            if (graph.repository.settings.value.deviceId() != target || graph.sessions.released.value ||
                graph.link.state.value != LinkState.READY || !current()) throw changed()
            _phase.value = Phase.SENDING
        }
        // Off is always attempted on the selected connected unit, even if status has stopped arriving.
        if (off) { beginSending(); return }
        val report = withTimeoutOrNull(graph.actions.freshStatusMs) {
            graph.link.sourcedStatus.filterNotNull().first { it.isFrom(target) && it.receiptOrder > receiptAfter &&
                graph.link.state.value == LinkState.READY }
        } ?: throw IllegalStateException(graph.context.getString(R.string.runtime_connection_no_report))
        val running = report.status
        val unfamiliarRun = running.isRunning && (seen == null || !seen.isRunning || seen.mode != running.mode ||
            seen.extra?.programStep != running.extra?.programStep)
        if (unfamiliarRun) {
            if (!_phase.compareAndSet(Phase.CONNECTING, Phase.REPLACE)) throw CancellationException()
            consent.await()
        }
        val latest = graph.link.latestSourcedStatus()?.takeIf { it.isFrom(target) }?.status ?: throw changed()
        if (latest.mode != running.mode || latest.extra?.programStep != running.extra?.programStep) throw changed()
        beginSending()
    }
}
