package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.Palette
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class TonightFanChartTest {
    @Test fun fanControlsHeightWhileDurationAndModeRemainIndependent() {
        val start = ZonedDateTime.parse("2026-11-20T00:00Z")
        fun step(mode: Mode, fan: Int, offset: Long, minutes: Int) = PlannedStep(Block.MIDDLE,
            ProgramStep(mode, fan / 5 - 1, 60, 0, minutes), start.plusMinutes(offset), minutes)
        val plan = Plan(start, start.plusMinutes(100), listOf(step(Mode.COOL, 70, 0, 30),
            step(Mode.COOL, 25, 30, 40), step(Mode.HEAT, 25, 70, 20), step(Mode.WAIT, 100, 90, 10)))
        for (palette in Palette.entries) {
            val bars = tonightBarSegments(plan, palette.tokens)
            assertEquals(listOf(30L, 40L, 20L, 10L), bars.map { it.minutes })
            assertEquals(.7f, bars[0].heightFraction, .001f)
            assertEquals(.25f, bars[1].heightFraction, .001f)
            assertEquals(bars[1].heightFraction, bars[2].heightFraction)
            assertNotEquals(bars[1].colours, bars[2].colours)
            assertTrue(bars[3].heightFraction < .05f)
        }
    }
}
