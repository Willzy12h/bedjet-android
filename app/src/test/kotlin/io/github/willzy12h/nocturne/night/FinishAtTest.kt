package io.github.willzy12h.nocturne.night

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class FinishAtTest {
    @Test fun selectionCrossesMidnightButQueuedSendCannotRollToTomorrow() {
        val now = ZonedDateTime.parse("2026-11-19T23:40:17Z")
        val end = nextManualFinish(now, LocalTime.of(0, 15))!!
        assertEquals(35, manualMinutesUntil(now.toInstant(), end, 60))
        assertEquals(34, manualMinutesUntil(now.plusMinutes(1).toInstant(), end, 60))
        assertNull(manualMinutesUntil(end, end, 60))
        assertNull(manualMinutesUntil(now.toInstant(), end, 30))
    }

    @Test fun missingLocalTimeIsRefusedAndRepeatedTimeSelectsTheNextOccurrence() {
        assertNull(nextManualFinish(ZonedDateTime.parse("2026-03-29T00:15Z[Europe/London]"), LocalTime.of(1, 30)))
        val duringOverlap = ZonedDateTime.parse("2026-10-25T01:45+01:00[Europe/London]")
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), nextManualFinish(duringOverlap, LocalTime.of(1, 30)))
    }
}
