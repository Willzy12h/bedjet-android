package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.NightRunner
import io.github.willzy12h.nocturne.core.night.Preparation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class NightDurationProblemTest {
    private val now = ZonedDateTime.parse("2026-10-26T22:30:00Z")
    private val runner = NightRunner(BedJetController(DemoBedJet { now }) { now }) { now }

    @Test fun `compiler keeps the maximum duration failure typed`() {
        val error = assertThrows(NightDurationException::class.java) {
            PlanCompiler.compile(Presets.springAutumn, now, now.plusHours(19))
        }
        assertEquals(NightDurationProblem.TOO_LONG, error.problem)
    }

    @Test fun `preview keeps the supported window failure typed`() {
        val preview = NightPreview.forStartNow(Presets.springAutumn, now, null,
            TonightOverride(now.plusMinutes(30).toString(), now.toString()))
        assertNull(preview.plan)
        assertEquals(NightDurationProblem.OUTSIDE_WINDOW, preview.durationProblem)
    }

    @Test fun `start refusal retains the preview duration reason`() {
        val refusal = runner.prepareStart(Presets.springAutumn, null, 2, DeviceId.DEMO, null, null,
            TonightOverride(now.plusMinutes(30).toString(), now.toString())) as Preparation.Refused
        assertEquals(NightDurationProblem.OUTSIDE_WINDOW, refusal.durationProblem)
    }

    @Test fun `rewrite refusal retains the supported window reason`() {
        val night = (runner.prepareStart(Presets.springAutumn, now.plusHours(8), 2, DeviceId.DEMO,
            null, null) as Preparation.Ready).change.intended
        val refusal = runner.reapply(night, Presets.springAutumn, night.identity, now.plusMinutes(30)) as Preparation.Refused
        assertEquals(NightDurationProblem.OUTSIDE_WINDOW, refusal.durationProblem)
    }
}
