package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZonedDateTime

/** Fan Ease remains effective when the chosen temperature shape has only one block. */
class IndependentFanTimelineTest {
    private val start = ZonedDateTime.parse("2026-10-29T22:00:00Z")
    private val profile = Profile("stay-ease", "Stay and ease", start = emptyList(),
        middle = Middle(Mode.COOL, 60, 20, 44, 44, rampSteps = 1), wake = emptyList())

    private fun compile(minutes: Long, value: Profile = profile) = PlanCompiler.compile(value, start, start.plusMinutes(minutes))
    private fun fan(step: PlannedStep) = (step.step.fanStep + 1) * 5

    @Test fun eightHourStayReachesTheSavedFanEndpoint() {
        val plan = compile(480)
        assertEquals(listOf(60, 20), plan.steps.map(::fan))
        assertEquals(listOf(240, 240), plan.steps.map { it.minutes })
        assertTrue(plan.steps.all { it.step.temperature == 44 })
        assertEquals(start.plusHours(8), plan.end)
    }

    @Test fun fanChangesDoNotActivateTheUnusedStayTemperatureEndpoint() {
        val value = profile.copy(middle = profile.middle.copy(mode = Mode.EXTENDED_HEAT, tempStart = 60, tempEnd = 52))
        val steps = compile(120, value).steps
        assertEquals(listOf(60, 20), steps.map(::fan))
        assertEquals(listOf(60, 60), steps.map { it.step.temperature })
    }

    @Test fun oddDurationKeepsEveryMinuteAndBothFanEndpoints() {
        val plan = compile(5)
        assertEquals(listOf(2, 3), plan.steps.map { it.minutes })
        assertEquals(listOf(60, 20), plan.steps.map(::fan))
        assertEquals(start.plusMinutes(5), plan.end)
        assertFalse(plan.steps.any { it.step.endsAtClockTime })
    }

    @Test fun twoMinuteMiddleCanRepresentBothSpeeds() {
        val steps = compile(2).steps
        assertEquals(listOf(1, 1), steps.map { it.minutes })
        assertEquals(listOf(60, 20), steps.map(::fan))
    }

    @Test fun oneMinuteEaseIsRefusedInsteadOfSilentlyIgnoringItsEndpoint() {
        assertThrows<PlanException> { compile(1) }
    }

    @Test fun fanOnlySegmentsStillRespectProfileLimits() {
        val value = profile.copy(middle = profile.middle.copy(fanStart = 95, fanEnd = 5),
            limits = ProfileLimits(minFan = 20, maxFan = 60))
        val steps = compile(120, value).steps
        assertEquals(listOf(60, 20), steps.map(::fan))
        assertTrue(steps.all { it.step.fitsUnitLimits })
    }

    @Test fun fanOnlyBoundaryCountsTowardsTheDeviceStepBudget() {
        val phases = List(30) { Phase(Mode.COOL, 44, 40, 1) }
        val allowed = compile(32, profile.copy(start = phases))
        assertEquals(32, allowed.steps.size)
        assertEquals(listOf(60, 20), allowed.steps.takeLast(2).map(::fan))
        assertThrows<PlanException> {
            compile(33, profile.copy(start = phases + Phase(Mode.COOL, 44, 40, 1)))
        }
    }

    @Test fun wakePriorityCanOmitTheMiddleWithoutInventingFanTime() {
        val plan = compile(1, profile.copy(wake = listOf(Phase(Mode.COOL, 44, 45, 1))))
        assertEquals(listOf(Block.WAKE), plan.steps.map { it.block })
        assertEquals(listOf(45), plan.steps.map(::fan))
        assertEquals(start.plusMinutes(1), plan.end)
    }

    @Test fun easingTheFanDoesNotRestartTurboAfterItsHeatChainBegins() {
        val value = profile.copy(middle = Middle(Mode.TURBO, 60, 20, 86, 86),
            turboChain = TurboChain.TURBO_THEN_HEAT)
        val steps = compile(60, value).steps
        assertEquals(10, steps.filter { it.step.mode == Mode.TURBO }.sumOf { it.minutes },
            "A fan-only boundary must not grant a second Turbo burst")
        assertEquals(listOf(Mode.TURBO, Mode.HEAT, Mode.HEAT), steps.map { it.step.mode })
        assertEquals(listOf(10, 20, 30), steps.map { it.minutes })
        assertEquals(listOf(60, 60, 20), steps.map(::fan))
    }

    @Test fun fanChangeInsideTurboKeepsOnlyTheRemainingTurboAllowance() {
        val value = profile.copy(middle = Middle(Mode.TURBO, 60, 20, 86, 86),
            turboChain = TurboChain.TURBO_THEN_HEAT)
        val steps = compile(16, value).steps
        assertEquals(listOf(Mode.TURBO, Mode.TURBO, Mode.HEAT), steps.map { it.step.mode })
        assertEquals(listOf(8, 2, 6), steps.map { it.minutes })
        assertEquals(listOf(60, 20, 20), steps.map(::fan))
        assertTrue(steps.all { it.step.fitsUnitLimits })
    }

    @Test fun quietFanBoundariesAlsoPreserveOneTurboThenHeatEnvelope() {
        val value = profile.copy(middle = Middle(Mode.TURBO, 50, 45, 86, 86,
            quietFan = QuietFan(50, 4, 25, 45, 6)), turboChain = TurboChain.TURBO_THEN_HEAT)
        val steps = compile(60, value).steps
        assertEquals(10, steps.filter { it.step.mode == Mode.TURBO }.sumOf { it.minutes })
        assertEquals(listOf(4, 6, 44, 6), steps.map { it.minutes })
        assertEquals(listOf(50, 25, 25, 45), steps.map(::fan))
        assertTrue(steps.dropWhile { it.step.mode == Mode.TURBO }.all { it.step.mode == Mode.HEAT })
    }

    @Test fun explicitRepeatTurboStillRepeatsWithinEachHardwareLimit() {
        val value = profile.copy(middle = Middle(Mode.TURBO, 60, 20, 86, 86),
            turboChain = TurboChain.REPEAT_TURBO)
        val steps = compile(60, value).steps
        assertTrue(steps.all { it.step.mode == Mode.TURBO && it.minutes <= 10 && it.step.fitsUnitLimits })
        assertEquals(60, steps.sumOf { it.minutes })
        assertEquals(60, fan(steps.first()))
        assertEquals(20, fan(steps.last()))
    }
}
