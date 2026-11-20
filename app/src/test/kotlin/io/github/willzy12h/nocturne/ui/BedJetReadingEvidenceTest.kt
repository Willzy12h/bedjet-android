package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

class BedJetReadingEvidenceTest {
    @Test fun turboAndDryKeepTheirOwnIconsAndOnlyConfirmedBoostUsesTheRamp() {
        assertEquals(R.drawable.ic_nc_turbo, readingModeIcon(Mode.TURBO, false))
        assertEquals(R.drawable.ic_nc_ramp, readingModeIcon(Mode.TURBO, true))
        assertEquals(R.drawable.ic_nc_ramp, readingModeIcon(Mode.HEAT, true))
        assertEquals(R.drawable.ic_nc_dry, readingModeIcon(Mode.DRY, false))
        assertEquals(R.drawable.ic_nc_heat, readingModeIcon(Mode.HEAT, false))
        assertEquals(R.drawable.ic_nc_heat, readingModeIcon(Mode.EXTENDED_HEAT, false))
    }

    @Test fun plannedChangesRequireTheBedJetToReportTheExpectedStepAndControls() {
        val start = ZonedDateTime.parse("2026-11-22T06:00:00Z")
        val wake = start.plusHours(1)
        val current = PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, 7, 44, 1, 0), start, 60)
        val after = PlannedStep(Block.AFTER_WAKE, ProgramStep(Mode.COOL, 15, 44, 0, 10), wake, 10)
        val plan = Plan(start, wake, listOf(current, after))
        val first = DeviceStatus(600, 46, 44, Mode.COOL, 7, 600, 38, 86, 44, 0, DeviceStatus.Extra(false, false, 1, 0))
        val at = start.plusMinutes(50)
        assertEquals(0, reportedStepIndex(plan, first, at))
        assertNull(reportedStepIndex(plan, first.copy(mode = Mode.TURBO), at))
        assertNull(reportedStepIndex(plan, first.copy(fanStep = 15), at))
        assertNull(reportedStepIndex(plan, first.copy(targetTemperature = 48), at))
        assertNull(reportedStepIndex(plan, first.copy(extra = null), at))
        assertNull(reportedStepIndex(plan, first.copy(extra = first.extra!!.copy(programStep = 2)), at))
        assertEquals(1, reportedStepIndex(plan, first.copy(fanStep = 15, extra = first.extra!!.copy(programStep = 2)), wake))
        assertNull(reportedStepIndex(plan, first, plan.end))
    }
}
