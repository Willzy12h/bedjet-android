package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class PlanErrorModeNameTest {
    private val now = ZonedDateTime.parse("2026-10-21T22:30:00Z")

    @Test
    fun heatRefusalUsesTheAppModeName() {
        assertEquals(
            "Heat cannot honour this profile's temperature limits",
            refusal(Mode.HEAT, 64, ProfileLimits(maxTemp = 44)),
        )
    }

    @Test
    fun gentleHeatRefusalUsesTheAppModeName() {
        assertEquals(
            "Gentle heat cannot honour this profile's temperature limits",
            refusal(Mode.EXTENDED_HEAT, 60, ProfileLimits(minTemp = 68)),
        )
    }

    @Test
    fun coolRefusalUsesTheAppModeName() {
        assertEquals(
            "Cool cannot honour this profile's temperature limits",
            refusal(Mode.COOL, 44, ProfileLimits(minTemp = 63)),
        )
    }

    @Test
    fun dryRefusalUsesTheAppModeName() {
        assertEquals(
            "Dry cannot honour this profile's temperature limits",
            refusal(Mode.DRY, 60, ProfileLimits(maxTemp = 39)),
        )
    }

    @Test
    fun turboRefusalUsesTheAppModeNameWhenDirectCoreInputHasOutOfRangeLimits() {
        // The editor and backup validator reject this range, but core accepts ProfileLimits directly.
        // With ordinary limits Turbo uses its separate 43 °C refusal before the generic error.
        assertEquals(
            "Turbo cannot honour this profile's temperature limits",
            refusal(Mode.TURBO, 86, ProfileLimits(minTemp = 87, maxTemp = 87)),
        )
    }

    private fun refusal(mode: Mode, temperature: Int, limits: ProfileLimits): String? {
        val profile = Profile(
            id = "p", name = "Test", start = emptyList(), wake = emptyList(),
            middle = Middle(mode, fanStart = 40, fanEnd = 40, tempStart = temperature, tempEnd = temperature),
            limits = limits,
        )
        val preview = NightPreview.forStartNow(profile, now, alarm = null)

        assertNull(preview.plan)
        return preview.refusalReason
    }
}
