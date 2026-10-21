package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanCompilerTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private val start = at(2026, 10, 21, 22, 30)
    private val wake = at(2026, 10, 22, 6, 45) // 495 minutes later

    private fun profile(
        start: List<Phase> = listOf(Phase(Mode.HEAT, 66, 40, 30)),
        middle: Middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 50, tempStart = 44, tempEnd = 44, rampSteps = 1),
        wake: List<Phase> = listOf(Phase(Mode.HEAT, 64, 30, 25)),
        afterWake: List<Phase> = emptyList(),
        limits: ProfileLimits = ProfileLimits(),
        turboChain: TurboChain = TurboChain.TURBO_THEN_HEAT,
    ) = Profile(id = "p", name = "Test", start = start, middle = middle, wake = wake, afterWake = afterWake, limits = limits, turboChain = turboChain)

    private fun Plan.minutesOf(block: Block) = steps.filter { it.block == block }.sumOf { it.minutes }

    @Test
    fun middleFillsGap() {
        val plan = PlanCompiler.compile(profile(), start, wake)
        assertEquals(30, plan.minutesOf(Block.START))
        assertEquals(495 - 30 - 25, plan.minutesOf(Block.MIDDLE))
        assertEquals(25, plan.minutesOf(Block.WAKE))
        val wakeStep = plan.steps.first { it.block == Block.WAKE }
        assertEquals(at(2026, 10, 22, 6, 20), wakeStep.startsAt)
        assertEquals(wake, plan.wake)
    }

    @Test
    fun rampInterpolatesFanAndTemp() {
        val p = profile(middle = Middle(Mode.EXTENDED_HEAT, fanStart = 60, fanEnd = 20, tempStart = 60, tempEnd = 52, rampSteps = 3))
        val middle = PlanCompiler.compile(p, start, wake).steps.filter { it.block == Block.MIDDLE }
        assertEquals(3, middle.size)
        assertEquals(listOf(60, 40, 20), middle.map { (it.step.fanStep + 1) * 5 })
        assertEquals(listOf(60, 56, 52), middle.map { it.step.temperature })
        assertEquals(440, middle.sumOf { it.minutes })
        assertTrue(middle.all { it.minutes in 146..147 })
    }

    @Test
    fun shortNightTrimsStartThenWake() {
        // 40 minutes: Wake-up keeps its 25, Start is trimmed to 15, no Middle.
        val plan = PlanCompiler.compile(profile(), start, start.plusMinutes(40))
        assertEquals(15, plan.minutesOf(Block.START))
        assertEquals(0, plan.minutesOf(Block.MIDDLE))
        assertEquals(25, plan.minutesOf(Block.WAKE))
        // 10 minutes: only the last 10 minutes of Wake-up are left.
        val tiny = PlanCompiler.compile(profile(), start, start.plusMinutes(10))
        assertEquals(0, tiny.minutesOf(Block.START))
        assertEquals(10, tiny.minutesOf(Block.WAKE))
        assertEquals(start.plusMinutes(10), tiny.steps.last().let { it.startsAt.plusMinutes(it.minutes.toLong()) })
    }

    @Test
    fun longStepSplitAtTwelveHours() {
        val p = profile(start = emptyList(), wake = emptyList())
        val plan = PlanCompiler.compile(p, start, start.plusHours(14))
        val middle = plan.steps.filter { it.block == Block.MIDDLE }
        assertEquals(2, middle.size)
        assertEquals(listOf(420, 420), middle.map { it.minutes })
    }

    @Test
    fun heatSplitByCutOff() {
        // 40 °C at full fan is cut off after 60 minutes, so 150 minutes become three steps of 50.
        val p = profile(start = listOf(Phase(Mode.HEAT, 80, 100, 150)))
        val heat = PlanCompiler.compile(p, start, wake).steps.filter { it.block == Block.START }
        assertEquals(listOf(50, 50, 50), heat.map { it.minutes })
        assertTrue(heat.all { it.step.fitsUnitLimits })
    }

    @Test
    fun turboChainedToHeat() {
        val p = profile(start = listOf(Phase(Mode.TURBO, 86, 100, 15)), limits = ProfileLimits(maxTemp = 76))
        val steps = PlanCompiler.compile(p, start, wake).steps.filter { it.block == Block.START }
        assertEquals(listOf(Mode.TURBO to 10, Mode.HEAT to 5), steps.map { it.step.mode to it.minutes })
        assertEquals(76, steps[1].step.temperature)
    }

    @Test
    fun turboRepeated() {
        val p = profile(start = listOf(Phase(Mode.TURBO, 86, 100, 15)), turboChain = TurboChain.REPEAT_TURBO)
        val steps = PlanCompiler.compile(p, start, wake).steps.filter { it.block == Block.START }
        assertEquals(listOf(Mode.TURBO to 10, Mode.TURBO to 5), steps.map { it.step.mode to it.minutes })
    }

    @Test
    fun limitsClampEveryStep() {
        val p = profile(
            start = listOf(Phase(Mode.HEAT, 80, 100, 30)),
            middle = Middle(Mode.COOL, 100, 5, 44, 44, rampSteps = 2),
            limits = ProfileLimits(minFan = 20, maxFan = 60, minTemp = 50, maxTemp = 70),
        )
        val steps = PlanCompiler.compile(p, start, wake).steps
        assertTrue(steps.all { (it.step.fanStep + 1) * 5 in 20..60 })
        assertEquals(70, steps.first().step.temperature)
        // Limits never push a mode outside what the BedJet accepts for it.
        assertTrue(steps.all { it.step.temperature in it.step.mode.temperatureRange })
    }

    @Test
    fun lastMiddleStepEndsAtClockTime() {
        val middle = PlanCompiler.compile(profile(), start, wake).steps.filter { it.block == Block.MIDDLE }
        val last = middle.last().step
        assertTrue(last.endsAtClockTime)
        assertEquals(6 to 20, last.hours to last.minutes)
        assertFalse(PlanCompiler.compile(profile(), start, wake).steps.filter { it.block != Block.MIDDLE }.any { it.step.endsAtClockTime })
    }

    @Test
    fun clockTimeStepFollowsTheBedJetClockAcrossDst() {
        // 2026-10-25 is the UK clocks-go-back night: 23:50 BST to 07:00 GMT is 8 h 10 min of real time.
        // The BedJet's clock is set at the start and keeps counting, so it reads 07:35 when the wall says 06:35.
        val plan = PlanCompiler.compile(profile(), at(2026, 10, 24, 23, 50), at(2026, 10, 25, 7, 0))
        val wakeStart = plan.steps.first { it.block == Block.WAKE }.startsAt
        assertEquals(at(2026, 10, 25, 6, 35), wakeStart)
        val last = plan.steps.last { it.block == Block.MIDDLE }.step
        assertEquals(7 to 35, last.hours to last.minutes)
        assertEquals(490, plan.steps.filter { it.block != Block.AFTER_WAKE }.sumOf { it.minutes })
    }

    @Test
    fun tinyMiddleRunsByDurationNotClockTime() {
        // A 1-minute Middle with a clock-time end would already be past when it begins, and the BedJet would wait
        // for the same time tomorrow.
        val plan = PlanCompiler.compile(profile(start = listOf(Phase(Mode.HEAT, 66, 40, 60))), start, start.plusMinutes(86))
        val middle = plan.steps.filter { it.block == Block.MIDDLE }
        assertEquals(1, middle.sumOf { it.minutes })
        assertFalse(middle.any { it.step.endsAtClockTime })
    }

    @Test
    fun startIsTakenToTheMinuteLikeTheBedJetClock() {
        val plan = PlanCompiler.compile(profile(), start.plusSeconds(59), wake)
        assertEquals(start, plan.start)
        assertEquals(6 to 20, plan.steps.last { it.block == Block.MIDDLE }.step.let { it.hours to it.minutes })
    }

    @Test
    fun tooManyStepsRejected() {
        val p = profile(start = List(30) { Phase(Mode.COOL, 44, 40, 5) }, middle = Middle(Mode.COOL, 50, 20, 44, 44, rampSteps = 8))
        val error = assertThrows<PlanException> { PlanCompiler.compile(p, start, wake) }
        assertTrue(error.message!!.contains("32"))
    }

    @Test
    fun zeroMinutePhasesDropped() {
        val p = profile(start = listOf(Phase(Mode.HEAT, 66, 40, 0), Phase(Mode.COOL, 44, 40, 10)))
        assertEquals(listOf(Mode.COOL), PlanCompiler.compile(p, start, wake).steps.filter { it.block == Block.START }.map { it.step.mode })
    }

    @Test
    fun afterWakeFollowsTheWakeTime() {
        val plan = PlanCompiler.compile(profile(afterWake = listOf(Phase(Mode.COOL, 44, 40, 30))), start, wake)
        val after = plan.steps.single { it.block == Block.AFTER_WAKE }
        assertEquals(wake, after.startsAt)
        assertEquals(wake.plusMinutes(30), plan.end)
    }

    @Test
    fun replanKeepsRemainingStart() {
        val original = PlanCompiler.compile(profile(), start, wake)
        val now = start.plusMinutes(10)
        val newWake = wake.minusMinutes(45)
        val replanned = PlanCompiler.replan(profile(), original, now, newWake)!!
        assertEquals(now, replanned.start)
        assertEquals(20, replanned.minutesOf(Block.START))
        assertEquals(newWake, replanned.wake)
        assertEquals(25, replanned.minutesOf(Block.WAKE))
    }

    @Test
    fun replanIgnoredAfterWakeBegins() {
        val original = PlanCompiler.compile(profile(), start, wake)
        assertNull(PlanCompiler.replan(profile(), original, wake.minusMinutes(5), wake.plusMinutes(30)))
    }
}
