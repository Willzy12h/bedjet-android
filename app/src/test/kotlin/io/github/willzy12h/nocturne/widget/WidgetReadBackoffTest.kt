package io.github.willzy12h.nocturne.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A BedJet that isn't found is tried less often, so the widget doesn't spend the battery looking for it. */
class WidgetReadBackoffTest {
    private val minute = 60_000L

    @Test fun noMissesMeansEveryPeriod() = assertTrue(WidgetReadBackoff.due(1_000, 0, 0))

    @Test fun waitsLongerAfterEachMiss() {
        val miss = 10_000 * minute
        assertFalse(WidgetReadBackoff.due(miss + 29 * minute, miss, 1))
        assertTrue(WidgetReadBackoff.due(miss + 30 * minute, miss, 1))
        assertFalse(WidgetReadBackoff.due(miss + 59 * minute, miss, 2))
        assertTrue(WidgetReadBackoff.due(miss + 60 * minute, miss, 2))
        assertFalse(WidgetReadBackoff.due(miss + 119 * minute, miss, 7))
        assertTrue(WidgetReadBackoff.due(miss + 120 * minute, miss, 7))
    }

    @Test fun aClockSetBackDoesNotStopReads() = assertTrue(WidgetReadBackoff.due(5 * minute, 100 * minute, 3))
}
