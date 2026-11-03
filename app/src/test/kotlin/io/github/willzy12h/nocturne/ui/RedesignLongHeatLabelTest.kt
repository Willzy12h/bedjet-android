package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedesignLongHeatLabelTest {
    @Test fun extendedHeatUsesLongHeatInResourceAndContextFreeFormatting() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("Long heat", context.getString(Format.modeNameRes(Mode.EXTENDED_HEAT)))
        assertEquals("Long heat", Format.modeName(Mode.EXTENDED_HEAT))
    }
}
