package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupModeNameTest {
    @Test
    fun temperatureErrorUsesTheAppNameForGentleHeat() {
        val profile = Presets.springAutumn.copy(
            name = "Test",
            start = listOf(Phase(Mode.EXTENDED_HEAT, 70, 40, 30)),
        )
        val bundle = BackupBundle(
            createdAt = "2026-10-21T22:30:00Z",
            profiles = listOf(profile),
            settings = Settings(),
        )

        assertEquals(
            listOf("Test: step 1 of the start: the temperature is 35 °C; Gentle heat allows 19 °C to 33.5 °C."),
            BackupValidator.validate(bundle),
        )
    }
}
