package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

class ScheduledThemeBoundaryTest {
    private val scheduled = Settings(themeMode = ThemeMode.SCHEDULED)

    @Test fun defaultBoundariesAreStrictlyInTheFuture() {
        listOf(
            "2026-10-23T21:59:59.900Z" to "2026-10-23T22:00Z",
            "2026-10-23T22:00Z" to "2026-10-24T07:00Z",
            "2026-10-23T06:59:59.900Z" to "2026-10-23T07:00Z",
            "2026-10-23T07:00Z" to "2026-10-23T22:00Z",
        ).forEach { (at, expected) ->
            assertEquals(ZonedDateTime.parse(expected), nextThemeBoundary(scheduled, ZonedDateTime.parse(at)))
        }
    }

    @Test fun fixedAndAlwaysDarkSchemesDoNotNeedATimer() {
        val at = ZonedDateTime.parse("2026-10-23T21:00Z")
        listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT).forEach {
            assertNull(nextThemeBoundary(scheduled.copy(themeMode = it), at))
        }
        assertNull(nextThemeBoundary(scheduled.copy(scheduledLightMinute = 22 * 60), at))
    }

    @Test fun changedScheduleUsesItsOwnNextBoundary() {
        val settings = scheduled.copy(scheduledDarkMinute = 23 * 60 + 15, scheduledLightMinute = 6 * 60 + 45)
        assertEquals(ZonedDateTime.parse("2026-10-23T23:15Z"),
            nextThemeBoundary(settings, ZonedDateTime.parse("2026-10-23T22:00Z")))
    }

    @Test fun daytimeDarkIntervalIsSupported() {
        val settings = scheduled.copy(scheduledDarkMinute = 6 * 60, scheduledLightMinute = 18 * 60)
        assertEquals(ZonedDateTime.parse("2026-10-23T18:00Z"),
            nextThemeBoundary(settings, ZonedDateTime.parse("2026-10-23T09:00Z")))
    }

    @Test fun skippedLocalHourRechecksAtTheClockTransition() {
        val settings = scheduled.copy(scheduledDarkMinute = 90)
        assertEquals(ZonedDateTime.parse("2026-03-29T02:00+01:00[Europe/London]"),
            nextThemeBoundary(settings, ZonedDateTime.parse("2026-03-29T00:59:59Z[Europe/London]")))
    }

    @Test fun repeatedLocalHourRechecksAtTheClockTransitionAndTheSecondBoundary() {
        val settings = scheduled.copy(scheduledDarkMinute = 90)
        assertEquals(ZonedDateTime.parse("2026-10-25T01:00Z[Europe/London]"),
            nextThemeBoundary(settings, ZonedDateTime.parse("2026-10-25T01:45+01:00[Europe/London]")))
        assertEquals(ZonedDateTime.parse("2026-10-25T01:30Z[Europe/London]"),
            nextThemeBoundary(settings, ZonedDateTime.parse("2026-10-25T01:00Z[Europe/London]")))
    }
}
