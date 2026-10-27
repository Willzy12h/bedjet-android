package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PaletteChoiceTest {
    private val noon = ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, ZoneId.of("Europe/London"))

    @Test
    fun defaultsAreMidnightInkByNightAndOvercastByDay() {
        assertEquals(Palette.MIDNIGHT_INK, Settings().palette(noon, systemDark = true))
        assertEquals(Palette.OVERCAST, Settings().palette(noon, systemDark = false))
    }

    @Test
    fun eitherSlotTakesAnyPalette() {
        val s = Settings(themeMode = ThemeMode.LIGHT, nightPalette = Palette.FOREST, dayPalette = Palette.GRAPHITE)
        assertEquals(Palette.GRAPHITE, s.palette(noon, systemDark = true))
        assertEquals(Palette.FOREST, s.copy(themeMode = ThemeMode.DARK).palette(noon, systemDark = false))
    }

    @Test
    fun olderSettingsWithoutPalettesLoadWithDefaults() {
        val s = BackupCodec.json.decodeFromString(Settings.serializer(), """{"themeMode":"DARK"}""")
        assertEquals(Palette.MIDNIGHT_INK, s.nightPalette)
        assertEquals(Palette.OVERCAST, s.dayPalette)
    }
}
