package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeatScaleTest {
    @Test
    fun warmerIsDeeperEmber() {
        // Half-degrees, at the design's drawn examples: 30° is Warm, 30.5°–33° Hot, 36°–38° Hotter, 41° up the deepest.
        assertEquals(1, HeatScale.level(Mode.HEAT, 60))
        assertEquals(2, HeatScale.level(Mode.HEAT, 61))
        assertEquals(2, HeatScale.level(Mode.HEAT, 66))
        assertEquals(2, HeatScale.level(Mode.HEAT, 71))
        assertEquals(3, HeatScale.level(Mode.EXTENDED_HEAT, 72))
        assertEquals(3, HeatScale.level(Mode.EXTENDED_HEAT, 76))
        assertEquals(3, HeatScale.level(Mode.HEAT, 81))
        assertEquals(4, HeatScale.level(Mode.HEAT, 82))
    }

    @Test
    fun turboIsAlwaysTheDeepest() = assertEquals(4, HeatScale.level(Mode.TURBO, 60))

    @Test
    fun nonHeatingModesHaveNoLevel() {
        listOf(Mode.COOL, Mode.DRY, Mode.WAIT, Mode.STANDBY).forEach { assertNull(HeatScale.level(it, 70)) }
    }
}
