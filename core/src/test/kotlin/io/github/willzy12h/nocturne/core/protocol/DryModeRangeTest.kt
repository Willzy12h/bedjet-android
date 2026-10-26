package io.github.willzy12h.nocturne.core.protocol

import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TurboChain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** BedJet's 220–240 V Model 3 manual, operating modes table, states Dry up to 31 °C. */
class DryModeRangeTest {
    @Test fun dryOffersTwentyToThirtyOneDegrees() {
        assertEquals(40..62, Mode.DRY.temperatureRange)
    }

    @Test fun anOlderFortyDegreeDryPhaseCompilesWithinTheDocumentedLimit() {
        val steps = PlanCompiler.phaseSteps(Phase(Mode.DRY, 80, 40, 30), ProfileLimits(), TurboChain.TURBO_THEN_HEAT)
        assertEquals(62, steps.single().temperature)
        assertEquals(Mode.DRY, steps.single().mode)
    }

    @Test fun theOtherModeMaximumsRemainUnchanged() {
        assertEquals(listOf(86, 80, 67, 62), listOf(Mode.TURBO, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL).map { it.temperatureRange.last })
    }
}
