package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.night.CommandFeedback
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Each collection belongs to MainActivity's (model, LocalView) effect. A tap captures that exact view's collection;
 * neither a new collection nor returning from onStop can adopt its feedback. There is no replay or outcome feed.
 * The existing view sink uses performHapticFeedback without ignore-setting flags: ACCEPTED maps to CLOCK_TICK,
 * CONFIRMED to CONFIRM. CONFIRM requires API 30; the app's minimum API is 31, so no fallback is required.
 */
internal class Haptics {
    private data class Pulse(val view: Any, val epoch: Long, val kind: OutcomeKind)
    private val gate = Any()
    private val pulses = MutableSharedFlow<Pulse>(extraBufferCapacity = 16)
    private var activeView: Any? = null
    private var visible = false
    private var screenEpoch = 0L

    val feedback: Flow<OutcomeKind> = flow {
        val view = Any()
        synchronized(gate) { activeView = view }
        try {
            pulses.collect { pulse ->
                // Check again at delivery: an enqueued pulse must not outlive its view or visible lifetime.
                val current = synchronized(gate) {
                    visible && activeView === view && pulse.view === view && pulse.epoch == screenEpoch
                }
                if (current) emit(pulse.kind)
            }
        } finally {
            synchronized(gate) { if (activeView === view) activeView = null }
        }
    }

    fun setVisible(shown: Boolean) = synchronized(gate) {
        if (visible != shown) screenEpoch++
        visible = shown
    }

    /** Called synchronously at the tap, before a save, service or owner can suspend it. */
    fun capture(): Request? = synchronized(gate) {
        val view = activeView ?: return null
        if (!visible) return null
        Request(UUID.randomUUID().toString(), view, screenEpoch)
    }

    inner class Request internal constructor(val id: String, private val view: Any, private val epoch: Long) {
        private var admitted = false
        private var finished = false
        val context = CommandFeedback(id, ::onAdmitted)

        private fun current() = visible && activeView === view && screenEpoch == epoch

        private fun onAdmitted(requestId: String, origin: CommandOrigin) {
            val pulse = synchronized(gate) {
                if (requestId != id || origin != CommandOrigin.APP || admitted || finished || !current()) return
                admitted = true
                Pulse(view, epoch, OutcomeKind.ACCEPTED)
            }
            pulses.tryEmit(pulse)
        }

        /** Only this request's returned, typed outcome may confirm its admission. Message text is irrelevant. */
        fun complete(outcome: CommandOutcome) {
            val pulse = synchronized(gate) {
                if (finished) return
                finished = true
                if (!admitted || outcome.origin != CommandOrigin.APP || outcome.kind != OutcomeKind.CONFIRMED || !current()) return
                Pulse(view, epoch, OutcomeKind.CONFIRMED)
            }
            pulses.tryEmit(pulse)
        }

        fun close() = synchronized(gate) { finished = true }
    }
}
