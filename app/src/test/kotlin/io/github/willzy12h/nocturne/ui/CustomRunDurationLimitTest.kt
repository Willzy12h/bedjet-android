package io.github.willzy12h.nocturne.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The BedJet's 11 h 59 min limit reads and is entered as 12 hours, and is stored as the BedJet's maximum. */
class CustomRunDurationLimitTest {
    @Test fun twelveHoursIsTheSpokenLimit() {
        assertEquals(720, spokenMaximum(719))
        assertEquals(600, spokenMaximum(600))
    }

    @Test fun twelveHoursIsStoredAsTheMaximum() {
        assertEquals(719, enteredMinutes(720, 719))
        assertEquals(719, enteredMinutes(719, 719))
        assertEquals(1, enteredMinutes(1, 719))
    }

    @Test fun beyondTheLimitOrNothingIsRefused() {
        assertNull(enteredMinutes(721, 719))
        assertNull(enteredMinutes(0, 719))
        assertNull(enteredMinutes(null, 719))
    }
}
