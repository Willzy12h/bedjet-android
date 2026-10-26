package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.Profile
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZonedDateTime

/** A source phase, rather than a compiled ramp/Turbo fragment. Safe to carry with a saved editor draft. */
@Serializable
data class EditorPhaseSelection(val block: Block, val index: Int)

internal fun editorPhaseSelection(profile: Profile, plan: Plan, block: Block, at: ZonedDateTime): EditorPhaseSelection {
    if (block == Block.MIDDLE) return EditorPhaseSelection(block, 0)
    val phases = when (block) {
        Block.START -> profile.start
        Block.WAKE -> profile.wake
        Block.AFTER_WAKE -> profile.afterWake
        Block.MIDDLE -> emptyList()
    }
    val steps = plan.steps.filter { it.block == block }
    var offset = Duration.between(steps.firstOrNull()?.startsAt ?: at, at).toMinutes().toInt().coerceAtLeast(0)
    // The compiler trims the front of Wake, but the end of Start; preserve the source phase index in both cases.
    if (block == Block.WAKE) offset += (phases.sumOf { it.minutes } - steps.sumOf { it.minutes }).coerceAtLeast(0)
    val index = phases.indexOfFirst { phase ->
        if (offset < phase.minutes) true else { offset -= phase.minutes; false }
    }.let { if (it >= 0) it else phases.lastIndex.coerceAtLeast(0) }
    return EditorPhaseSelection(block, index)
}
