package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode

/**
 * The redesign's Ember scale: heating steps are tinted by how warm they are, not by mode name, so a warmer step
 * reads deeper red at a glance. Level 1 is the lightest ember, 4 the deepest; Turbo is always 4.
 */
object HeatScale {
    /** Lower bounds in half-degrees Celsius for levels 2, 3 and 4 (30°, 34°, 38°). */
    private val BOUNDS = listOf(60, 68, 76)

    /** 1 to 4 for a heating step at [temperature] half-degrees, or null for a mode that doesn't heat. */
    fun level(mode: Mode, temperature: Int): Int? = when (mode) {
        Mode.TURBO -> 4
        Mode.HEAT, Mode.EXTENDED_HEAT -> 1 + BOUNDS.count { temperature >= it }
        Mode.COOL, Mode.DRY, Mode.WAIT, Mode.STANDBY -> null
    }
}
