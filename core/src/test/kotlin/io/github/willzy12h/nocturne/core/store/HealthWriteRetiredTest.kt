package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nocturne only reads sleep from Health Connect: a write choice saved by an earlier version is switched off. */
class HealthWriteRetiredTest {
    @Test fun aSavedWriteChoiceIsSwitchedOffWhileTheReadOverlayIsKept() {
        val saved = Settings(platform = PlatformSettings(healthSleepOverlay = true, healthSleepExport = true))
        val loaded = saved.withoutUnidentifiedAlarms()
        assertFalse(loaded.platform.healthSleepExport)
        assertTrue(loaded.platform.healthSleepOverlay)
    }
}
