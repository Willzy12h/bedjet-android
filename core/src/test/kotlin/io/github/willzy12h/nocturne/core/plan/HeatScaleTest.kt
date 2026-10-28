package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeatScaleTest {
    @Test
    fun warmerIsDeeperEmber() {
        // Half-degrees: 29.5°, 30°, 34°, 38°.
        assertEquals(1, HeatScale.level(Mode.HEAT, 59))
        assertEquals(2, HeatScale.level(Mode.HEAT, 60))
        assertEquals(3, HeatScale.level(Mode.EXTENDED_HEAT, 68))
        assertEquals(4, HeatScale.level(Mode.HEAT, 76))
    }

    @Test
    fun turboIsAlwaysTheDeepest() = assertEquals(4, HeatScale.level(Mode.TURBO, 60))

    @Test
    fun nonHeatingModesHaveNoLevel() {
        listOf(Mode.COOL, Mode.DRY, Mode.WAIT, Mode.STANDBY).forEach { assertNull(HeatScale.level(it, 70)) }
    }
}
