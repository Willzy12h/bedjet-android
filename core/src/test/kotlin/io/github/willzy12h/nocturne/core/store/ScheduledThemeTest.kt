package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class ScheduledThemeTest {
    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.parse("2026-10-22T00:00+01:00[Europe/London]")
        .withHour(hour).withMinute(minute)

    @Test fun overnightBoundaries() {
        val settings = Settings(themeMode = ThemeMode.SCHEDULED)
        assertTrue(settings.isDarkTheme(at(6, 59), false))
        assertFalse(settings.isDarkTheme(at(7), false))
        assertFalse(settings.isDarkTheme(at(21, 59), false))
        assertTrue(settings.isDarkTheme(at(22), false))
    }

    @Test fun daytimeAndEqualBoundaries() {
        val day = Settings(themeMode = ThemeMode.SCHEDULED, scheduledDarkMinute = 9 * 60, scheduledLightMinute = 17 * 60)
        assertFalse(day.isDarkTheme(at(8, 59), false))
        assertTrue(day.isDarkTheme(at(9), false))
        assertFalse(day.isDarkTheme(at(17), false))
        val alwaysDark = day.copy(scheduledLightMinute = 9 * 60)
        assertTrue(alwaysDark.isDarkTheme(at(12), false))
    }
}
