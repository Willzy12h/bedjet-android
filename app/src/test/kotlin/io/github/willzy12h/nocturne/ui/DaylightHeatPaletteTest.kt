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
    @Test fun overcastHeatingUsesTheSharedEmberFamily() {
        val heat = io.github.willzy12h.nocturne.core.store.Palette.OVERCAST.tokens.nocturneColors().heat
        val gentle = io.github.willzy12h.nocturne.core.store.Palette.OVERCAST.tokens.nocturneColors().gentleHeat
        val turbo = io.github.willzy12h.nocturne.core.store.Palette.OVERCAST.tokens.nocturneColors().turbo
        assertEquals(Color(0xFFF45A40), heat)
        assertEquals(Color(0xFFFF8257), gentle)
        assertEquals(Color(0xFFC0202E), turbo)
        assertEquals(Color(0xFF2B8EE0), io.github.willzy12h.nocturne.core.store.Palette.OVERCAST.tokens.nocturneColors().cool)
        assertEquals(Color(0xFFB88C12), io.github.willzy12h.nocturne.core.store.Palette.OVERCAST.tokens.nocturneColors().dry)
    }

    @Test fun simpleViewUsesTheSamePaletteTokens() {
        var colors: NocturneColors? = null
        setContent {
            NocturneTheme(Settings(themeMode = ThemeMode.LIGHT, simpleView = true), ZonedDateTime.parse("2026-10-26T12:00Z")) {
                val current = nocturneColors
                SideEffect { colors = current }
            }
        }
        assertEquals(Color(0xFFF45A40), colors!!.heat)
        assertEquals(Color(0xFFFF8257), colors!!.gentleHeat)
        assertEquals(Color(0xFFC0202E), colors!!.turbo)
    }
}
