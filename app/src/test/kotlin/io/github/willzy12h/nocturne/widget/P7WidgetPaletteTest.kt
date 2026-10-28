package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.content.res.Configuration
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.ui.tokens
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P7WidgetPaletteTest {
    private val noon = ZonedDateTime.parse("2026-10-27T12:00Z")
    private val night = noon.withHour(23)
    private val settings = Settings(nightPalette = Palette.FOREST, dayPalette = Palette.OVERCAST)

    private fun context(dark: Boolean): Context {
        val base = RuntimeEnvironment.getApplication()
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(config)
    }

    private fun assertPalette(settings: Settings, at: ZonedDateTime, systemDark: Boolean, expected: Palette) {
        val colours = widgetColors(settings, at)
        val context = context(systemDark)
        assertEquals(expected.tokens.ground, colours.background.getColor(context))
        assertEquals(expected.tokens.surface, colours.surface.getColor(context))
        assertEquals(expected.tokens.ink, colours.onSurface.getColor(context))
        assertEquals(expected.tokens.primary, colours.primary.getColor(context))
        assertEquals(expected.tokens.onPrimary, colours.onPrimary.getColor(context))
    }

    @Test fun fixedModesUseTheChosenSlotRegardlessOfSystemAppearance() {
        for (systemDark in listOf(false, true)) {
            assertPalette(settings.copy(themeMode = ThemeMode.DARK), noon, systemDark, Palette.FOREST)
            assertPalette(settings.copy(themeMode = ThemeMode.LIGHT), night, systemDark, Palette.OVERCAST)
            val reversed = settings.copy(nightPalette = Palette.OVERCAST, dayPalette = Palette.FOREST)
            assertPalette(reversed.copy(themeMode = ThemeMode.DARK), noon, systemDark, Palette.OVERCAST)
            assertPalette(reversed.copy(themeMode = ThemeMode.LIGHT), night, systemDark, Palette.FOREST)
        }
    }

    @Test fun systemModeRespondsToEachContextConfiguration() {
        val system = settings.copy(themeMode = ThemeMode.SYSTEM)
        assertPalette(system, noon, false, Palette.OVERCAST)
        assertPalette(system, noon, true, Palette.FOREST)
    }

    @Test fun scheduledModeFollowsTimeRatherThanSystemAppearance() {
        val scheduled = settings.copy(themeMode = ThemeMode.SCHEDULED)
        for (systemDark in listOf(false, true)) {
            assertPalette(scheduled, noon, systemDark, Palette.OVERCAST)
            assertPalette(scheduled, night, systemDark, Palette.FOREST)
        }
    }
}
