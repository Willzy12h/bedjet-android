package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Limits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PresetsTest {
    private val start = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, ZoneId.of("Europe/London"))

    @Test
    fun everyPresetFitsTheBedJetForShortNormalAndLongNights() {
        for (preset in Presets.all) {
            for (hours in listOf(1L, 8L, 13L)) {
                val plan = PlanCompiler.compile(preset, start, start.plusHours(hours))
                assertTrue(plan.steps.size <= Limits.STEPS_PER_PROGRAM, preset.name)
                assertTrue(plan.programSteps.all { it.fitsUnitLimits }, preset.name)
            }
        }
    }

    @Test
    fun coversEverySeason() {
        assertEquals(setOf(Season.SUMMER, Season.WINTER, Season.SPRING_AUTUMN, Season.NONE), Presets.all.map { it.season }.toSet())
        assertEquals(Presets.all.size, Presets.all.map { it.id }.toSet().size)
    }
}
