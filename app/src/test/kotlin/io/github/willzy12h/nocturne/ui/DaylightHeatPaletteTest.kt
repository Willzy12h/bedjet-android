package io.github.willzy12h.nocturne.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DaylightHeatPaletteTest : ComposeActivityFixture() {
    @Test fun daylightHeatingUsesAnAmberToOrangeRedFamily() {
        val heat = DaylightColors.heat
        val gentle = DaylightColors.gentleHeat
        val turbo = DaylightColors.turbo
        assertTrue("Heat needs an amber hue", heat.green / heat.red > .50f)
        assertTrue("Gentle heat is lighter amber", gentle.green > heat.green)
        assertTrue("Turbo should be orange-red, without the previous pink/rose component", turbo.green > turbo.blue * 1.5f)
        assertEquals(Color(0xFF2459B8), DaylightColors.cool)
        assertEquals(Color(0xFF1E7F72), DaylightColors.dry)
    }

    @Test fun simpleDaylightRetainsItsExistingModeColours() {
        var colors: NocturneColors? = null
        setContent {
            NocturneTheme(Settings(themeMode = ThemeMode.LIGHT, simpleView = true), ZonedDateTime.parse("2026-10-26T12:00Z")) {
                val current = nocturneColors
                SideEffect { colors = current }
            }
        }
        assertEquals(Color(0xFFC2561A), colors!!.heat)
        assertEquals(Color(0xFF9A6B00), colors!!.gentleHeat)
        assertEquals(Color(0xFFC42F3E), colors!!.turbo)
    }
}
