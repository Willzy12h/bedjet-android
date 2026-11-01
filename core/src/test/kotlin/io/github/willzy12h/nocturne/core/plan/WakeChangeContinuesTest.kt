package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Feedback 15: changing the wake time carries the night on from where it is, rather than jumping ahead. */
class WakeChangeContinuesTest {
    private val start = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, ZoneId.of("Europe/London"))
    private val wake = start.plusHours(8)

    private fun Plan.at(time: ZonedDateTime) = steps.first { !it.startsAt.isAfter(time) && it.endsAt.isAfter(time) }

    @Test
    fun whatIsRunningCarriesOnForEveryPresetAndMoment() {
        for (profile in Presets.all) for (minute in listOf(3, 12, 45, 60, 120, 240, 400)) for (shift in listOf(-45L, 30L, 90L)) {
            val plan = PlanCompiler.compile(profile, start, wake)
            val now = start.plusMinutes(minute.toLong())
            val replanned = PlanCompiler.replan(profile, plan, now, wake.plusMinutes(shift)) ?: continue
            // An earlier wake brings a Quiet rise forward; reaching it now is the new plan working, not a jump.
            val rise = profile.middle.quietFan?.riseMinutes?.toLong()
            if (rise != null && !now.isBefore(replanned.wakeUpStart.minusMinutes(rise))) continue
            val before = plan.at(now).step
            val after = replanned.at(now).step
            val label = "${profile.name} at $minute min, wake ${if (shift > 0) "+" else ""}$shift"
            assertEquals(before.mode, after.mode, label)
            assertEquals(before.temperature, after.temperature, label)
            assertEquals(before.fanStep, after.fanStep, label)
        }
    }

    @Test
    fun aRunningTurboFinishesButNeverRunsLonger() {
        val plan = PlanCompiler.compile(Presets.winterWarm, start, wake)
        val replanned = PlanCompiler.replan(Presets.winterWarm, plan, start.plusMinutes(3), wake.plusMinutes(30))!!
        val turbo = replanned.steps.filter { it.step.mode == Mode.TURBO }
        assertEquals(1, turbo.size)
        assertEquals(7, turbo.single().minutes)
        assertEquals(Mode.HEAT, replanned.steps[1].step.mode)
    }

    @Test
    fun aRunningQuietSettleFinishes() {
        // Hot Sleeper: Start 45 min, then a 30-minute settle at 50 % before the asleep 25 %.
        val profile = Presets.hotSleeper
        val plan = PlanCompiler.compile(profile, start, wake)
        val now = start.plusMinutes(55)
        val replanned = PlanCompiler.replan(profile, plan, now, wake.plusMinutes(30))!!
        val middle = replanned.steps.filter { it.block == Block.MIDDLE }
        assertEquals(50, (middle.first().step.fanStep + 1) * 5)
        assertEquals(20, middle.first().minutes)
        assertEquals(25, (middle[1].step.fanStep + 1) * 5)
    }

    @Test
    fun theRampContinuesToTheSameEnd() {
        val profile = Presets.winterWarm
        val plan = PlanCompiler.compile(profile, start, wake)
        val now = start.plusMinutes(240)
        val replanned = PlanCompiler.replan(profile, plan, now, wake.plusMinutes(30))!!
        val temperatures = replanned.steps.filter { it.block == Block.MIDDLE }.map { it.step.temperature }.distinct()
        assertEquals(plan.at(now).step.temperature, temperatures.first())
        assertEquals(54, temperatures.last())
        assertTrue(temperatures.zipWithNext().all { (a, b) -> b <= a }, "$temperatures")
    }

    @Test
    fun repeatedChangesStillCarryOn() {
        val profile = Presets.summerCool
        var plan = PlanCompiler.compile(profile, start, wake)
        for (minute in listOf(100L, 200L, 300L)) {
            val now = start.plusMinutes(minute)
            val before = plan.at(now).step
            plan = PlanCompiler.replan(profile, plan, now, plan.wake.plusMinutes(20))!!
            assertEquals(before.fanStep, plan.at(now).step.fanStep, "at $minute")
        }
        assertEquals(25, (plan.steps.last { it.block == Block.MIDDLE }.step.fanStep + 1) * 5)
    }
}
