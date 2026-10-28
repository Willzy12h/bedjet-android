package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class QuietMiddleEdgeDescriptionTest {
    private val start = ZonedDateTime.parse("2026-10-21T22:30Z")
    private fun entry(vararg speeds: Int): TimelineEntry {
        val steps = speeds.mapIndexed { i, fan ->
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, fan / 5 - 1, 44, 0, 30), start.plusMinutes(i * 30L), 30)
        }
        return timelineEntries(Plan(start, start.plusMinutes(steps.size * 30L), steps)).single()
    }

    @Test fun equalQuietSpeedsRemainRecognisableWithoutGuessingMissingStages() {
        val middle = Middle(Mode.COOL, 50, 50, 44, 44, quietFan = QuietFan(50, 30, 25, 50, 40))
        val description = entry(25, 50).detail(false, middle = middle)
        assertTrue(description.contains("Quiet middle"))
        assertTrue(description.contains("25%"))
        assertTrue(description.contains("50%"))
        assertFalse(description.contains("settle"))
        assertFalse(description.contains("rise"))
    }

    @Test fun nonmonotonicAppliedFanPathDoesNotClaimOnlyEasingOff() {
        val entry = entry(50, 25, 45)
        assertEquals("Cool", entry.title)
        assertTrue(entry.detail(false).contains("50% → 25% → 45%"))
    }
}
