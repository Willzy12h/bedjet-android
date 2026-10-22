package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZonedDateTime

class PlanningHardeningTest {
    private val start = ZonedDateTime.parse("2026-10-21T22:30:00Z")
    @Test fun `over sixteen hours is refused before producing a programme`() {
        val error = assertThrows<PlanException> { PlanCompiler.compile(Presets.springAutumn, start, start.plusHours(17)) }
        assertTrue(error.message!!.contains("tonight-only"))
    }
    @Test fun `turbo then heat cannot silently exceed the cap`() {
        val p = Presets.winterWarm.copy(limits = ProfileLimits(maxTemp = 76))
        assertEquals("Turbo always runs at 43 °C, above this profile's limit", assertThrows<PlanException> {
            PlanCompiler.compile(p, start, start.plusHours(8))
        }.message)
    }
    @Test fun `repeat turbo cannot silently exceed the cap`() {
        val p = Presets.winterWarm.copy(turboChain = TurboChain.REPEAT_TURBO, limits = ProfileLimits(maxTemp = 76))
        assertThrows<PlanException> { PlanCompiler.compile(p, start, start.plusHours(8)) }
    }
    @Test fun `mode minimum cannot silently exceed maximum`() {
        assertThrows<PlanException> { PlanCompiler.temperatureFor(Mode.HEAT, 64, ProfileLimits(maxTemp = 38)) }
    }
    @Test fun `fan quantisation honours both profile bounds`() {
        assertEquals(25, (PlanCompiler.fanStepFor(22,ProfileLimits(minFan=22,maxFan=53))+1)*5)
        assertEquals(50, (PlanCompiler.fanStepFor(53,ProfileLimits(minFan=22,maxFan=53))+1)*5)
    }

}
