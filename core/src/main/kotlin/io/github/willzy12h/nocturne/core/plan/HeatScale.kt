package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode

/**
 * The redesign's Ember scale: heating steps are tinted by how warm they are, not by mode name, so a warmer step
 * reads deeper red at a glance. Level 1 is the lightest ember, 4 the deepest; Turbo is always 4.
 */
object HeatScale {
    /**
     * Lower bounds in half-degrees Celsius for levels 2, 3 and 4 (30.5°, 36°, 41°), from the design's scale: Warm to 30°,
     * Hot 31–35°, Hotter 36–40°, then the Turbo colour. The design draws 30.5° as Hot.
     */
    private val BOUNDS = listOf(61, 72, 82)

    /** 1 to 4 for a heating step at [temperature] half-degrees, or null for a mode that doesn't heat. */
    fun level(mode: Mode, temperature: Int): Int? = when (mode) {
        Mode.TURBO -> 4
        Mode.HEAT, Mode.EXTENDED_HEAT -> 1 + BOUNDS.count { temperature >= it }
        Mode.COOL, Mode.DRY, Mode.WAIT, Mode.STANDBY -> null
    }
}
