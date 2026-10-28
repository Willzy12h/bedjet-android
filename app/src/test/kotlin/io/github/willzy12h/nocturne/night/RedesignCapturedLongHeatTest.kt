package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedesignCapturedLongHeatTest {
    @Test fun capturedExtendedHeatUsesLongHeatLabel() {
        val message = capturedPresetMessage(RuntimeEnvironment.getApplication(), ActionPreset(Mode.EXTENDED_HEAT, 60, 30, 10))
        assertEquals("Long heat for 10 minutes.", message)
    }
}
