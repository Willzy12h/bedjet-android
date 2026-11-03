package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.BackupCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** R1: the fan's shape through the Middle is chosen apart from the temperature's. */
class FanShapeTest {
    private val zone = ZoneId.of("Europe/London")
    private val start = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, zone)
    private val wake = start.plusMinutes(495) // Middle gets 495 - 30 - 25 = 440

    private val quiet = QuietFan(settle = 50, settleMinutes = 30, asleep = 25, rise = 45, riseMinutes = 40)

    private fun profile(middle: Middle) = Profile(
        id = "p", name = "Test",
        start = listOf(Phase(Mode.HEAT, 66, 40, 30)),
        middle = middle,
        wake = listOf(Phase(Mode.HEAT, 64, 30, 25)),
    )

    private fun middleOf(plan: Plan) = plan.steps.filter { it.block == Block.MIDDLE }
    private fun fan(step: PlannedStep) = (step.step.fanStep + 1) * 5

    @Test
    fun shapeIsDerived() {
        assertEquals(FanShape.STEADY, Middle(Mode.COOL, 40, 40, 44, 44).fanShape)
        assertEquals(FanShape.EASE, Middle(Mode.COOL, 60, 20, 44, 44).fanShape)
        assertEquals(FanShape.QUIET_MIDDLE, Middle(Mode.COOL, 50, 45, 44, 44, quietFan = quiet).fanShape)
    }

    @Test
    fun quietMiddleSettlesSleepsAndRises() {
        val m = middleOf(PlanCompiler.compile(profile(Middle(Mode.COOL, 50, 45, 44, 44, quietFan = quiet)), start, wake))
        assertEquals(listOf(30, 370, 40), m.map { it.minutes })
        assertEquals(listOf(50, 25, 45), m.map(::fan))
        assertEquals(listOf(44, 44, 44), m.map { it.step.temperature })
    }

    @Test
    fun quietMiddleCutsAtTemperatureAndFanBoundaries() {
        // Two temperature blocks of 220; fan boundaries at 30 and 400.
        val middle = Middle(Mode.EXTENDED_HEAT, 50, 45, 60, 52, rampSteps = 2, quietFan = quiet)
        val m = middleOf(PlanCompiler.compile(profile(middle), start, wake))
        assertEquals(listOf(30, 190, 180, 40), m.map { it.minutes })
        assertEquals(listOf(50, 25, 25, 45), m.map(::fan))
        assertEquals(listOf(60, 60, 52, 52), m.map { it.step.temperature })
    }

    @Test
    fun shortMiddleKeepsTheRiseFirst() {
        // 50 minutes of Middle: the rise keeps its 40, the settle gets the other 10, nothing asleep.
        val m = middleOf(PlanCompiler.compile(profile(Middle(Mode.COOL, 50, 45, 44, 44, quietFan = quiet)), start, start.plusMinutes(105)))
        assertEquals(listOf(10, 40), m.map { it.minutes })
        assertEquals(listOf(50, 45), m.map(::fan))
    }

    @Test
    fun legacyEaseIsUnchanged() {
        val m = middleOf(PlanCompiler.compile(profile(Middle(Mode.EXTENDED_HEAT, 60, 20, 60, 52, rampSteps = 3)), start, wake))
        assertEquals(listOf(60, 40, 20), m.map(::fan))
    }

    @Test
    fun replanAfterTheSettleDoesNotSettleAgain() {
        val p = profile(Middle(Mode.COOL, 50, 45, 44, 44, quietFan = quiet))
        val plan = PlanCompiler.compile(p, start, wake)
        val replanned = PlanCompiler.replan(p, plan, start.plusMinutes(120), wake.plusMinutes(30))!!
        val m = middleOf(replanned)
        assertEquals(listOf(25, 45), m.map(::fan))
        assertEquals(40, m.last().minutes)
    }

    @Test
    fun shiftFanMovesEverySpeed() {
        val shifted = Middle(Mode.COOL, 50, 45, 44, 44, quietFan = quiet).shiftFan(10)
        assertEquals(listOf(60, 55, 60, 35, 55), shifted.fanSpeeds)
        assertEquals(30, shifted.quietFan!!.settleMinutes)
    }

    @Test
    fun olderBackupsWithoutQuietFanStillLoad() {
        val json = """{"mode":"COOL","fanStart":40,"fanEnd":40,"tempStart":44,"tempEnd":44,"rampSteps":1}"""
        val middle = BackupCodec.json.decodeFromString(Middle.serializer(), json)
        assertNull(middle.quietFan)
        assertEquals(FanShape.STEADY, middle.fanShape)
    }

    @Test
    fun quietFanRoundTrips() {
        val middle = Middle(Mode.COOL, 50, 45, 44, 44, quietFan = quiet)
        val text = BackupCodec.json.encodeToString(Middle.serializer(), middle)
        assertEquals(middle, BackupCodec.json.decodeFromString(Middle.serializer(), text))
    }
}
